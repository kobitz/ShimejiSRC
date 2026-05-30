package com.group_finity.mascot.assistant;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Captures system audio output via WASAPI loopback (no VB-Cable required).
 * Returns PCM converted to 16 kHz / mono / signed 16-bit LE -- Whisper's preferred format.
 *
 * Usage:
 *   WasapiLoopbackCapture w = new WasapiLoopbackCapture();
 *   if (w.open()) {
 *       byte[] pcm = w.readConverted(); // call repeatedly in capture loop
 *       w.close();
 *   }
 */
public class WasapiLoopbackCapture
{
    private static final Logger log = Logger.getLogger( WasapiLoopbackCapture.class.getName() );

    // WASAPI / COM constants
    private static final int  AUDCLNT_SHAREMODE_SHARED     = 0;
    private static final int  AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int  AUDCLNT_BUFFERFLAGS_SILENT   = 0x00000002;
    private static final int  CLSCTX_ALL                   = 0x17;
    private static final long BUFFER_DURATION_100NS         = 5_000_000L; // 500 ms
    private static final int  WAVE_FORMAT_IEEE_FLOAT        = 3;
    private static final int  WAVE_FORMAT_EXTENSIBLE        = 0xFFFE;
    private static final int  TARGET_RATE                   = 16_000;

    // COM vtable slot indices (0=QueryInterface, 1=AddRef, 2=Release, then interface methods)
    private static final int SLOT_ENUM_GetDefaultAudioEndpoint = 4;
    private static final int SLOT_DEV_Activate                 = 3;
    private static final int SLOT_AC_Initialize                = 3;
    private static final int SLOT_AC_GetMixFormat              = 8;
    private static final int SLOT_AC_Start                     = 10;
    private static final int SLOT_AC_Stop                      = 11;
    private static final int SLOT_AC_GetService                = 14;
    private static final int SLOT_CAP_GetBuffer                = 3;
    private static final int SLOT_CAP_ReleaseBuffer            = 4;
    private static final int SLOT_CAP_GetNextPacketSize        = 5;
    private static final int SLOT_UNK_Release                  = 2;

    // ── JNA ──────────────────────────────────────────────────────────────────

    public interface Ole32 extends Library
    {
        Ole32 INSTANCE = (Ole32) Native.loadLibrary( "ole32", Ole32.class );
        int  CoInitializeEx( Pointer reserved, int dwCoInit );
        void CoUninitialize();
        int  CoCreateInstance( GUID rclsid, Pointer outer, int ctx, GUID riid, PointerByReference ppv );
        void CoTaskMemFree( Pointer pv );
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

    // ── GUIDs ────────────────────────────────────────────────────────────────

    // CLSID_MMDeviceEnumerator = {BCDE0395-E52F-467C-8E3D-C4579291692E}
    private static final GUID CLSID_MMDeviceEnumerator = g(
        0xBCDE0395, 0xE52F, 0x467C, 0x8E,0x3D,0xC4,0x57,0x92,0x91,0x69,0x2E );
    // IID_IMMDeviceEnumerator = {A95664D2-9614-4F35-A746-DE8DB63617E6}
    private static final GUID IID_IMMDeviceEnumerator = g(
        0xA95664D2, 0x9614, 0x4F35, 0xA7,0x46,0xDE,0x8D,0xB6,0x36,0x17,0xE6 );
    // IID_IAudioClient = {1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}
    private static final GUID IID_IAudioClient = g(
        0x1CB9AD4C, 0xDBFA, 0x4C32, 0xB1,0x78,0xC2,0xF5,0x68,0xA7,0x03,0xB2 );
    // IID_IAudioCaptureClient = {C8ADBD64-E71E-48A0-A4DE-185C395CD317}
    private static final GUID IID_IAudioCaptureClient = g(
        0xC8ADBD64, 0xE71E, 0x48A0, 0xA4,0xDE,0x18,0x5C,0x39,0x5C,0xD3,0x17 );

    private static GUID g( int d1, int d2, int d3, int... b )
    {
        byte[] d4 = new byte[8];
        for ( int i = 0; i < 8; i++ ) d4[i] = (byte) b[i];
        return new GUID( d1, d2, d3, d4 );
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private Pointer enumerator;
    private Pointer device;
    private Pointer audioClient;
    private Pointer captureClient;

    private int     nativeCh;
    private int     nativeRate;
    private int     nativeBps;      // bytes per sample per channel
    private boolean nativeFloat;

    private boolean comInited;
    private boolean open;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initialise WASAPI loopback capture on the default audio output device.
     * Returns true on success. Always call close() when done.
     */
    public boolean open()
    {
        Ole32 ole32 = Ole32.INSTANCE;
        int hr = ole32.CoInitializeEx( null, 0 ); // COINIT_MULTITHREADED
        // S_OK=0, S_FALSE=1 (already init same mode) -- both mean we own a ref
        comInited = ( hr == 0 || hr == 1 );

        PointerByReference ppv = new PointerByReference();

        hr = ole32.CoCreateInstance( CLSID_MMDeviceEnumerator, null, CLSCTX_ALL,
                                     IID_IMMDeviceEnumerator, ppv );
        if ( fail( hr, "CoCreateInstance" ) ) { cleanupCom(); return false; }
        enumerator = ppv.getValue();

        // GetDefaultAudioEndpoint( eRender=0, eConsole=0, &ppDevice )
        hr = call( enumerator, SLOT_ENUM_GetDefaultAudioEndpoint, 0, 0, ppv );
        if ( fail( hr, "GetDefaultAudioEndpoint" ) ) { cleanup(); return false; }
        device = ppv.getValue();

        // IMMDevice::Activate( IID_IAudioClient, CLSCTX_ALL, null, &ppAudioClient )
        hr = call( device, SLOT_DEV_Activate, IID_IAudioClient, CLSCTX_ALL, Pointer.NULL, ppv );
        if ( fail( hr, "Activate" ) ) { cleanup(); return false; }
        audioClient = ppv.getValue();

        // GetMixFormat( &ppFormat )
        PointerByReference ppFmt = new PointerByReference();
        hr = call( audioClient, SLOT_AC_GetMixFormat, ppFmt );
        if ( fail( hr, "GetMixFormat" ) ) { cleanup(); return false; }
        Pointer pFmt = ppFmt.getValue();
        readFormat( pFmt );

        // Initialize( SHARED, LOOPBACK, bufDuration, 0, pFormat, null )
        hr = call( audioClient, SLOT_AC_Initialize,
                   AUDCLNT_SHAREMODE_SHARED,
                   AUDCLNT_STREAMFLAGS_LOOPBACK,
                   BUFFER_DURATION_100NS,
                   0L,
                   pFmt,
                   Pointer.NULL );
        ole32.CoTaskMemFree( pFmt );
        if ( fail( hr, "Initialize" ) ) { cleanup(); return false; }

        // GetService( IID_IAudioCaptureClient, &ppCaptureClient )
        hr = call( audioClient, SLOT_AC_GetService, IID_IAudioCaptureClient, ppv );
        if ( fail( hr, "GetService" ) ) { cleanup(); return false; }
        captureClient = ppv.getValue();

        hr = call( audioClient, SLOT_AC_Start );
        if ( fail( hr, "Start" ) ) { cleanup(); return false; }

        open = true;
        log.info( String.format( "[WASAPI] Loopback started: %dch %dHz %dbit %s",
                  nativeCh, nativeRate, nativeBps * 8, nativeFloat ? "float" : "int" ) );
        return true;
    }

    /**
     * Drain all currently buffered audio packets and return them converted to
     * 16 kHz / mono / signed 16-bit LE. Returns an empty array if no audio is
     * available yet, or null on error.
     */
    public byte[] readConverted()
    {
        if ( !open ) return null;

        IntByReference pSize = new IntByReference();
        if ( call( captureClient, SLOT_CAP_GetNextPacketSize, pSize ) != 0 ) return null;
        if ( pSize.getValue() == 0 ) return new byte[0];

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while ( pSize.getValue() > 0 )
        {
            PointerByReference ppData  = new PointerByReference();
            IntByReference     pFrames = new IntByReference();
            IntByReference     pFlags  = new IntByReference();

            if ( call( captureClient, SLOT_CAP_GetBuffer,
                       ppData, pFrames, pFlags, Pointer.NULL, Pointer.NULL ) != 0 ) break;

            final int frames = pFrames.getValue();
            final int flags  = pFlags.getValue();
            if ( ( flags & AUDCLNT_BUFFERFLAGS_SILENT ) == 0 && frames > 0 )
            {
                final byte[] chunk = ppData.getValue().getByteArray(
                        0, frames * nativeCh * nativeBps );
                try { raw.write( chunk ); } catch ( IOException ignored ) {}
            }
            call( captureClient, SLOT_CAP_ReleaseBuffer, frames );
            if ( call( captureClient, SLOT_CAP_GetNextPacketSize, pSize ) != 0 ) break;
        }

        final byte[] nativeBytes = raw.toByteArray();
        if ( nativeBytes.length == 0 ) return new byte[0];
        return convert( nativeBytes, nativeBytes.length / ( nativeCh * nativeBps ) );
    }

    public void close()
    {
        if ( open ) { call( audioClient, SLOT_AC_Stop ); open = false; }
        cleanup();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void readFormat( final Pointer p )
    {
        final int tag = p.getShort( 0 ) & 0xFFFF;
        nativeCh   = p.getShort( 2 ) & 0xFFFF;
        nativeRate = p.getInt( 4 );
        nativeBps  = ( p.getShort( 14 ) & 0xFFFF ) / 8;
        if ( tag == WAVE_FORMAT_EXTENSIBLE )
        {
            // SubFormat.Data1 sits at offset 24 in WAVEFORMATEXTENSIBLE
            nativeFloat = ( p.getInt( 24 ) == WAVE_FORMAT_IEEE_FLOAT );
        }
        else
        {
            nativeFloat = ( tag == WAVE_FORMAT_IEEE_FLOAT );
        }
    }

    /** Down-mix to mono, resample to 16 kHz, convert to signed 16-bit LE. */
    private byte[] convert( final byte[] raw, final int frames )
    {
        // Step 1: extract mono float samples
        final float[] mono = new float[frames];
        for ( int i = 0; i < frames; i++ )
        {
            float sum = 0;
            for ( int c = 0; c < nativeCh; c++ )
            {
                final int off = ( i * nativeCh + c ) * nativeBps;
                float s;
                if ( nativeFloat )
                {
                    final int bits = ( raw[off+3] & 0xFF ) << 24
                                   | ( raw[off+2] & 0xFF ) << 16
                                   | ( raw[off+1] & 0xFF ) << 8
                                   | ( raw[off]   & 0xFF );
                    s = Float.intBitsToFloat( bits );
                }
                else
                {
                    s = (short) ( ( raw[off+1] & 0xFF ) << 8 | ( raw[off] & 0xFF ) ) / 32768f;
                }
                sum += s;
            }
            mono[i] = sum / nativeCh;
        }

        // Step 2: linear-interpolation resample to TARGET_RATE
        final int outFrames = (int) ( (long) frames * TARGET_RATE / nativeRate );
        if ( outFrames == 0 ) return new byte[0];
        final byte[] out = new byte[outFrames * 2];
        for ( int i = 0; i < outFrames; i++ )
        {
            final double pos = (double) i * nativeRate / TARGET_RATE;
            final int    idx = (int) pos;
            final float  frc = (float) ( pos - idx );
            final float  s0  = mono[Math.min( idx,     mono.length - 1 )];
            final float  s1  = mono[Math.min( idx + 1, mono.length - 1 )];
            final int    pcm = Math.max( -32768, Math.min( 32767,
                               (int) ( ( s0 + ( s1 - s0 ) * frc ) * 32768f ) ) );
            out[i * 2]     = (byte) ( pcm       & 0xFF );
            out[i * 2 + 1] = (byte) ( ( pcm >> 8 ) & 0xFF );
        }
        return out;
    }

    private void cleanup()
    {
        if ( captureClient != null ) { call( captureClient, SLOT_UNK_Release ); captureClient = null; }
        if ( audioClient   != null ) { call( audioClient,   SLOT_UNK_Release ); audioClient   = null; }
        if ( device        != null ) { call( device,        SLOT_UNK_Release ); device        = null; }
        if ( enumerator    != null ) { call( enumerator,    SLOT_UNK_Release ); enumerator    = null; }
        cleanupCom();
    }

    private void cleanupCom()
    {
        if ( comInited ) { Ole32.INSTANCE.CoUninitialize(); comInited = false; }
    }

    private boolean fail( final int hr, final String op )
    {
        if ( hr != 0 )
        {
            log.warning( "[WASAPI] " + op + " failed: 0x" + Integer.toHexString( hr ) );
            return true;
        }
        return false;
    }

    /**
     * Call a COM vtable method. Prepends the COM object (this-pointer) automatically.
     * All extra args are marshalled by JNA -- pass Integer/Long/Pointer/Structure/etc.
     */
    private static int call( final Pointer obj, final int slot, final Object... args )
    {
        final Pointer   vtable  = obj.getPointer( 0 );
        final Pointer   funcPtr = vtable.getPointer( (long) slot * Native.POINTER_SIZE );
        final Function  func    = Function.getFunction( funcPtr );
        final Object[]  all     = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy( args, 0, all, 1, args.length );
        return func.invokeInt( all );
    }
}
