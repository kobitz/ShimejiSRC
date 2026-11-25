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
 * Thin async client for the configured AI provider.
 *
 * <p>Two transports share the same queue/callback surface:
 * <ul>
 *   <li><b>ollama</b> (default) — POST {@code /api/generate} to a local Ollama server,
 *       keeping the original {@code system}/{@code prompt}/{@code images} body,
 *       keep-alive, resource caps and CPU-load gating.</li>
 *   <li><b>openai</b> — POST {@code /chat/completions} to any OpenAI-compatible
 *       endpoint (OpenAI, OpenRouter, Groq, Together, LM Studio, llama.cpp, vLLM…)
 *       using a bearer API key and {@code messages} with {@code image_url} content.
 *       Remote calls are not CPU-gated and use a shorter dispatch floor, since they
 *       do not compete with the local tick loop.</li>
 * </ul>
 * Provider selection lives in {@link AiSettings}. The callback is always invoked on
 * the queue worker thread — callers must dispatch to EDT themselves if they update Swing.
 *
 * If the provider is unreachable, the error callback receives a friendly message
 * rather than a stack trace.
 */
public class OllamaClient
{
    private static final Logger log = Logger.getLogger( OllamaClient.class.getName() );

    /** Default endpoint — change if the user runs Ollama on a different port. */
    public static final String DEFAULT_ENDPOINT = AiSettings.DEFAULT_OLLAMA_ENDPOINT;

    /** Remote (cloud) endpoints are cheap locally but cost money per call; keep a
     *  small floor so reaction storms cannot burst the provider's rate limit. */
    private static final long REMOTE_DISPATCH_INTERVAL_MS = 1_000L;

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

    /** Poll cadence while a deferrable request waits for CPU headroom (ms). */
    private static final long THERMAL_POLL_MS = 1500L;

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
        final boolean  deferrable;    // true: queue worker may hold/drop it under CPU load
        final Callback callback;

        Request( String system, String user, int maxTokens, Callback callback )
        {
            this( system, user, null, null, maxTokens, false, callback );
        }

        Request( String system, String user, String imageBase64, String modelOverride,
                 int maxTokens, Callback callback )
        {
            this( system, user, imageBase64, modelOverride, maxTokens, false, callback );
        }

        Request( String system, String user, String imageBase64, String modelOverride,
                 int maxTokens, boolean deferrable, Callback callback )
        {
            this.system        = system;
            this.user          = user;
            this.imageBase64   = imageBase64;
            this.modelOverride = modelOverride;
            this.maxTokens     = maxTokens;
            this.deferrable    = deferrable;
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

    private final String provider;   // AiSettings.PROVIDER_OLLAMA or PROVIDER_OPENAI
    private final String endpoint;   // full chat URL for the selected provider
    private final String model;      // text model
    private final String apiKey;     // bearer token (remote only; may be "")
    private final boolean remote;    // true = OpenAI-compatible cloud/remote provider

    /**
     * Bounded queue — holds pending requests while the worker is busy.
     * ArrayBlockingQueue is FIFO; offer() returns false immediately if full.
     */
    private final BlockingQueue<Request> requestQueue =
        new ArrayBlockingQueue<>( MAX_QUEUE_DEPTH );

    /** Single worker thread that drains the queue at the provider's dispatch cadence. */
    private final Thread queueWorker;

    /** Client built from the current settings (the normal construction path). */
    public static OllamaClient fromSettings( )
    {
        return new OllamaClient( AiSettings.provider( ), AiSettings.chatUrl( ),
                                 AiSettings.textModel( ), AiSettings.apiKey( ) );
    }

    /** Local Ollama client (back-compat; endpoint is the full /api/generate URL). */
    public OllamaClient()
    {
        this( AiSettings.PROVIDER_OLLAMA, DEFAULT_ENDPOINT, DEFAULT_MODEL, "" );
    }

    /** Local Ollama client (back-compat). */
    public OllamaClient( final String endpoint, final String model )
    {
        this( AiSettings.PROVIDER_OLLAMA, endpoint, model, "" );
    }

    /** Explicit provider constructor. */
    public OllamaClient( final String provider, final String endpoint,
                         final String model, final String apiKey )
    {
        this.provider = AiSettings.PROVIDER_OPENAI.equalsIgnoreCase( provider )
            ? AiSettings.PROVIDER_OPENAI : AiSettings.PROVIDER_OLLAMA;
        this.remote   = AiSettings.PROVIDER_OPENAI.equals( this.provider );
        if( remote && AiSettings.isInsecureRemote( ) )
            log.warning( "[AI] The API base URL uses plain http to a non-local host; the key and your prompts travel unencrypted. Use https." );
        this.endpoint = endpoint;
        this.model    = model;
        this.apiKey   = apiKey == null ? "" : apiKey;
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

                // Enforce minimum interval from the last dispatch start. Remote
                // providers do not burn local CPU/GPU, so they get a shorter floor.
                final long interval = remote ? REMOTE_DISPATCH_INTERVAL_MS : DISPATCH_INTERVAL_MS;
                final long wait = interval
                    - ( System.currentTimeMillis() - lastDispatchMs );
                if( wait > 0 )
                    Thread.sleep( wait );

                // CPU-load backpressure: deferrable (unprompted) reactions yield while the
                // system is already busy, so they don't pile inference heat onto real work
                // and push the CPU toward the campfire's 80C cooler-boost trigger. Load
                // leads temperature, so gating on load acts before the heat exists. Direct
                // user-initiated replies are never deferrable and skip this entirely.
                //
                // Remote API calls run no local inference, so they are never gated on
                // local CPU/GPU load — a cloud model does not heat this machine.
                if( req.deferrable && !remote && !awaitCpuHeadroom( req ) )
                    continue;   // dropped (system stayed busy) — onError already fired

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
     * Hold a deferrable request while the CPU (and, if enabled, GPU) is above its gate.
     * Measured here, on the serial worker, the model is idle — so the reading is the
     * AMBIENT load (the user's real work), never the request's own inference. Returns
     * true once there is headroom (dispatch it); returns false if the system stays busy
     * past OllamaThermalDeferMaxMs, having already called the request's onError so a
     * "Thinking..." bubble doesn't hang. A gate value >= 100 disables that signal, so the
     * default OllamaGpuGate=100 makes the GPU half inert (CPU-only gating). An unknown
     * sensor reading (-1) counts as "not hot" — never gate on a value we can't measure.
     */
    private boolean awaitCpuHeadroom( final Request req )
    {
        final long   start    = System.currentTimeMillis();
        final long   maxDefer = thermalMaxDeferMs();
        final double loadGate = loadGate();
        final double gpuGate  = gpuGate();
        boolean logged = false;
        while( true )
        {
            final com.group_finity.mascot.environment.CpuTempMonitor m =
                com.group_finity.mascot.environment.CpuTempMonitor.getInstance();
            final double cpu = m.getCpuLoad();
            final double gpu = m.getGpuLoad();
            final boolean cpuHot = loadGate < 100 && cpu >= 0 && cpu >= loadGate;
            final boolean gpuHot = gpuGate  < 100 && gpu >= 0 && gpu >= gpuGate;
            if( !cpuHot && !gpuHot )
                return true;

            // Never let a held reaction delay a direct user-initiated reply: if any
            // non-deferrable request is waiting behind us, drop this one and yield.
            for( final Request pending : requestQueue )
                if( !pending.deferrable )
                {
                    try { req.callback.onError( "Skipped — yielding to user request." ); }
                    catch( final Exception ignored ) {}
                    return false;
                }

            if( System.currentTimeMillis() - start >= maxDefer )
            {
                log.log( Level.WARNING,
                    "[OllamaQueue] Dropped deferred request after {0}ms — CPU {1}% (gate {2}%)",
                    new Object[]{ System.currentTimeMillis() - start, (int) cpu, (int) loadGate } );
                try { req.callback.onError( "Skipped — system busy." ); }
                catch( final Exception ignored ) {}
                return false;
            }
            if( !logged )
            {
                log.log( Level.FINE, "[OllamaQueue] Deferring request — CPU {0}% >= gate {1}%",
                         new Object[]{ (int) cpu, (int) loadGate } );
                logged = true;
            }
            try { Thread.sleep( THERMAL_POLL_MS ); }
            catch( final InterruptedException e )
            {
                Thread.currentThread().interrupt();
                try { req.callback.onError( "Skipped — shutting down." ); }
                catch( final Exception ignored ) {}
                return false;
            }
        }
    }

    /** CPU-load gate percent (OllamaLoadGate, default 60). >= 100 disables CPU gating. */
    private static double loadGate( )
    {
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance()
                .getProperties().getProperty( "OllamaLoadGate", "60" );
            return Math.max( 1.0, Math.min( 100.0, Double.parseDouble( v.trim() ) ) );
        }
        catch( final Exception e ) { return 60.0; }
    }

    /** GPU-load gate percent (OllamaGpuGate, default 100 = inert). >= 100 disables GPU gating. */
    private static double gpuGate( )
    {
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance()
                .getProperties().getProperty( "OllamaGpuGate", "100" );
            return Math.max( 1.0, Math.min( 100.0, Double.parseDouble( v.trim() ) ) );
        }
        catch( final Exception e ) { return 100.0; }
    }

    /** Max time a deferrable request is held before it's dropped (OllamaThermalDeferMaxMs, default 25000). */
    private static long thermalMaxDeferMs( )
    {
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance()
                .getProperties().getProperty( "OllamaThermalDeferMaxMs", "25000" );
            return Math.max( 0L, Long.parseLong( v.trim() ) );
        }
        catch( final Exception e ) { return 25000L; }
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
        enqueue( new Request( systemPrompt, userMessage, maxTokens, callback ) );
    }

    /**
     * Like {@link #generate} but the request is DEFERRABLE: the queue worker holds it
     * while the CPU is above OllamaLoadGate and drops it after OllamaThermalDeferMaxMs if
     * the system stays busy. Use ONLY for unprompted reactions / background tasks — never
     * for direct user-initiated replies, which must always answer.
     */
    public void generate( final String systemPrompt,
                          final String userMessage,
                          final int maxTokens,
                          final boolean deferrable,
                          final Callback callback )
    {
        enqueue( new Request( systemPrompt, userMessage, null, null, maxTokens, deferrable, callback ) );
    }

    /** Convenience: a deferrable request with the default token cap. */
    public void generateDeferrable( final String systemPrompt,
                                    final String userMessage,
                                    final Callback callback )
    {
        generate( systemPrompt, userMessage, MAX_TOKENS, true, callback );
    }

    /** Enqueue, dropping the oldest pending request if the queue is full so the newest
     *  (most relevant) request stays in the pipeline. */
    private void enqueue( final Request req )
    {
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
     * Like {@link #generate} but attaches a base64-encoded image and uses the specified
     * vision model. Routed through the SAME queue as text (June 2026), so vision requests
     * obey the DISPATCH_INTERVAL_MS spacing instead of bypassing it — one generation at a
     * time, spaced, whether it's text or vision. {@code deferrable}=false for direct user
     * screen queries (spaced, but never CPU-gated or dropped); true for unprompted screen
     * glances (also CPU-gated like the other unprompted reactions). Larger token cap
     * (MAX_TOKENS*3) for descriptive vision output. {@link sendRequest} already handles the
     * image + vision-model + keep-alive on the shared worker.
     */
    public void generateWithImage( final String systemPrompt,
                                   final String userMessage,
                                   final String imageBase64,
                                   final String visionModel,
                                   final boolean deferrable,
                                   final Callback callback )
    {
        enqueue( new Request( systemPrompt, userMessage, imageBase64, visionModel,
                              MAX_TOKENS * 3, deferrable, callback ) );
    }

    /** Back-compat: non-deferrable vision (direct user screen query) — queued + spaced. */
    public void generateWithImage( final String systemPrompt,
                                   final String userMessage,
                                   final String imageBase64,
                                   final String visionModel,
                                   final Callback callback )
    {
        generateWithImage( systemPrompt, userMessage, imageBase64, visionModel, false, callback );
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String sendRequest( final String system, final String user,
                                 final String imageBase64, final String modelOverride,
                                 final int maxTokens ) throws IOException
    {
        final String effectiveModel = modelOverride != null ? modelOverride : model;
        final String body;
        if( remote )
        {
            body = buildOpenAiJson( system, user, maxTokens, imageBase64, effectiveModel );
        }
        else
        {
            final int    numGpu    = computeNumGpuLayers();
            final int    numThread = computeNumThreads();
            // Vision requests normally unload immediately (rare + large), but when the
            // vision model IS the text model (shared multimodal setup), keep_alive:0
            // would evict the model the text path is paying TEXT_KEEP_ALIVE_SEC to
            // keep warm — so shared models always use the text linger.
            final boolean isVision  = imageBase64 != null && !effectiveModel.equals( model );
            final int     keepAlive = isVision ? VISION_KEEP_ALIVE_SEC : textKeepAliveSec();
            body = buildJson( system, user, numGpu, numThread, maxTokens,
                              imageBase64, effectiveModel, keepAlive );
        }
        final byte[] bodyBytes = body.getBytes( StandardCharsets.UTF_8 );

        final URL url = new URL( endpoint );
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod( "POST" );
        conn.setRequestProperty( "Content-Type", "application/json; charset=utf-8" );
        conn.setRequestProperty( "Accept", "application/json" );
        if( remote && !apiKey.isEmpty() )
            conn.setRequestProperty( "Authorization", "Bearer " + apiKey );
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
            // connection can be reused) — the provider's message names the real
            // cause, e.g. a bad key or an unknown model, which "HTTP 401" hides.
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
            throw new IOException( "HTTP " + status + " from "
                + ( remote ? "AI API" : "Ollama" ) + " (model=" + effectiveModel + ")"
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

        return remote ? parseOpenAiResponse( sb.toString() ) : parseResponse( sb.toString() );
    }

    /**
     * Build an OpenAI-compatible /chat/completions body.
     * Text-only requests send a plain string content; vision requests send the
     * multipart content array with a data: URL image, which is the format every
     * major compatible provider (OpenAI, OpenRouter, Groq, llama.cpp, vLLM, …) accepts.
     */
    private String buildOpenAiJson( final String system, final String user,
                                    final int maxTokens, final String imageBase64,
                                    final String modelName )
    {
        final StringBuilder sb = new StringBuilder( 256 + user.length( ) );
        sb.append( "{\"model\":" ).append( jsonStr( modelName ) ).append( ',' );
        sb.append( "\"messages\":[" );
        if( system != null && !system.isEmpty( ) )
            sb.append( "{\"role\":\"system\",\"content\":" ).append( jsonStr( system ) ).append( "}," );
        sb.append( "{\"role\":\"user\",\"content\":" );
        if( imageBase64 != null )
        {
            sb.append( "[{\"type\":\"text\",\"text\":" ).append( jsonStr( user ) ).append( "}," );
            sb.append( "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," )
              .append( imageBase64 ).append( "\"}}]" );
        }
        else
        {
            sb.append( jsonStr( user ) );
        }
        sb.append( "}]," );
        sb.append( '"' ).append( AiSettings.maxTokensField( ) ).append( "\":" ).append( maxTokens ).append( ',' );
        sb.append( "\"stream\":false}" );
        return sb.toString( );
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
        // Cores actually available to Ollama, not the raw logical count — it's
        // affinity-restricted away from the cores reserved for Shimeji (see
        // ProcessPriorityUtil), so a thread count based on the full core
        // count would oversubscribe its own smaller allotted set.
        final int totalCores = Runtime.getRuntime().availableProcessors();
        final int reserved   = com.group_finity.mascot.environment.ProcessPriorityUtil.reservedCoreCount();
        final int cores       = Math.max( 1, totalCores - reserved );
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

    /**
     * Parse an OpenAI-compatible /chat/completions reply.
     * Standard shape: {"choices":[{"message":{"role":"assistant","content":"..."}}]}
     * Some providers return content as an array of text parts; both are handled.
     */
    private String parseOpenAiResponse( final String json )
    {
        final int choices = json.indexOf( "\"choices\"" );
        if( choices < 0 )
            return "(no choices field in AI reply)";

        int key = json.indexOf( "\"content\"", choices );
        if( key < 0 )
            return "(no content field in AI reply)";

        int i = key + "\"content\"".length( );
        while( i < json.length( ) && isJsonSpace( json.charAt( i ) ) ) i++;
        if( i < json.length( ) && json.charAt( i ) == ':' ) i++;
        while( i < json.length( ) && isJsonSpace( json.charAt( i ) ) ) i++;
        if( i >= json.length( ) )
            return "";

        if( json.charAt( i ) == '"' )
        {
            final int[] next = new int[ 1 ];
            final String text = readJsonString( json, i, next );
            return text == null ? "" : text.trim( );
        }

        if( json.charAt( i ) == '[' )
        {
            // Content-parts array: concatenate every "text" value.
            final StringBuilder out = new StringBuilder( );
            int p = i;
            while( ( p = json.indexOf( "\"text\"", p ) ) >= 0 )
            {
                int colon = json.indexOf( ':', p + 6 );
                if( colon < 0 ) break;
                int q = colon + 1;
                while( q < json.length( ) && isJsonSpace( json.charAt( q ) ) ) q++;
                if( q < json.length( ) && json.charAt( q ) == '"' )
                {
                    final int[] next = new int[ 1 ];
                    final String part = readJsonString( json, q, next );
                    if( part != null ) out.append( part );
                    p = next[ 0 ];
                }
                else
                {
                    p = colon + 1;
                }
            }
            return out.toString( ).trim( );
        }

        if( json.startsWith( "null", i ) )
            return "";
        return "(unexpected AI response format)";
    }

    private static boolean isJsonSpace( final char c )
    {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    /**
     * Read a JSON string starting at {@code openQuote}. Handles the standard escapes
     * and {@code \\uXXXX}. Sets {@code next[0]} to the index after the closing quote.
     * Returns null on malformed input.
     */
    private static String readJsonString( final String json, final int openQuote, final int[] next )
    {
        if( openQuote >= json.length( ) || json.charAt( openQuote ) != '"' ) return null;
        final StringBuilder sb = new StringBuilder( );
        int i = openQuote + 1;
        while( i < json.length( ) )
        {
            final char c = json.charAt( i );
            if( c == '\\' && i + 1 < json.length( ) )
            {
                final char n = json.charAt( i + 1 );
                switch( n )
                {
                    case '"':  sb.append( '"' );  i += 2; break;
                    case '\\': sb.append( '\\' ); i += 2; break;
                    case '/':  sb.append( '/' );  i += 2; break;
                    case 'b':  sb.append( '\b' ); i += 2; break;
                    case 'f':  sb.append( '\f' ); i += 2; break;
                    case 'n':  sb.append( '\n' ); i += 2; break;
                    case 'r':  sb.append( '\r' ); i += 2; break;
                    case 't':  sb.append( '\t' ); i += 2; break;
                    case 'u':
                        if( i + 6 <= json.length( ) )
                        {
                            try
                            {
                                sb.append( (char) Integer.parseInt(
                                    json.substring( i + 2, i + 6 ), 16 ) );
                                i += 6;
                            }
                            catch( final NumberFormatException e )
                            {
                                sb.append( n );
                                i += 2;
                            }
                        }
                        else
                        {
                            sb.append( n );
                            i += 2;
                        }
                        break;
                    default: sb.append( n ); i += 2; break;
                }
            }
            else if( c == '"' )
            {
                next[ 0 ] = i + 1;
                return sb.toString( );
            }
            else
            {
                sb.append( c );
                i++;
            }
        }
        next[ 0 ] = i;
        return sb.toString( );
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

    private String buildFriendlyError( final IOException e )
    {
        final String msg = e.getMessage( );
        final String lower = msg == null ? "" : msg.toLowerCase( java.util.Locale.ROOT );

        if( lower.contains( "connection refused" ) )
        {
            if( remote )
                return "AI API isn't reachable. Check AiBaseUrl (" + endpoint + ").";
            return "Ollama isn't running. Start it with: ollama serve";
        }
        if( lower.contains( "http 401" ) || lower.contains( "http 403" ) )
        {
            if( remote )
                return "AI API rejected the key (HTTP " + ( lower.contains( "http 401" ) ? "401" : "403" )
                    + "). Check AiApiKey in Settings.";
            return "Ollama rejected the request (HTTP " + ( lower.contains( "http 401" ) ? "401" : "403" )
                + "). Check AiApiKey / OllamaEndpoint.";
        }
        if( lower.contains( "http 429" ) )
            return "AI provider rate limit hit (HTTP 429). Try again shortly or use a smaller model.";
        if( lower.contains( "http 404" ) )
        {
            // Extract the model name from "... (model=NAME)..."
            String name = model;
            final int m = msg.indexOf( "model=" );
            if( m >= 0 )
            {
                final int close = msg.indexOf( ')', m );
                name = ( close > m ? msg.substring( m + 6, close )
                                   : msg.substring( m + 6 ) ).trim( );
            }
            if( remote )
                return "Model or endpoint not found: " + name
                    + ". Check AiTextModel/AiBaseUrl (some providers need the /v1 suffix).";
            return "Model not found: " + name + ". Run: ollama pull " + name;
        }
        return "Couldn't reach " + ( remote ? "the AI API" : "Ollama" ) + ": "
            + ( msg != null ? msg : e.getClass( ).getSimpleName( ) );
    }
}
