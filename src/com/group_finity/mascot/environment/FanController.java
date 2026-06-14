package com.group_finity.mascot.environment;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Drives MSI Cooler Boost. Manager.checkCoolerBoostState() calls triggerFanOn()
 * every tick while a mascot broadcasts the CoolerBoost affordance, triggerFanOff()
 * every tick while none does.
 *
 * Each toggle spawns a SHORT-LIVED "TempSensor.exe boost on|off" process that opens a
 * fresh WMI connection, does the one EC write, and exits. History of why:
 *   1. In-process WMI in the long-running TempSensor froze the whole process (a hung
 *      COM call stalls the GC -> all threads freeze).
 *   2. Moving the EC write into a child spawned BY TempSensor fixed that, but exposed
 *      that the long-lived stdin pipe (Java -> TempSensor) silently stops delivering
 *      after ~20 min (Java's PrintWriter.checkError stays false, TempSensor's ReadLine
 *      never returns; confirmed via fancontroller.log healthy + tempsensor.log frozen).
 *   3. So fan commands no longer go through that pipe at all: FanController spawns the
 *      boost process directly. No long-lived pipe, no long-lived WMI -> neither failure
 *      mode exists. TempSensor.exe stays purely the temperature streamer.
 *
 * LEVEL-TRIGGERED with periodic RE-ASSERT so a manual Fn+F8 / MSI Center toggle or an
 * external EC change self-heals within REASSERT_MS instead of sticking.
 * Writes fancontroller.log (install folder) for observability.
 */
public class FanController
{
    private static final Logger log = Logger.getLogger( FanController.class.getName( ) );

    private static final long REASSERT_MS            = 7000;
    private static final long OFF_REASSERT_WINDOW_MS = 30000;
    private static final long BOOST_PROC_TIMEOUT_S   = 8;

    private static FanController instance;

    public static synchronized FanController getInstance( )
    {
        if( instance == null ) instance = new FanController( );
        return instance;
    }

    private volatile boolean desiredOn = false;
    private volatile long lastSendMs = 0;
    private volatile long lastOffTransitionMs = 0;

    private FanController( ) { }

    /** Affordance present this tick. */
    public synchronized void triggerFanOn( )
    {
        long now = System.currentTimeMillis( );
        if( !desiredOn )
        {
            desiredOn = true;
            spawnBoost( true, now, "blue fire present" );
        }
        else if( now - lastSendMs >= REASSERT_MS )
        {
            spawnBoost( true, now, "re-assert" );
        }
    }

    /** Affordance absent this tick. */
    public synchronized void triggerFanOff( )
    {
        long now = System.currentTimeMillis( );
        if( desiredOn )
        {
            desiredOn = false;
            lastOffTransitionMs = now;
            spawnBoost( false, now, "blue fire gone" );
        }
        else if( now - lastSendMs >= REASSERT_MS && now - lastOffTransitionMs < OFF_REASSERT_WINDOW_MS )
        {
            spawnBoost( false, now, "re-assert" );
        }
    }

    public boolean isFanOn( ) { return desiredOn; }

    private void spawnBoost( boolean on, long now, String why )
    {
        lastSendMs = now;
        try
        {
            final Process p = new ProcessBuilder( "TempSensor.exe", "boost", on ? "on" : "off" )
                .redirectOutput( ProcessBuilder.Redirect.DISCARD )
                .redirectError( ProcessBuilder.Redirect.DISCARD )
                .start( );
            flog( "boost " + ( on ? "on" : "off" ) + " (" + why + ") -> spawned pid " + p.pid( ) );
            // Reap on a daemon so a (rare) hung fresh-connection write can't linger forever
            // and never blocks the tick thread.
            Thread reaper = new Thread( ( ) ->
            {
                try
                {
                    if( !p.waitFor( BOOST_PROC_TIMEOUT_S, TimeUnit.SECONDS ) )
                    {
                        p.destroyForcibly( );
                        flog( "boost process pid " + p.pid( ) + " exceeded " + BOOST_PROC_TIMEOUT_S + "s -- killed" );
                    }
                }
                catch( InterruptedException ie ) { Thread.currentThread( ).interrupt( ); }
            } );
            reaper.setDaemon( true );
            reaper.start( );
        }
        catch( Exception e )
        {
            flog( "boost " + ( on ? "on" : "off" ) + " (" + why + ") SPAWN FAILED: " + e.getMessage( ) );
        }
    }

    private static void flog( String msg )
    {
        try ( java.io.FileWriter fw = new java.io.FileWriter( "fancontroller.log", true ) )
        {
            fw.write( new java.text.SimpleDateFormat( "HH:mm:ss" ).format( new java.util.Date( ) )
                + "  " + msg + System.lineSeparator( ) );
        }
        catch( Exception ignore ) { }
    }
}
