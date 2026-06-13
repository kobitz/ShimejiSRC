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
 *   batteryLevel - Win32 GetSystemPowerStatus via JNA, instant
 *
 * Persistent process (1s stream):
 *   gpuTemp/Load/Mem - one "nvidia-smi --query-gpu=... -l 1" process streams a CSV
 *                  line per second (NVIDIA only, no admin; -1 otherwise). Replaces
 *                  the old spawn-per-second model (~86k process spawns/day).
 *                  memory.free/total also feed OllamaClient's GPU-fit heuristic.
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
    private volatile double cpuLoad      = -1;
    private volatile double gpuTemp      = -1;
    private volatile double gpuLoad      = -1;
    private volatile double gpuMemFree   = -1; // MiB
    private volatile double gpuMemTotal  = -1; // MiB
    private volatile double batteryLevel = -1;

    private volatile Process tempProcess = null;
    private volatile Process gpuProcess  = null;

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

        Thread gpu = new Thread( this::gpuPollLoop, "CpuTempMonitor-Gpu" );
        gpu.setDaemon( true );
        gpu.setPriority( Thread.MIN_PRIORITY );
        gpu.start( );

        // Kill TempSensor.exe / nvidia-smi cleanly when the JVM exits.
        Runtime.getRuntime( ).addShutdownHook( new Thread( ( ) -> {
            Process p = tempProcess;
            if( p != null ) p.destroy( );
            Process g = gpuProcess;
            if( g != null ) g.destroy( );
        } ) );
    }

    // --- Fast loop: cpuLoad, GPU, battery ---

    private void fastPollLoop( )
    {
        int demoteCountdown = 0;
        while( true )
        {
            try { readCpuLoad( );  } catch( Exception e ) { log.log( Level.FINE, "cpuLoad error", e ); }
            try { readBattery( );  } catch( Exception e ) { log.log( Level.FINE, "battery error", e ); }

            // Keep the inference processes BELOW_NORMAL so generation bursts
            // can't starve the tick loop. ollama.exe demoted => future
            // llama-server children inherit the class; the 5s rescan catches
            // strays (already-demoted processes are skipped, so this is ~1ms).
            if( --demoteCountdown <= 0 )
            {
                demoteCountdown = 5;
                try
                {
                    ProcessPriorityUtil.ensureSelfAboveNormal( );
                    ProcessPriorityUtil.demoteByName( "ollama.exe", "llama-server.exe" );
                }
                catch( Exception e ) { log.log( Level.FINE, "priority demote error", e ); }
            }

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

    // --- GPU loop: one persistent nvidia-smi streaming a CSV line per second ---

    private void gpuPollLoop( )
    {
        while( true )
        {
            try
            {
                runGpuStream( );
            }
            catch( java.io.IOException e )
            {
                // nvidia-smi not on PATH — no NVIDIA GPU, don't retry
                log.log( Level.FINE, "nvidia-smi unavailable, GPU sensors disabled", e );
                return;
            }
            catch( Exception e )
            {
                log.log( Level.FINE, "nvidia-smi stream exited, restarting", e );
            }

            gpuTemp = -1; gpuLoad = -1; gpuMemFree = -1; gpuMemTotal = -1;
            try { Thread.sleep( TEMP_RESTART_DELAY_MS ); }
            catch( InterruptedException e ) { Thread.currentThread( ).interrupt( ); return; }
        }
    }

    // Spawns nvidia-smi in loop mode (-l 1) and reads one CSV line per second
    // until the process exits.
    private void runGpuStream( ) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder(
            "nvidia-smi",
            "--query-gpu=temperature.gpu,utilization.gpu,memory.free,memory.total",
            "--format=csv,noheader,nounits",
            "-l", "1"
        );
        pb.redirectErrorStream( true );
        Process process = pb.start( );
        gpuProcess = process;

        try( BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream( ) ) ) )
        {
            String line;
            while( ( line = reader.readLine( ) ) != null )
            {
                String[] parts = line.trim( ).split( "\\s*,\\s*" );
                if( parts.length >= 4 )
                {
                    gpuTemp     = parseDouble( parts[0] );
                    gpuLoad     = parseDouble( parts[1] );
                    gpuMemFree  = parseDouble( parts[2] );
                    gpuMemTotal = parseDouble( parts[3] );
                }
            }
        }
        finally
        {
            gpuProcess = null;
            process.destroy( );
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
                    // Cooler Boost is driven purely by the CampfireON_blue mascot (Manager).
                    // No temperature-based trigger here.
                    cpuTemp = parseDouble( line.substring( "cpuTemp=".length( ) ).trim( ) );
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
    /** Free GPU memory in MiB, or -1 when no NVIDIA GPU data is available. */
    public double getGpuMemFree( )   { return gpuMemFree;   }
    /** Total GPU memory in MiB, or -1 when no NVIDIA GPU data is available. */
    public double getGpuMemTotal( )  { return gpuMemTotal;  }
    public double getRamLoad( )      { return -1; }
    public double getBatteryLevel( ) { return batteryLevel; }
}
