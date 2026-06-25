package com.group_finity.mascot;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Global hotkey manager for Shimeji.
 *
 * Reads hotkey-to-behavior mappings and fires manager.setBehaviorAllSafe() for
 * the matching active mascots when a bound key is pressed.
 *
 * TWO SOURCES (per-mascot overrides global, per combo):
 *   1. GLOBAL  conf/hotkeys.properties — applies to every active mascot, unless a
 *      value is prefixed "ImageSet:Behavior" to target one image set.
 *   2. PER-MASCOT  img/&lt;ImageSet&gt;/conf/hotkeys.properties — bindings here are
 *      implicitly scoped to that image set (write a BARE behavior name, no prefix),
 *      and OVERRIDE the global binding for the same combo for that image set only.
 *      Combos the per-mascot file does NOT define still fall through to the global.
 *
 * Resolution is per (combo, imageSet): {@link #effectiveBindings} returns the
 * per-mascot bindings if that image set defines the combo, otherwise the global
 * bindings that apply to it. Every fire / hold / cancel path goes through it, so
 * override behaves consistently across one-shot presses and hold-to-loop.
 *
 * HOLD-TO-LOOP:
 * When a key is held the OS fires repeated nativeKeyPressed events which would snap
 * the mascot back to frame 1 each repeat. HotkeyManager tracks held combos and
 * suppresses the OS repeats. Manager.tick() calls getHeldBehaviorFor() each tick and
 * re-fires only when the current action has fully completed, so the animation plays
 * through cleanly then loops. Release the key and the mascot returns to normal
 * behavior on its next natural completion.
 *
 * --- hotkeys.properties format (both files) ---
 *
 * Each line is:  &lt;combo&gt;=&lt;Behavior&gt;
 *
 * Key combo syntax (case-insensitive, tokens joined by +):
 *   Modifiers : ctrl, shift, alt, meta
 *   Keys      : any NativeKeyEvent.VC_* name with VC_ stripped (F1, A, SPACE, ...)
 *   Mouse     : MOUSE1, MOUSE2, MOUSE3, MOUSE4, MOUSE5
 *
 * In the GLOBAL file a bare behavior fires for all mascots; prefix "ImageSet:Behavior"
 * to target one. In a PER-MASCOT file the behavior is always bare (the image set is the
 * folder it lives in). Suffix the behavior with !hold for hold-to-loop, optionally
 * !hold:ContinuationBehavior. Lines starting with # are comments; blank lines ignored;
 * duplicate combos within a file are merged.
 */

public class HotkeyManager implements NativeKeyListener, NativeMouseListener
{
    // This tracks keys that have already triggered a behavior so they don't "stutter"
    private final Set<Integer> toggledKeys = new HashSet<>();
    private static final Logger log = Logger.getLogger( HotkeyManager.class.getName( ) );

    private static final java.nio.file.Path HOTKEYS_PATH =
        java.nio.file.Paths.get( ".", "conf", "hotkeys.properties" );
    private static final java.nio.file.Path IMG_DIR =
        java.nio.file.Paths.get( ".", "img" );

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
        final String  behaviorEntry;   // global: "Mario:MoveRight" or "MoveRight"; per-mascot: bare "MoveRight"
        final boolean hold;            // true → loop while held; false → fire once on press
        final String  continuation;    // optional: behavior to loop after first completion
        Binding( String entry, boolean hold, String continuation ) {
            this.behaviorEntry = entry;
            this.hold          = hold;
            this.continuation  = continuation; // null if not specified
        }
    }

    // GLOBAL: combo → bindings (from conf/hotkeys.properties)
    private final Map<String, List<Binding>> bindings = new LinkedHashMap<>( );

    // PER-MASCOT: imageSet → (combo → bindings) from img/<imageSet>/conf/hotkeys.properties.
    // behaviorEntry here is a bare behavior name, implicitly scoped to imageSet.
    private final Map<String, Map<String, List<Binding>>> perMascotBindings = new LinkedHashMap<>( );

    // ── Hold state ────────────────────────────────────────────────────────────

    /**
     * Combos currently held by the user AND resolving to at least one !hold binding.
     * Written from JNativeHook's thread; read from Manager's tick thread.
     */
    private final Set<String> heldCombos = ConcurrentHashMap.newKeySet( );

    /**
     * Combo string recorded at press time, keyed by keyCode / button. Release rebuilds
     * the combo from CURRENT modifiers — if the user releases a modifier before the main
     * key the rebuilt combo no longer matches and the heldCombos entry would leak. Looking
     * up the press-time combo guarantees release always clears what press set.
     */
    private final Map<Integer, String> pressedKeyCombos   = new ConcurrentHashMap<>( );
    private final Map<Integer, String> pressedMouseCombos = new ConcurrentHashMap<>( );

    private Manager manager;
    private boolean hooked = false;

    private HotkeyManager( ) { }

    // ── Public API ────────────────────────────────────────────────────────────

    public void init( final Manager manager )
    {
        this.manager = manager;
        loadAllBindings( );

        if( bindings.isEmpty( ) && perMascotBindings.isEmpty( ) )
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
            log.log( Level.INFO, "HotkeyManager: global hook registered ({0} global combo(s), {1} per-mascot file(s)).",
                new Object[]{ bindings.size( ), perMascotBindings.size( ) } );
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
        pressedKeyCombos.clear( );
        pressedMouseCombos.clear( );
    }

    /**
     * Returns the held !hold behavior name for the given imageSet, or null if none held.
     * Per-mascot bindings override global for the same combo.
     */
    public String getHeldBehaviorFor( final String imageSet )
    {
        if( heldCombos.isEmpty( ) ) return null;
        for( String combo : heldCombos )
            for( Binding b : effectiveBindings( combo, imageSet ) )
                if( b.hold ) return b.behaviorEntry;
        return null;
    }

    /**
     * Returns the continuation behavior for the held !hold binding for this imageSet,
     * or null if none specified or no key held. (RIGHT=Mario:MoveRight!hold:MoveRightRun
     * plays MoveRight once, then loops MoveRightRun while held.)
     */
    public String getHeldContinuationFor( final String imageSet )
    {
        if( heldCombos.isEmpty( ) ) return null;
        for( String combo : heldCombos )
            for( Binding b : effectiveBindings( combo, imageSet ) )
                if( b.hold && b.continuation != null ) return b.continuation;
        return null;
    }

    /**
     * Returns the held directional: -1 if a "left" behavior is held, +1 if "right", else 0.
     * Used by Jump TargetX calculation to bias the jump direction.
     */
    public int getHeldDirectional( final String imageSet )
    {
        if( heldCombos.isEmpty( ) ) return 0;
        for( String combo : heldCombos )
            for( Binding b : effectiveBindings( combo, imageSet ) )
            {
                if( !b.hold ) continue;
                String lower = b.behaviorEntry.toLowerCase( );
                if( lower.contains( "left"  ) ) return -1;
                if( lower.contains( "right" ) ) return  1;
            }
        return 0;
    }

    // ── NativeKeyListener ─────────────────────────────────────────────────────

    @Override
    public void nativeKeyPressed( NativeKeyEvent e ) {
        int keyCode = e.getKeyCode();

        // THE BOUNCER: if the key is already pressed, do nothing (suppress OS repeats).
        if( toggledKeys.contains( keyCode ) ) return;
        toggledKeys.add( keyCode );

        String combo = buildKeyCombo( e );
        pressedKeyCombos.put( keyCode, combo );
        firePress( combo );
    }

    @Override
    public void nativeKeyReleased( NativeKeyEvent e ) {
        toggledKeys.remove( e.getKeyCode() );

        // Use the combo recorded at press time — rebuilding from current modifiers
        // leaks the held entry if a modifier was released first.
        final String pressCombo = pressedKeyCombos.remove( e.getKeyCode() );
        final String combo = pressCombo != null ? pressCombo : buildKeyCombo( e );
        heldCombos.remove( combo );

        // Cancel the current move/jump action so it stops immediately.
        cancelHeldForCombo( combo );
    }

    @Override public void nativeKeyTyped( NativeKeyEvent e ) { }

    // ── NativeMouseListener ───────────────────────────────────────────────────

    @Override
    public void nativeMousePressed( NativeMouseEvent e )
    {
        String combo = buildMouseCombo( e );
        pressedMouseCombos.put( e.getButton(), combo );
        firePress( combo );
    }

    @Override
    public void nativeMouseReleased( NativeMouseEvent e )
    {
        final String pressCombo = pressedMouseCombos.remove( e.getButton() );
        final String combo = pressCombo != null ? pressCombo : buildMouseCombo( e );
        heldCombos.remove( combo );
        cancelHeldForCombo( combo );
    }

    @Override public void nativeMouseClicked( NativeMouseEvent e ) { }

    // ── Fire / resolve ────────────────────────────────────────────────────────

    /**
     * Fire a pressed combo across all active image sets, resolving per-mascot overrides.
     * Registers the combo as held if it resolves to any !hold binding for any image set.
     */
    private void firePress( final String combo )
    {
        if( manager == null || !comboIsBound( combo ) ) return;

        boolean anyHold = false;
        for( String imageSet : activeImageSets( ) )
        {
            for( Binding b : effectiveBindings( combo, imageSet ) )
            {
                if( b.hold ) anyHold = true;
                if( !applyJumpSteerIfDirectional( b.behaviorEntry, imageSet, manager ) )
                    manager.setBehaviorAllSafe( imageSet, b.behaviorEntry );
            }
        }
        if( anyHold ) heldCombos.add( combo );
    }

    /** Cancel any held actions a combo resolves to (stops Move/Jump in place on release). */
    private void cancelHeldForCombo( final String combo )
    {
        if( manager == null ) return;
        for( String imageSet : activeImageSets( ) )
            for( Binding b : effectiveBindings( combo, imageSet ) )
                if( b.hold )
                    manager.cancelHeldActions( imageSet, b.behaviorEntry );
    }

    /**
     * The effective bindings for one combo on one image set, as a list of bare-behavior
     * Bindings. Per-mascot file wins (override) if it defines the combo; otherwise the
     * global bindings whose scope includes this image set (bare = all, or "ImageSet:"
     * prefix matching it). Comma-separated entries are split out.
     */
    private List<Binding> effectiveBindings( final String combo, final String imageSet )
    {
        final List<Binding> out = new ArrayList<>( );

        // 1. Per-mascot override — bare behaviors, already scoped to this image set.
        final Map<String, List<Binding>> pm =
            ( imageSet == null ) ? null : perMascotBindings.get( imageSet );
        if( pm != null && pm.containsKey( combo ) )
        {
            for( Binding b : pm.get( combo ) )
                for( String part : b.behaviorEntry.split( "," ) )
                {
                    part = part.trim( );
                    if( !part.isEmpty( ) ) out.add( new Binding( part, b.hold, b.continuation ) );
                }
            return out;   // override: do NOT fall through to global for this combo
        }

        // 2. Global — parts whose scope includes this image set.
        final List<Binding> g = bindings.get( combo );
        if( g == null ) return out;
        for( Binding gb : g )
            for( String part : gb.behaviorEntry.split( "," ) )
            {
                part = part.trim( );
                if( part.isEmpty( ) ) continue;
                if( part.contains( ":" ) )
                {
                    String[] kv = part.split( ":", 2 );
                    if( kv[0].trim( ).equals( imageSet ) )
                        out.add( new Binding( kv[1].trim( ), gb.hold, gb.continuation ) );
                }
                else
                {
                    out.add( new Binding( part, gb.hold, gb.continuation ) );   // bare = all image sets
                }
            }
        return out;
    }

    /** True if the combo is bound either globally or in any per-mascot file. */
    private boolean comboIsBound( final String combo )
    {
        if( bindings.containsKey( combo ) ) return true;
        for( Map<String, List<Binding>> pm : perMascotBindings.values( ) )
            if( pm.containsKey( combo ) ) return true;
        return false;
    }

    /** Distinct image sets among currently active mascots. */
    private Set<String> activeImageSets( )
    {
        final Set<String> s = new LinkedHashSet<>( );
        if( manager != null )
            for( Mascot m : manager.getMascotList( ) )
                s.add( m.getImageSet( ) );
        return s;
    }

    /**
     * For directional behaviors (name contains "left"/"right"):
     * - If the mascot is mid-Jump, queues a steer nudge and suppresses setBehaviorAllSafe.
     * - If airborne but not mid-Jump (free-falling), also suppresses it so the Falling
     *   nudge branch in MoveLeft/Right handles it instead.
     * - If on the ground, returns false so normal walking fires.
     */
    private boolean applyJumpSteerIfDirectional( final String behaviorName,
                                                 final String imageSet,
                                                 final Manager mgr )
    {
        String lower = behaviorName.toLowerCase( );
        int dx;
        if(      lower.contains( "left"  ) ) dx = -8;
        else if( lower.contains( "right" ) ) dx =  8;
        else return false;

        boolean suppressBehavior = false;
        for( com.group_finity.mascot.Mascot mascot : mgr.getMascotList( ) )
        {
            if( imageSet != null && !mascot.getImageSet( ).equals( imageSet ) ) continue;

            if( mascot.isJumping( ) )   // thread-safe (reads volatile behavior reference)
            {
                mascot.addJumpTargetXOffset( dx );
                suppressBehavior = true;
                continue;
            }

            try
            {
                com.group_finity.mascot.environment.MascotEnvironment env = mascot.getEnvironment( );
                java.awt.Point anchor = mascot.getAnchor( );
                boolean onGround = env.getFloor( ).isOn( anchor )
                                || env.getActiveIE( ).getTopBorder( ).isOn( anchor );
                if( !onGround )
                    suppressBehavior = true;
            }
            catch( Exception ignored ) { }
        }
        return suppressBehavior;
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

    // ── Loading ───────────────────────────────────────────────────────────────

    private void loadAllBindings( )
    {
        bindings.clear( );
        perMascotBindings.clear( );

        // Global file
        File global = HOTKEYS_PATH.toAbsolutePath( ).toFile( );
        log.log( Level.INFO, "HotkeyManager: looking for global hotkeys file at {0}", global.getAbsolutePath( ) );
        if( global.exists( ) )
            parseHotkeyFile( global, bindings, null );
        else
            log.log( Level.INFO, "HotkeyManager: {0} not found.", global.getAbsolutePath( ) );

        // Per-mascot files: img/<imageSet>/conf/hotkeys.properties
        File imgDir = IMG_DIR.toAbsolutePath( ).toFile( );
        File[] dirs = imgDir.listFiles( File::isDirectory );
        if( dirs != null )
            for( File dir : dirs )
            {
                File f = new File( new File( dir, "conf" ), "hotkeys.properties" );
                if( !f.exists( ) ) continue;
                String imageSet = dir.getName( );
                Map<String, List<Binding>> map = new LinkedHashMap<>( );
                parseHotkeyFile( f, map, imageSet );
                if( !map.isEmpty( ) )
                    perMascotBindings.put( imageSet, map );
            }
    }

    /**
     * Parse one hotkeys.properties file into the given combo→bindings map.
     * imageSetLabel is null for the global file (used only for logging).
     */
    private void parseHotkeyFile( final File file, final Map<String, List<Binding>> target, final String imageSetLabel )
    {
        final String label = imageSetLabel == null ? "global" : imageSetLabel;
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

                boolean hold = false;
                String continuation = null;
                int holdIdx = rawVal.indexOf( "!hold" );
                if( holdIdx >= 0 )
                {
                    hold = true;
                    String afterHold = rawVal.substring( holdIdx + "!hold".length( ) ).trim( );
                    rawVal = rawVal.substring( 0, holdIdx ).trim( );
                    if( afterHold.startsWith( ":" ) )
                        continuation = afterHold.substring( 1 ).trim( );
                }

                Binding binding = new Binding( rawVal, hold, continuation );
                target.computeIfAbsent( normKey, k -> new ArrayList<>( ) ).add( binding );

                log.log( Level.INFO, "HotkeyManager: [{0}] bound [{1}] -> {2}{3}{4}",
                    new Object[]{ label, normKey, rawVal,
                        hold ? " [hold-loop]" : "",
                        continuation != null ? " [continuation: " + continuation + "]" : "" } );
            }
        }
        catch( IOException e )
        {
            log.log( Level.WARNING, "HotkeyManager: failed to read " + file.getAbsolutePath( ), e );
        }
    }
}
