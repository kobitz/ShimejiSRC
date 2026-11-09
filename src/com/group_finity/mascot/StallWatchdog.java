package com.group_finity.mascot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Freeze diagnostics. A daemon thread watches two things: whether the Manager tick has been
 * running for too long, and whether the Swing event thread still answers. When either stalls
 * past {@link #THRESHOLD_MS} it writes ONE log record per stall episode containing every
 * thread's stack (so the log shows what the program was stuck on), any deadlocked threads,
 * and how much of the stall was garbage collection. Nothing is logged while things are healthy.
 */
public final class StallWatchdog
{
    private static final Logger log = Logger.getLogger( StallWatchdog.class.getName( ) );
    static final long THRESHOLD_MS = 4000;
    private static final int  MAX_FRAMES = 25;

    private static volatile long tickStartNs = 0;      // 0 = no tick in progress
    private static volatile long edtPingSentNs = 0;    // 0 = answered
    private static Thread thread;

    private StallWatchdog( ) { }

    /** Manager brackets each tick with these. */
    public static void tickBegin( ) { tickStartNs = System.nanoTime( ); }
    public static void tickEnd( )   { tickStartNs = 0; }

    /** Total GC time so far in ms (sum over collectors); -1 if unavailable. */
    public static long gcTimeMs( )
    {
        long t = 0;
        try
        {
            for( final GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans( ) )
                if( b.getCollectionTime( ) > 0 ) t += b.getCollectionTime( );
            return t;
        }
        catch( final Throwable e ) { return -1; }
    }

    public static synchronized void start( )
    {
        if( thread != null ) return;
        thread = new Thread( StallWatchdog::run, "StallWatchdog" );
        thread.setDaemon( true );
        thread.start( );
    }

    private static void run( )
    {
        boolean reported = false;
        long lastGc = gcTimeMs( );
        long gcAtEpisodeStart = lastGc;
        while( true )
        {
            try { Thread.sleep( 1000 ); } catch( final InterruptedException e ) { return; }
            try
            {
                // EDT ping: queue a no-op and see whether it runs.
                if( edtPingSentNs == 0 )
                {
                    edtPingSentNs = System.nanoTime( );
                    javax.swing.SwingUtilities.invokeLater( () -> edtPingSentNs = 0 );
                }
                final long now = System.nanoTime( );
                final long tickMs = tickStartNs == 0 ? 0 : ( now - tickStartNs ) / 1_000_000L;
                final long edtMs  = edtPingSentNs == 0 ? 0 : ( now - edtPingSentNs ) / 1_000_000L;
                final boolean stalled = tickMs >= THRESHOLD_MS || edtMs >= THRESHOLD_MS;
                if( !stalled )
                {
                    if( reported )
                    {
                        log.warning( "[StallWatchdog] Recovered. GC during the stall: "
                            + ( gcTimeMs( ) - gcAtEpisodeStart ) + "ms" );
                        reported = false;
                    }
                    continue;
                }
                if( reported ) continue;
                reported = true;
                gcAtEpisodeStart = lastGc;
                final long gcNow = gcTimeMs( );
                final Runtime rt = Runtime.getRuntime( );
                final StringBuilder sb = new StringBuilder( 8192 );
                sb.append( "[StallWatchdog] STALL: managerTick=" ).append( tickMs ).append( "ms" )
                  .append( " edtUnresponsive=" ).append( edtMs ).append( "ms" )
                  .append( " gcSinceLastCheck=" ).append( gcNow - lastGc ).append( "ms" )
                  .append( " heap=" ).append( ( rt.totalMemory( ) - rt.freeMemory( ) ) / 1_048_576L )
                  .append( "/" ).append( rt.maxMemory( ) / 1_048_576L ).append( "MB(max)" ).append( '\n' );
                final ThreadMXBean mx = ManagementFactory.getThreadMXBean( );
                final long[] dead = mx.findDeadlockedThreads( );
                if( dead != null && dead.length > 0 )
                {
                    sb.append( "DEADLOCK among threads:" );
                    for( final ThreadInfo ti : mx.getThreadInfo( dead, MAX_FRAMES ) )
                        if( ti != null ) sb.append( ' ' ).append( ti.getThreadName( ) );
                    sb.append( '\n' );
                }
                for( final ThreadInfo ti : mx.dumpAllThreads( true, true, MAX_FRAMES ) )
                {
                    final String n = ti.getThreadName( );
                    // Skip idle JVM housekeeping threads to keep the record readable.
                    if( n.startsWith( "Reference Handler" ) || n.startsWith( "Finalizer" )
                     || n.startsWith( "Signal Dispatcher" ) || n.startsWith( "Common-Cleaner" )
                     || n.equals( "StallWatchdog" ) ) continue;
                    sb.append( "--- " ).append( n ).append( " [" ).append( ti.getThreadState( ) ).append( ']' );
                    if( ti.getLockName( ) != null )
                        sb.append( " waiting on " ).append( ti.getLockName( ) )
                          .append( ti.getLockOwnerName( ) != null ? " held by " + ti.getLockOwnerName( ) : "" );
                    sb.append( '\n' );
                    for( final StackTraceElement f : ti.getStackTrace( ) )
                        sb.append( "    at " ).append( f ).append( '\n' );
                }
                log.warning( sb.toString( ) );
                lastGc = gcNow;
            }
            catch( final Throwable t )
            {
                log.log( Level.FINE, "StallWatchdog check failed", t );
            }
        }
    }
}
