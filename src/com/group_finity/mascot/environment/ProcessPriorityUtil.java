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
        boolean SetProcessAffinityMask( Pointer hProcess, long dwProcessAffinityMask );
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

    private static volatile boolean selfWarned = false;

    /**
     * Verifies this JVM still holds ABOVE_NORMAL and re-raises if not.
     * Logs at WARNING (visible at the install folder's WARNING-only file
     * handler level) only when the raise is genuinely failing — silent when
     * healthy, same philosophy as TickWatch. Called from CpuTempMonitor's
     * periodic pass.
     */
    public static void ensureSelfAboveNormal()
    {
        if( !WINDOWS ) return;
        try
        {
            final Pointer self = K32.INSTANCE.GetCurrentProcess();
            if( K32.INSTANCE.GetPriorityClass( self ) == ABOVE_NORMAL_PRIORITY_CLASS ) return;
            if( !K32.INSTANCE.SetPriorityClass( self, ABOVE_NORMAL_PRIORITY_CLASS ) && !selfWarned )
            {
                selfWarned = true;
                log.warning( "[Priority] Shimeji is NOT ABOVE_NORMAL and the raise failed — "
                    + "inference bursts can starve the tick loop." );
            }
        }
        catch( final Throwable t )
        {
            if( !selfWarned )
            {
                selfWarned = true;
                log.log( Level.WARNING, "[Priority] self-priority check failed", t );
            }
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

    // ── Hard core reservation ────────────────────────────────────────────
    //
    // Priority classes only win *preemption* — under sustained near-100%
    // load across every core, a lower-priority thread can still occupy a
    // core for a full scheduling quantum before Windows swaps it out, and a
    // burst of inference load can pin all cores simultaneously so there's
    // nowhere for the tick thread to run until one frees up. That's visible
    // as TickWatch apply=/tick= inflating even for code that's already been
    // moved off the tick thread onto a pool — the handoff itself still needs
    // a core to execute on.
    //
    // Affinity masking is a stronger guarantee than priority: reserve a
    // small set of cores Shimeji alone may use, and forbid the inference
    // processes from scheduling onto them at all. The tick thread then
    // always has a free core the instant it wants one, independent of how
    // saturated the rest of the machine is.
    //
    // Configurable via "ReservedCoresForSelf" (default 2). 0 disables hard
    // reservation — priority-class separation above still applies on its own.

    private static volatile int reservedCoresCache = -1;

    private static int reservedCores()
    {
        if( reservedCoresCache >= 0 ) return reservedCoresCache;
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance()
                .getProperties().getProperty( "ReservedCoresForSelf", "2" );
            reservedCoresCache = Math.max( 0, Integer.parseInt( v.trim() ) );
        }
        catch( final Exception e )
        {
            reservedCoresCache = 2;
        }
        return reservedCoresCache;
    }

    /** Exposed so OllamaClient's thread-count math can avoid requesting more
     *  threads than actually fit on the cores it's restricted to. */
    public static int reservedCoreCount()
    {
        return reservedCores();
    }

    private static long maskRange( final int fromInclusive, final int toExclusive )
    {
        long mask = 0L;
        for( int i = fromInclusive; i < toExclusive; i++ ) mask |= ( 1L << i );
        return mask;
    }

    private static volatile boolean affinitySelfWarned = false;

    /** Pin this JVM to the top N reserved cores. Call once at startup. */
    public static void reserveCoresForSelf()
    {
        if( !WINDOWS ) return;
        final int n = reservedCores();
        if( n <= 0 ) return;
        final int total = Runtime.getRuntime().availableProcessors();
        if( total <= n )
        {
            log.warning( "[Affinity] Only " + total + " logical core(s) available — "
                + "skipping reservation of " + n + "." );
            return;
        }
        try
        {
            final long mask = maskRange( total - n, total ); // top n cores, self only
            if( K32.INSTANCE.SetProcessAffinityMask( K32.INSTANCE.GetCurrentProcess(), mask ) )
                log.info( "[Affinity] Shimeji pinned to " + n + " reserved core(s) of " + total + "." );
            else if( !affinitySelfWarned )
            {
                affinitySelfWarned = true;
                log.warning( "[Affinity] Could not set own affinity mask." );
            }
        }
        catch( final Throwable t )
        {
            log.log( Level.WARNING, "[Affinity] reserveCoresForSelf failed", t );
        }
    }

    /** Restrict a specific process (by pid) to every core EXCEPT the ones
     *  reserved for Shimeji. Safe to call redundantly — cheap, idempotent. */
    public static void restrictAffinity( final long pid )
    {
        if( !WINDOWS ) return;
        final int n = reservedCores();
        if( n <= 0 ) return;
        final int total = Runtime.getRuntime().availableProcessors();
        if( total <= n ) return;
        try
        {
            final Pointer h = K32.INSTANCE.OpenProcess( PROCESS_SET_INFORMATION, false, (int) pid );
            if( h == null ) return;
            try
            {
                final long mask = maskRange( 0, total - n ); // everything but the reserved top n
                K32.INSTANCE.SetProcessAffinityMask( h, mask );
            }
            finally
            {
                K32.INSTANCE.CloseHandle( h );
            }
        }
        catch( final Throwable t )
        {
            log.log( Level.FINE, "[Affinity] restrictAffinity(" + pid + ") failed", t );
        }
    }

    /** Same population scan as demoteByName, but restricts affinity instead
     *  (or in addition). Called alongside demoteByName from the periodic
     *  rescan so freshly spawned inference processes get caught. */
    public static void restrictAffinityByName( final String... exeNames )
    {
        if( !WINDOWS ) return;
        if( reservedCores() <= 0 ) return;
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
                        restrictAffinity( pe.th32ProcessID );
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
            log.log( Level.FINE, "[Affinity] restrictAffinityByName failed", t );
        }
    }
}
