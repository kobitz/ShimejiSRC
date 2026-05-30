package com.group_finity.mascot.assistant;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Snapshots which applications are actively playing audio on the default
 * render endpoint via IAudioSessionManager2. Uses the same JNA COM vtable
 * pattern as WasapiLoopbackCapture.
 *
 * Call getActiveAudioSources() just before transcription to capture a
 * snapshot of active audio producers while they are still playing.
 */
public class AudioSessionUtil
{
    private static final Logger log = Logger.getLogger( AudioSessionUtil.class.getName() );

    private static final int CLSCTX_ALL                        = 0x17;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int AUDIO_SESSION_STATE_ACTIVE        = 1;

    // ── vtable slots ──────────────────────────────────────────────────────────
    // IUnknown
    private static final int SLOT_QI      = 0;
    private static final int SLOT_RELEASE = 2;
    // IMMDeviceEnumerator: IUnknown(0-2), EnumAudioEndpoints(3), GetDefaultAudioEndpoint(4)
    private static final int SLOT_ENUM_GetDefaultAudioEndpoint = 4;
    // IMMDevice: IUnknown(0-2), Activate(3)
    private static final int SLOT_DEV_Activate                 = 3;
    // IAudioSessionManager2: IUnknown(0-2), IAudioSessionManager(3-4), GetSessionEnumerator(5)
    private static final int SLOT_SM2_GetSessionEnumerator     = 5;
    // IAudioSessionEnumerator: IUnknown(0-2), GetCount(3), GetSession(4)
    private static final int SLOT_SE_GetCount                  = 3;
    private static final int SLOT_SE_GetSession                = 4;
    // IAudioSessionControl: IUnknown(0-2), GetState(3)
    private static final int SLOT_SC_GetState                  = 3;
    // IAudioSessionControl2: IAudioSessionControl(0-11), GetSessionIdentifier(12),
    //   GetSessionInstanceIdentifier(13), GetProcessId(14)
    private static final int SLOT_SC2_GetProcessId             = 14;

    // ── JNA interfaces ────────────────────────────────────────────────────────

    interface Ole32 extends Library
    {
        Ole32 INSTANCE = (Ole32) Native.loadLibrary( "ole32", Ole32.class );
        int  CoInitializeEx( Pointer reserved, int dwCoInit );
        void CoUninitialize();
        int  CoCreateInstance( GUID rclsid, Pointer outer, int ctx, GUID riid, PointerByReference ppv );
    }

    interface Kernel32 extends Library
    {
        Kernel32 INSTANCE = (Kernel32) Native.loadLibrary( "kernel32", Kernel32.class );
        Pointer OpenProcess( int dwDesiredAccess, boolean bInheritHandle, int dwProcessId );
        boolean QueryFullProcessImageNameA( Pointer hProcess, int dwFlags,
                                            byte[] lpExeName, IntByReference lpdwSize );
        boolean CloseHandle( Pointer hObject );
    }

    public static class GUID extends Structure
    {
        public int    Data1;
        public short  Data2;
        public short  Data3;
        public byte[] Data4 = new byte[8];

        public GUID() {}
        public GUID( int d1, int d2, int d3, byte[] d4 )
        {
            Data1 = d1; Data2 = (short) d2; Data3 = (short) d3; Data4 = d4;
        }
        @Override
        protected List<String> getFieldOrder()
        {
            return Arrays.asList( "Data1", "Data2", "Data3", "Data4" );
        }
    }

    // ── GUIDs ─────────────────────────────────────────────────────────────────
    // CLSID_MMDeviceEnumerator = {BCDE0395-E52F-467C-8E3D-C4579291692E}
    private static final GUID CLSID_MMDeviceEnumerator = g(
        0xBCDE0395, 0xE52F, 0x467C, 0x8E,0x3D,0xC4,0x57,0x92,0x91,0x69,0x2E );
    // IID_IMMDeviceEnumerator = {A95664D2-9614-4F35-A746-DE8DB63617E6}
    private static final GUID IID_IMMDeviceEnumerator = g(
        0xA95664D2, 0x9614, 0x4F35, 0xA7,0x46,0xDE,0x8D,0xB6,0x36,0x17,0xE6 );
    // IID_IAudioSessionManager2 = {77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}
    private static final GUID IID_IAudioSessionManager2 = g(
        0x77AA99A0, 0x1BD6, 0x484F, 0x8B,0xC7,0x2C,0x65,0x4C,0x9A,0x9B,0x6F );
    // IID_IAudioSessionControl2 = {BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}
    private static final GUID IID_IAudioSessionControl2 = g(
        0xBFB7FF88, 0x7239, 0x4FC9, 0x8F,0xA2,0x07,0xC9,0x50,0xBE,0x9C,0x6D );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a comma-separated string of applications currently playing
     * audio on the default render endpoint, e.g. "Spotify, Chrome".
     * Returns null if no active audio sessions are found or on any error.
     * Fast -- just COM enumeration, no I/O.
     */
    public static String getActiveAudioSources()
    {
        final Ole32 ole32 = Ole32.INSTANCE;
        boolean comInited = false;
        Pointer enumerator  = null;
        Pointer device      = null;
        Pointer sessionMgr  = null;
        Pointer sessionEnum = null;

        try
        {
            final int hr = ole32.CoInitializeEx( null, 0 ); // COINIT_MULTITHREADED
            comInited = ( hr == 0 || hr == 1 );

            final PointerByReference ppv = new PointerByReference();

            if( ole32.CoCreateInstance( CLSID_MMDeviceEnumerator, null, CLSCTX_ALL,
                    IID_IMMDeviceEnumerator, ppv ) != 0 ) return null;
            enumerator = ppv.getValue();

            // GetDefaultAudioEndpoint(eRender=0, eConsole=0, &device)
            if( call( enumerator, SLOT_ENUM_GetDefaultAudioEndpoint, 0, 0, ppv ) != 0 ) return null;
            device = ppv.getValue();

            // IMMDevice::Activate(IID_IAudioSessionManager2, ...)
            if( call( device, SLOT_DEV_Activate, IID_IAudioSessionManager2,
                      CLSCTX_ALL, Pointer.NULL, ppv ) != 0 ) return null;
            sessionMgr = ppv.getValue();

            if( call( sessionMgr, SLOT_SM2_GetSessionEnumerator, ppv ) != 0 ) return null;
            sessionEnum = ppv.getValue();

            final IntByReference pCount = new IntByReference();
            if( call( sessionEnum, SLOT_SE_GetCount, pCount ) != 0 ) return null;
            final int count = pCount.getValue();

            final Set<String> names = new LinkedHashSet<>();
            for( int i = 0; i < count; i++ )
            {
                Pointer ctrl  = null;
                Pointer ctrl2 = null;
                try
                {
                    if( call( sessionEnum, SLOT_SE_GetSession, i, ppv ) != 0 ) continue;
                    ctrl = ppv.getValue();
                    if( ctrl == null ) continue;

                    // Skip inactive / expired sessions
                    final IntByReference pState = new IntByReference();
                    if( call( ctrl, SLOT_SC_GetState, pState ) != 0 ) continue;
                    if( pState.getValue() != AUDIO_SESSION_STATE_ACTIVE ) continue;

                    // QI to IAudioSessionControl2 to get the PID
                    if( call( ctrl, SLOT_QI, IID_IAudioSessionControl2, ppv ) != 0 ) continue;
                    ctrl2 = ppv.getValue();
                    if( ctrl2 == null ) continue;

                    final IntByReference pPid = new IntByReference();
                    if( call( ctrl2, SLOT_SC2_GetProcessId, pPid ) != 0 ) continue;
                    final int pid = pPid.getValue();
                    if( pid <= 4 ) continue; // skip System process (pid 0 / 4)

                    final String name = pidToName( pid );
                    if( name != null && !name.isEmpty() ) names.add( name );
                }
                finally
                {
                    if( ctrl2 != null ) call( ctrl2, SLOT_RELEASE );
                    if( ctrl  != null ) call( ctrl,  SLOT_RELEASE );
                }
            }

            if( names.isEmpty() ) return null;

            final StringBuilder sb = new StringBuilder();
            for( final String n : names )
            {
                if( sb.length() > 0 ) sb.append( ", " );
                sb.append( n );
            }
            return sb.toString();
        }
        catch( final Exception e )
        {
            log.log( Level.WARNING, "[AudioSession] Failed to enumerate sessions", e );
            return null;
        }
        finally
        {
            if( sessionEnum != null ) call( sessionEnum, SLOT_RELEASE );
            if( sessionMgr  != null ) call( sessionMgr,  SLOT_RELEASE );
            if( device      != null ) call( device,      SLOT_RELEASE );
            if( enumerator  != null ) call( enumerator,  SLOT_RELEASE );
            if( comInited ) ole32.CoUninitialize();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    // Windows system processes that hold audio sessions but aren't producing user audio
    private static final java.util.Set<String> SYSTEM_PROCESSES;
    static
    {
        final java.util.HashSet<String> s = new java.util.HashSet<>();
        s.add( "svchost" );   // Windows Service Host — hosts audio services
        s.add( "audiodg" );   // Windows Audio Device Graph Isolation
        s.add( "system" );
        s.add( "ntoskrnl" );
        s.add( "csrss" );
        s.add( "dwm" );       // Desktop Window Manager — may have UI sound sessions
        SYSTEM_PROCESSES = java.util.Collections.unmodifiableSet( s );
    }

    private static String pidToName( final int pid )
    {
        Pointer handle = null;
        try
        {
            handle = Kernel32.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION, false, pid );
            if( handle == null || Pointer.nativeValue( handle ) == 0 ) return null;

            final byte[]         buf  = new byte[512];
            final IntByReference size = new IntByReference( buf.length );
            if( !Kernel32.INSTANCE.QueryFullProcessImageNameA( handle, 0, buf, size ) )
                return null;

            final String fullPath = Native.toString( buf );
            if( fullPath == null || fullPath.isEmpty() ) return null;

            final int slash = Math.max( fullPath.lastIndexOf( '\\' ), fullPath.lastIndexOf( '/' ) );
            String exeName = fullPath.substring( slash + 1 );
            if( exeName.toLowerCase().endsWith( ".exe" ) )
                exeName = exeName.substring( 0, exeName.length() - 4 );

            if( SYSTEM_PROCESSES.contains( exeName.toLowerCase() ) ) return null;

            return friendlyName( exeName );
        }
        catch( final Exception e )
        {
            return null;
        }
        finally
        {
            if( handle != null && Pointer.nativeValue( handle ) != 0 )
                Kernel32.INSTANCE.CloseHandle( handle );
        }
    }

    /** Sentinel returned for any recognised web browser. */
    public static final String BROWSER = "Internet Browser";

    private static String friendlyName( final String exe )
    {
        switch( exe.toLowerCase() )
        {
            // Browsers — all collapsed to one label so the mascot says
            // "from an Internet Browser" and assumes video/music context
            case "chrome":        return BROWSER;
            case "firefox":       return BROWSER;
            case "msedge":        return BROWSER;
            case "librewolf":     return BROWSER;
            case "brave":         return BROWSER;
            case "opera":         return BROWSER;
            case "vivaldi":       return BROWSER;
            case "waterfox":      return BROWSER;
            case "palemoon":      return BROWSER;
            case "iexplore":      return BROWSER;
            // Media players
            case "spotify":   return "Spotify";
            case "vlc":       return "VLC";
            case "mpc-hc":    return "MPC-HC";
            case "mpc-hc64":  return "MPC-HC";
            case "mpv":       return "mpv";
            case "wmplayer":  return "Windows Media Player";
            // Communication
            case "discord":   return "Discord";
            case "zoom":      return "Zoom";
            case "teams":     return "Teams";
            case "slack":     return "Slack";
            // Streaming / capture
            case "obs64":     return "OBS";
            case "obs32":     return "OBS";
            default:          return exe;
        }
    }

    private static GUID g( final int d1, final int d2, final int d3, final int... b )
    {
        final byte[] d4 = new byte[8];
        for( int i = 0; i < 8; i++ ) d4[i] = (byte) b[i];
        return new GUID( d1, d2, d3, d4 );
    }

    private static int call( final Pointer obj, final int slot, final Object... args )
    {
        final Pointer  vtable  = obj.getPointer( 0 );
        final Pointer  funcPtr = vtable.getPointer( (long) slot * Native.POINTER_SIZE );
        final Function func    = Function.getFunction( funcPtr );
        final Object[] all     = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy( args, 0, all, 1, args.length );
        return func.invokeInt( all );
    }
}
