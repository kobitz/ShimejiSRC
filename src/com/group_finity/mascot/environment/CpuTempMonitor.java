package com.group_finity.mascot.environment;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls system sensor data using lightweight platform APIs where possible.
 *
 * Fast loop (1s):
 *   cpuLoad      - OperatingSystemMXBean.getSystemCpuLoad(), pure Java, instant
 *   gpuTemp/Load - nvidia-smi (~80ms spawn, NVIDIA only, no admin; -1 otherwise)
 *   batteryLevel - Win32 GetSystemPowerStatus via JNA, instant
 *
 * Persistent process (1s stream):
 *   cpuTemp      - TempSensor.exe kept alive; outputs one "cpuTemp=XX.X" line per
 *                  second. Driver loaded once at startup (~700ms), then each read
 *                  is instant. Requires admin/UAC. Built from SensorReader project:
 *                  cd SensorReader && dotnet publish
 *                  Copy TempSensor.exe from publish output to install folder.
 *                  Returns -1 if absent or not running as admin.
 *
 * ramLoad is not polled here - computed live in MascotEnvironment.getRamLoad().
 */
public class CpuTempMonitor
{
    private static final Logger log = Logger.getLogger( CpuTempMonitor.class.getName( ) );

    private static final long FAST_POLL_MS = 1000;
    private static final long TEMP_RESTART_DELAY_MS = 5000;

    /** CPU temp (degrees C) at which the fan-max is turned off if currently on. */
    private static final double FAN_ON_THRESHOLD  = 80.0; // trigger fan max above this
    private static final double COOL_THRESHOLD     = 75.0; // turn fan off below this

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

    // --- JNA: GetSystemPowerStatus (kernel32.dll) ---

    private interface Kernel32Ext extends Library
    {
        Kernel32Ext INSTANCE = (Kernel32Ext) Native.loadLibrary( "kernel32", Kernel32Ext.class );
        boolean GetSystemPowerStatus( SYSTEM_POWER_STATUS lpSystemPowerStatus );
    }

    public static class SYSTEM_POWER_STATUS extends Structure
    {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;  // 0-100, or 255 = unknown / no battery
        public byte SystemStatusFlag;
        public int  BatteryLifeTime;
        public int  BatteryFullLifeTime;

        @Override
        protected List<String> getFieldOrder( )
        {
            return Arrays.asList( "ACLineStatus", "BatteryFlag", "BatteryLifePercent",
                "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime" );
        }
    }

    // --- OperatingSystemMXBean for CPU load ---

    private static final com.sun.management.OperatingSystemMXBean OS_MX_BEAN;
    static
    {
        com.sun.management.OperatingSystemMXBean bean = null;
        try
        {
            bean = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean( );
        }
        catch( Exception e ) { /* leave null */ }
        OS_MX_BEAN = bean;
    }

    // --- Sensor values (volatile for cross-thread visibility) ---

    private volatile double cpuTemp      = -1;
    private volatile boolean cpuTempWasHot = false; // for cool-down crossing detection
    private volatile double cpuLoad      = -1;
    private volatile double gpuTemp      = -1;
    private volatile double gpuLoad      = -1;
    private volatile double batteryLevel = -1;

    private volatile boolean nvidiaSmiAvailable = true;
    private volatile Process tempProcess       = null;

    private CpuTempMonitor( ) { }

    private void start( )
    {
        Thread fast = new Thread( this::fastPollLoop, "CpuTempMonitor-Fast" );
        fast.setDaemon( true );
        fast.setPriority( Thread.MIN_PRIORITY );
        fast.start( );

        Thread temp = new Thread( this::tempPollLoop, "CpuTempMonitor-Temp" );
        temp.setDaemon( true );
        temp.setPriority( Thread.MIN_PRIORITY );
        temp.start( );

        // Kill TempSensor.exe cleanly when the JVM exits.
        Runtime.getRuntime( ).addShutdownHook( new Thread( ( ) -> {
            Process p = tempProcess;
            if( p != null ) p.destroy( );
        } ) );
    }

    // --- Fast loop: cpuLoad, GPU, battery ---

    private void fastPollLoop( )
    {
        while( true )
        {
            try { readCpuLoad( );  } catch( Exception e ) { log.log( Level.FINE, "cpuLoad error", e ); }
            try { readGpu( );      } catch( Exception e ) { log.log( Level.FINE, "gpu error", e ); }
            try { readBattery( );  } catch( Exception e ) { log.log( Level.FINE, "battery error", e ); }

            try { Thread.sleep( FAST_POLL_MS ); }
            catch( InterruptedException e ) { Thread.currentThread( ).interrupt( ); break; }
        }
    }

    private void readCpuLoad( )
    {
        if( OS_MX_BEAN == null ) return;
        double load = OS_MX_BEAN.getSystemCpuLoad( );
        cpuLoad = ( load >= 0.0 ) ? load * 100.0 : -1;
    }

    private void readGpu( )
    {
        if( !nvidiaSmiAvailable ) return;

        Process process = null;
        BufferedReader reader = null;
        try
        {
            ProcessBuilder pb = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=temperature.gpu,utilization.gpu",
                "--format=csv,noheader,nounits"
            );
            pb.redirectErrorStream( true );
            process = pb.start( );
            reader = new BufferedReader( new InputStreamReader( process.getInputStream( ) ) );

            String line = reader.readLine( );
            if( line != null )
            {
                String[] parts = line.trim( ).split( "\\s*,\\s*" );
                if( parts.length >= 2 )
                {
                    gpuTemp = parseDouble( parts[0] );
                    gpuLoad = parseDouble( parts[1] );
                }
            }
            process.waitFor( );
        }
        catch( java.io.IOException e )
        {
            nvidiaSmiAvailable = false;
            gpuTemp = -1;
            gpuLoad = -1;
            log.log( Level.FINE, "nvidia-smi unavailable, GPU sensors disabled", e );
        }
        catch( Exception e )
        {
            log.log( Level.FINE, "nvidia-smi error", e );
        }
        finally
        {
            if( reader  != null ) try { reader.close( );  } catch( Exception ignored ) { }
            if( process != null ) process.destroy( );
        }
    }

    private void readBattery( )
    {
        try
        {
            SYSTEM_POWER_STATUS sps = new SYSTEM_POWER_STATUS( );
            if( Kernel32Ext.INSTANCE.GetSystemPowerStatus( sps ) )
            {
                int pct = sps.BatteryLifePercent & 0xFF;
                batteryLevel = ( pct == 255 ) ? -1 : pct;
            }
        }
        catch( Exception e ) { batteryLevel = -1; }
    }

    // --- Temp loop: persistent TempSensor.exe process, restarts on crash ---

    private void tempPollLoop( )
    {
        while( true )
        {
            try
            {
                runTempSensor( );
            }
            catch( java.io.IOException e )
            {
                // TempSensor.exe not found — don't retry
                log.log( Level.FINE, "TempSensor.exe not found, cpuTemp disabled", e );
                return;
            }
            catch( Exception e )
            {
                log.log( Level.FINE, "TempSensor.exe exited unexpectedly, restarting", e );
            }

            cpuTemp = -1;
            try { Thread.sleep( TEMP_RESTART_DELAY_MS ); }
            catch( InterruptedException e ) { Thread.currentThread( ).interrupt( ); return; }
        }
    }

    // Spawns TempSensor.exe and reads "cpuTemp=XX.X" lines until the process exits.
    private void runTempSensor( ) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder( "TempSensor.exe" );
        pb.redirectErrorStream( false );
        Process process = pb.start( );
        tempProcess = process;

        // Register the stdin stream so FanController can send fan_on / fan_off commands.
        java.io.PrintWriter stdin = new java.io.PrintWriter(
            new java.io.OutputStreamWriter( process.getOutputStream( ) ) );
        FanController.getInstance( ).setSensorStdin( stdin );

        try( BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream( ) ) ) )
        {
            String line;
            while( ( line = reader.readLine( ) ) != null )
            {
                if( line.startsWith( "cpuTemp=" ) )
                {
                    cpuTemp = parseDouble( line.substring( "cpuTemp=".length( ) ).trim( ) );
                    if( cpuTemp > 0 )
                    {
                        if( cpuTemp >= FAN_ON_THRESHOLD && !cpuTempWasHot )
                        {
                            cpuTempWasHot = true;
                            FanController.getInstance( ).triggerFanOn( );
                        }
                        else if( cpuTempWasHot && cpuTemp < COOL_THRESHOLD )
                        {
                            cpuTempWasHot = false;
                            FanController.getInstance( ).triggerFanOff( );
                        }
                    }
                }
            }
        }
        finally
        {
            FanController.getInstance( ).setSensorStdin( null );
            tempProcess = null;
            process.destroy( );
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
    public double getRamLoad( )      { return -1; }
    public double getBatteryLevel( ) { return batteryLevel; }
}
