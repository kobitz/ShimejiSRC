package com.group_finity.mascot.environment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls system sensor data every few seconds by calling a PowerShell script
 * that reads from LibreHardwareMonitorLib.dll directly.
 *
 * Requirements:
 *   - LibreHardwareMonitorLib.dll in the shimeji root folder
 *   - get_cpu_temp.ps1 in the shimeji root folder
 *   - Shimeji running as administrator
 *
 * Exposes the following to XML scripting via mascot.environment:
 *   cpuTemp      - CPU Core Average temperature (°C)
 *   cpuLoad      - CPU Total load (%)
 *   gpuTemp      - GPU Core temperature (°C)
 *   gpuLoad      - GPU Core load (%)
 *   ramLoad      - Total RAM usage (%)
 *   batteryLevel - Battery charge level (%)
 *
 * All values return -1 if unavailable.
 */
public class CpuTempMonitor
{
    private static final Logger log = Logger.getLogger( CpuTempMonitor.class.getName( ) );

    private static final long POLL_INTERVAL_MS = 3000;

    private static CpuTempMonitor instance;

    public static synchronized CpuTempMonitor getInstance( )
    {
        if( instance == null )
        {
            instance = new CpuTempMonitor( );
            instance.start( );
        }
        return instance;
    }

    private volatile double cpuTemp      = -1;
    private volatile double cpuLoad      = -1;
    private volatile double gpuTemp      = -1;
    private volatile double gpuLoad      = -1;
    private volatile double ramLoad      = -1;
    private volatile double batteryLevel = -1;

    private CpuTempMonitor( ) { }

    private void start( )
    {
        Thread t = new Thread( this::pollLoop, "CpuTempMonitor" );
        t.setDaemon( true );
        t.setPriority( Thread.MIN_PRIORITY );
        t.start( );
    }

    private void pollLoop( )
    {
        while( true )
        {
            try
            {
                readSensors( );
            }
            catch( Exception e )
            {
                log.log( Level.FINE, "Error polling sensor data", e );
            }

            try
            {
                Thread.sleep( POLL_INTERVAL_MS );
            }
            catch( InterruptedException e )
            {
                Thread.currentThread( ).interrupt( );
                break;
            }
        }
    }

    private void readSensors( )
    {
        // Resolve sensors.exe path relative to the shimeji working directory
        String exePath = Paths.get( "sensors.exe" ).toAbsolutePath( ).toString( );

        ProcessBuilder pb = new ProcessBuilder( exePath );
        pb.redirectErrorStream( true );

        try
        {
            Process process = pb.start( );
            BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream( ) ) );

            String line;
            while( ( line = reader.readLine( ) ) != null )
            {
                String[] parts = line.split( "=", 2 );
                if( parts.length != 2 ) continue;

                String key   = parts[0].trim( );
                String value = parts[1].trim( );

                double val = parseDouble( value );
                switch( key )
                {
                    case "cpuTemp":      cpuTemp      = val; break;
                    case "cpuLoad":      cpuLoad      = val; break;
                    case "gpuTemp":      gpuTemp      = val; break;
                    case "gpuLoad":      gpuLoad      = val; break;
                    case "ramLoad":      ramLoad      = val; break;
                    case "batteryLevel": batteryLevel = val; break;
                }
            }

            process.waitFor( );
        }
        catch( Exception e )
        {
            log.log( Level.FINE, "Could not run sensor script", e );
        }
    }

    private double parseDouble( String s )
    {
        try { return Double.parseDouble( s ); }
        catch( NumberFormatException e ) { return -1; }
    }

    public double getCpuTemp( )      { return cpuTemp;      }
    public double getCpuLoad( )      { return cpuLoad;      }
    public double getGpuTemp( )      { return gpuTemp;      }
    public double getGpuLoad( )      { return gpuLoad;      }
    public double getRamLoad( )      { return ramLoad;      }
    public double getBatteryLevel( ) { return batteryLevel; }
}
