package com.group_finity.mascot.assistant;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin async client for a locally-running Ollama instance.
 *
 * Calls POST http://localhost:11434/api/generate (non-streaming).
 * The callback is always invoked on the calling executor thread —
 * callers must dispatch to EDT themselves if they update Swing.
 *
 * If Ollama is not running, the error callback receives a friendly message
 * rather than a stack trace.
 */
public class OllamaClient
{
    private static final Logger log = Logger.getLogger( OllamaClient.class.getName() );

    /** Default endpoint — change if the user runs Ollama on a different port. */
    public static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/generate";

    /** Default model. Small, fast, good instruction following. */
    public static final String DEFAULT_MODEL = "llama3.2";

    /** Connection / read timeout in milliseconds. Cold model load can take 60-90s. */
    private static final int TIMEOUT_MS = 90_000;

    /** Hard cap on generated tokens - physically prevents runaway responses. */
    private static final int MAX_TOKENS = 100;

    /** Text model linger after a call. 30s is tuned to the response window: it keeps
     *  the model warm long enough for a follow-up/queued request to land, then lets it
     *  unload between the 45-90s reaction cadence gaps to free VRAM/RAM. Deliberately
     *  shorter than the cadence — reloads between reactions are an accepted cost. */
    private static final int TEXT_KEEP_ALIVE_SEC   = 30;
    /** Unload vision model immediately — it's called rarely and is large. */
    private static final int VISION_KEEP_ALIVE_SEC = 0;

    /**
     * Rate-limit: minimum ms between successive Ollama calls dispatched by the
     * queue worker. Requests that arrive while a call is in-flight are held in the
     * queue; only the oldest waiting request fires when the interval elapses.
     */
    private static final long DISPATCH_INTERVAL_MS = 2_000L;

    /** Maximum number of pending requests. Oldest entries are dropped when full. */
    private static final int MAX_QUEUE_DEPTH = 5;

    public interface Callback
    {
        /** Called with the model's response text on success. */
        void onResponse( String text );
        /** Called with a human-readable error message on failure. */
        void onError( String message );
    }

    /** Internal work item queued for dispatch. */
    private static final class Request
    {
        final String   system;
        final String   user;
        final String   imageBase64;   // null for text-only requests
        final String   modelOverride; // null to use client's default model
        final int      maxTokens;
        final Callback callback;

        Request( String system, String user, int maxTokens, Callback callback )
        {
            this( system, user, null, null, maxTokens, callback );
        }

        Request( String system, String user, String imageBase64, String modelOverride,
                 int maxTokens, Callback callback )
        {
            this.system        = system;
            this.user          = user;
            this.imageBase64   = imageBase64;
            this.modelOverride = modelOverride;
            this.maxTokens     = maxTokens;
            this.callback      = callback;
        }
    }

    private final String endpoint;
    private final String model;

    /**
     * Bounded queue — holds pending requests while the worker is busy.
     * ArrayBlockingQueue is FIFO; offer() returns false immediately if full.
     */
    private final BlockingQueue<Request> requestQueue =
        new ArrayBlockingQueue<>( MAX_QUEUE_DEPTH );

    /** Single worker thread that drains the queue at DISPATCH_INTERVAL_MS cadence. */
    private final Thread queueWorker;

    /** Total transformer layer count for the loaded model; -1 = not yet fetched. */
    private volatile int    cachedLayerCount   = -1;

    public OllamaClient()
    {
        this( DEFAULT_ENDPOINT, DEFAULT_MODEL );
    }

    public OllamaClient( final String endpoint, final String model )
    {
        this.endpoint = endpoint;
        this.model    = model;
        // Start the queue worker immediately.
        queueWorker = new Thread( this::runQueueWorker, "ollama-queue-worker" );
        queueWorker.setDaemon( true );
        queueWorker.start();
    }

    /**
     * Queue worker: drains requests one at a time, enforcing a minimum
     * DISPATCH_INTERVAL_MS gap between the *start* of each call.
     * Runs on its own daemon thread for the lifetime of the client.
     */
    private void runQueueWorker()
    {
        long lastDispatchMs = 0L;
        while( !Thread.currentThread().isInterrupted() )
        {
            try
            {
                // Block until a request is available
                final Request req = requestQueue.take();

                // Enforce minimum interval from the last dispatch start
                final long wait = DISPATCH_INTERVAL_MS
                    - ( System.currentTimeMillis() - lastDispatchMs );
                if( wait > 0 )
                    Thread.sleep( wait );

                lastDispatchMs = System.currentTimeMillis();
                log.log( Level.FINE, "[OllamaQueue] Dispatching request, queue depth={0}",
                         requestQueue.size() );

                // Execute synchronously on this worker thread
                try
                {
                    final String response = sendRequest(
                        req.system, req.user, req.imageBase64, req.modelOverride, req.maxTokens );
                    req.callback.onResponse( response );
                }
                catch( IOException e )
                {
                    final String msg = buildFriendlyError( e );
                    log.log( Level.WARNING, "Ollama request failed: {0}", msg );
                    req.callback.onError( msg );
                }
            }
            catch( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Send a prompt asynchronously.
     * The request is queued and dispatched at most once every DISPATCH_INTERVAL_MS.
     * If the queue is full (MAX_QUEUE_DEPTH), the oldest pending request is dropped
     * and this one takes its place so user-initiated requests stay fresh.
     *
     * @param systemPrompt Personality / context block.
     * @param userMessage  What the user typed.
     * @param callback     Receives the response (or error) when done.
     */
    public void generate( final String systemPrompt,
                          final String userMessage,
                          final Callback callback )
    {
        generate( systemPrompt, userMessage, MAX_TOKENS, callback );
    }

    /**
     * Like {@link #generate} but with a caller-supplied token limit.
     * Use this for tasks (e.g. summarization) that need more output than the
     * default MAX_TOKENS cap used for normal mascot responses.
     */
    public void generate( final String systemPrompt,
                          final String userMessage,
                          final int maxTokens,
                          final Callback callback )
    {
        final Request req = new Request( systemPrompt, userMessage, maxTokens, callback );
        if( !requestQueue.offer( req ) )
        {
            // Queue full — drop the oldest pending request and enqueue this one.
            // This keeps user-initiated requests (most recently fired) moving through
            // rather than stale autonomous reactions clogging the pipeline.
            final Request dropped = requestQueue.poll();
            if( dropped != null )
            {
                log.warning( "[OllamaQueue] Queue full — dropped oldest pending request." );
                // Notify the dropped caller so its UI (e.g. a "Thinking..." bubble)
                // doesn't wait forever for a response that will never come.
                try { dropped.callback.onError( "Request skipped — queue full." ); }
                catch( final Exception ignored ) {}
            }
            requestQueue.offer( req );
        }
    }

    /**
     * Like {@link #generate} but attaches a base64-encoded image and uses the
     * specified vision model (e.g. "llava"). Bypasses the queue so the capture
     * result isn't held behind pending text requests.
     */
    public void generateWithImage( final String systemPrompt,
                                   final String userMessage,
                                   final String imageBase64,
                                   final String visionModel,
                                   final Callback callback )
    {
        final Request req = new Request( systemPrompt, userMessage,
                                         imageBase64, visionModel,
                                         MAX_TOKENS * 3, callback );
        // Vision requests go straight to the front — they're always user-initiated
        // and carry a large payload that shouldn't wait behind queued text calls.
        final Thread t = new Thread( () ->
        {
            try
            {
                final String response = sendRequest(
                    req.system, req.user, req.imageBase64, req.modelOverride, req.maxTokens );
                req.callback.onResponse( response );
            }
            catch( final IOException e )
            {
                req.callback.onError( buildFriendlyError( e ) );
            }
        }, "ollama-vision-worker" );
        t.setDaemon( true );
        t.start();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String sendRequest( final String system, final String user,
                                 final String imageBase64, final String modelOverride,
                                 final int maxTokens ) throws IOException
    {
        final int    numGpu        = computeNumGpuLayers();
        final int    numThread     = computeNumThreads();
        final String effectiveModel = modelOverride != null ? modelOverride : model;
        // Vision requests normally unload immediately (rare + large), but when the
        // vision model IS the text model (shared multimodal setup), keep_alive:0
        // would evict the model the text path is paying TEXT_KEEP_ALIVE_SEC to
        // keep warm — so shared models always use the text linger.
        final boolean isVision     = imageBase64 != null && !effectiveModel.equals( model );
        final int    keepAlive     = isVision ? VISION_KEEP_ALIVE_SEC : TEXT_KEEP_ALIVE_SEC;
        final String body          = buildJson( system, user, numGpu, numThread, maxTokens,
                                               imageBase64, effectiveModel, keepAlive );
        final byte[] bodyBytes = body.getBytes( StandardCharsets.UTF_8 );

        final URL url = new URL( endpoint );
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod( "POST" );
        conn.setRequestProperty( "Content-Type", "application/json; charset=utf-8" );
        conn.setConnectTimeout( TIMEOUT_MS );
        conn.setReadTimeout( TIMEOUT_MS );
        conn.setDoOutput( true );

        try ( OutputStream os = conn.getOutputStream() )
        {
            os.write( bodyBytes );
        }

        final int status = conn.getResponseCode();
        if( status != 200 )
        {
            // Drain and close the error stream so the keep-alive connection can be reused.
            try( final java.io.InputStream es = conn.getErrorStream() )
            {
                if( es != null )
                {
                    final byte[] buf = new byte[ 1024 ];
                    while( es.read( buf ) > 0 ) { /* discard */ }
                }
            }
            catch( final IOException ignored ) {}
            throw new IOException( "HTTP " + status + " from Ollama (model=" + effectiveModel + ")" );
        }

        final StringBuilder sb = new StringBuilder();
        try ( BufferedReader br = new BufferedReader(
                new InputStreamReader( conn.getInputStream(), StandardCharsets.UTF_8 ) ) )
        {
            String line;
            while( ( line = br.readLine() ) != null )
                sb.append( line );
        }

        return parseResponse( sb.toString() );
    }

    /**
     * Build the JSON body manually — avoids pulling in a JSON library.
     * Uses non-streaming mode (stream:false) so we get one complete response.
     * numGpu: how many transformer layers to run on GPU (rest run on CPU).
     * imageBase64: optional base64-encoded PNG; when non-null adds "images":[...] for vision models.
     * modelName: the model to use (may differ from this.model for vision requests).
     */
    private String buildJson( final String system, final String user,
                               final int numGpu, final int numThread, final int maxTokens,
                               final String imageBase64, final String modelName,
                               final int keepAliveSeconds )
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( "{\"model\":" ).append( jsonStr( modelName ) ).append( ',' );
        if( imageBase64 != null )
            sb.append( "\"images\":[" ).append( jsonStr( imageBase64 ) ).append( "]," );
        sb.append( "\"system\":" ).append( jsonStr( system ) ).append( ',' );
        sb.append( "\"prompt\":" ).append( jsonStr( user ) ).append( ',' );
        sb.append( "\"stream\":false," );
        sb.append( "\"think\":false," );
        // Duration string ("10s"), NOT a bare number: Go's time.Duration parses bare
        // JSON numbers as NANOSECONDS, so keep_alive:10 meant 10ns = unload instantly.
        sb.append( "\"keep_alive\":\"" ).append( keepAliveSeconds ).append( "s\"," );
        sb.append( "\"options\":{\"num_predict\":" ).append( maxTokens )
          .append( ",\"num_gpu\":" ).append( numGpu );
        // <= 0: omit and let Ollama use its default (physical core count).
        if( numThread > 0 )
            sb.append( ",\"num_thread\":" ).append( numThread );
        sb.append( ",\"repeat_penalty\":1.15}}" );
        return sb.toString();
    }

    /**
     * Fraction of the machine's *available* CPU and GPU the model is allowed to use.
     * Read from settings "OllamaResourceCap" (clamped 0.1-1.0, default 0.5).
     * 1.0 disables capping entirely (legacy behavior: GPU auto-fit when mostly idle,
     * Ollama's own thread default). "Available" is measured at dispatch time — the
     * request queue is serial, so the model is idle when measured and its own usage
     * never counts against itself.
     */
    private double resourceCap()
    {
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance()
                .getProperties().getProperty( "OllamaResourceCap", "0.5" );
            final double d = Double.parseDouble( v.trim() );
            return Math.max( 0.1, Math.min( 1.0, d ) );
        }
        catch( final Exception e )
        {
            return 0.5;
        }
    }

    /**
     * Rounds an availability fraction to quarter steps (floor 0.25). num_gpu and
     * num_thread are model-LOAD options in Ollama — a value that wobbles with every
     * load sample would force a full model reload on each request. Quarter buckets
     * keep the options identical across small load fluctuations.
     */
    private static double bucketQuarter( final double f )
    {
        final double b = Math.round( f * 4.0 ) / 4.0;
        return Math.max( 0.25, Math.min( 1.0, b ) );
    }

    /**
     * Returns how many transformer layers to offload to GPU for this request:
     * layerCount x available-GPU-fraction x resource cap. With the default 0.5 cap
     * the model only ever claims half of whatever GPU compute/VRAM is currently
     * free — the rest of the layers run on the (separately capped) CPU.
     * With cap=1.0 the legacy behavior applies: -1 lets Ollama auto-fit when the
     * GPU is mostly idle (measured June 2026: gemma3:4b lands 100% GPU at 82 tok/s;
     * the old unconditional 0.20 layer cap cut generation to ~15 tok/s).
     */
    private int computeNumGpuLayers()
    {
        final double cap           = resourceCap();
        final double availFraction = bucketQuarter( queryGpuAvailableFraction() );
        if( cap >= 0.99 && availFraction >= 0.5 ) return -1; // uncapped + mostly idle
        if( cachedLayerCount < 0 )
        {
            cachedLayerCount = fetchModelLayers();
            log.log( Level.INFO, "Ollama model layer count: {0}", cachedLayerCount );
        }
        final int layers = Math.max( 1, (int) Math.round( cachedLayerCount * availFraction * cap ) );
        log.log( Level.FINE, "GPU avail={0,number,#.##} cap={1,number,#.##} num_gpu={2}",
                 new Object[]{ availFraction, cap, layers } );
        return layers;
    }

    /**
     * Threads for the CPU side of inference: logical cores x idle-CPU-fraction x
     * resource cap, quarter-bucketed for option stability. On a 16-thread CPU at
     * the default 0.5 cap this is 8 threads when idle, 4 at 50% system load.
     * Returns 0 (= omit the option) when uncapped, so Ollama keeps its own
     * physical-core default.
     */
    private int computeNumThreads()
    {
        final double cap = resourceCap();
        if( cap >= 0.99 ) return 0;
        double idle = 1.0;
        try
        {
            final double load = com.group_finity.mascot.environment.CpuTempMonitor
                .getInstance().getCpuLoad();
            if( load >= 0 ) idle = ( 100.0 - load ) / 100.0;
        }
        catch( final Exception ignored ) {}
        final int cores = Runtime.getRuntime().availableProcessors();
        return Math.max( 1, (int) Math.round( cores * bucketQuarter( idle ) * cap ) );
    }

    /**
     * Returns the GPU available fraction as min(compute_headroom, memory_headroom),
     * read from CpuTempMonitor's persistent nvidia-smi stream — no subprocess spawn
     * per request. Returns 1.0 when no NVIDIA GPU data is available (allow full use;
     * Ollama itself falls back to CPU when there is genuinely no GPU).
     */
    private double queryGpuAvailableFraction()
    {
        try
        {
            final com.group_finity.mascot.environment.CpuTempMonitor mon =
                com.group_finity.mascot.environment.CpuTempMonitor.getInstance();
            final double load     = mon.getGpuLoad();
            final double memFree  = mon.getGpuMemFree();
            final double memTotal = mon.getGpuMemTotal();
            if( load < 0 )
                return 1.0; // no NVIDIA GPU / nvidia-smi unavailable
            final double computeAvail = ( 100.0 - load ) / 100.0;
            final double memAvail     = memTotal > 0 ? memFree / memTotal : 1.0;
            return Math.min( computeAvail, memAvail );
        }
        catch( final Exception e )
        {
            return 1.0;
        }
    }

    /**
     * Fetches transformer layer count from Ollama's /api/show endpoint.
     * Looks for "block_count" in the model info JSON.
     * Returns 32 as a safe default on failure.
     */
    private int fetchModelLayers()
    {
        try
        {
            final String base = endpoint.replaceFirst( "/api/generate.*", "" );
            final URL url = new URL( base + "/api/show" );
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod( "POST" );
            conn.setRequestProperty( "Content-Type", "application/json; charset=utf-8" );
            conn.setConnectTimeout( 5_000 );
            conn.setReadTimeout( 5_000 );
            conn.setDoOutput( true );
            final byte[] body = ( "{\"name\":" + jsonStr( model ) + "}" )
                                .getBytes( StandardCharsets.UTF_8 );
            try( OutputStream os = conn.getOutputStream() )
            {
                os.write( body );
            }
            if( conn.getResponseCode() != 200 )
                return 32;
            final StringBuilder sb = new StringBuilder();
            try( BufferedReader br = new BufferedReader(
                    new InputStreamReader( conn.getInputStream(), StandardCharsets.UTF_8 ) ) )
            {
                String ln;
                while( ( ln = br.readLine() ) != null )
                    sb.append( ln );
            }
            // Find "block_count": N in the modelinfo section
            final String json  = sb.toString();
            int idx = json.indexOf( "block_count" );
            if( idx < 0 )
                return 32;
            idx = json.indexOf( ':', idx + 11 );
            if( idx < 0 )
                return 32;
            idx++;
            while( idx < json.length() && Character.isWhitespace( json.charAt( idx ) ) )
                idx++;
            final StringBuilder numStr = new StringBuilder();
            while( idx < json.length() && Character.isDigit( json.charAt( idx ) ) )
                numStr.append( json.charAt( idx++ ) );
            if( numStr.length() == 0 )
                return 32;
            final int count = Integer.parseInt( numStr.toString() );
            return count > 0 ? count : 32;
        }
        catch( final Exception e )
        {
            return 32;
        }
    }

    /**
     * Parse "response" field from the Ollama JSON reply without a library.
     * Ollama non-streaming format: {"model":"...","response":"...","done":true,...}
     */
    private String parseResponse( final String json )
    {
        final String key = "\"response\":";
        final int start = json.indexOf( key );
        if( start < 0 )
            return "(no response field in Ollama reply)";

        int i = start + key.length();
        // skip whitespace
        while( i < json.length() && json.charAt(i) == ' ' ) i++;
        if( i >= json.length() || json.charAt(i) != '"' )
            return "(unexpected Ollama response format)";

        // read quoted string with basic escape handling
        final StringBuilder sb = new StringBuilder();
        i++; // skip opening "
        while( i < json.length() )
        {
            final char c = json.charAt(i);
            if( c == '\\' && i + 1 < json.length() )
            {
                final char next = json.charAt( i + 1 );
                switch( next )
                {
                    case '"':  sb.append('"');  i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case 'n':  sb.append('\n'); i += 2; break;
                    case 't':  sb.append('\t'); i += 2; break;
                    case 'r':  sb.append('\r'); i += 2; break;
                    case 'u':
                        // \\uXXXX — Go's JSON encoder HTML-escapes < > & as \\u003c etc.
                        // Without this, those characters surface as literal "u003c" text.
                        if( i + 6 <= json.length() )
                        {
                            try
                            {
                                sb.append( (char) Integer.parseInt(
                                    json.substring( i + 2, i + 6 ), 16 ) );
                                i += 6;
                            }
                            catch( final NumberFormatException e )
                            {
                                sb.append( next );
                                i += 2;
                            }
                        }
                        else
                        {
                            sb.append( next );
                            i += 2;
                        }
                        break;
                    default:   sb.append(next); i += 2; break;
                }
            }
            else if( c == '"' )
            {
                break; // end of string
            }
            else
            {
                sb.append(c);
                i++;
            }
        }
        return sb.toString().trim();
    }

    /** Escape a Java string for inclusion in a JSON string literal. */
    private static String jsonStr( final String s )
    {
        final StringBuilder sb = new StringBuilder( s.length() + 10 );
        sb.append('"');
        for( int i = 0; i < s.length(); i++ )
        {
            final char c = s.charAt(i);
            switch(c)
            {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);      break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String buildFriendlyError( final IOException e )
    {
        final String msg = e.getMessage();
        if( msg != null && msg.contains( "Connection refused" ) )
            return "Ollama isn't running. Start it with: ollama serve";
        if( msg != null && msg.contains( "HTTP 404" ) )
        {
            // Extract the model name from "HTTP 404 from Ollama (model=NAME)"
            final int m = msg.indexOf( "model=" );
            final String name = m >= 0 ? msg.substring( m + 6 ).replace( ")", "" ).trim()
                                       : DEFAULT_MODEL;
            return "Model not found: " + name + ". Run: ollama pull " + name;
        }
        return "Couldn't reach Ollama: " + ( msg != null ? msg : e.getClass().getSimpleName() );
    }
}
