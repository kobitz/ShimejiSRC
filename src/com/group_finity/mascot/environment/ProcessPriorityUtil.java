package com.group_finity.mascot.environment;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows process scheduling priority control (no-op on other platforms).
 *
 * Purpose: the local inference processes (Ollama's llama-server, the Whisper
 * python server) saturate every core during a generation burst, starving the
 * 40ms tick loop — TickWatch showed all phases inflating uniformly at
 * cpu 97-99% while a mascot reaction generated. Capping their thread counts
 * limits throughput but not scheduling: at equal priority Windows still
 * preempts the tick thread constantly. The fix is rank, not rate:
 *   - Shimeji itself runs ABOVE_NORMAL (it uses a few percent CPU; priority
 *     only matters under contention, so this costs other apps ~nothing).
 *   - Inference processes run BELOW_NORMAL: they keep ALL idle CPU but yield
 *     instantly to interactive work. Latency cost is irrelevant per Ko.
 * Windows inheritance rule: a BELOW_NORMAL parent spawns BELOW_NORMAL
 * children, so demoting ollama.exe makes future llama-server runners start
 * demoted — the periodic rescan only has to catch strays.
 */
public final class ProcessPriorityUtil
{
    private static final Logger log = Logger.getLogger( ProcessPriorityUtil.class.getName() );

    private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000;
    private static final int BELOW_NORMAL_PRIORITY_CLASS = 0x00004000;
    private static final int IDLE_PRIORITY_CLASS         = 0x00000040;
    private static final int PROCESS_SET_INFORMATION     = 0x0200;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int TH32CS_SNAPPROCESS          = 0x00000002;

    private static final boolean WINDOWS =
        System.getProperty( "os.name", "" ).toLowerCase().contains( "windows" );

    private ProcessPriorityUtil() {}

    private interface K32 extends Library
    {
        K32 INSTANCE = (K32) Native.loadLibrary( "kernel32", K32.class );
        Pointer GetCurrentProcess();
        boolean SetPriorityClass( Pointer hProcess, int dwPriorityClass );
        int     GetPriorityClass( Pointer hProcess );
        Pointer OpenProcess( int dwDesiredAccess, boolean bInheritHandle, int dwProcessId );
        boolean CloseHandle( Pointer hObject );
        Pointer CreateToolhelp32Snapshot( int dwFlags, int th32ProcessID );
        boolean Process32FirstW( Pointer hSnapshot, PROCESSENTRY32W lppe );
        boolean Process32NextW( Pointer hSnapshot, PROCESSENTRY32W lppe );
    }

    /** Wide-char PROCESSENTRY32 (JNA maps Java char[] to wchar_t[]). */
    public static class PROCESSENTRY32W extends Structure
    {
        public int     dwSize;
        public int     cntUsage;
        public int     th32ProcessID;
        public Pointer th32DefaultHeapID;
        public int     th32ModuleID;
        public int     cntThreads;
        public int     th32ParentProcessID;
        public int     pcPriClassBase;
        public int     dwFlags;
        public char[]  szExeFile = new char[ 260 ];

        @Override
        protected List<String> getFieldOrder()
        {
            return Arrays.asList( "dwSize", "cntUsage", "th32ProcessID",
                "th32DefaultHeapID", "th32ModuleID", "cntThreads",
                "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile" );
        }
    }

    /** Raise this JVM to ABOVE_NORMAL so the tick loop wins scheduling contention. */
    public static void raiseSelfAboveNormal()
    {
        if( !WINDOWS ) return;
        try
        {
            if( K32.INSTANCE.SetPriorityClass(
                    K32.INSTANCE.GetCurrentProcess(), ABOVE_NORMAL_PRIORITY_CLASS ) )
                log.info( "[Priority] Shimeji raised to ABOVE_NORMAL." );
            else
                log.warning( "[Priority] Could not raise own priority class." );
        }
        catch( final Throwable t )
        {
            log.log( Level.WARNING, "[Priority] raiseSelfAboveNormal failed", t );
        }
    }

    /** Demote a process we spawned (e.g. the Whisper server) to BELOW_NORMAL. */
    public static void setBelowNormal( final long pid )
    {
        if( !WINDOWS ) return;
        try
        {
            final Pointer h = K32.INSTANCE.OpenProcess(
                PROCESS_SET_INFORMATION, false, (int) pid );
            if( h == null ) return;
            try
            {
                if( K32.INSTANCE.SetPriorityClass( h, BELOW_NORMAL_PRIORITY_CLASS ) )
                    log.info( "[Priority] pid " + pid + " demoted to BELOW_NORMAL." );
            }
            finally
            {
                K32.INSTANCE.CloseHandle( h );
            }
        }
        catch( final Throwable t )
        {
            log.log( Level.FINE, "[Priority] setBelowNormal(" + pid + ") failed", t );
        }
    }

    /**
     * Scans running processes and demotes any whose exe name matches (case-
     * insensitive) to BELOW_NORMAL. Already-demoted processes are skipped so
     * repeated calls stay silent and cheap (~1ms snapshot). Called periodically
     * from CpuTempMonitor's poll loop to catch freshly spawned llama-servers.
     */
    public static void demoteByName( final String... exeNames )
    {
        if( !WINDOWS ) return;
        try
        {
            final Pointer snap = K32.INSTANCE.CreateToolhelp32Snapshot( TH32CS_SNAPPROCESS, 0 );
            if( snap == null || Pointer.nativeValue( snap ) == -1L ) return;
            try
            {
                final PROCESSENTRY32W pe = new PROCESSENTRY32W();
                pe.dwSize = pe.size();
                if( !K32.INSTANCE.Process32FirstW( snap, pe ) ) return;
                do
                {
                    final String exe = Native.toString( pe.szExeFile );
                    for( final String name : exeNames )
                    {
                        if( !exe.equalsIgnoreCase( name ) ) continue;
                        demotePidIfNeeded( pe.th32ProcessID, exe );
                        break;
                    }
                } while( K32.INSTANCE.Process32NextW( snap, pe ) );
            }
            finally
            {
                K32.INSTANCE.CloseHandle( snap );
            }
        }
        catch( final Throwable t )
        {
            log.log( Level.FINE, "[Priority] demoteByName failed", t );
        }
    }

    private static void demotePidIfNeeded( final int pid, final String exe )
    {
        final Pointer h = K32.INSTANCE.OpenProcess(
            PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION, false, pid );
        if( h == null ) return; // elevated process we can't touch — leave it
        try
        {
            final int current = K32.INSTANCE.GetPriorityClass( h );
            if( current == BELOW_NORMAL_PRIORITY_CLASS || current == IDLE_PRIORITY_CLASS )
                return;
            if( K32.INSTANCE.SetPriorityClass( h, BELOW_NORMAL_PRIORITY_CLASS ) )
                log.info( "[Priority] " + exe + " (pid " + pid + ") demoted to BELOW_NORMAL." );
        }
        finally
        {
            K32.INSTANCE.CloseHandle( h );
        }
    }
}
