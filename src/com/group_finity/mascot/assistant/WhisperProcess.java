package com.group_finity.mascot.assistant;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a single persistent whisper_server.py subprocess.
 * Shared singleton — both AudioTranscriptBuffer and VoiceCommandListener use it.
 *
 * The server loads the model once on startup, then accepts WAV file paths
 * via stdin and returns transcripts via stdout — one line per request.
 *
 * Thread-safe: a ReentrantLock serializes concurrent transcription requests.
 */
public class WhisperProcess
{
    private static final Logger log = Logger.getLogger( WhisperProcess.class.getName() );

    private static final String  SCRIPT     = "whisper_server.py";
    private static final long    READY_TIMEOUT_MS = 60_000; // model load can take a while
    private static final long    TRANSCRIBE_TIMEOUT_MS = 30_000;

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static WhisperProcess instance = null;
    private static final Object INSTANCE_LOCK = new Object();

    public static WhisperProcess getInstance()
    {
        synchronized( INSTANCE_LOCK )
        {
            if( instance == null ) instance = new WhisperProcess();
            return instance;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private Process         proc          = null;
    private BufferedWriter  stdin         = null;
    private BufferedReader  stdout        = null;
    private Thread          stderrDrainer = null;
    private boolean         ready         = false;

    private final ReentrantLock   lock     = new ReentrantLock();
    /** Single reusable thread for stdout reads — avoids spawning a thread per transcription. */
    private final ExecutorService reader   = Executors.newSingleThreadExecutor( r ->
    {
        final Thread t = new Thread( r, "whisper-read" );
        t.setDaemon( true );
        return t;
    });

    private WhisperProcess() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ensure the server process is running. Blocks until "READY" is received.
     * Safe to call multiple times.
     */
    public synchronized boolean ensureStarted()
    {
        if( ready && proc != null && proc.isAlive() ) return true;

        // Find whisper_server.py relative to the jar
        final File script = findScript();
        if( script == null )
        {
            log.severe( "[Whisper] Cannot find " + SCRIPT + " — place it next to ShimejiEE.jar" );
            return false;
        }

        try
        {
            log.info( "[Whisper] Starting server: " + script.getAbsolutePath() );
            final ProcessBuilder pb = new ProcessBuilder(
                "python", script.getAbsolutePath(), getModel(), getThreads() );
            pb.redirectErrorStream( false );
            pb.environment().put( "PYTHONIOENCODING", "utf-8" );
            pb.environment().put( "PYTHONUNBUFFERED", "1" );

            proc = pb.start();

            // Whisper transcription saturates its threads — run it BELOW_NORMAL
            // so it yields to the tick loop (and everything else interactive).
            com.group_finity.mascot.environment.ProcessPriorityUtil
                .setBelowNormal( proc.pid() );

            stdin  = new BufferedWriter(
                new OutputStreamWriter( proc.getOutputStream(),
                    java.nio.charset.StandardCharsets.UTF_8 ) );
            stdout = new BufferedReader(
                new InputStreamReader( proc.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8 ) );

            // Drain stderr to log
            stderrDrainer = new Thread( () ->
            {
                try( BufferedReader err = new BufferedReader(
                        new InputStreamReader( proc.getErrorStream(),
                            java.nio.charset.StandardCharsets.UTF_8 ) ) )
                {
                    String line;
                    while( ( line = err.readLine() ) != null )
                        log.info( "[Whisper/py] " + line );
                }
                catch( IOException ignored ) {}
            }, "whisper-stderr" );
            stderrDrainer.setDaemon( true );
            stderrDrainer.start();

            // Wait for READY
            final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
            while( System.currentTimeMillis() < deadline )
            {
                final String line = stdout.readLine();
                if( line == null ) break;
                if( line.trim().equals( "READY" ) )
                {
                    ready = true;
                    log.info( "[Whisper] Server ready." );
                    return true;
                }
            }

            log.severe( "[Whisper] Server did not send READY in time." );
            stop();
            return false;
        }
        catch( IOException e )
        {
            log.log( Level.SEVERE, "[Whisper] Failed to start server", e );
            return false;
        }
    }

    /**
     * Transcribe a WAV file. Blocks until the result is returned.
     * Returns null on failure or empty transcript.
     */
    public String transcribe( final File wav ) { return transcribe( wav, null ); }

    /**
     * Transcribe a WAV file with an optional reference audio file for echo
     * suppression. When sysRef is non-null the server receives
     * "mic_path\tref_path" and applies non-stationary noise reduction using
     * the reference as the echo profile before calling Whisper.
     */
    public String transcribe( final File wav, final File sysRef )
    {
        if( !ensureStarted() ) return null;

        lock.lock();
        try
        {
            // Check process is still alive
            if( proc == null || !proc.isAlive() )
            {
                ready = false;
                if( !ensureStarted() ) return null;
            }

            if( sysRef != null )
                stdin.write( wav.getAbsolutePath() + "\t" + sysRef.getAbsolutePath() );
            else
                stdin.write( wav.getAbsolutePath() );
            stdin.newLine();
            stdin.flush();

            // Read response with timeout via the reusable reader thread
            final Future<String> future = reader.submit( () ->
            {
                try { return stdout.readLine(); }
                catch( IOException e ) { return null; }
            });
            final String text;
            try
            {
                text = future.get( TRANSCRIBE_TIMEOUT_MS, TimeUnit.MILLISECONDS );
            }
            catch( java.util.concurrent.TimeoutException e )
            {
                future.cancel( true );
                log.warning( "[Whisper] Transcription timed out." );
                return null;
            }
            catch( Exception e )
            {
                log.log( Level.WARNING, "[Whisper] Read error", e );
                return null;
            }
            return ( text == null || text.isBlank() ) ? null : text.trim();
        }
        catch( Exception e )
        {
            log.log( Level.WARNING, "[Whisper] Transcription error", e );
            ready = false;
            return null;
        }
        finally
        {
            lock.unlock();
        }
    }

    public synchronized void stop()
    {
        ready = false;
        try
        {
            if( stdin != null ) { stdin.write( "QUIT\n" ); stdin.flush(); }
        }
        catch( IOException ignored ) {}
        if( proc != null ) { proc.destroyForcibly(); proc = null; }
        stdin  = null;
        stdout = null;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static String getModel()
    {
        try
        {
            final java.util.Properties props =
                com.group_finity.mascot.Main.getInstance().getProperties();
            return props.getProperty( "WhisperModel", "tiny" );
        }
        catch( final Exception e ) { return "tiny"; }
    }

    private static String getThreads()
    {
        try
        {
            final java.util.Properties props =
                com.group_finity.mascot.Main.getInstance().getProperties();
            final int t = Math.max( 1, Integer.parseInt(
                props.getProperty( "WhisperThreads", "1" ) ) );
            return String.valueOf( t );
        }
        catch( final Exception e ) { return "1"; }
    }

    private static File findScript()
    {
        // 1. Same directory as the running jar
        try
        {
            final File jarDir = new File(
                WhisperProcess.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI() ).getParentFile();
            final File f = new File( jarDir, SCRIPT );
            if( f.exists() ) return f;
        }
        catch( Exception ignored ) {}

        // 2. Working directory
        final File cwd = new File( System.getProperty( "user.dir" ), SCRIPT );
        if( cwd.exists() ) return cwd;

        return null;
    }
}
