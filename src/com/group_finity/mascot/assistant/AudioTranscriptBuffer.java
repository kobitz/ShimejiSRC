package com.group_finity.mascot.assistant;

import javax.sound.sampled.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Captures system audio (WASAPI loopback) into a 15-second rolling PCM buffer.
 * Mic audio for echo removal is borrowed from VoiceCommandListener's ring buffer
 * rather than captured independently -- VCL already holds the mic line open.
 */
public class AudioTranscriptBuffer
{
    private static final Logger log = Logger.getLogger( AudioTranscriptBuffer.class.getName() );

    // ── Audio format ──────────────────────────────────────────────────────────
    private static final float SAMPLE_RATE  = 16_000f;
    private static final int   SAMPLE_BITS  = 16;
    private static final int   CHANNELS     = 1;
    private static final int   BUFFER_SECS  = 15;
    private static final AudioFormat FORMAT = new AudioFormat(
        SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false ); // signed, little-endian

    private static final int BUFFER_BYTES =
        (int)( SAMPLE_RATE * ( SAMPLE_BITS / 8 ) * CHANNELS * BUFFER_SECS );

    // Minimum useful audio: 2 seconds
    private static final int MIN_BYTES = (int)( SAMPLE_RATE * 2 * ( SAMPLE_BITS / 8 ) );

    // ── System audio ring buffer ──────────────────────────────────────────────
    private final byte[]    ring     = new byte[ BUFFER_BYTES ];
    private int             writePos = 0;
    private int             filled   = 0;

    // ── Capture resources ─────────────────────────────────────────────────────
    private TargetDataLine        line   = null;
    private WasapiLoopbackCapture wasapi = null;
    private Thread                thread = null;

    private final AtomicBoolean running = new AtomicBoolean( false );

    // ── Static instance reference (for VoiceCommandListener echo reference) ──
    private static volatile AudioTranscriptBuffer activeInstance = null;

    public AudioTranscriptBuffer() { activeInstance = this; }

    /**
     * Returns a snapshot of the most recently started instance's system audio
     * ring buffer (16 kHz mono 16-bit LE), or null if no instance is running.
     * Used by VoiceCommandListener to supply a reference signal for echo
     * suppression in whisper_server.py.
     */
    public static byte[] staticSysSnapshot()
    {
        final AudioTranscriptBuffer inst = activeInstance;
        return ( inst != null && inst.running.get() ) ? inst.sysSnapshot() : null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start capturing system audio.
     * Returns true if system audio capture started successfully.
     */
    public boolean start()
    {
        if( running.get() ) return true;

        // Prefer WASAPI loopback for system audio
        final WasapiLoopbackCapture w = new WasapiLoopbackCapture();
        if( w.open() )
        {
            wasapi = w;
            running.set( true );
            thread = new Thread( this::captureLoopWasapi, "audio-capture" );
            thread.setDaemon( true );
            thread.start();
            log.info( "[Audio] System capture started via WASAPI loopback." );
        }
        else
        {
            log.info( "[Audio] WASAPI unavailable, falling back to device scan." );
            line = findLoopbackLine();
            if( line == null )
            {
                log.warning( "[Audio] No suitable system audio input found." );
                return false;
            }
            try
            {
                line.open( FORMAT, BUFFER_BYTES / 4 );
                line.start();
            }
            catch( LineUnavailableException e )
            {
                log.log( Level.WARNING, "[Audio] Could not open system audio line", e );
                return false;
            }
            running.set( true );
            thread = new Thread( this::captureLoopLine, "audio-capture" );
            thread.setDaemon( true );
            thread.start();
            log.info( "[Audio] System capture started on: " + line.getLineInfo() );
        }

        return true;
    }

    /** Stop all capture and release devices. */
    public void stop()
    {
        running.set( false );
        currentSysRms = 0;
        if( thread != null ) { thread.interrupt(); thread = null; }
        if( wasapi != null ) { wasapi.close();     wasapi = null; }
        if( line   != null ) { line.stop(); line.close(); line = null; }
        log.info( "[Audio] Capture stopped." );
    }

    public boolean isRunning() { return running.get(); }

    /**
     * Transcribes only the mic buffer (borrowed from VoiceCommandListener).
     * Useful when there is no system audio but the user spoke after calling the mascot's name.
     * Returns null if mic is unavailable, silent, or transcript is blank.
     */
    public String transcribeUserSpeech()
    {
        return transcribeMic();
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public static final class TranscriptResult
    {
        /** System audio transcript (primary). */
        public final String transcript;
        /** Apps actively playing audio, e.g. "Spotify" or "Internet Browser". Null if unknown. */
        public final String source;
        /** Mic transcript with system-audio echo removed. Null if mic unavailable or silent. */
        public final String userSpeech;

        public TranscriptResult( final String transcript,
                                 final String source,
                                 final String userSpeech )
        {
            this.transcript = transcript;
            this.source     = source;
            this.userSpeech = userSpeech;
        }
    }

    /**
     * Snapshots active audio sources, transcribes system audio, then borrows
     * VoiceCommandListener's mic buffer (echo-cleaned) for user speech.
     * Returns null if system audio transcription failed or audio too short.
     */
    public TranscriptResult transcribeWithSource()
    {
        // Sample sources now — apps may stop playing by the time Whisper finishes
        final String source = AudioSessionUtil.getActiveAudioSources();

        final String sysTranscript = transcribe();
        if( sysTranscript == null ) return null;

        String userSpeech = transcribeMic();
        if( userSpeech != null )
            userSpeech = stripOverlap( sysTranscript, userSpeech );

        return new TranscriptResult( sysTranscript, source, userSpeech );
    }

    /** Last system audio transcript — used by VoiceCommandListener to strip video bleed from mic. */
    public static volatile String lastSysTranscript = null;

    /** Real-time system audio RMS. Read by VoiceCommandListener and exposed via MascotEnvironment.audioLevel. */
    public static volatile int currentSysRms = 0;

    /**
     * Transcribe only the system audio buffer.
     * Returns the transcript text, or null if too short / nothing heard.
     */
    public String transcribe()
    {
        final byte[] snap = sysSnapshot();
        if( snap == null || snap.length < MIN_BYTES )
        {
            log.info( "[Audio] System buffer too short to transcribe." );
            return null;
        }
        final String t = transcribeWav( snap, "shimeji_audio_" );
        lastSysTranscript = t;
        return t;
    }

    // ── Private capture loops ─────────────────────────────────────────────────

    private void captureLoopWasapi()
    {
        while( running.get() )
        {
            final byte[] converted = wasapi.readConverted();
            if( converted == null ) { running.set( false ); break; }
            if( converted.length > 0 )
                appendToSysRing( converted, converted.length );
            else
            {
                try { Thread.sleep( 10 ); }
                catch( InterruptedException e ) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void captureLoopLine()
    {
        final byte[] chunk = new byte[ 4096 ];
        while( running.get() )
        {
            final int n = line.read( chunk, 0, chunk.length );
            if( n > 0 ) appendToSysRing( chunk, n );
        }
    }

    // ── Private transcription helpers ─────────────────────────────────────────

    /**
     * Borrows VoiceCommandListener's mic ring buffer for transcription.
     * VCL already owns the mic line, so ATB doesn't need its own capture.
     */
    private String transcribeMic()
    {
        final byte[] snap = VoiceCommandListener.getInstance().snapshotMic();
        if( snap == null || snap.length < MIN_BYTES ) return null;
        return transcribeWav( snap, "shimeji_mic_" );
    }

    private String transcribeWav( final byte[] pcm, final String prefix )
    {
        File wav = null;
        try
        {
            wav = File.createTempFile( prefix, ".wav" );
            wav.deleteOnExit();
            writeWav( pcm, wav );
            final String t = WhisperProcess.getInstance().transcribe( wav );
            return ( t == null || t.isBlank() ) ? null : t.trim();
        }
        catch( IOException e )
        {
            log.log( Level.WARNING, "[Audio] Transcription error (" + prefix + ")", e );
            return null;
        }
        finally
        {
            if( wav != null ) wav.delete();
        }
    }

    // ── Echo removal ──────────────────────────────────────────────────────────

    /**
     * Removes words from micText whose 3-word context also appears in sysText.
     * Returns null if fewer than 4 words remain (probably just noise or full echo).
     */
    public static String stripOverlap( final String sysText, final String micText )
    {
        if( sysText == null || sysText.isBlank() ) return micText;
        if( micText == null || micText.isBlank() ) return null;

        final String[] sysW = words( sysText );
        final String[] micW = words( micText );
        if( sysW.length == 0 ) return micText;

        final int N = 3;
        final java.util.Set<String> sysNgrams = new java.util.HashSet<>();
        if( sysW.length >= N )
        {
            for( int i = 0; i <= sysW.length - N; i++ )
                sysNgrams.add( sysW[i] + '\t' + sysW[i+1] + '\t' + sysW[i+2] );
        }
        else
        {
            for( final String w : sysW ) sysNgrams.add( w );
        }

        final boolean[] remove = new boolean[ micW.length ];
        if( micW.length >= N )
        {
            for( int i = 0; i <= micW.length - N; i++ )
            {
                if( sysNgrams.contains( micW[i] + '\t' + micW[i+1] + '\t' + micW[i+2] ) )
                    remove[i] = remove[i+1] = remove[i+2] = true;
            }
        }

        final StringBuilder sb = new StringBuilder();
        for( int i = 0; i < micW.length; i++ )
        {
            if( !remove[i] )
            {
                if( sb.length() > 0 ) sb.append( ' ' );
                sb.append( micW[i] );
            }
        }
        final String result = sb.toString().trim();
        return result.split( "\\s+" ).length >= 4 ? result : null;
    }

    /** Lowercase, strip non-alphanumeric except apostrophes, split on whitespace. */
    private static String[] words( final String text )
    {
        final String norm = text.toLowerCase().replaceAll( "[^a-z0-9'\\s]", "" ).trim();
        return norm.isEmpty() ? new String[0] : norm.split( "\\s+" );
    }

    // ── Ring buffer helpers ───────────────────────────────────────────────────

    private void appendToSysRing( final byte[] src, final int len )
    {
        synchronized( ring )
        {
            for( int i = 0; i < len; i++ )
            {
                ring[ writePos ] = src[ i ];
                writePos = ( writePos + 1 ) % BUFFER_BYTES;
            }
            filled = Math.min( filled + len, BUFFER_BYTES );
        }
        // Exponential moving average of system audio RMS — used by VoiceCommandListener
        // to detect speaker echo on the mic line and set the correct silence floor.
        if( len >= 2 )
            currentSysRms = (int)( currentSysRms * 0.85f + rms16( src, len ) * 0.15f );
    }

    private static int rms16( final byte[] pcm, final int len )
    {
        if( len < 2 ) return 0;
        long sum = 0;
        final int frames = len / 2;
        for( int i = 0; i < frames; i++ )
        {
            final short s = (short)( ( pcm[ i * 2 ] & 0xFF ) | ( pcm[ i * 2 + 1 ] << 8 ) );
            sum += (long) s * s;
        }
        return (int) Math.sqrt( (double) sum / frames );
    }

    private byte[] sysSnapshot()
    {
        synchronized( ring )
        {
            if( filled == 0 ) return null;
            return copyRing( ring, writePos, filled );
        }
    }

    private static byte[] copyRing( final byte[] buf, final int wp, final int fill )
    {
        final byte[] out = new byte[ fill ];
        if( fill < BUFFER_BYTES )
        {
            System.arraycopy( buf, 0, out, 0, fill );
        }
        else
        {
            final int tail = BUFFER_BYTES - wp;
            System.arraycopy( buf, wp, out, 0,    tail );
            System.arraycopy( buf, 0,  out, tail, wp   );
        }
        return out;
    }

    // ── WAV writing ───────────────────────────────────────────────────────────

    private void writeWav( final byte[] pcm, final File dest ) throws IOException
    {
        final int dataLen  = pcm.length;
        final int fmtLen   = 16;
        final int totalLen = 4 + 8 + fmtLen + 8 + dataLen;

        try( DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream( new FileOutputStream( dest ) ) ) )
        {
            dos.writeBytes( "RIFF" );
            writeInt32LE( dos, totalLen );
            dos.writeBytes( "WAVE" );

            dos.writeBytes( "fmt " );
            writeInt32LE( dos, fmtLen );
            writeInt16LE( dos, 1 );
            writeInt16LE( dos, CHANNELS );
            writeInt32LE( dos, (int) SAMPLE_RATE );
            writeInt32LE( dos, (int) SAMPLE_RATE * CHANNELS * ( SAMPLE_BITS / 8 ) );
            writeInt16LE( dos, CHANNELS * ( SAMPLE_BITS / 8 ) );
            writeInt16LE( dos, SAMPLE_BITS );

            dos.writeBytes( "data" );
            writeInt32LE( dos, dataLen );
            dos.write( pcm );
        }
    }

    private static void writeInt32LE( final DataOutputStream dos, final int v ) throws IOException
    {
        dos.write(  v        & 0xFF );
        dos.write( (v >>  8) & 0xFF );
        dos.write( (v >> 16) & 0xFF );
        dos.write( (v >> 24) & 0xFF );
    }

    private static void writeInt16LE( final DataOutputStream dos, final int v ) throws IOException
    {
        dos.write(  v       & 0xFF );
        dos.write( (v >> 8) & 0xFF );
    }

    // ── Device discovery (system audio only) ─────────────────────────────────

    /** Finds a loopback/mix device for system audio (fallback when WASAPI unavailable).
     *  Only matches devices whose names identify them as loopback/mix sources — no
     *  blind fallback: grabbing an arbitrary capture device here would silently
     *  treat the microphone as "system audio". */
    private static TargetDataLine findLoopbackLine()
    {
        final String[][] preferred = {
            { "cable", "output" },
            { "vb-audio", "virtual" },
            { "loopback" },
            { "stereo mix" },
            { "wave out mix" }
        };

        for( final Mixer.Info mi : AudioSystem.getMixerInfo() )
        {
            final String name = mi.getName().toLowerCase();
            for( final String[] frags : preferred )
            {
                boolean all = true;
                for( final String f : frags ) if( !name.contains( f ) ) { all = false; break; }
                if( all )
                {
                    final TargetDataLine tdl = tryOpenLine( mi );
                    if( tdl != null ) return tdl;
                }
            }
        }
        return null;
    }

    private static TargetDataLine tryOpenLine( final Mixer.Info mi )
    {
        try
        {
            final Mixer mixer = AudioSystem.getMixer( mi );
            for( final Line.Info info : mixer.getTargetLineInfo() )
            {
                if( !( info instanceof DataLine.Info ) ) continue;
                if( ( (DataLine.Info) info ).isFormatSupported( FORMAT ) )
                    return (TargetDataLine) mixer.getLine( info );
            }
        }
        catch( Exception ignored ) {}
        return null;
    }
}
