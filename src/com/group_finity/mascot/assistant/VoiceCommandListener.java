package com.group_finity.mascot.assistant;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Continuously captures microphone input and watches for mascot name triggers.
 * When the mascot's name is detected in a transcription, the registered Runnable
 * is fired — it is responsible for pulling context from AudioTranscriptBuffer.
 *
 * Shared singleton — all mascots register their name + callback here.
 * Uses a short rolling buffer (BUFFER_SECS) transcribed every POLL_MS.
 */
public class VoiceCommandListener
{
    private static final Logger log = Logger.getLogger( VoiceCommandListener.class.getName() );

    // ── Audio format ──────────────────────────────────────────────────────────
    private static final float SAMPLE_RATE  = 16_000f;
    private static final int   SAMPLE_BITS  = 16;
    private static final int   CHANNELS     = 1;
    private static final int   BUFFER_SECS  = 6;   // rolling window to transcribe
    private static final int   POLL_MS      = 6_000; // how often to run Whisper

    private static final AudioFormat FORMAT = new AudioFormat(
        SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false );

    private static final int BUFFER_BYTES =
        (int)( SAMPLE_RATE * ( SAMPLE_BITS / 8 ) * CHANNELS * BUFFER_SECS );

    // Native mic formats to try when the device won't open at 16 kHz
    private static final AudioFormat[] FALLBACK_MIC_FORMATS = {
        new AudioFormat( 44100f, 16, 1, true, false ),
        new AudioFormat( 48000f, 16, 1, true, false ),
        new AudioFormat( 44100f, 16, 2, true, false ),
        new AudioFormat( 48000f, 16, 2, true, false ),
    };


    // ── Singleton ─────────────────────────────────────────────────────────────
    private static VoiceCommandListener instance = null;
    private static final Object INSTANCE_LOCK = new Object();

    public static VoiceCommandListener getInstance()
    {
        synchronized( INSTANCE_LOCK )
        {
            if( instance == null ) instance = new VoiceCommandListener();
            return instance;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final byte[]        ring     = new byte[ BUFFER_BYTES ];
    private volatile int        writePos = 0;
    private volatile int        filled   = 0;

    private TargetDataLine      line     = null;
    private float               lineConvRatio    = 1.0f;
    private int                 lineConvChannels = 1;
    private Thread              captureThread = null;
    private ScheduledExecutorService pollExecutor = null;
    private final AtomicBoolean running  = new AtomicBoolean( false );

    /** name (lowercase) -> trigger (receives the full mic transcript that fired it) */
    private final Map<String, java.util.function.Consumer<String>> callbacks = new ConcurrentHashMap<>();

    /** Debounce: ignore commands within COOLDOWN_MS of the last one per name */
    private final Map<String, Long> lastCommandTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 4_000;

    /** Last transcript — avoid re-processing the same text */
    private String lastTranscript = "";

    // ── Endpoint detection ────────────────────────────────────────────────────
    // Monitors mic energy per chunk. On speech onset, records into a command
    // buffer. When silence persists for SILENCE_HOLD_MS after speech, the
    // segment is immediately sent to Whisper — no poll wait, no video bleed.

    private static final int   SILENCE_HOLD_MS  = 400;               // ms of quiet to end utterance
    private static final int   MIN_SPEECH_BYTES =
        (int)( SAMPLE_RATE * 2 * 0.15 );                             // 150 ms minimum
    private static final int   MAX_CMD_BYTES    =
        (int)( SAMPLE_RATE * 2 * 8.0 );                              // 8 s hard cap
    private static final float SPEECH_MULT      = 3.5f;   // threshold = ambient * this
    private static final int   MIN_THRESHOLD    = 400;    // floor even in total silence
    private static final float AMBIENT_ALPHA    = 0.008f; // noise floor tracking (~16s time constant)

    // When speakers are playing (video, music), the acoustic echo that bleeds into
    // the mic would raise ambientRms over time, pushing the speech-onset threshold
    // too high. Freeze ambient tracking when system audio is detected.
    private static final int   AMBIENT_SYS_FREEZE = 200;
    // When audio is playing the mic echo keeps inSpeech=true, so the hard cap is
    // the only flush trigger. Shorten it so the user's utterance is in a smaller
    // window and easier for Whisper to extract from the mixed signal.
    private static final int   AUDIO_PLAYING_SYS_RMS = 300;
    private static final int   MAX_CMD_BYTES_AUDIO =
        (int)( SAMPLE_RATE * 2 * 3.0 );                          // 3 s cap when audio plays

    /** Adaptive ambient noise floor estimate (RMS units). Updated when not in speech. */
    private float   ambientRms    = 300f;

    private byte[]  cmdBuf        = null;
    private int     cmdLen        = 0;
    private boolean inSpeech      = false;
    private long    silenceStartMs = 0;
    // True if any sub-threshold chunk arrived during the current speech segment.
    // A full-cap flush with no silence = sustained background noise, not speech.
    private boolean hadSilence    = false;

    // Peak RMS seen in the current speech segment. Used for relative-silence detection:
    // a drop to RELATIVE_SILENCE_FRAC of the peak counts as silence even when still
    // above the global threshold (handles constant fan/AC noise raising the floor).
    private int     inSpeechMaxRms = 0;
    // Per-segment energy range for variance-based noise discrimination.
    private int     segMinRms      = Integer.MAX_VALUE;
    private int     segMaxRms      = 0;

    // Relative-silence fraction: RMS must fall below this fraction of the utterance
    // peak (and peak must be >= 1.2x threshold) to count as within-speech silence.
    private static final float RELATIVE_SILENCE_FRAC = 0.70f;
    // max/min RMS ratio below which a full-cap no-silence segment is pure noise.
    private static final float NOISE_VARIANCE_RATIO   = 1.4f;

    private VoiceCommandListener() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Register a mascot name and its name-trigger callback.
     * Starts capture if not already running.
     * Name matching is case-insensitive.
     */
    public void register( final String mascotName, final java.util.function.Consumer<String> callback )
    {
        callbacks.put( mascotName.toLowerCase(), callback );
        log.info( "[Voice] Registered: " + mascotName );
        startIfNeeded();
    }

    /** Unregister a mascot. Stops capture if no callbacks remain. */
    public void unregister( final String mascotName )
    {
        callbacks.remove( mascotName.toLowerCase() );
        log.info( "[Voice] Unregistered: " + mascotName );
        if( callbacks.isEmpty() ) stop();
    }

    public boolean isRunning() { return running.get(); }

    /**
     * Returns a snapshot of the mic ring buffer for external use (e.g. echo removal
     * in AudioTranscriptBuffer). The returned PCM is 16 kHz mono 16-bit signed LE.
     * Returns null if not running or buffer empty.
     */
    public byte[] snapshotMic()
    {
        if( !running.get() ) return null;
        return snapshot();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private synchronized void startIfNeeded()
    {
        if( running.get() ) return;

        line = findMicLine();
        if( line == null )
        {
            log.warning( "[Voice] No microphone found." );
            return;
        }

        boolean opened = false;
        try
        {
            line.open( FORMAT, BUFFER_BYTES / 4 );
            lineConvRatio = 1.0f; lineConvChannels = 1;
            opened = true;
        }
        catch( LineUnavailableException e )
        {
            log.info( "[Voice] Mic does not support 16 kHz natively, trying native formats." );
            for( final AudioFormat nfmt : FALLBACK_MIC_FORMATS )
            {
                try
                {
                    line.open( nfmt );
                    lineConvRatio    = nfmt.getSampleRate() / FORMAT.getSampleRate();
                    lineConvChannels = nfmt.getChannels();
                    opened = true;
                    log.warning( "[Voice] Mic opened at " + (int) nfmt.getSampleRate()
                        + " Hz / " + nfmt.getChannels() + "ch (will downsample)." );
                    break;
                }
                catch( LineUnavailableException ex )
                {
                    log.info( "[Voice] Mic format " + (int) nfmt.getSampleRate()
                        + "/" + nfmt.getChannels() + "ch failed: " + ex.getMessage() );
                }
            }
        }
        if( !opened )
        {
            log.warning( "[Voice] Could not open mic line at any format." );
            return;
        }
        line.start();

        running.set( true );

        captureThread = new Thread( this::captureLoop, "voice-capture" );
        captureThread.setDaemon( true );
        captureThread.start();

        int pollMs = POLL_MS;
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance()
                .getProperties().getProperty( "VoicePollMs", String.valueOf( POLL_MS ) );
            pollMs = Math.max( 1_000, Math.min( 30_000, Integer.parseInt( v.trim() ) ) );
        }
        catch( Exception ignored ) {}
        final int pollMsFinal = pollMs;

        pollExecutor = Executors.newSingleThreadScheduledExecutor( r ->
        {
            final Thread t = new Thread( r, "voice-poll" );
            t.setDaemon( true );
            return t;
        });
        pollExecutor.scheduleAtFixedRate( this::poll, pollMsFinal, pollMsFinal,
                                          TimeUnit.MILLISECONDS );
        log.warning( "[Voice] Poll interval: " + pollMsFinal + " ms" );

        log.warning( "[Voice] Capture started on: " + line.getLineInfo() );
    }

    private synchronized void stop()
    {
        if( !running.get() ) return;
        running.set( false );

        if( pollExecutor != null ) { pollExecutor.shutdownNow(); pollExecutor = null; }
        if( captureThread != null ) { captureThread.interrupt(); captureThread = null; }
        if( line != null ) { line.stop(); line.close(); line = null; }
        lineConvRatio = 1.0f; lineConvChannels = 1;
        cmdBuf = null; cmdLen = 0; inSpeech = false; silenceStartMs = 0; hadSilence = false;
        inSpeechMaxRms = 0; segMinRms = Integer.MAX_VALUE; segMaxRms = 0;

        log.info( "[Voice] Capture stopped." );
    }

    private void captureLoop()
    {
        final byte[] chunk = new byte[ 4096 ];
        while( running.get() )
        {
            final int n = line.read( chunk, 0, chunk.length );
            if( n <= 0 ) continue;
            final byte[] pcm;
            final int    pcmLen;
            if( lineConvRatio == 1.0f )
            {
                pcm = chunk; pcmLen = n;
            }
            else
            {
                pcm = downmixResample16( chunk, n, lineConvChannels, lineConvRatio );
                pcmLen = pcm.length;
            }
            synchronized( ring )
            {
                for( int i = 0; i < pcmLen; i++ )
                {
                    ring[ writePos ] = pcm[ i ];
                    writePos = ( writePos + 1 ) % BUFFER_BYTES;
                }
                filled = Math.min( filled + pcmLen, BUFFER_BYTES );
            }
            handleEndpoint( pcm, pcmLen );
        }
    }

    private void handleEndpoint( final byte[] pcm, final int len )
    {
        if( callbacks.isEmpty() ) return;

        final int rms       = rms16( pcm, len );
        final int threshold = Math.max( MIN_THRESHOLD, (int)( ambientRms * SPEECH_MULT ) );

        // Relative silence: if RMS drops well below the utterance peak, treat it as
        // silence even when still above the global threshold. A constant noise floor
        // (fan, AC) raises the absolute floor — the drop back to that floor after
        // speech is the actual silence signal.  Guard requires the peak to be clearly
        // above threshold so a fan just barely over the threshold doesn't trigger this.
        final boolean relSilent = inSpeech
            && inSpeechMaxRms > (int)( threshold * 1.2f )
            && rms < (int)( inSpeechMaxRms * RELATIVE_SILENCE_FRAC );

        if( rms >= threshold && !relSilent )
        {
            if( !inSpeech )
            {
                inSpeech       = true;
                cmdBuf         = new byte[ MAX_CMD_BYTES ];
                cmdLen         = 0;
                silenceStartMs = 0;
                hadSilence     = false;
                inSpeechMaxRms = rms;
                segMinRms      = rms;
                segMaxRms      = rms;
            }
            if( rms > inSpeechMaxRms ) inSpeechMaxRms = rms;
            if( rms < segMinRms )      segMinRms      = rms;
            if( rms > segMaxRms )      segMaxRms      = rms;
            silenceStartMs = 0;
            appendCmd( pcm, len );
        }
        else
        {
            // Freeze ambient tracking when system audio is audible. Speaker echo
            // on the mic line would otherwise inflate ambientRms over time and
            // push the speech-onset threshold too high to detect the user's voice.
            if( !inSpeech && AudioTranscriptBuffer.currentSysRms <= AMBIENT_SYS_FREEZE )
                ambientRms = ambientRms * ( 1f - AMBIENT_ALPHA ) + rms * AMBIENT_ALPHA;

            if( inSpeech )
            {
                if( rms < segMinRms ) segMinRms = rms;
                appendCmd( pcm, len );
                if( silenceStartMs == 0 )
                {
                    silenceStartMs = System.currentTimeMillis();
                    hadSilence     = true;
                }
                if( System.currentTimeMillis() - silenceStartMs >= SILENCE_HOLD_MS )
                    flushCmd();
            }
        }
    }

    private void appendCmd( final byte[] pcm, final int len )
    {
        if( cmdBuf == null ) return;
        // Use a tighter cap while speakers are active — the echo keeps inSpeech=true
        // permanently, so the cap is the only flush trigger. A 3 s window is small
        // enough that the user's name stands out against the background.
        final int capBytes = AudioTranscriptBuffer.currentSysRms > AUDIO_PLAYING_SYS_RMS
            ? MAX_CMD_BYTES_AUDIO : MAX_CMD_BYTES;
        if( cmdLen >= capBytes ) { flushCmd(); return; }
        final int copy = Math.min( len, capBytes - cmdLen );
        System.arraycopy( pcm, 0, cmdBuf, cmdLen, copy );
        cmdLen += copy;
        if( cmdLen >= capBytes ) flushCmd();
    }

    private void flushCmd()
    {
        if( cmdBuf == null ) return;
        final byte[] segment = java.util.Arrays.copyOf( cmdBuf, cmdLen );
        final boolean noSilence = !hadSilence;
        final int     snapMin   = segMinRms;
        final int     snapMax   = segMaxRms;
        cmdBuf         = null;
        cmdLen         = 0;
        inSpeech       = false;
        silenceStartMs = 0;
        hadSilence     = false;
        inSpeechMaxRms = 0;
        segMinRms      = Integer.MAX_VALUE;
        segMaxRms      = 0;

        if( segment.length < MIN_SPEECH_BYTES ) return;

        // Full-cap flush with no silence = potentially sustained background noise.
        // If energy was flat (low max/min variance ratio) it is pure noise — discard
        // and update ambient so the threshold rises above the noise floor next time.
        // If energy varied significantly (speech mixed with noise), send to Whisper.
        if( noSilence && segment.length >= MAX_CMD_BYTES - 8192
                && AudioTranscriptBuffer.currentSysRms <= AMBIENT_SYS_FREEZE )
        {
            final boolean hasVariance = snapMin > 0
                && (float) snapMax / snapMin >= NOISE_VARIANCE_RATIO;
            if( !hasVariance )
            {
                final int segRms = rms16( segment, segment.length );
                ambientRms = ambientRms * 0.5f + segRms * 0.5f;
                log.warning( "[Voice] Sustained noise — ambient=" + (int)ambientRms
                    + " threshold=" + (int)( ambientRms * SPEECH_MULT ) );
                return;
            }
            log.warning( "[Voice] Noisy cap-flush but has speech variance (max/min="
                + snapMax + "/" + snapMin + ") — sending to Whisper." );
        }

        log.warning( "[Voice] Endpoint flush: " + segment.length + " bytes, ambient="
            + (int)ambientRms + " threshold=" + Math.max( MIN_THRESHOLD, (int)( ambientRms * SPEECH_MULT ) ) );

        final ScheduledExecutorService exec = pollExecutor;
        if( exec == null || exec.isShutdown() ) return;
        try
        {
            exec.submit( () -> pollEndpoint( segment ) );
        }
        catch( java.util.concurrent.RejectedExecutionException ignored ) {}
    }

    private void pollEndpoint( final byte[] segment )
    {
        if( callbacks.isEmpty() ) return;

        final String transcript = transcribe( segment );
        if( transcript == null || transcript.isBlank() ) return;

        log.warning( "[Voice] Endpoint heard: " + transcript );

        final String sysHint  = AudioTranscriptBuffer.lastSysTranscript;
        final String stripped = ( sysHint != null )
            ? AudioTranscriptBuffer.stripOverlap( sysHint, transcript ) : null;
        final String toCheck  = ( stripped != null && !stripped.isBlank() )
            ? stripped : transcript;

        checkForCommands( toCheck, sysHint );
    }

    private static int rms16( final byte[] pcm, final int len )
    {
        if( len < 2 ) return 0;
        long sum    = 0;
        final int frames = len / 2;
        for( int i = 0; i < frames; i++ )
        {
            final short s = (short)( ( pcm[ i * 2 ] & 0xFF ) | ( pcm[ i * 2 + 1 ] << 8 ) );
            sum += (long) s * s;
        }
        return (int) Math.sqrt( (double) sum / frames );
    }

    private static byte[] downmixResample16( final byte[] src, final int len,
                                              final int srcCh, final float ratio )
    {
        final int srcFrames = len / ( srcCh * 2 );
        if( srcFrames == 0 ) return new byte[0];
        final short[] mono = new short[ srcFrames ];
        for( int i = 0; i < srcFrames; i++ )
        {
            long sum = 0;
            for( int c = 0; c < srcCh; c++ )
            {
                final int off = ( i * srcCh + c ) * 2;
                sum += (short) ( ( src[off] & 0xFF ) | ( src[off + 1] << 8 ) );
            }
            mono[i] = (short) ( sum / srcCh );
        }
        final int dstFrames = Math.max( 1, (int) ( srcFrames / ratio ) );
        final byte[] dst = new byte[ dstFrames * 2 ];
        for( int i = 0; i < dstFrames; i++ )
        {
            final float pos  = i * ratio;
            final int   idx  = (int) pos;
            final float frac = pos - idx;
            final short s0   = mono[ Math.min( idx,     mono.length - 1 ) ];
            final short s1   = mono[ Math.min( idx + 1, mono.length - 1 ) ];
            final short out  = (short) ( s0 + (int) ( ( s1 - s0 ) * frac ) );
            dst[i * 2]     = (byte) (  out        & 0xFF );
            dst[i * 2 + 1] = (byte) ( (out >> 8)  & 0xFF );
        }
        return dst;
    }

    private void poll()
    {
        if( callbacks.isEmpty() ) return;

        final byte[] snapshot = snapshot();
        if( snapshot == null || snapshot.length < SAMPLE_RATE * 2 )
        {
            log.warning( "[Voice] Buffer too short to transcribe: "
                + ( snapshot == null ? "null" : snapshot.length + " bytes" ) );
            return;
        }

        final String transcript = transcribe( snapshot );
        if( transcript == null || transcript.isBlank() ) return;
        if( transcript.equals( lastTranscript ) ) return;
        lastTranscript = transcript;

        log.warning( "[Voice] Heard: " + transcript );

        // Strip system audio words from mic transcript before name checking.
        // Prevents video/speaker bleed from drowning out or polluting the name trigger.
        final String sysHint  = AudioTranscriptBuffer.lastSysTranscript;
        final String stripped = ( sysHint != null )
            ? AudioTranscriptBuffer.stripOverlap( sysHint, transcript )
            : transcript;
        // Fall back to full transcript if stripping removed too much — the name is
        // still likely present even in a mixed transcript.
        final String toCheck  = ( stripped != null && !stripped.isBlank() )
            ? stripped : transcript;

        log.warning( "[Voice] After strip: " + toCheck );
        checkForCommands( toCheck, sysHint );
    }

    private void checkForCommands( final String transcript, final String sysHint )
    {
        final String lower    = transcript.toLowerCase();
        final String sysLower = ( sysHint != null ) ? sysHint.toLowerCase() : "";

        for( final Map.Entry<String, java.util.function.Consumer<String>> entry : callbacks.entrySet() )
        {
            final String name = entry.getKey(); // already lowercase
            if( !lower.contains( name ) ) continue;

            // Name also appears in system audio — almost certainly echo, not a user call.
            if( !sysLower.isEmpty() && sysLower.contains( name ) ) continue;

            // Cooldown check
            final long now = System.currentTimeMillis();
            final Long last = lastCommandTime.get( name );
            if( last != null && now - last < COOLDOWN_MS ) continue;
            lastCommandTime.put( name, now );

            log.warning( "[Voice] Name trigger: " + name );
            entry.getValue().accept( transcript );
            // Only dispatch to the first matching name per transcript
            break;
        }
    }

    private byte[] snapshot()
    {
        synchronized( ring )
        {
            if( filled == 0 ) return null;
            final byte[] out = new byte[ filled ];
            if( filled < BUFFER_BYTES )
            {
                System.arraycopy( ring, 0, out, 0, filled );
            }
            else
            {
                final int tail = BUFFER_BYTES - writePos;
                System.arraycopy( ring, writePos, out, 0,    tail );
                System.arraycopy( ring, 0,         out, tail, writePos );
            }
            return out;
        }
    }

    private String transcribe( final byte[] pcm )
    {
        File wav    = null;
        File sysWav = null;
        try
        {
            wav = File.createTempFile( "shimeji_voice_", ".wav" );
            wav.deleteOnExit();
            writeWav( pcm, wav );

            // If system audio is being captured, pass it as an echo reference.
            // whisper_server.py uses it for non-stationary noise reduction to
            // strip speaker bleed from the mic signal before Whisper runs.
            final byte[] sysSnap = AudioTranscriptBuffer.staticSysSnapshot();
            if( sysSnap != null && sysSnap.length > 0 )
            {
                sysWav = File.createTempFile( "shimeji_sysref_", ".wav" );
                sysWav.deleteOnExit();
                writeWav( sysSnap, sysWav );
            }

            return WhisperProcess.getInstance().transcribe( wav, sysWav );
        }
        catch( IOException e )
        {
            log.log( Level.WARNING, "[Voice] Transcription error", e );
            return null;
        }
        finally
        {
            if( wav    != null ) wav.delete();
            if( sysWav != null ) sysWav.delete();
        }
    }

    private static final String[] LOOPBACK_FRAGS = {
        "cable", "vb-audio", "virtual", "loopback", "wave out", "stereo mix", "what u hear"
    };

    private static boolean isLoopback( final String name )
    {
        final String lower = name.toLowerCase();
        for( final String f : LOOPBACK_FRAGS )
            if( lower.contains( f ) ) return true;
        return false;
    }

    private static TargetDataLine findMicLine()
    {
        TargetDataLine preferred = null;
        TargetDataLine fallback  = null;

        for( final Mixer.Info mi : AudioSystem.getMixerInfo() )
        {
            final String name = mi.getName().toLowerCase();
            if( name.contains( "java sound" ) ) continue;
            if( isLoopback( name ) ) continue;

            try
            {
                final Mixer mixer = AudioSystem.getMixer( mi );
                for( final Line.Info info : mixer.getTargetLineInfo() )
                {
                    if( !TargetDataLine.class.isAssignableFrom( info.getLineClass() ) ) continue;
                    try
                    {
                        final TargetDataLine tdl = (TargetDataLine) mixer.getLine( info );
                        if( name.contains( "microphone" ) || name.contains( "mic" ) )
                        {
                            if( preferred == null )
                            {
                                log.info( "[Voice] Preferred mic device: " + mi.getName() );
                                preferred = tdl;
                            }
                        }
                        else if( fallback == null )
                        {
                            log.info( "[Voice] Candidate mic device: " + mi.getName() );
                            fallback = tdl;
                        }
                    }
                    catch( Exception ignored ) {}
                }
            }
            catch( Exception ignored ) {}
        }

        if( preferred == null && fallback == null )
            log.warning( "[Voice] No mic device found in hardware mixer scan." );
        return preferred != null ? preferred : fallback;
    }

    private static TargetDataLine tryOpenLine( final Mixer.Info mixerInfo )
    {
        try
        {
            final Mixer mixer = AudioSystem.getMixer( mixerInfo );
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

    private static void writeWav( final byte[] pcm, final File dest ) throws IOException
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

    private static void writeInt32LE( final DataOutputStream d, final int v ) throws IOException
    { d.write(v&0xFF); d.write((v>>8)&0xFF); d.write((v>>16)&0xFF); d.write((v>>24)&0xFF); }

    private static void writeInt16LE( final DataOutputStream d, final int v ) throws IOException
    { d.write(v&0xFF); d.write((v>>8)&0xFF); }

}
