package com.group_finity.mascot;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Global hotkey manager for Shimeji.
 *
 * Reads hotkey-to-behavior mappings from conf/hotkeys.properties and fires
 * manager.setBehaviorAllSafe() for every active mascot when a bound key is pressed.
 *
 * HOLD-TO-LOOP:
 * When a key is held down the OS fires repeated nativeKeyPressed events which
 * would snap the mascot back to frame 1 of its animation on every repeat.
 * Instead, HotkeyManager tracks which combos are currently held and suppresses
 * the OS repeats. Manager.tick() calls tickHeldKeys() each tick, which calls
 * manager.reFireBehaviorIfFinished() — that only re-fires the behavior when
 * the current action has fully completed, so the animation plays through
 * cleanly to the end and then loops from the start. Release the key and the
 * mascot falls back to normal behavior on its next natural completion.
 *
 * --- conf/hotkeys.properties format ---
 *
 * Each line is:  <combo>=<BehaviorName>
 *
 * Key combo syntax (case-insensitive, tokens joined by +):
 *   Modifiers : ctrl, shift, alt, meta
 *   Keys      : any NativeKeyEvent.VC_* name with VC_ stripped, e.g.
 *               F1, F2, A, B, SPACE, ENTER, HOME, etc.
 *   Mouse     : MOUSE1, MOUSE2, MOUSE3, MOUSE4, MOUSE5
 *
 * To mark a binding as hold-to-loop, suffix the behavior name with !hold:
 *   RIGHT=Mario:MoveRight!hold
 *   LEFT=Mario:MoveLeft!hold
 *
 * Without !hold, the key fires once on press (original behaviour).
 * With !hold, the behavior loops for as long as the key is held.
 *
 * Lines starting with # are comments. Blank lines are ignored.
 * Duplicate key lines are merged: later lines are appended with comma.
 */

public class HotkeyManager implements NativeKeyListener, NativeMouseListener
{
    // This tracks keys that have already triggered a behavior so they don't "stutter"
    private final Set<Integer> toggledKeys = new HashSet<>();
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

    // ── Binding data ──────────────────────────────────────────────────────────

    /** Parsed entry: behavior name + whether it is marked !hold */
    private static class Binding {
        final String  behaviorEntry; // e.g. "Mario:MoveRight" or "MoveRight"
        final boolean hold;          // true → loop while held; false → fire once on press
        Binding( String entry, boolean hold ) {
            this.behaviorEntry = entry;
            this.hold          = hold;
        }
    }

    // combo → list of bindings (comma-joined in file maps to multiple entries)
    private final Map<String, java.util.List<Binding>> bindings = new LinkedHashMap<>( );

    // ── Hold state ────────────────────────────────────────────────────────────

    /**
     * Combos currently held by the user AND marked !hold.
     * Written from JNativeHook's thread; read from Manager's tick thread.
     * ConcurrentHashMap for thread-safe access without blocking either side.
     */
    private final Set<String> heldCombos = ConcurrentHashMap.newKeySet( );

    private Manager manager;
    private boolean hooked = false;

    private HotkeyManager( ) { }

    // ── Public API ────────────────────────────────────────────────────────────

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

    public void shutdown( )
    {
        if( hooked )
        {
            try
            {
                GlobalScreen.removeNativeKeyListener( this );
                GlobalScreen.removeNativeMouseListener( this );
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
        heldCombos.clear( );
    }

    /**
     * Returns the held !hold behavior name for the given imageSet, or null if none held.
     * Called per-mascot from Manager.tick() after mascot.tick() to detect when the held
     * behavior has completed and needs to be restarted.
     *
     * If multiple held combos match (unlikely), returns the first one found.
     * imageSet may be null to match bindings with no imageSet prefix.
     */
    public String getHeldBehaviorFor( final String imageSet )
    {
        if( heldCombos.isEmpty( ) ) return null;
        for( String combo : heldCombos )
        {
            java.util.List<Binding> list = bindings.get( combo );
            if( list == null ) continue;
            for( Binding b : list )
            {
                if( !b.hold ) continue;
                // Parse the behavior entry to check imageSet match
                for( String part : b.behaviorEntry.split( "," ) )
                {
                    part = part.trim( );
                    if( part.isEmpty( ) ) continue;
                    if( part.contains( ":" ) )
                    {
                        String[] kv = part.split( ":", 2 );
                        String   is = kv[0].trim( );
                        String   bv = kv[1].trim( );
                        if( is.equals( imageSet ) ) return bv;
                    }
                    else
                    {
                        // No imageSet prefix — applies to all
                        return part;
                    }
                }
            }
        }
        return null;
    }

    // ── NativeKeyListener ─────────────────────────────────────────────────────

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int keyCode = e.getKeyCode();

        // 1. THE BOUNCER: If the key is already pressed, stop and do nothing!
        if (toggledKeys.contains(keyCode)) {
            return; 
        }

        // 2. THE NOTE: Record that the key is now pressed
        toggledKeys.add(keyCode);

        // 3. YOUR ORIGINAL CODE (Now safely inside the method):
        String combo = buildKeyCombo( e );
        java.util.List<Binding> list = bindings.get( combo );
        if( list == null ) return;

        for( Binding b : list )
        {
            if( b.hold )
            {
                // Register as held — suppress OS repeats entirely.
                heldCombos.add( combo );
                // Fire immediately on first press
                if( manager != null )
                    fireBindingEntry( b.behaviorEntry, manager, false );
            }
            else
            {
                // One-shot fire
                if( manager != null )
                    fireBindingEntry( b.behaviorEntry, manager, false );
            }
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        // 1. Clear the "Bouncer" clipboard so the key can be pressed again later
        toggledKeys.remove(e.getKeyCode());

        // 2. Your existing logic to stop the looping
        heldCombos.remove( buildKeyCombo( e ) );
    }
    

    @Override public void nativeKeyTyped( NativeKeyEvent e ) { }

    // ── NativeMouseListener ───────────────────────────────────────────────────

    @Override
    public void nativeMousePressed( NativeMouseEvent e )
    {
        String combo = buildMouseCombo( e );
        java.util.List<Binding> list = bindings.get( combo );
        if( list == null ) return;
        for( Binding b : list )
        {
            if( b.hold ) heldCombos.add( combo );
            if( manager != null ) fireBindingEntry( b.behaviorEntry, manager, false );
        }
    }

    @Override
    public void nativeMouseReleased( NativeMouseEvent e )
    {
        heldCombos.remove( buildMouseCombo( e ) );
    }

    @Override public void nativeMouseClicked( NativeMouseEvent e ) { }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Fire a single behavior entry string (may be "ImageSet:Behavior" or just "Behavior").
     * @param reFireMode if true, calls reFireBehaviorIfFinished (for hold-loop ticks)
     *                   instead of setBehaviorAllSafe (which always interrupts).
     */
    private void fireBindingEntry( final String entry, final Manager mgr, final boolean reFireMode )
    {
        for( String part : entry.split( "," ) )
        {
            part = part.trim( );
            if( part.isEmpty( ) ) continue;

            if( part.contains( ":" ) )
            {
                String[] kv      = part.split( ":", 2 );
                String imageSet  = kv[0].trim( );
                String behavior  = kv[1].trim( );
                if( reFireMode )
                    mgr.reFireBehaviorIfFinished( imageSet, behavior );
                else
                {
                    applyJumpSteerIfDirectional( behavior, imageSet, mgr );
                    mgr.setBehaviorAllSafe( imageSet, behavior );
                }
            }
            else
            {
                if( reFireMode )
                    mgr.reFireBehaviorIfFinished( null, part );
                else
                {
                    applyJumpSteerIfDirectional( part, null, mgr );
                    mgr.setBehaviorAllSafe( part );
                }
            }
        }
    }

    /**
     * If the behavior name contains "Left" or "Right", nudge the Jump targetX
     * of any mascot (matching imageSet if non-null) that is currently mid-jump.
     * Uses ±8 px (pre-scale) matching the MoveLeft/MoveRight fall-nudge value.
     * The mascot's Jump.getTargetX() will consume and apply the offset next tick.
     */
    private void applyJumpSteerIfDirectional( final String behaviorName,
                                              final String imageSet,
                                              final Manager mgr )
    {
        String lower = behaviorName.toLowerCase( );
        int dx;
        if(      lower.contains( "left"  ) ) dx = -8;
        else if( lower.contains( "right" ) ) dx =  8;
        else return;

        for( com.group_finity.mascot.Mascot mascot : mgr.getMascotList( ) )
        {
            if( imageSet != null && !mascot.getImageSet( ).equals( imageSet ) ) continue;
            // Only steer if the mascot is currently executing a Jump action
            com.group_finity.mascot.behavior.Behavior beh = mascot.getBehavior( );
            if( !( beh instanceof com.group_finity.mascot.behavior.UserBehavior ) ) continue;
            com.group_finity.mascot.action.Action action =
                ( (com.group_finity.mascot.behavior.UserBehavior) beh ).getAction( );
            if( action instanceof com.group_finity.mascot.action.Jump )
                mascot.addJumpTargetXOffset( dx );
        }
    }

    private String buildKeyCombo( NativeKeyEvent e )
    {
        StringBuilder sb = new StringBuilder( );
        int mod = e.getModifiers( );
        if( ( mod & NativeKeyEvent.CTRL_MASK  ) != 0 ) sb.append( "ctrl+"  );
        if( ( mod & NativeKeyEvent.SHIFT_MASK ) != 0 ) sb.append( "shift+" );
        if( ( mod & NativeKeyEvent.ALT_MASK   ) != 0 ) sb.append( "alt+"   );
        if( ( mod & NativeKeyEvent.META_MASK  ) != 0 ) sb.append( "meta+"  );
        sb.append( NativeKeyEvent.getKeyText( e.getKeyCode( ) ).toLowerCase( ).replace( " ", "" ) );
        return sb.toString( );
    }

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

                // Parse !hold suffix
                boolean hold = false;
                if( rawVal.endsWith( "!hold" ) )
                {
                    hold   = true;
                    rawVal = rawVal.substring( 0, rawVal.length( ) - "!hold".length( ) ).trim( );
                }

                Binding binding = new Binding( rawVal, hold );
                bindings.computeIfAbsent( normKey, k -> new java.util.ArrayList<>( ) ).add( binding );

                log.log( Level.INFO, "HotkeyManager: bound [{0}] -> {1}{2}",
                    new Object[]{ normKey, rawVal, hold ? " [hold-loop]" : "" } );
            }
        }
        catch( IOException e )
        {
            log.log( Level.WARNING, "HotkeyManager: failed to read " + HOTKEYS_FILE, e );
        }
    }
}
