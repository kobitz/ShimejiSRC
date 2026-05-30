package com.group_finity.mascot.environment;

import java.io.PrintWriter;
import java.util.logging.Logger;

/**
 * Toggles Cooler Boost by sending "fan_on" / "fan_off" commands to TempSensor.exe stdin.
 * TempSensor writes those to EC register 0x98 via LibreHardwareMonitor Ring0.
 *
 * Fan ON:  CampfireON_blue active (Manager) OR cpuTemp > 80 C (CpuTempMonitor)
 * Fan OFF: CampfireON_blue gone AND cpuTemp < 75 C
 *
 * CpuTempMonitor calls setSensorStdin() after spawning TempSensor so commands
 * are delivered through the already-open admin process.
 */
public class FanController
{
    private static final Logger log = Logger.getLogger( FanController.class.getName( ) );

    private static FanController instance;

    public static synchronized FanController getInstance( )
    {
        if( instance == null ) instance = new FanController( );
        return instance;
    }

    private volatile boolean fanOn = false;
    private volatile PrintWriter sensorStdin = null;

    private FanController( ) { }

    /** Called by CpuTempMonitor after TempSensor.exe is spawned. */
    public void setSensorStdin( PrintWriter pw ) { sensorStdin = pw; }

    public synchronized void triggerFanOn( )
    {
        if( !fanOn )
        {
            send( "fan_on" );
            fanOn = true;
            log.info( "Fan max ON" );
        }
    }

    public synchronized void triggerFanOff( )
    {
        if( fanOn )
        {
            send( "fan_off" );
            fanOn = false;
            log.info( "Fan max OFF" );
        }
    }

    public boolean isFanOn( ) { return fanOn; }

    private void send( String cmd )
    {
        PrintWriter pw = sensorStdin;
        if( pw != null )
        {
            pw.println( cmd );
            pw.flush( );
        }
        else
        {
            log.warning( "Fan command dropped (TempSensor not ready): " + cmd );
        }
    }
}
