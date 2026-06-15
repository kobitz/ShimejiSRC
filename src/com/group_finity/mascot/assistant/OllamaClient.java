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

    /** Default model. gemma4:e2b-it-qat — elastic E2B QAT build: multimodal (covers
     *  chat + vision), fully VRAM-resident on a 6 GB GPU (~1.5 GB, 100% GPU), and faster
     *  + higher quality (incl. instruction/tag following) than gemma3:4b in the June 2026
     *  benchmark. Replaced gemma3:4b as the shipped default. */
    public static final String DEFAULT_MODEL = "gemma4:e2b-it-qat";

    /** Connection / read timeout in milliseconds. Cold model load can take 60-90s. */
    private static final int TIMEOUT_MS = 90_000;

    /** Hard cap on generated tokens - physically prevents runaway responses. */
    private static final int MAX_TOKENS = 100;

    /** Text model linger after a call. Tuned to the response window: it keeps
     *  the model warm long enough for follow-up/queued requests to land, then lets it
     *  unload between the 45-90s reaction cadence gaps to free VRAM/RAM. Deliberately
     *  shorter than the cadence — reloads between reactions are an accepted cost.
     *  45s (was 30) compensates for the 5s dispatch spacing: a full 5-deep queue now
     *  takes ~25s to drain, so the warm window must outlast it. */
    private static final int TEXT_KEEP_ALIVE_SEC   = 45;
    /** Unload vision model immediately — it's called rarely and is large. */
    private static final int VISION_KEEP_ALIVE_SEC = 0;

    /**
     * Text-model keep-alive seconds, configurable via OllamaKeepAliveSec (default 45).
     * Raising it is the mitigation for a model that does NOT fit VRAM (e.g. gemma4:e4b on
     * a 6 GB GPU): at 45s the model unloads during the 90s–3min reaction-cooldown gaps and
     * reloads on the next reaction, and each ~6.5 GB reload hard-pages the desktop out
     * (the 1-3 fps crawl). A value past the longest cooldown (~300s) keeps it resident
     * across reactions so it loads once and settles, trading repeated thrash spikes for
     * steady memory pressure. Costs RAM/VRAM that stays occupied longer (worse if you game
     * mid-session) — drop it back to 45 if that bites. The real fix remains a VRAM-fitting
     * model (the default gemma4:e2b-it-qat, which sits 100% in VRAM, or gemma3:4b);
     * this keep-alive lever only softens gemma4:e4b.
     */
    private static int textKeepAliveSec()
    {
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance().getProperties()
                .getProperty( "OllamaKeepAliveSec", String.valueOf( TEXT_KEEP_ALIVE_SEC ) );
            return Math.max( 0, Integer.parseInt( v.trim() ) );
        }
        catch( final Exception e ) { return TEXT_KEEP_ALIVE_SEC; }
    }

    /**
     * Rate-limit: minimum ms between successive Ollama calls dispatched by the
     * queue worker. Requests that arrive while a call is in-flight are held in the
     * queue; only the oldest waiting request fires when the interval elapses.
     * 5s (was 2s): peer-reaction storms (5 generations in 16s observed) created
     * sustained CPU/GPU bursts whose kernel/compositor contention stuttered the
     * tick loop even with priority separation active. Same total work, spread
     * out — replies landing a few seconds later is fine by design.
     */
    private static final long DISPATCH_INTERVAL_MS = 5_000L;

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

    /** Wall-clock of the most recent dispatch by ANY client — lets background tasks
     *  (e.g. SituationModel synthesis) yield while the model is in active use. */
    private static volatile long lastGlobalDispatchMs = 0L;

    /** ms since any OllamaClient last dispatched a request, or a large value if none yet. */
    public static long msSinceLastDispatch( )
    {
        return ( lastGlobalDispatchMs == 0L ) ? Long.MAX_VALUE / 2
                                              : System.currentTimeMillis( ) - lastGlobalDispatchMs;
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
                lastGlobalDispatchMs = lastDispatchMs;
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
        final int    keepAlive     = isVision ? VISION_KEEP_ALIVE_SEC : textKeepAliveSec();
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
            // Read the error body (also drains the stream so the keep-alive
            // connection can be reused) — Ollama's message names the real cause,
            // e.g. a crashed llama-server, which "HTTP 500" alone hides.
            String detail = "";
            try( final java.io.InputStream es = conn.getErrorStream() )
            {
                if( es != null )
                {
                    final byte[] buf = new byte[ 2048 ];
                    final StringBuilder eb = new StringBuilder();
                    int n;
                    while( ( n = es.read( buf ) ) > 0 && eb.length() < 600 )
                        eb.append( new String( buf, 0, n, StandardCharsets.UTF_8 ) );
                    detail = eb.toString().trim();
                    if( detail.length() > 300 ) detail = detail.substring( 0, 300 ) + "...";
                }
            }
            catch( final IOException ignored ) {}
            throw new IOException( "HTTP " + status + " from Ollama (model=" + effectiveModel + ")"
                + ( detail.isEmpty() ? "" : ": " + detail ) );
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
     * Fraction of the machine's *available* CPU the model is allowed to use.
     * Read from settings "OllamaResourceCap" (clamped 0.1-1.0, default 0.5).
     * 1.0 disables capping (num_thread omitted; Ollama's own thread default).
     * CPU-compute only: GPU layer placement is NOT capped — an explicit num_gpu
     * split both shifted weights into system RAM (vetoed by Ko: throttle compute,
     * never RAM/VRAM) and crashed llama-server on gemma4 with
     * GGML_ASSERT(n_inputs < GGML_SCHED_MAX_SPLIT_INPUTS) (June 2026).
     * "Available" is measured at dispatch time — the request queue is serial, so
     * the model is idle when measured and its own usage never counts against itself.
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
     * Rounds an availability fraction to quarter steps (floor 0.25). num_thread is
     * a model-LOAD option in Ollama — a value that wobbles with every load sample
     * would force a full model reload on each request. Quarter buckets keep the
     * option identical across small load fluctuations.
     */
    private static double bucketQuarter( final double f )
    {
        final double b = Math.round( f * 4.0 ) / 4.0;
        return Math.max( 0.25, Math.min( 1.0, b ) );
    }

    /**
     * GPU layer offload is always left to Ollama's byte-accurate auto-fit (-1):
     * it measures free VRAM at load, so a running game already shrinks what the
     * model claims. Explicit num_gpu values are never sent — every fraction-based
     * split tried here either moved weights into system RAM (the old 0.20 cap cut
     * gemma3:4b from 82 to 15 tok/s and caused system-wide CPU spikes) or crashed
     * llama-server outright (gemma4 + partial offload hit
     * GGML_ASSERT(n_inputs < GGML_SCHED_MAX_SPLIT_INPUTS), June 2026 — every
     * request 500'd until the cap was removed).
     */
    private int computeNumGpuLayers()
    {
        return -1;
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
            // Extract the model name from "HTTP 404 from Ollama (model=NAME)..."
            // — an error-body detail may follow the closing parenthesis.
            String name = DEFAULT_MODEL;
            final int m = msg.indexOf( "model=" );
            if( m >= 0 )
            {
                final int close = msg.indexOf( ')', m );
                name = ( close > m ? msg.substring( m + 6, close )
                                   : msg.substring( m + 6 ) ).trim();
            }
            return "Model not found: " + name + ". Run: ollama pull " + name;
        }
        return "Couldn't reach Ollama: " + ( msg != null ? msg : e.getClass().getSimpleName() );
    }
}
