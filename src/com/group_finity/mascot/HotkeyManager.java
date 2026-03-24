package com.group_finity.mascot;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Global hotkey manager for Shimeji.
 *
 * Reads hotkey-to-behavior mappings from conf/hotkeys.properties and fires
 * manager.setBehaviorAll() for every active mascot when a bound key is pressed.
 *
 * --- conf/hotkeys.properties format ---
 *
 * Each line is:   <combo>=<BehaviorName>
 *
 * Key combo syntax (case-insensitive, tokens joined by +):
 *   Modifiers : ctrl, shift, alt, meta
 *   Keys      : any NativeKeyEvent.VC_* name with the VC_ stripped, e.g.
 *               F1, F2, A, B, SPACE, ENTER, HOME, etc.
 *   Mouse     : MOUSE1, MOUSE2, MOUSE3, MOUSE4, MOUSE5
 *
 * Examples:
 *   ctrl+shift+j=JumpToCursor
 *   F9=ChaseMouse
 *   ctrl+alt+d=Dismissed
 *   MOUSE4=JumpToCursor
 *
 * Lines starting with # are comments. Blank lines are ignored.
 * The behavior name must match a behavior defined in the mascot's behaviors.xml.
 * If a mascot's image set does not define the named behavior it is silently
 * skipped for that mascot (no crash).
 * --------------------------------------
 */
public class HotkeyManager implements NativeKeyListener, NativeMouseListener
{
    private static final Logger log = Logger.getLogger( HotkeyManager.class.getName( ) );

    private static final String HOTKEYS_FILE = "conf/hotkeys.properties";
    private static final java.nio.file.Path HOTKEYS_PATH =
        java.nio.file.Paths.get( ".", "conf", "hotkeys.properties" );

    private static HotkeyManager instance;

    public static synchronized HotkeyManager getInstance( )
    {
        if( instance == null )
            instance = new HotkeyManager( );
        return instance;
    }

    // Maps normalised combo string -> behavior name
    private final Map<String, String> bindings = new LinkedHashMap<>( );

    private Manager manager;
    private boolean hooked = false;

    private HotkeyManager( ) { }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Call once after the Manager is started.
     * Loads conf/hotkeys.properties and registers the OS-level hook.
     * Safe to call even if the file doesn't exist (just does nothing).
     */
    public void init( final Manager manager )
    {
        this.manager = manager;
        loadBindings( );

        if( bindings.isEmpty( ) )
        {
            log.log( Level.INFO, "HotkeyManager: no bindings found, skipping hook registration." );
            return;
        }

        // Silence JNativeHook's very noisy logger
        Logger jnhLog = Logger.getLogger( GlobalScreen.class.getPackage( ).getName( ) );
        jnhLog.setLevel( Level.WARNING );
        jnhLog.setUseParentHandlers( false );

        try
        {
            GlobalScreen.registerNativeHook( );
            GlobalScreen.addNativeKeyListener( this );
            GlobalScreen.addNativeMouseListener( this );
            hooked = true;
            log.log( Level.INFO, "HotkeyManager: global hook registered with {0} binding(s).", bindings.size( ) );
        }
        catch( NativeHookException e )
        {
            log.log( Level.WARNING, "HotkeyManager: could not register global hook. Hotkeys will not work.", e );
        }
    }

    /**
     * Call during application shutdown so JNativeHook cleans up its thread.
     */
    public void shutdown( )
    {
        if( hooked )
        {
            try
            {
                GlobalScreen.removeNativeKeyListener( this );
                GlobalScreen.removeNativeMouseListener( this );
                // Replace the event dispatcher with one that shuts down
                // immediately — this prevents JNativeHook's thread from
                // blocking System.exit() after unregisterNativeHook().
                GlobalScreen.setEventDispatcher( new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>( 1 ),
                    r -> {
                        Thread t = new Thread( r, "JNHShutdown" );
                        t.setDaemon( true );
                        return t;
                    } ) );
                GlobalScreen.unregisterNativeHook( );
            }
            catch( NativeHookException e )
            {
                log.log( Level.WARNING, "HotkeyManager: error unregistering hook.", e );
            }
            hooked = false;
        }
    }

    // -------------------------------------------------------------------------
    // NativeKeyListener
    // -------------------------------------------------------------------------

    @Override
    public void nativeKeyPressed( NativeKeyEvent e )
    {
        String combo = buildKeyCombo( e );
        String behavior = bindings.get( combo );
        if( behavior != null )
            fireBehavior( behavior );
    }

    @Override public void nativeKeyReleased( NativeKeyEvent e ) { }
    @Override public void nativeKeyTyped( NativeKeyEvent e )    { }

    // -------------------------------------------------------------------------
    // NativeMouseListener
    // -------------------------------------------------------------------------

    @Override
    public void nativeMousePressed( NativeMouseEvent e )
    {
        String combo = buildMouseCombo( e );
        String behavior = bindings.get( combo );
        if( behavior != null )
            fireBehavior( behavior );
    }

    @Override public void nativeMouseReleased( NativeMouseEvent e ) { }
    @Override public void nativeMouseClicked( NativeMouseEvent e )  { }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void fireBehavior( final String behaviorName )
    {
        if( manager == null )
            return;

        for( String entry : behaviorName.split( "," ) )
        {
            entry = entry.trim( );
            if( entry.isEmpty( ) ) continue;

            if( entry.contains( ":" ) )
            {
                // Format is ImageSet:BehaviorName — target only that image set
                String[] parts = entry.split( ":", 2 );
                String imageSet = parts[0].trim( );
                String behavior = parts[1].trim( );
                manager.setBehaviorAllSafe( imageSet, behavior );
            }
            else
            {
                // No prefix — fire for all mascots
                manager.setBehaviorAllSafe( entry );
            }
        }
    }

    /**
     * Builds a normalised combo key from a key event, e.g. "ctrl+shift+f9".
     */
    private String buildKeyCombo( NativeKeyEvent e )
    {
        StringBuilder sb = new StringBuilder( );
        int mod = e.getModifiers( );

        if( ( mod & NativeKeyEvent.CTRL_MASK  ) != 0 ) sb.append( "ctrl+"  );
        if( ( mod & NativeKeyEvent.SHIFT_MASK ) != 0 ) sb.append( "shift+" );
        if( ( mod & NativeKeyEvent.ALT_MASK   ) != 0 ) sb.append( "alt+"   );
        if( ( mod & NativeKeyEvent.META_MASK  ) != 0 ) sb.append( "meta+"  );

        // getKeyText gives human-readable names like "F9", "A", "Space"
        sb.append( NativeKeyEvent.getKeyText( e.getKeyCode( ) ).toLowerCase( ).replace( " ", "" ) );

        return sb.toString( );
    }

    /**
     * Builds a normalised combo key from a mouse button event, e.g. "mouse4".
     * Modifier keys held during mouse press are included, e.g. "ctrl+mouse4".
     */
    private String buildMouseCombo( NativeMouseEvent e )
    {
        StringBuilder sb = new StringBuilder( );
        int mod = e.getModifiers( );

        if( ( mod & NativeMouseEvent.CTRL_MASK  ) != 0 ) sb.append( "ctrl+"  );
        if( ( mod & NativeMouseEvent.SHIFT_MASK ) != 0 ) sb.append( "shift+" );
        if( ( mod & NativeMouseEvent.ALT_MASK   ) != 0 ) sb.append( "alt+"   );
        if( ( mod & NativeMouseEvent.META_MASK  ) != 0 ) sb.append( "meta+"  );

        sb.append( "mouse" ).append( e.getButton( ) );
        return sb.toString( );
    }

    private void loadBindings( )
    {
        bindings.clear( );
        File file = HOTKEYS_PATH.toAbsolutePath( ).toFile( );
        log.log( Level.INFO, "HotkeyManager: looking for hotkeys file at {0}", file.getAbsolutePath( ) );
        if( !file.exists( ) )
        {
            log.log( Level.INFO, "HotkeyManager: {0} not found, hotkeys disabled.", file.getAbsolutePath( ) );
            return;
        }

        try( java.io.BufferedReader reader = new java.io.BufferedReader( new java.io.FileReader( file ) ) )
        {
            String line;
            while( ( line = reader.readLine( ) ) != null )
            {
                line = line.trim( );
                if( line.isEmpty( ) || line.startsWith( "#" ) ) continue;

                int eq = line.indexOf( '=' );
                if( eq < 0 ) continue;

                String rawKey = line.substring( 0, eq ).trim( );
                String rawVal = line.substring( eq + 1 ).trim( );
                if( rawKey.isEmpty( ) || rawVal.isEmpty( ) ) continue;

                String normKey = rawKey.toLowerCase( ).replaceAll( "\\s*\\+\\s*", "+" );

                // If the key already exists, append with comma
                if( bindings.containsKey( normKey ) )
                    bindings.put( normKey, bindings.get( normKey ) + "," + rawVal );
                else
                    bindings.put( normKey, rawVal );

                log.log( Level.INFO, "HotkeyManager: bound [{0}] -> {1}", new Object[]{ normKey, bindings.get( normKey ) } );
            }
        }
        catch( IOException e )
        {
            log.log( Level.WARNING, "HotkeyManager: failed to read " + HOTKEYS_FILE, e );
        }
    }
}
