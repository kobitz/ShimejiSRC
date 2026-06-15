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
    private static final int  AUDIO_ACTIVE_RMS   = 600;              // speaker energy => "sound playing"
    private static final int  MULTITASK_SWITCHES = 4;                // switches in TEMPO_WINDOW => multitasking
    private static final double FOCUS_FRACTION   = 0.8;              // share of FOCUS_WINDOW in one app

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

        Situation( String state, String activity, String notable, double salience, long sessionMinutes,
                   double cpuLoad, double gpuLoad, double ramLoad, boolean audioPlaying, boolean lateNight )
        {
            this.state = state; this.activity = activity; this.notable = notable;
            this.salience = salience; this.sessionMinutes = sessionMinutes;
            this.cpuLoad = cpuLoad; this.gpuLoad = gpuLoad; this.ramLoad = ramLoad;
            this.audioPlaying = audioPlaying; this.lateNight = lateNight;
        }

        /** Compact neutral context block for prompt injection / logging (no hedging words). */
        public String asContextBlock( )
        {
            StringBuilder sb = new StringBuilder( );
            sb.append( "User state: " ).append( state ).append( ". Activity: " ).append( activity ).append( '.' );
            if( audioPlaying ) sb.append( " Audio is playing." );
            if( lateNight )    sb.append( " It is late at night." );
            if( notable != null && !notable.isEmpty( ) ) sb.append( " Recent change: " ).append( notable ).append( '.' );
            return sb.toString( );
        }
    }

    private final AtomicReference<Situation> current = new AtomicReference<>(
        new Situation( "active", "(starting up)", "", 0, 0, -1, -1, -1, false, false ) );

    private final List<Session> sessions = new ArrayList<>( );
    private final Deque<Sample>  samples = new ArrayDeque<>( );
    private String  prevApp          = null;
    private boolean prevAudioPlaying = false;
    private String  prevState        = "active";

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

    private void loop( )
    {
        while( true )
        {
            try { sampleOnce( ); }
            catch( Exception e ) { log.log( Level.FINE, "situation sample error", e ); }
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
        }
        else if( !app.equals( "(none)" ) )   // never start a session for a blank foreground
        {
            sessions.add( new Session( app, title, now ) );
            appChanged = ( prevApp != null );   // suppress the very first sample
        }
        sessions.removeIf( s -> s.lastMs < now - WINDOW_MS );
        while( sessions.size( ) > MAX_SESSIONS ) sessions.remove( 0 );

        samples.addLast( new Sample( now, cpu ) );
        while( !samples.isEmpty( ) && samples.peekFirst( ).ms < now - WINDOW_MS ) samples.removeFirst( );

        // ── derive ──
        final boolean audioPlaying = audio >= AUDIO_ACTIVE_RMS;
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
            salience += 0.3;
            if( notable.isEmpty( ) ) notable = audioPlaying ? "audio started" : "audio stopped";
        }
        if( !state.equals( prevState ) ) salience += 0.4;
        salience = Math.min( 1.0, salience );

        final String activity = ( cur == null ) ? "(no foreground window)"
                                                 : cur.app + " (~" + sessionMin + "m)";

        final Situation sit = new Situation( state, activity, notable, salience, sessionMin,
            cpu, gpu, ram, audioPlaying, lateNight );
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
        String key = ( i >= 0 ) ? title.substring( i + 3 ) : title;
        key = key.trim( );
        return key.isEmpty( ) ? title.trim( ) : key;
    }

    private static boolean isLateNight( )
    {
        int h = java.time.LocalTime.now( ).getHour( );
        return h >= 22 || h < 5;
    }

    private static String fmt( double d ) { return String.format( "%.2f", d ); }
}
