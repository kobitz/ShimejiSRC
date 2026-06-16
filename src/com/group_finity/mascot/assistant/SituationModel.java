package com.group_finity.mascot.assistant;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.NativeFactory;
import com.group_finity.mascot.environment.CpuTempMonitor;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Situational synthesis layer -- LAYER A (rule-based sampling, NO LLM).
 *
 * One shared singleton daemon (sibling to CpuTempMonitor / DriveIndexTool, started
 * idempotently from the Mascot constructor when assistantMode is on) maintains a
 * continuous, FUSED read of what the user is doing -- so mascots can react against
 * one situational picture instead of a single raw signal (one window title, one audio
 * clip). This Layer A is intentionally LLM-free: it only samples cheap signals and
 * derives a rule-based summary, so it costs nothing and carries no repetition/poisoning
 * risk. Layer B (a small periodic LLM synthesis) and Layer C (prompt-injection +
 * salience-gated proactivity) layer on top of the same Situation snapshot later.
 *
 * Signals sampled every SAMPLE_MS (all already available, all cheap):
 *   - foreground window title -> coalesced into app-sessions (keyed by the trailing
 *     "... - App" / "... -- App" token, the common Windows convention)
 *   - cpuLoad / gpuLoad (CpuTempMonitor), ramLoad (OS MX bean), system audio RMS
 *   - activity tempo: window-switch rate, idle detection, session length, time-of-day
 *
 * Output: an immutable Situation snapshot in an AtomicReference, recomputed each cycle.
 * Routine summaries log at FINE; state transitions / high-salience events log at INFO.
 *
 * Everything stays local. Setting: SituationModelEnabled (default true).
 */
public class SituationModel
{
    private static final Logger log = Logger.getLogger( SituationModel.class.getName( ) );

    private static final long SAMPLE_MS          = 5_000;             // poll cadence
    private static final long WINDOW_MS          = 60L * 60 * 1000;   // rolling memory horizon
    private static final long TEMPO_WINDOW_MS    = 3L * 60 * 1000;    // window-switch counting window
    private static final long FOCUS_WINDOW_MS    = 10L * 60 * 1000;   // dominance window for "focused"
    private static final long IDLE_MS            = 5L * 60 * 1000;    // same foreground this long => idle
    private static final long MIN_FOCUS_MS       = 2L * 60 * 1000;    // min session age to call it "focused"
    private static final int  MAX_SESSIONS       = 40;
    private static final int  AUDIO_ON_RMS       = 600;             // RMS to call audio "playing"
    private static final int  AUDIO_OFF_RMS      = 250;             // RMS to call it "stopped" (hysteresis, anti-flicker)
    private static final long AMBIENT_ACTIVE_MS  = 600_000;        // salient pulse every 10 min during sustained activity
    private static final int  MULTITASK_SWITCHES = 4;                // switches in TEMPO_WINDOW => multitasking
    private static final double FOCUS_FRACTION   = 0.8;              // share of FOCUS_WINDOW in one app
    private static final double SALIENT_THRESHOLD = 0.5;            // salience >= this == a "salient change"
    private static final long   SELF_AUDIO_MUTE_MS = 4_000;        // ignore loopback audio this long after a mascot SFX

    // ── Layer B: periodic LLM synthesis ──
    private static final long SYNTH_INTERVAL_MS      = 240_000;     // synthesis cadence (4 min)
    private static final long SYNTH_RETRY_MS         = 30_000;      // retry sooner when gated (model busy / no data)
    private static final long SYNTH_QUIET_MS         = 15_000;      // skip if any generation dispatched this recently
    private static final long SYNTH_INITIAL_DELAY_MS = 60_000;      // wait after launch so there's data to summarize
    private static final int  SYNTH_MAX_TOKENS       = 100;
    private static final String SYNTH_SYSTEM =
          "You summarize what a computer user is currently doing, for a desktop companion's situational awareness."
        + " Output EXACTLY two lines and nothing else:"
        + "\nSUMMARY: one neutral third-person sentence (max 25 words) describing the user's current activity and recent pattern."
        + "\nMOOD: one lowercase word for the user's likely state (focused, relaxed, frustrated, tired, ...), or 'neutral' if unclear."
        + " Base it ONLY on the data given; do not invent details, do not give advice, and when unsure about mood say neutral.";

    private static SituationModel instance;
    private static volatile boolean started = false;

    /** Idempotent start, mirroring DriveIndexTool.ensureStarted(). Safe to call per mascot. */
    public static synchronized void ensureStarted( )
    {
        if( started ) return;
        if( !Boolean.parseBoolean( Main.getInstance( ).getProperties( )
                .getProperty( "SituationModelEnabled", "true" ) ) )
            return;
        getInstance( );
        started = true;
    }

    public static synchronized SituationModel getInstance( )
    {
        if( instance == null )
        {
            instance = new SituationModel( );
            instance.start( );
        }
        return instance;
    }

    // ── A coalesced run of time spent in one app ────────────────────────────────
    private static final class Session
    {
        final String app;
        final long   firstMs;
        long         lastMs;
        String       latestTitle;
        Session( String app, String title, long now )
        {
            this.app = app; this.latestTitle = title; this.firstMs = now; this.lastMs = now;
        }
    }

    private static final class Sample
    {
        final long ms; final double cpu;
        Sample( long ms, double cpu ) { this.ms = ms; this.cpu = cpu; }
    }

    /** Immutable snapshot consumed by mascots (Layer C will inject asContextBlock()). */
    public static final class Situation
    {
        public final String  state;          // focused / multitasking / idle / winding-down / active
        public final String  activity;       // e.g. "Visual Studio Code (~42m)"
        public final String  notable;        // recent delta worth a reaction, or ""
        public final double  salience;       // 0..1 -- how much just changed
        public final long    sessionMinutes;
        public final double  cpuLoad, gpuLoad, ramLoad;
        public final boolean audioPlaying;
        public final boolean lateNight;
        public final String  summary;        // Layer B: LLM narrative, or "" (falls back to rule-based facts)
        public final String  mood;           // Layer B: conservative one-word mood, or "" / "neutral"

        Situation( String state, String activity, String notable, double salience, long sessionMinutes,
                   double cpuLoad, double gpuLoad, double ramLoad, boolean audioPlaying, boolean lateNight,
                   String summary, String mood )
        {
            this.state = state; this.activity = activity; this.notable = notable;
            this.salience = salience; this.sessionMinutes = sessionMinutes;
            this.cpuLoad = cpuLoad; this.gpuLoad = gpuLoad; this.ramLoad = ramLoad;
            this.audioPlaying = audioPlaying; this.lateNight = lateNight;
            this.summary = summary; this.mood = mood;
        }

        /** Compact context block for prompt injection / logging. Leads with the LLM narrative
         *  when present (Layer B), else the rule-based facts (Layer A). No hedging in output. */
        public String asContextBlock( )
        {
            StringBuilder sb = new StringBuilder( );
            if( summary != null && !summary.isEmpty( ) ) sb.append( summary ).append( ' ' );
            else sb.append( "User state: " ).append( state ).append( ". " );
            sb.append( "Activity: " ).append( activity ).append( '.' );
            if( audioPlaying ) sb.append( " Audio is playing." );
            if( lateNight )    sb.append( " It is late at night." );
            if( mood != null && !mood.isEmpty( ) && !mood.equalsIgnoreCase( "neutral" ) )
                sb.append( " Inferred mood: " ).append( mood ).append( '.' );
            if( notable != null && !notable.isEmpty( ) ) sb.append( " Recent change: " ).append( notable ).append( '.' );
            return sb.toString( );
        }
    }

    private final AtomicReference<Situation> current = new AtomicReference<>(
        new Situation( "active", "(starting up)", "", 0, 0, -1, -1, -1, false, false, "", "" ) );

    private final List<Session> sessions = new ArrayList<>( );
    private final Deque<Sample>  samples = new ArrayDeque<>( );
    private String  prevApp          = null;
    private String  pendingApp       = null;   // candidate app awaiting a 2nd-sample confirmation (debounce)
    private boolean prevAudioPlaying = false;
    private String  prevState        = "active";
    private volatile long lastSalientMs   = 0; // wall-clock of last salient change (read cross-thread)
    private volatile long lastSelfAudioMs = 0; // wall-clock of last mascot-played sound (self-reference guard)
    private long lastActivityPulseMs = 0;      // last ambient activity pulse (loop thread only)
    private volatile String synthSummary = "";  // Layer B LLM narrative (written on worker thread, read on loop thread)
    private volatile String synthMood    = "";
    private OllamaClient synthClient = null;     // lazy; only the loop thread touches the reference
    private long nextSynthAtMs = System.currentTimeMillis( ) + SYNTH_INITIAL_DELAY_MS; // loop thread only

    private SituationModel( ) { }

    private void start( )
    {
        Thread t = new Thread( this::loop, "SituationModel" );
        t.setDaemon( true );
        t.setPriority( Thread.MIN_PRIORITY );
        t.start( );
    }

    /** Current fused situation snapshot. Never null. */
    public Situation current( ) { return current.get( ); }

    /** True once the daemon is running (respects SituationModelEnabled). */
    public static boolean isActive( ) { return started; }

    /** ms since the last salient change (>= SALIENT_THRESHOLD), or a large value if none yet. */
    public long msSinceSalientChange( )
    {
        return ( lastSalientMs == 0 ) ? Long.MAX_VALUE / 2 : System.currentTimeMillis( ) - lastSalientMs;
    }

    /** Timestamp of the most recent salient event or activity pulse (for edge-triggered consumers). */
    public long salientStamp( ) { return lastSalientMs; }

    /**
     * Called when a mascot plays a sound. Its own audio reaches the WASAPI loopback that feeds
     * the audio signal, so freeze that signal briefly -- the companion must never treat its own
     * SFX as a change in the user's environment (self-reference guard). No-op until the daemon runs.
     */
    public static void noteSelfAudio( )
    {
        if( instance != null ) instance.lastSelfAudioMs = System.currentTimeMillis( );
    }

    private void loop( )
    {
        while( true )
        {
            try { sampleOnce( ); }
            catch( Exception e ) { log.log( Level.FINE, "situation sample error", e ); }
            try { maybeSynthesize( ); }
            catch( Exception e ) { log.log( Level.FINE, "situation synth error", e ); }
            try { Thread.sleep( SAMPLE_MS ); }
            catch( InterruptedException e ) { Thread.currentThread( ).interrupt( ); return; }
        }
    }

    private void sampleOnce( )
    {
        final long now = System.currentTimeMillis( );

        final String title = foregroundTitle( );
        final String app   = appKey( title );

        final CpuTempMonitor mon = CpuTempMonitor.getInstance( );
        final double cpu   = mon.getCpuLoad( );
        final double gpu   = mon.getGpuLoad( );
        final double ram   = ramLoad( );
        final int    audio = AudioTranscriptBuffer.currentSysRms;

        // ── coalesce app-sessions ──
        boolean appChanged = false;
        if( !sessions.isEmpty( ) && sessions.get( sessions.size( ) - 1 ).app.equals( app ) )
        {
            Session s = sessions.get( sessions.size( ) - 1 );
            s.lastMs = now;
            s.latestTitle = title;
            pendingApp = null;                  // back on the current app -- cancel any pending switch
        }
        else if( !app.equals( "(none)" ) )   // skip blank foreground -- also excludes our own bubble
                                             // (non-focusable) and reply dialog (undecorated/untitled => "(none)")
        {
            // Debounce: only commit a switch once the new app shows up on TWO
            // consecutive samples. A one-sample title blip -- e.g. a CLI spinner
            // glyph that survives normalization, or a transient popup -- must not
            // manufacture a session/switch (phantom salience + false multitasking).
            if( app.equals( pendingApp ) )
            {
                sessions.add( new Session( app, title, now ) );
                appChanged = ( prevApp != null );   // suppress the very first sample
                pendingApp = null;
            }
            else
            {
                pendingApp = app;                   // first sighting -- hold, don't switch yet
            }
        }
        sessions.removeIf( s -> s.lastMs < now - WINDOW_MS );
        while( sessions.size( ) > MAX_SESSIONS ) sessions.remove( 0 );

        samples.addLast( new Sample( now, cpu ) );
        while( !samples.isEmpty( ) && samples.peekFirst( ).ms < now - WINDOW_MS ) samples.removeFirst( );

        // ── derive ──
        // Freeze the audio signal right after a mascot plays a sound: loopback captures our
        // own SFX, so the companion must not read its own chirp as the user's environment changing.
        final boolean selfAudioRecent = ( now - lastSelfAudioMs ) < SELF_AUDIO_MUTE_MS;
        final boolean audioPlaying = selfAudioRecent ? prevAudioPlaying
            : ( prevAudioPlaying ? ( audio >= AUDIO_OFF_RMS ) : ( audio >= AUDIO_ON_RMS ) );
        final boolean lateNight    = isLateNight( );
        final Session cur          = sessions.isEmpty( ) ? null : sessions.get( sessions.size( ) - 1 );
        final long    sessionMin   = ( cur == null ) ? 0 : Math.max( 0, ( now - cur.firstMs ) / 60_000 );
        final int     switches     = countRecentSwitches( now );
        final boolean idle         = ( cur != null && ( now - cur.firstMs ) >= IDLE_MS
                                       && !audioPlaying && avgCpu( now ) < 15 );
        final String  state        = classify( idle, switches, lateNight, audioPlaying, cur, now );

        // ── notable delta + salience ──
        String notable  = "";
        double salience = 0;
        if( appChanged && cur != null )
        {
            notable = "switched to " + cur.app + ( prevApp != null ? " (from " + prevApp + ")" : "" );
            salience += 0.6;
        }
        if( audioPlaying != prevAudioPlaying )
        {
            salience += 0.5;   // audio onset/offset alone is a salient change (crosses threshold)
            if( notable.isEmpty( ) ) notable = audioPlaying ? "audio started" : "audio stopped";
        }
        if( !state.equals( prevState ) ) salience += 0.4;
        salience = Math.min( 1.0, salience );
        if( salience >= SALIENT_THRESHOLD ) lastSalientMs = now;

        // Activity-aware ambient pulse: during sustained activity (e.g. a long video) emit a
        // low-frequency salient pulse so the companion isn't silent for the whole stretch.
        // Suppress ONLY genuine idle and SILENT deep-work. A movie in one window also classifies
        // as "focused" (one app dominating), but audio playing means passive watching, so it
        // should still pulse -- only focused-WITHOUT-audio is true don't-interrupt deep work.
        final boolean silentDeepWork = state.equals( "focused" ) && !audioPlaying;
        if( !state.equals( "idle" ) && !silentDeepWork
            && now - lastActivityPulseMs >= AMBIENT_ACTIVE_MS )
        {
            lastActivityPulseMs = now;
            lastSalientMs       = now;
        }

        final String activity = ( cur == null ) ? "(no foreground window)"
                                                 : cur.app + " (~" + sessionMin + "m)";

        final Situation sit = new Situation( state, activity, notable, salience, sessionMin,
            cpu, gpu, ram, audioPlaying, lateNight, synthSummary, synthMood );
        current.set( sit );

        // routine summary at FINE; transitions / high-salience at INFO (matches the
        // VoiceCommandListener logging convention: per-cycle chatter invisible by default)
        log.fine( "[Situation] " + sit.asContextBlock( ) + " salience=" + fmt( salience ) );
        if( !state.equals( prevState ) || salience >= 0.6 )
        {
            log.info( "[Situation] state=" + state + " activity=" + activity
                + ( notable.isEmpty( ) ? "" : " notable=" + notable ) + " salience=" + fmt( salience ) );
        }

        if( cur != null ) prevApp = cur.app;
        prevAudioPlaying = audioPlaying;
        prevState        = state;
    }

    private String classify( boolean idle, int switches, boolean lateNight,
                             boolean audioPlaying, Session cur, long now )
    {
        if( idle ) return lateNight ? "winding-down" : "idle";
        if( switches >= MULTITASK_SWITCHES ) return "multitasking";
        if( cur != null && ( now - cur.firstMs ) >= MIN_FOCUS_MS
            && dominantFraction( cur.app, now ) >= FOCUS_FRACTION )
            return "focused";
        if( lateNight && audioPlaying ) return "winding-down";   // media at night
        return "active";
    }

    /** Number of distinct app-sessions begun within the last TEMPO_WINDOW (~switch count). */
    private int countRecentSwitches( long now )
    {
        int n = 0;
        for( Session s : sessions ) if( s.firstMs >= now - TEMPO_WINDOW_MS ) n++;
        return n;
    }

    /** Fraction of the last FOCUS_WINDOW spent in the given app (by coalesced session time). */
    private double dominantFraction( String app, long now )
    {
        final long from = now - FOCUS_WINDOW_MS;
        long appMs = 0, totalMs = 0;
        for( Session s : sessions )
        {
            long start = Math.max( s.firstMs, from );
            long end   = Math.max( start, s.lastMs );
            long dur   = end - start;
            if( dur <= 0 ) continue;
            totalMs += dur;
            if( s.app.equals( app ) ) appMs += dur;
        }
        return ( totalMs <= 0 ) ? 0 : (double) appMs / totalMs;
    }

    private double avgCpu( long now )
    {
        double sum = 0; int n = 0;
        final long from = now - 60_000;   // last minute
        for( Sample s : samples ) if( s.ms >= from && s.cpu >= 0 ) { sum += s.cpu; n++; }
        return ( n == 0 ) ? 0 : sum / n;
    }

    // ── Layer B: periodic LLM synthesis (enriches the rule-based read with a narrative + mood) ──
    // Runs on the sampling loop thread, so it reads sessions/current() safely; the async
    // callback only writes the volatile synthSummary/synthMood. Heavily gated to stay cheap.

    private void maybeSynthesize( )
    {
        final long now = System.currentTimeMillis( );
        if( now < nextSynthAtMs ) return;

        if( !Boolean.parseBoolean( Main.getInstance( ).getProperties( )
                .getProperty( "SituationSynthEnabled", "true" ) ) )
        {
            nextSynthAtMs = now + SYNTH_INTERVAL_MS;   // disabled — check again next cadence
            return;
        }
        // Don't pile on: a recent dispatch by any client means the user is likely
        // mid-conversation. Retry shortly rather than competing for the model.
        if( OllamaClient.msSinceLastDispatch( ) < SYNTH_QUIET_MS || sessions.isEmpty( ) )
        {
            nextSynthAtMs = now + SYNTH_RETRY_MS;
            return;
        }

        nextSynthAtMs = now + SYNTH_INTERVAL_MS;   // scheduled regardless of outcome

        if( synthClient == null )
        {
            final String model = Main.getInstance( ).getProperties( )
                .getProperty( "OllamaModel", OllamaClient.DEFAULT_MODEL );
            final String endpoint = Main.getInstance( ).getProperties( )
                .getProperty( "OllamaEndpoint", "http://localhost:11434/api/generate" );
            synthClient = new OllamaClient( endpoint, model );
        }

        final String user = buildSynthPrompt( );
        synthClient.generate( SYNTH_SYSTEM, user, SYNTH_MAX_TOKENS, new OllamaClient.Callback( )
        {
            @Override public void onResponse( String raw ) { applySynth( raw ); }
            @Override public void onError( String err )    { log.fine( "[Situation] synth error: " + err ); }
        } );
        log.fine( "[Situation] synthesis dispatched" );
    }

    private String buildSynthPrompt( )
    {
        final StringBuilder sb = new StringBuilder( "Recent apps (oldest first, minutes spent):\n" );
        final int from = Math.max( 0, sessions.size( ) - 6 );
        for( int i = from; i < sessions.size( ); i++ )
        {
            Session s = sessions.get( i );
            long mins = Math.max( 0, ( s.lastMs - s.firstMs ) / 60_000 );
            sb.append( "- " ).append( s.app ).append( ": " ).append( mins ).append( "m\n" );
        }
        final Situation cur = current.get( );
        sb.append( "Foreground now: " ).append( cur.activity ).append( '\n' );
        sb.append( "Heuristic state: " ).append( cur.state ).append( '\n' );
        sb.append( "Audio playing: " ).append( cur.audioPlaying ).append( '\n' );
        sb.append( "Time: " ).append( isLateNight( ) ? "late night" : "daytime/evening" )
          .append( " (hour " ).append( java.time.LocalTime.now( ).getHour( ) ).append( ")\n" );
        if( cur.cpuLoad >= 0 ) sb.append( "CPU load ~" ).append( Math.round( cur.cpuLoad ) ).append( "%\n" );
        if( !synthSummary.isEmpty( ) ) sb.append( "Previous summary: " ).append( synthSummary ).append( '\n' );
        return sb.toString( );
    }

    private void applySynth( String raw )
    {
        if( raw == null ) return;
        String summary = "", mood = "";
        for( String line : raw.split( "\\r?\\n" ) )
        {
            final String t   = line.trim( );
            final String low = t.toLowerCase( java.util.Locale.ROOT );
            if( low.startsWith( "summary:" ) )   summary = t.substring( 8 ).trim( );
            else if( low.startsWith( "mood:" ) ) mood    = t.substring( 5 ).trim( );
        }
        if( !summary.isEmpty( ) ) synthSummary = summary;
        if( !mood.isEmpty( ) )
            synthMood = mood.replaceAll( "[^A-Za-z -]", "" ).trim( ).toLowerCase( java.util.Locale.ROOT );
        log.info( "[Situation] synthesized: "
            + ( synthSummary.isEmpty( ) ? "(none)" : synthSummary )
            + ( synthMood.isEmpty( ) ? "" : " | mood=" + synthMood ) );
    }

    private double ramLoad( )
    {
        try
        {
            com.sun.management.OperatingSystemMXBean b = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean( );
            long total = b.getTotalMemorySize( );
            long free  = b.getFreeMemorySize( );
            return ( total <= 0 ) ? -1 : ( total - free ) * 100.0 / total;
        }
        catch( Exception e ) { return -1; }
    }

    private String foregroundTitle( )
    {
        try
        {
            com.group_finity.mascot.environment.Environment env =
                NativeFactory.getInstance( ).getEnvironment( );
            if( env == null ) return "";
            String t = env.getForegroundWindowTitle( );
            return ( t != null ) ? t.trim( ) : "";
        }
        catch( Exception e ) { return ""; }
    }

    /** Trailing "App" token after the last " - " / " -- " separator (Windows title convention). */
    private static String appKey( String title )
    {
        if( title == null || title.isBlank( ) ) return "(none)";
        int em = title.lastIndexOf( " — " );   // " -- " (em dash)
        int hy = title.lastIndexOf( " - " );
        int i  = Math.max( em, hy );
        String key = stripDecoration( ( i >= 0 ) ? title.substring( i + 3 ) : title );
        if( key.isEmpty( ) ) key = stripDecoration( title );
        return key.isEmpty( ) ? "(none)" : key;
    }

    /** Strip a leading run of decorative non-alphanumeric characters (and surrounding
     *  whitespace) -- e.g. the animated spinner glyph many CLIs prepend to the terminal
     *  title (Claude Code cycles "✳", "⠐", "⠂", ...). Separator-less titles otherwise
     *  go through appKey() whole, so that one changing glyph flips the key every sample
     *  and the coalescer reads it as a rapid app switch: phantom salience + false
     *  "multitasking" for a window the user never left. Also drops volatile leading
     *  notification counts like "(1) ". */
    private static String stripDecoration( String s )
    {
        if( s == null ) return "";
        s = s.trim( );
        int start = 0;
        while( start < s.length( ) && !Character.isLetterOrDigit( s.charAt( start ) ) )
            start++;
        return s.substring( start ).trim( );
    }

    private static boolean isLateNight( )
    {
        int h = java.time.LocalTime.now( ).getHour( );
        return h >= 22 || h < 5;
    }

    private static String fmt( double d ) { return String.format( "%.2f", d ); }
}
