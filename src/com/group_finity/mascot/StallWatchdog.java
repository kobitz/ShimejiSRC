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
    static final long THRESHOLD_MS = 1500;          // first dump
    static final long SECOND_DUMP_MS = 4000;        // second sample if still stuck
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
        int dumps = 0;
        long gcHealthy = gcTimeMs( );      // GC total at the last healthy check
        while( true )
        {
            try { Thread.sleep( 200 ); } catch( final InterruptedException e ) { return; }
            try
            {
                // EDT ping: queue a no-op and see whether it runs.
                if( edtPingSentNs == 0 )
                {
                    edtPingSentNs = System.nanoTime( );
                    javax.swing.SwingUtilities.invokeLater( () -> edtPingSentNs = 0 );
                }
                final long now = System.nanoTime( );
                final long ts     = tickStartNs;
                final long tickMs = ts == 0 ? 0 : ( now - ts ) / 1_000_000L;
                final long sent   = edtPingSentNs;          // read once: the EDT may zero it between reads
                final long edtMs  = sent == 0 ? 0 : ( now - sent ) / 1_000_000L;
                final boolean stalled = tickMs >= THRESHOLD_MS || edtMs >= THRESHOLD_MS;
                if( !stalled )
                {
                    final long g = gcTimeMs( );
                    if( reported )
                    {
                        log.warning( "[StallWatchdog] Recovered. GC since the stall began: "
                            + ( g - gcHealthy ) + "ms" );
                        reported = false;
                    }
                    dumps = 0;
                    gcHealthy = g;
                    continue;
                }
                final long worstMs = Math.max( tickMs, edtMs );
                // One dump at THRESHOLD_MS, a second sample if it is still stuck at SECOND_DUMP_MS
                // (two stacks of the same thread show whether it is stuck or just slow).
                if( dumps >= 2 || ( dumps == 1 && worstMs < SECOND_DUMP_MS ) ) continue;
                dumps++;
                reported = true;
                final long gcNow = gcTimeMs( );
                final Runtime rt = Runtime.getRuntime( );
                final StringBuilder sb = new StringBuilder( 8192 );
                sb.append( "[StallWatchdog] STALL (dump " ).append( dumps ).append( "): managerTick=" ).append( tickMs ).append( "ms" )
                  .append( " edtUnresponsive=" ).append( edtMs ).append( "ms" )
                  .append( " gcSinceStallBegan=" ).append( gcNow - gcHealthy ).append( "ms" )
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
                    // Keep the record readable: always show the two threads that matter, and
                    // otherwise only threads that are running or blocked (idle pool/sleeping
                    // threads, ~35 'Direct Clip' threads etc. are noise).
                    final boolean key = n.equals( "ManagerThread" ) || n.startsWith( "AWT-EventQueue" );
                    final Thread.State st = ti.getThreadState( );
                    if( !key && st != Thread.State.RUNNABLE && st != Thread.State.BLOCKED ) continue;
                    if( n.equals( "StallWatchdog" ) || n.startsWith( "Reference Handler" ) || n.startsWith( "Finalizer" )
                     || n.startsWith( "Signal Dispatcher" ) || n.startsWith( "Common-Cleaner" )
                     || n.startsWith( "Attach Listener" ) || n.startsWith( "Notification Thread" ) ) continue;
                    sb.append( "--- " ).append( n ).append( " [" ).append( ti.getThreadState( ) ).append( ']' );
                    if( ti.getLockName( ) != null )
                        sb.append( " waiting on " ).append( ti.getLockName( ) )
                          .append( ti.getLockOwnerName( ) != null ? " held by " + ti.getLockOwnerName( ) : "" );
                    sb.append( '\n' );
                    for( final StackTraceElement f : ti.getStackTrace( ) )
                        sb.append( "    at " ).append( f ).append( '\n' );
                }
                log.warning( sb.toString( ) );
            }
            catch( final Throwable t )
            {
                log.log( Level.FINE, "StallWatchdog check failed", t );
            }
        }
    }
}
