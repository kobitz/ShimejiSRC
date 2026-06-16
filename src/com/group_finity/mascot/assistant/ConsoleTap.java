package com.group_finity.mascot.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Global capture of everything the program would print to a console, kept in a
 * small rolling ring buffer for the on-screen "system readout" (2B's overlay).
 *
 * <p>Two sources are tapped, installed once at startup ({@link #install()}):
 * <ol>
 *   <li>A {@link Handler} on the root logger — this is the alive stream
 *       (TickWatch spikes, peer/memory/situation/drive events). It is captured
 *       regardless of whether the {@code ConsoleHandler} is enabled in
 *       {@code logging.properties}; the real Debug.bat console only shows these
 *       if that handler is on, so tapping the logger directly is both faithful
 *       to intent and independent of config.</li>
 *   <li>A tee on {@code System.out} / {@code System.err} — the handful of raw
 *       prints (e.g. VideoAreaServer) and any stack traces.</li>
 * </ol>
 *
 * <p>Thread-safe: log handlers fire on the logging caller thread and the tee on
 * whatever thread printed. Appends are short and synchronized; readers poll
 * {@link #version()} to repaint only on change.
 */
public final class ConsoleTap
{
    /** Lines retained. Larger than the overlay shows so brief bursts aren't lost. */
    private static final int CAPACITY = 200;
    /** A single console line is truncated to this before storage (memory dumps are long). */
    private static final int MAX_LINE_LEN = 240;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>( CAPACITY );
    private static final Object LOCK = new Object();
    private static volatile long version = 0L;
    private static volatile boolean installed = false;

    // Consecutive-duplicate collapsing: a line identical to the previous one bumps a
    // repeat counter on that line instead of flooding the readout (e.g. the per-cycle
    // [Situation] line during a steady state). syslog-style "(xN)".
    private static String lastBase  = null;
    private static int    lastCount = 0;

    private ConsoleTap() { }

    /** Idempotent. Safe to call from multiple entry points. */
    public static void install()
    {
        if( installed ) return;
        synchronized( LOCK )
        {
            if( installed ) return;
            installed = true;

            // 1) Root-logger handler — the rich stream.
            try
            {
                final Handler h = new Handler()
                {
                    @Override public void publish( final LogRecord r )
                    {
                        if( r == null ) return;
                        try
                        {
                            String msg = getFormatter() != null
                                ? getFormatter().formatMessage( r ) : r.getMessage();
                            if( msg == null ) msg = "";
                            // Collapse multi-line messages (e.g. raw memory summaries) to one line.
                            msg = msg.replace( '\r', ' ' ).replace( '\n', ' ' ).trim();
                            final Throwable t = r.getThrown();
                            if( t != null )
                                msg = msg + " | " + t.getClass().getSimpleName()
                                    + ": " + t.getMessage();
                            if( !msg.isEmpty() ) append( msg );
                        }
                        catch( final Exception ignored ) { }
                    }
                    @Override public void flush() { }
                    @Override public void close() { }
                };
                h.setLevel( Level.ALL );
                Logger.getLogger( "" ).addHandler( h );
            }
            catch( final Exception ignored ) { }

            // 2) Tee System.out / System.err.
            System.setOut( tee( System.out ) );
            System.setErr( tee( System.err ) );
        }
    }

    /** Monotonic counter bumped on every appended line; cheap change-detection for pollers. */
    public static long version() { return version; }

    /** The most recent {@code n} lines, oldest first. */
    public static List<String> lastLines( final int n )
    {
        synchronized( LOCK )
        {
            final int skip = Math.max( 0, LINES.size() - n );
            final List<String> out = new ArrayList<>( Math.min( n, LINES.size() ) );
            int i = 0;
            for( final String s : LINES )
            {
                if( i++ < skip ) continue;
                out.add( s );
            }
            return out;
        }
    }

    static void append( String line )
    {
        if( line == null ) return;
        line = line.strip();
        if( line.isEmpty() ) return;
        // Drop the high-frequency whisper energy-skip chatter — pure noise on the readout.
        if( line.contains( "skipping Whisper" ) || line.contains( "Post-NR energy" ) ) return;
        if( line.length() > MAX_LINE_LEN )
            line = line.substring( 0, MAX_LINE_LEN - 1 ) + "…";
        synchronized( LOCK )
        {
            if( line.equals( lastBase ) && !LINES.isEmpty() )
            {
                // Consecutive duplicate — bump the counter on the existing line.
                lastCount++;
                LINES.removeLast();
                LINES.addLast( line + " (x" + lastCount + ")" );
            }
            else
            {
                lastBase  = line;
                lastCount = 1;
                LINES.addLast( line );
                while( LINES.size() > CAPACITY ) LINES.removeFirst();
            }
            version++;
        }
    }

    /** Wrap a PrintStream so every full line it emits is mirrored into the ring. */
    private static java.io.PrintStream tee( final java.io.PrintStream original )
    {
        final java.io.OutputStream capture = new java.io.OutputStream()
        {
            private final StringBuilder sb = new StringBuilder( 256 );
            @Override public synchronized void write( final int b )
            {
                original.write( b );
                if( b == '\n' ) { append( sb.toString() ); sb.setLength( 0 ); }
                else if( b != '\r' ) sb.append( (char) b );
            }
            @Override public void flush() { original.flush(); }
        };
        try { return new java.io.PrintStream( capture, true, "UTF-8" ); }
        catch( final Exception e ) { return original; }
    }
}
