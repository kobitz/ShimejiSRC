package com.group_finity.mascot;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.ResourceBundle;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.sound.sampled.Clip;

import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.environment.MascotEnvironment;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.hotspot.Hotspot;
import com.group_finity.mascot.image.ImagePairs;
import com.group_finity.mascot.image.MascotImage;
import com.group_finity.mascot.image.NativeImage;
import com.group_finity.mascot.image.TranslucentWindow;
import com.group_finity.mascot.menu.JLongMenu;
import com.group_finity.mascot.assistant.AssistantBubble;
import com.group_finity.mascot.assistant.AssistantInputDialog;
import com.group_finity.mascot.assistant.OllamaClient;
import com.group_finity.mascot.script.VariableMap;
import com.group_finity.mascot.sound.Sounds;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

/**
 * Mascot object.
 *
 * The mascot represents the long-term, complex behavior and (@link Behavior),
 * Represents a short-term movements in the monotonous work with (@link Action).
 *
 * The mascot they have an internal timer, at a constant interval to call (@link Action).
 * (@link Action) is (@link #animate (Point, MascotImage, boolean)) method or by calling
 * To animate the mascot.
 *
 * (@link Action) or exits, the other at a certain time is called (@link Behavior), the next move to (@link Action).
 *
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public class Mascot
{
    private static final long serialVersionUID = 1L;

    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private static AtomicInteger lastId = new AtomicInteger( );

    private final int id;

    private String imageSet = "";
    /**
     * A window that displays the mascot.
     */
    private final TranslucentWindow window = NativeFactory.getInstance( ).newTransparentWindow( );

    /**
     * Managers are managing the mascot.
     */
    private Manager manager = null;

    /**
     * Mascot ground coordinates.
     * Or feet, for example, when part of the hand is hanging.
     */
    private Point anchor = new Point( 0, 0 );

    /**
     * Last known good on-screen anchor position. Updated every tick after a
     * successful behavior step while the mascot is within screen bounds.
     * Used as the recovery position instead of a random off-screen drop when
     * the engine would otherwise teleport the mascot to a random location.
     */
    private Point savedAnchor = null;

    /**
     * Behavior name recorded at the same time as savedAnchor, so recovery
     * can restart the correct behavior rather than defaulting to Fall.
     */
    private String savedBehaviorName = null;

    /**
     * Image to display.
     */
    private MascotImage image = null;

    private double currentScale = 1.0;
    private MascotImage lastScaledImage = null;
    private double lastDisplayScale = 0.0;
    private java.util.LinkedHashMap<MascotImage, com.group_finity.mascot.image.ScalableNativeImage> scalables = null;
    // Source image dimensions (base image at globalScaling).
    // Used by renderCX/renderCY to compute anchor pixel offsets from actual displayed image size
    // instead of raw currentScale, avoiding a mismatch when the cache is stale.
    private int lastBaseImageHeight = -1;
    private int lastBaseImageWidth  = -1;

    // Raw (unscaled) anchor from the current pose's ImageAnchor attribute.
    // Set by Pose.next() each frame. Used by apply() and getBounds() to compute
    // screen-pixel cx/cy without baking the anchor into the ImagePair cache.
    private int renderAnchorX = 0;
    private int renderAnchorY = 0;

    // Tint overlay — color/current opacity rendered every frame. Persists until snap-cleared.
    private java.awt.Color tintColor = null;
    private float tintCurrentOpacity = 0f;
    // Target opacity and lerp speed — updated by Tint action or by dynamic expression each tick.
    private float tintTargetOpacity = 0f;
    private float tintLerpFactor    = 0.1f;
    // Dynamic expressions: re-evaluated every tick in Mascot.tick() independent of active behavior.
    // Set by Tint.init() when Target/Color are script expressions; persist after the action ends.
    private com.group_finity.mascot.script.Variable  tintTargetVar = null;
    private com.group_finity.mascot.script.Variable  tintColorVar  = null;
    private com.group_finity.mascot.script.VariableMap tintVarMap  = null;
    // Lerped color channels for dynamic color expressions — current lerps toward target each tick.
    private float tintCurrentR = 255f; private float tintTargetR = 255f;
    private float tintCurrentG = 0f;   private float tintTargetG = 0f;
    private float tintCurrentB = 0f;   private float tintTargetB = 0f;
    // Scratch buffers for per-frame tint compositing — reused, reallocated only on image size change.
    private java.awt.image.BufferedImage tintScratchBuf = null;
    private com.group_finity.mascot.image.NativeImage tintScratchNative = null;

    // Cached global Scaling setting — mirrors ActionBase.scalingConstant but as a double.
    private static double cachedGlobalScaling = Double.NaN;

    private static double getGlobalScaling( )
    {
        if( Double.isNaN( cachedGlobalScaling ) )
            cachedGlobalScaling = Double.parseDouble(
                Main.getInstance( ).getProperties( ).getProperty( "Scaling", "1.0" ) );
        return cachedGlobalScaling;
    }

    /** Call when the Scaling setting changes so the cached value is refreshed. */
    public static void invalidateGlobalScaling( ) { cachedGlobalScaling = Double.NaN; }

    /**
     * Whether looking right or left.
     * The original image is treated as left, true means picture must be inverted.
     */
    private boolean lookRight = false;

    /**
     * Object representing the long-term behavior.
     */
    private Behavior behavior = null;

    /**
     * Whether this mascot's location (and behavior) is pinned and will be
     * restored on the next program launch.
     */
    private boolean pinnedLocation = false;

    // ── General-purpose script scratchpad ────────────────────────────────────────
    // Allows XML/JS scripts to store transient per-mascot values (e.g. jump target block X).
    private final java.util.Map<String, Object> userData = new java.util.HashMap<>( );

    // ── Movement velocity tracking ───────────────────────────────────────────────
    // Updated every setAnchor call so XML can read horizontal speed at jump time.
    private int lastDeltaX = 0;
    private int lastDeltaY = 0;

    // ── Jump air-steer ────────────────────────────────────────────────────────
    // Written by HotkeyManager on a directional key-press, consumed once by
    // Jump.getTargetX() each tick. Volatile so the JNH thread and tick thread
    // see it without locking.
    private volatile int jumpTargetXOffset = 0;

    // ── Manual-only mode ──────────────────────────────────────────────────────
    // When true, buildNextBehavior only allows Fall/Dragged/Thrown/Stand/GrabWall.
    private boolean manualOnly = false;

    // ── Assistant mode ─────────────────────────────────────────────────────────
    // When true, left-clicking the mascot opens a chat input box instead of
    // triggering the normal drag behavior. Responses come from a local Ollama LLM.
    private boolean assistantMode = false;
    private com.group_finity.mascot.assistant.AssistantBubble assistantBubble = null;
    // Dedicated bubble for the active countdown timer / fired reminder. Separate from
    // assistantBubble so it can maintain its fixed stacking position independently.
    private volatile com.group_finity.mascot.assistant.AssistantBubble timerBubble = null;
    // All bubbles this mascot has ever created — each AI response gets its own entry so
    // the chronological stacking system can weave them between other mascots' messages.
    // CopyOnWriteArrayList: Manager thread iterates for reposition, EDT adds entries.
    private final java.util.concurrent.CopyOnWriteArrayList<com.group_finity.mascot.assistant.AssistantBubble>
        activeBubbles = new java.util.concurrent.CopyOnWriteArrayList<>();
    private com.group_finity.mascot.assistant.AssistantInputDialog activeDialog = null;
    // Strong reference — the registry only holds a WeakReference, so without this the listener gets GC'd
    private com.group_finity.mascot.assistant.MascotSpeechRegistry.PeerListener peerListener = null;
    private static com.group_finity.mascot.assistant.OllamaClient ollamaClient = null; // shared across all mascots
    // Ollama request rate-limiting is handled inside OllamaClient (queue-based).

    // Click-vs-hold detection: a press shorter than CLICK_MAX_MS with movement
    // under CLICK_MAX_MOVE_PX is treated as a click (opens chat); longer = drag.
    private long  assistantPressTime  = 0;
    private Point assistantPressPoint = null;

    /** Foreground window title captured just before the input dialog opens,
     *  so the mascot knows what the user was looking at when they spoke to it. */
    private String lastActiveWindowTitle = "";

    // ── Spontaneous window-aware comments ─────────────────────────────────────
    /** Minimum ticks between spontaneous comments (~40 ticks/sec, so 1800 = ~45s). */
    // Window comment cadence: anywhere from ~30s to ~3min, biased toward longer
    private static final int SPONTANEOUS_MIN_TICKS  = 1_200; // ~30s floor
    private static final int SPONTANEOUS_MAX_TICKS  = 7_200; // ~3min ceiling
    private int  spontaneousTickCounter = 0;
    private int  spontaneousNextTrigger = -1; // -1 = randomize on first tick
    private final java.util.Random spontaneousRng = new java.util.Random();
    /** Title that triggered the last spontaneous comment - avoid repeating. */
    private volatile String lastSpontaneousTitle = "";

    // Audio reaction cadence: anywhere from ~45s to ~4min, biased toward longer
    private static final int AUDIO_MIN_TICKS  = 1_800; // ~45s floor
    private static final int AUDIO_MAX_TICKS  = 9_600; // ~4min ceiling
    private int  audioTickCounter  = 0;
    private int  audioNextTrigger  = -1; // -1 = randomize on first tick
    /** Shared across all mascots — only one capture thread needed. */
    private static com.group_finity.mascot.assistant.AudioTranscriptBuffer audioBuffer = null;
    private static final Object AUDIO_LOCK = new Object();
    /** Last system audio transcript; used to skip near-duplicate reactions across fires. */
    private static volatile String lastAudioTranscriptText = "";

    // Vision screen-glance cadence: anywhere from ~2min to ~7.5min, biased toward longer
    private static final int VISION_MIN_TICKS  = 4_800; // ~2min floor
    private static final int VISION_MAX_TICKS  = 18_000; // ~7.5min ceiling
    private int  visionTickCounter  = 0;
    private int  visionNextTrigger  = -1; // -1 = randomize on first tick

    // Global cross-mascot cooldowns — when any mascot fires a type, all others skip that
    // type until the cooldown elapses. Each type is independent; peer reactions excluded.
    private static final long GLOBAL_SPONTANEOUS_COOLDOWN_MS = 120_000L; // 2 min
    private static final long GLOBAL_AUDIO_COOLDOWN_MS       =  90_000L; // 90 s
    private static final long GLOBAL_VISION_COOLDOWN_MS      = 180_000L; // 3 min
    private static final java.util.concurrent.atomic.AtomicLong globalSpontaneousLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );
    private static final java.util.concurrent.atomic.AtomicLong globalAudioLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );
    private static final java.util.concurrent.atomic.AtomicLong globalVisionLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );

    private static final int CLICK_MAX_MS      = 100;
    private static final int CLICK_MAX_MOVE_PX = 8;

    // Drag delay: mousePressed is not forwarded to the behavior immediately.
    // Instead a timer fires after DRAG_DELAY_MS; only then is drag initiated.
    // This prevents the mascot from jumping on a quick click.
    // Shared threshold with assistant-mode click detection.
    private javax.swing.Timer dragDelayTimer    = null;
    private MouseEvent        pendingPressEvent = null;

    // ── Floor collision ────────────────────────────────────────────────────────
    // When false, workarea bottom is not treated as a floor for this mascot.
    private boolean floorEnabled = true;

    // ── Behavior tooltip overlay ──────────────────────────────────────────────
    // When true, a small floating label above this mascot shows its current
    // behavior name and remaining action timer ticks.
    private boolean tooltipEnabled = false;
    private MascotTooltip mascotTooltip = null;
    // The property key under which this mascot's location is saved.
    // Set at save time and carried across restarts so removePinnedLocation()
    // always deletes the right key regardless of the current runtime id.
    private String pinnedKey = null;

    /**
     * Increases with each tick of the timer.
     */
    private int time = 0;

    /**
     * Whether the animation is running.
     */
    private boolean animating = true;

    private boolean paused = false;
    
    /**
     * Set by behaviours when the shimeji is being dragged by the mouse cursor, 
     * as opposed to hotspots or the like.
     */
    private boolean dragging = false;

    private MascotEnvironment environment = new MascotEnvironment( this );

    private String sound = null;
    
    protected DebugWindow debugWindow = null;

    private JPopupMenu activePopup = null;
    private NativeMouseListener popupDismissListener = null;
    
    private ArrayList<String> affordances = new ArrayList( 5 );
    
    private ArrayList<Hotspot> hotspots = new ArrayList( 5 );
    
    /**
     * Set by behaviours when the user has triggered a hotspot on this shimeji, 
     * so that the shimeji knows to check for any new hotspots that emerge while 
     * the mouse is held down.
     */
    private Point cursor = null;
    
    private VariableMap variables = null;

    public Mascot( final String imageSet )
    {
        id = lastId.incrementAndGet( );
        this.imageSet = imageSet;

        // Restore persisted dynamic scale for this image set, if any.
        try
        {
            String saved = Main.getInstance( ).getProperties( )
                .getProperty( "Scale." + imageSet );
            if( saved != null )
                currentScale = Double.parseDouble( saved );
        }
        catch( NumberFormatException ignored ) { }

        // Restore per-mascot manualOnly flag if it was persisted
        String savedManualOnly = Main.getInstance( ).getProperties( ).getProperty( "ManualOnly.mascot" + id );
        if( savedManualOnly != null )
            manualOnly = Boolean.parseBoolean( savedManualOnly );

        String savedFloorEnabled = Main.getInstance( ).getProperties( ).getProperty( "FloorEnabled.mascot" + id );
        if( savedFloorEnabled != null )
            floorEnabled = Boolean.parseBoolean( savedFloorEnabled );
        else
            floorEnabled = Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "FloorCollision", "true" ) );

        String savedTooltip = Main.getInstance( ).getProperties( ).getProperty( "Tooltip.mascot" + id );
        if( savedTooltip != null )
            tooltipEnabled = Boolean.parseBoolean( savedTooltip );

        String savedAssistantMode = Main.getInstance( ).getProperties( ).getProperty( "AssistantMode." + imageSet );
        if( savedAssistantMode != null )
        {
            assistantMode = Boolean.parseBoolean( savedAssistantMode );
        }
        else
        {
            // Default to on if this mascot has a <Personality> defined
            final com.group_finity.mascot.config.Configuration cfg =
                Main.getInstance( ).getConfiguration( imageSet );
            if( cfg != null )
            {
                final String p = cfg.getInformation( "Personality" );
                assistantMode = ( p != null && !p.trim( ).isEmpty( ) );
            }
        }

        log.log( Level.INFO, "Created a mascot ({0})", this );

        // Register with voice command listener if assistant mode is on
        if( assistantMode ) registerVoiceCommand();

        // Register as a peer listener so this mascot hears what other mascots say
        if( assistantMode ) registerPeerListener();

        // Always on top — global setting AND per-imageset override, both default true
        applyAlwaysOnTop( );

        // Register the mouse handler
        getWindow( ).asComponent( ).addMouseListener( new MouseAdapter( )
        {
            @Override
            public void mousePressed( final MouseEvent e )
            {
                Mascot.this.mousePressed( e );
            }

            @Override
            public void mouseReleased( final MouseEvent e )
            {
                Mascot.this.mouseReleased( e );
            }
        } );
        getWindow( ).asComponent( ).addMouseMotionListener( new MouseMotionListener( )
        {
            @Override
            public void mouseMoved( final MouseEvent e )
            {
                if( paused )
                    refreshCursor( false );
                else
                {
                    if( isHotspotClicked( ) )
                        setCursorPosition( e.getPoint( ) );
                    else
                        refreshCursor( e.getPoint( ) );
                }
            }

            @Override
            public void mouseDragged( final MouseEvent e )
            {
                if( paused )
                    refreshCursor( false );
                else
                {
                    if( isHotspotClicked( ) )
                        setCursorPosition( e.getPoint( ) );
                    else
                        refreshCursor( e.getPoint( ) );
                }
            }
        } );
    }

    @Override
    public String toString( )
    {
        return "mascot" + id;
    }

    public int getId( )
    {
        return id;
    }

    private void mousePressed( final MouseEvent event )
    {
        // Left-click dismisses any open right-click popup
        if( SwingUtilities.isLeftMouseButton( event ) && activePopup != null && activePopup.isVisible( ) )
        {
            activePopup.setVisible( false );
            activePopup = null;
            return;
        }

        if( SwingUtilities.isLeftMouseButton( event ) )
        {
            // Record press for both assistant-mode click detection and drag delay
            assistantPressTime  = System.currentTimeMillis( );
            assistantPressPoint = event.getLocationOnScreen( );
            // Snapshot the foreground window NOW — before any Swing window
            // gets focus. By the time the click resolves or the dialog opens
            // the previous window will already have lost foreground status.
            if( assistantMode )
            {
                lastActiveWindowTitle = getActiveWindowTitle( );
                log.info( "[Assistant] window snapshot at press: \"" + lastActiveWindowTitle + "\"" );
            }

            // Don't forward to behavior immediately — wait CLICK_MAX_MS before
            // initiating drag so a quick click never moves the mascot.
            // Exception: if dragging is already active (e.g. re-press mid-drag)
            // pass through instantly so it doesn't interrupt an ongoing drag.
            if( isDragging( ) )
            {
                forwardMousePressed( event );
                return;
            }

            // Cancel any previous pending timer (shouldn't normally be one)
            if( dragDelayTimer != null )
            {
                dragDelayTimer.stop( );
                dragDelayTimer = null;
            }

            pendingPressEvent = event;
            dragDelayTimer = new javax.swing.Timer( CLICK_MAX_MS, e ->
            {
                dragDelayTimer    = null;
                final MouseEvent pending = pendingPressEvent;
                pendingPressEvent = null;
                if( pending != null )
                    forwardMousePressed( pending );
            });
            dragDelayTimer.setRepeats( false );
            dragDelayTimer.start( );
        }
        else
        {
            // Non-left button (shouldn't normally reach here but handle cleanly)
            forwardMousePressed( event );
        }
    }

    /** Actually forward a mousePressed event to the current behavior. */
    private void forwardMousePressed( final MouseEvent event )
    {
        if( !isPaused( ) && getBehavior( ) != null )
        {
            try
            {
                getBehavior( ).mousePressed( event );
            }
            catch( final CantBeAliveException e )
            {
                log.log( Level.SEVERE, "Fatal Error", e );
                Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "SevereShimejiErrorErrorMessage" ), e );
                dispose( );
            }
        }
    }

    private void mouseReleased( final MouseEvent event )
    {
        if( event.isPopupTrigger( ) )
        {
            SwingUtilities.invokeLater( new Runnable( )
            {
                @Override
                public void run( )
                {
                    showPopup( event.getX( ), event.getY( ) );
                }
            } );
            return;
        }

        if( SwingUtilities.isLeftMouseButton( event ) )
        {
            // If the drag-delay timer is still pending, the hold wasn't long enough
            // to start a drag — cancel it. The mascot never moved.
            if( dragDelayTimer != null )
            {
                dragDelayTimer.stop( );
                dragDelayTimer    = null;
                pendingPressEvent = null;

                // Assistant mode: short click → open input
                if( assistantMode && assistantPressPoint != null )
                {
                    final Point release = event.getLocationOnScreen( );
                    final int   dx      = release.x - assistantPressPoint.x;
                    final int   dy      = release.y - assistantPressPoint.y;
                    final int   moved   = (int) Math.sqrt( dx * dx + dy * dy );
                    assistantPressPoint = null;
                    if( moved < CLICK_MAX_MOVE_PX && !isDragging() )
                    {
                        SwingUtilities.invokeLater( this::openAssistantInput );
                        return;
                    }
                }
                assistantPressPoint = null;
                // No drag was started and no assistant action — nothing to release
                return;
            }

            assistantPressPoint = null;

            // Drag is active (or hotspot was clicked) — forward release normally
            if( !isPaused( ) && getBehavior( ) != null )
            {
                try
                {
                    getBehavior( ).mouseReleased( event );
                }
                catch( final CantBeAliveException e )
                {
                    log.log( Level.SEVERE, "Fatal Error", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "SevereShimejiErrorErrorMessage" ), e );
                    dispose( );
                }
            }
        }
    }

    private void showPopup( final int x, final int y )
    {
        final JPopupMenu popup = new JPopupMenu( );
        final ResourceBundle languageBundle = Main.getInstance( ).getLanguageBundle( );

        popup.addPopupMenuListener( new PopupMenuListener( )
        {
            @Override
            public void popupMenuCanceled( final PopupMenuEvent e )
            {
            }

            @Override
            public void popupMenuWillBecomeInvisible( final PopupMenuEvent e )
            {
                setAnimating( true );
                activePopup = null;
                if( popupDismissListener != null )
                {
                    GlobalScreen.removeNativeMouseListener( popupDismissListener );
                    popupDismissListener = null;
                }
            }

            @Override
            public void popupMenuWillBecomeVisible( final PopupMenuEvent e )
            {
                setAnimating( false );
            }
        } );

        // "Another One!" menu item
        final JMenuItem increaseMenu = new JMenuItem( languageBundle.getString( "CallAnother" ) );
        increaseMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                Main.getInstance( ).createMascot( imageSet );
            }
        } );

        // "Bye Bye!" menu item
        final JMenuItem disposeMenu = new JMenuItem( languageBundle.getString( "Dismiss" ) );
        disposeMenu.addActionListener( new ActionListener( )
        {
            @Override
            public void actionPerformed( final ActionEvent e )
            {
                // Clear any saved pin so a dismissed mascot doesn't respawn next launch.
                if( pinnedLocation )
                    removePinnedLocation( );
                dispose( );
            }
        } );

        // "Follow Mouse!" Menu item
        final JMenuItem gatherMenu = new JMenuItem( languageBundle.getString( "FollowCursor" ) );
        gatherMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                getManager( ).setBehaviorAll( Main.getInstance( ).getConfiguration(imageSet), Main.BEHAVIOR_GATHER, imageSet );
            }
        } );

        // "Reduce to One!" menu item
        final JMenuItem oneMenu = new JMenuItem( languageBundle.getString( "DismissOthers" ) );
        oneMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                getManager( ).remainOne( imageSet );
            }
        } );

        // "Reduce to One!" menu item
        final JMenuItem onlyOneMenu = new JMenuItem( languageBundle.getString( "DismissAllOthers" ) );
        onlyOneMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                getManager( ).remainOne( Mascot.this );
            }
        } );

        // "Restore IE!" menu item
        final JMenuItem restoreMenu = new JMenuItem( languageBundle.getString( "RestoreWindows" ) );
        restoreMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                NativeFactory.getInstance( ).getEnvironment( ).restoreIE( );
            }
        } );

        // Debug menu item
        final JMenuItem debugMenu = new JMenuItem( languageBundle.getString( "RevealStatistics" ) );
        debugMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                if( debugWindow == null )
                {
                    debugWindow = new DebugWindow( );
                    //debugWindow.setIcon
                }
                debugWindow.setAlwaysOnTop( Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "AlwaysOnTopDebugWindow", "false" ) ) );
                debugWindow.setVisible( true );
            }
        } );
        
        // "Bye Everyone!" menu item
        final JMenuItem closeMenu = new JMenuItem( languageBundle.getString( "DismissAll" ) );
        closeMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent e )
            {
                Main.getInstance( ).exit( );
            }
        } );

        // "Save Location" checkbox — pins this mascot's current position and behavior
        // so it is restored at the same spot on next launch.
        final JCheckBoxMenuItem saveLocationMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "SaveLocation" ), pinnedLocation );
        saveLocationMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                pinnedLocation = saveLocationMenu.isSelected( );
                if( pinnedLocation )
                    savePinnedLocation( );
                else
                    removePinnedLocation( );
            }
        } );

        // "Manual Only" checkbox — restricts autonomous behavior to Stand/Fall/Dragged/Thrown/GrabWall.
        // All other behaviors can still be triggered manually via hotkey or right-click menu.
        final JCheckBoxMenuItem manualOnlyMenu = new JCheckBoxMenuItem( "Manual Only", manualOnly );
        manualOnlyMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                manualOnly = manualOnlyMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "ManualOnly.mascot" + id, String.valueOf( manualOnly ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        // "Paused" Menu item
        final JMenuItem pauseMenu = new JMenuItem( isAnimating( ) ? languageBundle.getString( "PauseAnimations" ) : languageBundle.getString( "ResumeAnimations" ) );
        pauseMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                Mascot.this.setPaused( !Mascot.this.isPaused( ) );
            }
        } );

        // Add the Behaviors submenu.  Currently slightly buggy, sometimes the menu ghosts.
        JLongMenu submenu = new JLongMenu( languageBundle.getString( "SetBehaviour" ), 30 );
        JLongMenu allowedSubmenu = new JLongMenu( languageBundle.getString( "PersonalBehaviours" ), 30 );
        // The MenuScroller would look better than the JLongMenu, but the initial positioning is not working correctly.
        //MenuScroller.setScrollerFor(submenu, 30, 125);
        submenu.setAutoscrolls( true );
        JMenuItem item;
        JCheckBoxMenuItem toggleItem;
        final com.group_finity.mascot.config.Configuration config = Main.getInstance( ).getConfiguration( getImageSet( ) );
        for( String behaviorName : config.getBehaviorNames( ) )
        {
            final String command = behaviorName;
            try
            {
                if( !config.isBehaviorHidden( command ) )
                {
                    String caption = behaviorName.replaceAll( "([a-z])(IE)?([A-Z])", "$1 $2 $3" ).replaceAll( "  ", " " );
                    if( config.isBehaviorEnabled( command, Mascot.this ) && !command.contains( "/" ) && !config.isBehaviorToggleable( command ) )
                    {
                        item = new JMenuItem( languageBundle.containsKey( behaviorName ) ? 
                                              languageBundle.getString( behaviorName ) : 
                                              caption );
                        item.addActionListener( new ActionListener( )
                        {
                            @Override
                            public void actionPerformed( final ActionEvent e )
                            {
                                try
                                {	
                                    if( isCurrentActionInterruptable( ) )
                                        setBehavior( config.buildBehavior( command ) );
                                }
                                catch( Exception err )
                                {
                                    log.log( Level.SEVERE, "Error ({0})", this );
                                    Main.showError( languageBundle.getString( "CouldNotSetBehaviourErrorMessage" ), err );
                                }
                            }
                        } );
                        submenu.add( item );
                    }
                    
                    if( config.isBehaviorToggleable( command ) && !command.contains( "/" ) )
                    {
                        toggleItem = new JCheckBoxMenuItem( caption, config.isBehaviorEnabled( command, Mascot.this ) );
                        toggleItem.addItemListener( new ItemListener( )
                        {
                            public void itemStateChanged( final ItemEvent e )
                            {
                                final boolean nowEnabled = !config.isBehaviorEnabled( command, Mascot.this );
                                Main.getInstance( ).setMascotBehaviorEnabled( command, Mascot.this, nowEnabled );
                                if( config.isBehaviorClearTintOnDisable( command ) )
                                {
                                    if( nowEnabled )
                                    {
                                        try { setBehavior( config.buildBehavior( command ) ); }
                                        catch( Exception ignored ) {}
                                    }
                                    else
                                    {
                                        snapClearTint( );
                                        // Break out of the running Loop=true behavior so it cannot
                                        // re-call Tint.init() and re-register the color expression.
                                        try
                                        {
                                            setBehavior( config.buildNextBehavior( command, Mascot.this ) );
                                        }
                                        catch( Exception ignored ) {}
                                    }
                                }
                            }
                        } );
                        allowedSubmenu.add( toggleItem );
                    }
                }
            }
            catch( Exception err )
            {
                // just skip if something goes wrong
            }
        }

        // Per-mascot universal behavior toggles
        final String mascotKey = "mascot" + id;
        final java.util.Properties props = Main.getInstance( ).getProperties( );

        final JCheckBoxMenuItem breedingMenu = new JCheckBoxMenuItem( languageBundle.getString( "BreedingCloning" ),
            ( props.getProperty( "Breeding.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Breeding.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Breeding", "true" ) ) ) );
        breedingMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Breeding.imageset." + getImageSet( ), String.valueOf( breedingMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem transientMenu = new JCheckBoxMenuItem( languageBundle.getString( "BreedingTransient" ),
            ( props.getProperty( "Transients.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Transients.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Transients", "true" ) ) ) );
        transientMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Transients.imageset." + getImageSet( ), String.valueOf( transientMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem transformationMenu = new JCheckBoxMenuItem( languageBundle.getString( "Transformation" ),
            ( props.getProperty( "Transformation.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Transformation.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Transformation", "true" ) ) ) );
        transformationMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Transformation.imageset." + getImageSet( ), String.valueOf( transformationMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem throwingMenu = new JCheckBoxMenuItem( languageBundle.getString( "ThrowingWindows" ),
            ( props.getProperty( "Throwing.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Throwing.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Throwing", "true" ) ) ) );
        throwingMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Throwing.imageset." + getImageSet( ), String.valueOf( throwingMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem soundsMenu = new JCheckBoxMenuItem( languageBundle.getString( "SoundEffects" ),
            ( props.getProperty( "Sounds.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Sounds.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Sounds", "true" ) ) ) );
        soundsMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Sounds.imageset." + getImageSet( ), String.valueOf( soundsMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem multiscreenMenu = new JCheckBoxMenuItem( languageBundle.getString( "Multiscreen" ),
            ( props.getProperty( "Multiscreen.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Multiscreen.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Multiscreen", "true" ) ) ) );
        multiscreenMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Multiscreen.imageset." + getImageSet( ), String.valueOf( multiscreenMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem screenLoopMenu = new JCheckBoxMenuItem( languageBundle.getString( "ScreenLoop" ),
            ( props.getProperty( "ScreenLoop.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "ScreenLoop.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "ScreenLoop", "false" ) ) ) );
        screenLoopMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "ScreenLoop.imageset." + getImageSet( ), String.valueOf( screenLoopMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem floorMenu = new JCheckBoxMenuItem( "Floor Collision", floorEnabled );
        floorMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                floorEnabled = floorMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "FloorEnabled.mascot" + id, String.valueOf( floorEnabled ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem tooltipMenu = new JCheckBoxMenuItem( "Tooltip", tooltipEnabled );
        tooltipMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                tooltipEnabled = tooltipMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "Tooltip.mascot" + id, String.valueOf( tooltipEnabled ) );
                Main.getInstance( ).updateConfigFile( );
                if( !tooltipEnabled && mascotTooltip != null )
                {
                    mascotTooltip.dispose( );
                    mascotTooltip = null;
                }
            }
        } );

        JLongMenu universalSubmenu = new JLongMenu( languageBundle.getString( "UniversalBehaviours" ), 30 );
        universalSubmenu.add( breedingMenu );
        universalSubmenu.add( transientMenu );
        universalSubmenu.add( transformationMenu );
        universalSubmenu.add( throwingMenu );
        universalSubmenu.add( soundsMenu );
        universalSubmenu.add( multiscreenMenu );
        universalSubmenu.add( screenLoopMenu );
        universalSubmenu.add( floorMenu );

        // Personal Filter submenu — lets users override the global filter for this imageSet
        final String imageSetFilterKey = "Filter.imageset." + getImageSet( );
        final String globalFilter = props.getProperty( "Filter", "false" );
        final String currentFilter = props.getProperty( imageSetFilterKey, globalFilter );

        final JCheckBoxMenuItem filterNearestMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "PersonalFilterNearest" ),
            currentFilter.equalsIgnoreCase( "false" ) || currentFilter.equalsIgnoreCase( "nearest" ) );
        final JCheckBoxMenuItem filterBicubicMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "PersonalFilterBicubic" ),
            currentFilter.equalsIgnoreCase( "bicubic" ) );
        final JCheckBoxMenuItem filterHqxMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "PersonalFilterHqx" ),
            currentFilter.equalsIgnoreCase( "true" ) || currentFilter.equalsIgnoreCase( "hqx" ) );

        final Runnable applyPersonalFilter = new Runnable( )
        {
            public void run( )
            {
                // Determine which filter is now selected
                String chosen;
                if( filterBicubicMenu.isSelected( ) )
                    chosen = "bicubic";
                else if( filterHqxMenu.isSelected( ) )
                    chosen = "hqx";
                else
                    chosen = "false";

                props.setProperty( imageSetFilterKey, chosen );

                // Clear cached images for this imageSet so they reload with the new filter
                ImagePairs.removeAll( getImageSet( ) );

                // Reload configuration and respawn
                final String imageSet = getImageSet( );
                final boolean isExit = Main.getInstance( ).getManager( ).isExitOnLastRemoved( );
                Main.getInstance( ).getManager( ).setExitOnLastRemoved( false );
                dispose( );
                Main.getInstance( ).loadConfiguration( imageSet );
                Main.getInstance( ).createMascot( imageSet );
                Main.getInstance( ).getManager( ).setExitOnLastRemoved( isExit );
            }
        };

        filterNearestMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                if( filterNearestMenu.isSelected( ) )
                {
                    filterBicubicMenu.setSelected( false );
                    filterHqxMenu.setSelected( false );
                    applyPersonalFilter.run( );
                }
                else if( !filterBicubicMenu.isSelected( ) && !filterHqxMenu.isSelected( ) )
                    filterNearestMenu.setSelected( true );
            }
        } );
        filterBicubicMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                if( filterBicubicMenu.isSelected( ) )
                {
                    filterNearestMenu.setSelected( false );
                    filterHqxMenu.setSelected( false );
                    applyPersonalFilter.run( );
                }
                else if( !filterNearestMenu.isSelected( ) && !filterHqxMenu.isSelected( ) )
                    filterBicubicMenu.setSelected( true );
            }
        } );
        filterHqxMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                if( filterHqxMenu.isSelected( ) )
                {
                    filterNearestMenu.setSelected( false );
                    filterBicubicMenu.setSelected( false );
                    applyPersonalFilter.run( );
                }
                else if( !filterNearestMenu.isSelected( ) && !filterBicubicMenu.isSelected( ) )
                    filterHqxMenu.setSelected( true );
            }
        } );

        JLongMenu personalFilterSubmenu = new JLongMenu( languageBundle.getString( "PersonalFilter" ), 30 );
        personalFilterSubmenu.add( filterNearestMenu );
        personalFilterSubmenu.add( filterBicubicMenu );
        personalFilterSubmenu.add( filterHqxMenu );

        popup.add( increaseMenu );
        popup.add( new JSeparator( ) );
        popup.add( gatherMenu );
        popup.add( restoreMenu );
        popup.add( debugMenu );
        popup.add( tooltipMenu );
        popup.add( new JSeparator( ) );
        if( submenu.getMenuComponentCount( ) > 0 )
            popup.add( submenu );
        popup.add( universalSubmenu );
        if( allowedSubmenu.getMenuComponentCount( ) > 0 )
            popup.add( allowedSubmenu );
        popup.add( personalFilterSubmenu );
        popup.add( new JSeparator( ) );
        popup.add( saveLocationMenu );
        popup.add( manualOnlyMenu );
        // Assistant mode toggle — lets the mascot respond to left-clicks via local LLM
        final JCheckBoxMenuItem assistantModeMenu = new JCheckBoxMenuItem( "Assistant Mode", assistantMode );
        assistantModeMenu.addItemListener( new ItemListener( )
        {
            @Override
            public void itemStateChanged( final ItemEvent e )
            {
                assistantMode = assistantModeMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty(
                    "AssistantMode." + getImageSet(), String.valueOf( assistantMode ) );
                if( assistantMode )
                    registerVoiceCommand();
                else
                    unregisterVoiceCommand();
                // Dismiss all open bubbles when turning off
                if( !assistantMode )
                {
                    for( final com.group_finity.mascot.assistant.AssistantBubble b : activeBubbles )
                        b.dismiss();
                    assistantBubble = null;
                    timerBubble     = null;
                }
                if( !assistantMode && activeDialog != null )
                {
                    activeDialog.close( );
                    activeDialog = null;
                }
            }
        } );
        popup.add( assistantModeMenu );
        popup.add( pauseMenu );

        final boolean currentAlwaysOnTop = Boolean.parseBoolean(
            props.getProperty( "AlwaysOnTop.imageset." + getImageSet( ), "true" ) );
        final JCheckBoxMenuItem alwaysOnTopMenu = new JCheckBoxMenuItem(
            ( languageBundle.containsKey( "AlwaysOnTop" ) ? languageBundle.getString( "AlwaysOnTop" ) : "Always On Top" ), currentAlwaysOnTop );
        alwaysOnTopMenu.addItemListener( new ItemListener( )
        {
            @Override
            public void itemStateChanged( final ItemEvent e )
            {
                final boolean onTop = alwaysOnTopMenu.isSelected( );
                props.setProperty( "AlwaysOnTop.imageset." + getImageSet( ), String.valueOf( onTop ) );
                applyAlwaysOnTop( );
            }
        } );
        popup.add( alwaysOnTopMenu );
        popup.add( new JSeparator( ) );
        popup.add( disposeMenu );
        popup.add( oneMenu );
        popup.add( onlyOneMenu );
        popup.add( closeMenu );

        getWindow( ).asComponent( ).requestFocus( );

        // lightweight popups expect the shimeji window to draw them if they fall inside the shimeji window boundary
        // as the shimeji window can't support this we need to set them to heavyweight
        popup.setLightWeightPopupEnabled( false );
        activePopup = popup;

        // jnativehook intercepts native mouse events OS-wide, so this fires for clicks
        // on the desktop or other windows where Java's AWTEventListener cannot reach.
        // The Timer delay lets Swing process any menu-item ActionListener first before
        // we force-close the popup, ensuring menu actions are never swallowed.
        popupDismissListener = new NativeMouseListener( )
        {
            @Override
            public void nativeMousePressed( final NativeMouseEvent e )
            {
                if( activePopup != null )
                {
                    SwingUtilities.invokeLater( new Runnable( )
                    {
                        @Override
                        public void run( )
                        {
                            javax.swing.Timer t = new javax.swing.Timer( 150, new ActionListener( )
                            {
                                @Override
                                public void actionPerformed( ActionEvent ae )
                                {
                                    if( activePopup != null )
                                        activePopup.setVisible( false );
                                }
                            } );
                            t.setRepeats( false );
                            t.start( );
                        }
                    } );
                }
            }
            @Override public void nativeMouseReleased( NativeMouseEvent e ) { }
            @Override public void nativeMouseClicked( NativeMouseEvent e )  { }
        };
        GlobalScreen.addNativeMouseListener( popupDismissListener );

        popup.show( getWindow( ).asComponent( ), x, y );
    }

    // ── Assistant mode ────────────────────────────────────────────────────────


    /**
     * Handle a voice utterance addressed to this mascot.
     * Mirrors the text-input pipeline but speaks the response aloud via TTS.
     * Called on a background thread by AssistantCoordinator.
     */
    private void handleTextInput( final String userText,
                                   final String mascotName,
                                   final com.group_finity.mascot.config.Configuration cfg )
    {
        // Bubble state is managed by AssistantCoordinator callbacks:
        //   wake word → onListening() → "Listening..."
        //   command captured → onThinking() → "Thinking..."
        // By the time we get here, "Thinking..." should already be showing.
        // If somehow it isn't (e.g. wake word + command in one utterance), set it now.
        SwingUtilities.invokeLater( ( ) ->
        {
            if( assistantBubble == null || !assistantBubble.isThinking() )
            {
                ensureFreshBubble();
                assistantBubble.showThinking( getBounds( ) );
            }
        });

        final String keywordMatch = cfg != null ? findActionByKeyword( userText, cfg ) : null;
        log.info( "Voice keyword match for \"" + userText + "\": " + keywordMatch );
        final String system       = buildSystemPrompt( cfg, mascotName, userText );

        // ── Fire action immediately — don't wait for Ollama ──────────────────
        // This means voice commands interrupt whatever the mascot is doing right now,
        // same as manually selecting a behavior from the right-click menu.
        if( keywordMatch != null && cfg != null )
        {
            SwingUtilities.invokeLater( ( ) ->
            {
                tryRunBehavior( keywordMatch, cfg );
            });
        }

        if( ollamaClient != null )
        {
            if( isScreenQuery( userText ) )
            {
                com.group_finity.mascot.assistant.ChatLog.append( "User(voice)", userText );
                handleScreenQuery( userText, system, ollamaClient );
                return;
            }
            com.group_finity.mascot.assistant.ChatLog.append( "User(voice)", userText );
            final String safeUserText = buildUserMessage( userText ) + weatherContext( userText );
            ollamaClient.generate( system, safeUserText, new OllamaClient.Callback( )
            {
                @Override
                public void onResponse( final String raw )
                {
                    fireActionFromResponse( raw );
                    final String display = applyPersonaRewrites( stripActionTag( sanitizeResponse( raw ) ) );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName, display );
                    if( !isEphemeralQuery( userText ) )
                        com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                            .recordUserExchange( userText, display );
                    maybeSummarizeMemory();
                    // Record without triggering peer reactions — don't interrupt user conversations
                    com.group_finity.mascot.assistant.MascotSpeechRegistry
                        .record( getImageSet(), mascotName, display, 1, false );

                    SwingUtilities.invokeLater( ( ) ->
                    {
                        if( assistantBubble != null )
                            assistantBubble.showResponse( display, getBounds( ) );
                    });

                }

                @Override
                public void onError( final String message )
                {
                    SwingUtilities.invokeLater( ( ) ->
                    {
                        if( assistantBubble != null )
                            assistantBubble.showError( message, getBounds( ) );
                    });
                }
            });
        }
        else
        {
            // No LLM — keyword action only
            SwingUtilities.invokeLater( ( ) ->
            {
                if( assistantBubble != null )
                {
                    if( keywordMatch != null && cfg != null )
                    {
                        final String fail = tryRunBehavior( keywordMatch, cfg );
                        assistantBubble.showResponse(
                            fail == null ? "On it!" : fail, getBounds( ) );
                    }
                    else
                    {
                        assistantBubble.showError( "Ollama isn't running.", getBounds( ) );
                    }
                }
            });
        }
    }

    private void openAssistantInput( )
    {
        // lastActiveWindowTitle was already captured at mouse-press time,
        // before the mascot window took foreground focus.

        // Close any existing dialog for this mascot before opening a new one
        if( activeDialog != null )
        {
            activeDialog.close( );
            activeDialog = null;
        }

        // Lazily create the shared Ollama client
        if( ollamaClient == null )
            ollamaClient = new OllamaClient( );

        // Ensure a bubble exists so the dialog has something to attach to
        if( assistantBubble == null )
        {
            assistantBubble = new AssistantBubble( this::handleReply, getImageSet() );
            activeBubbles.add( assistantBubble );
        }

        final Rectangle bounds = getBounds( );

        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( getImageSet( ) );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" )
            : getImageSet( );

        activeDialog = new AssistantInputDialog(
            bounds,
            mascotName,
            userText ->
            {
                activeDialog = null;

                // ── Step 1: keyword action fires immediately ───────────────────
                final String keywordMatch = cfg != null ? findActionByKeyword( userText, cfg ) : null;
                if( keywordMatch != null && cfg != null )
                    tryRunBehavior( keywordMatch, cfg ); // on EDT — dialog callback is on EDT

                if( ollamaClient == null ) ollamaClient = createOllamaClient();
                final OllamaClient client = ollamaClient;

                SwingUtilities.invokeLater( ( ) ->
                {
                    ensureFreshBubble();
                    assistantBubble.showThinking( getBounds( ) );
                } );

                final String system = buildSystemPrompt( cfg, mascotName, userText );
                if( isScreenQuery( userText ) )
                {
                    com.group_finity.mascot.assistant.ChatLog.append( "User", userText );
                    handleScreenQuery( userText, system, client );
                    return;
                }
                com.group_finity.mascot.assistant.ChatLog.append( "User", userText );
                final String safeUserText = buildUserMessage( userText );
                new Thread( () ->
                {
                    client.generate( system, safeUserText + weatherContext( userText ), new OllamaClient.Callback( )
                    {
                        @Override
                        public void onResponse( final String raw )
                        {
                            fireActionFromResponse( raw );
                            final String display = applyPersonaRewrites( stripActionTag( sanitizeResponse( raw ) ) );
                            com.group_finity.mascot.assistant.ChatLog.append( mascotName, display );
                            if( !isEphemeralQuery( userText ) )
                                com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                                    .recordUserExchange( userText, display );
                            maybeSummarizeMemory();
                            SwingUtilities.invokeLater( ( ) ->
                                assistantBubble.showResponse( display, getBounds( ) ) );
                        }

                        @Override
                        public void onError( final String message )
                        {
                            SwingUtilities.invokeLater( ( ) ->
                                assistantBubble.showError( message, getBounds( ) ) );
                        }
                    });
                }, "text-input-thread" ).start();
            }
        );
        activeDialog.show( );
    }

    /**
     * Keyword-based behavior search: returns the first behavior name whose
     * words overlap with the user's input words (case-insensitive).
     * Ignores hidden behaviors and sub-behaviors (those containing "/").
     */
    /**
     * Strip injected content from an LLM response before displaying or speaking it.
     * Cuts off at the first line that looks like injected instructions.
     */
    /**
     * Returns true if two audio transcripts share > 70% word overlap.
     * Used to skip reacting when the rolling buffer yields essentially the same content
     * across consecutive fires (same background audio, or repeated Whisper hallucination).
     */
    private static boolean isAudioTranscriptDuplicate( final String a, final String b )
    {
        if( a == null || b == null || b.isEmpty() ) return false;
        final String[] wa = a.toLowerCase().split( "\\W+" );
        final String[] wb = b.toLowerCase().split( "\\W+" );
        if( wa.length < 3 || wb.length < 3 ) return false;
        final java.util.Set<String> sa = new java.util.HashSet<>( java.util.Arrays.asList( wa ) );
        int overlap = 0;
        for( final String w : wb ) if( sa.contains( w ) ) overlap++;
        return (float) overlap / Math.min( wa.length, wb.length ) > 0.70f;
    }

    private static String stripActionTag( final String text )
    {
        if( text == null ) return text;
        // Case-insensitive: catches [ACTION:...], [Action:...], [blink], [Gasping: Startled], etc.
        return text.replaceAll( "(?i)\\[[a-zA-Z][^\\]]*\\]", "" ).trim();
    }

    /**
     * Post-generation rewrite: replaces first-person pronouns with the mascot's name
     * when the speech rule contains a third-person self-reference constraint.
     * Gated by: assistantMode on, non-empty speech rule, mascot name in the rule.
     */
    private String applyPersonaRewrites( final String text )
    {
        if( text == null ) return text;
        final String name = getImageSet( );
        // Strip "Name: " self-prefix the model occasionally echoes
        String s = text.startsWith( name + ": " ) ? text.substring( name.length() + 2 ) : text;
        // Check XML flag first; fall through to per-name hardcodes as backup.
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( name );
        if( cfg != null )
        {
            final String flag = cfg.getInformation( "ThirdPersonRewrite" );
            if( "true".equalsIgnoreCase( flag ) )
                return rewriteFirstPerson( s, name );
        }
        // Hardcoded fallback so this works even if config lookup fails.
        if( "Paimon".equals( name ) )
            return rewriteFirstPerson( s, name );
        return s;
    }

    private static String rewriteFirstPerson( String s, final String name )
    {
        // Contractions — must come before bare-I replacements
        s = s.replaceAll( "\\b" + name + "'m\\b", name + " is" ); // model sometimes generates e.g. "Paimon'm"
        s = s.replaceAll( "\\bI'm not\\b", name + " is not" );
        s = s.replaceAll( "\\bI'm\\b",     name + " is" );
        s = s.replaceAll( "\\bI've\\b",    name + " has" );
        s = s.replaceAll( "\\bI'll\\b",    name + " will" );
        s = s.replaceAll( "\\bI'd\\b",     name + " would" );
        // be / have
        s = s.replaceAll( "\\bI am\\b",   name + " is" );
        s = s.replaceAll( "\\bI was\\b",  name + " was" );
        s = s.replaceAll( "\\bI have\\b", name + " has" );
        s = s.replaceAll( "\\bI had\\b",  name + " had" );
        // do
        s = s.replaceAll( "\\bI do\\b",  name + " does" );
        s = s.replaceAll( "\\bI did\\b", name + " did" );
        // modals (person-invariant)
        s = s.replaceAll( "\\bI can\\b",    name + " can" );
        s = s.replaceAll( "\\bI will\\b",   name + " will" );
        s = s.replaceAll( "\\bI would\\b",  name + " would" );
        s = s.replaceAll( "\\bI could\\b",  name + " could" );
        s = s.replaceAll( "\\bI should\\b", name + " should" );
        s = s.replaceAll( "\\bI might\\b",  name + " might" );
        s = s.replaceAll( "\\bI must\\b",   name + " must" );
        // common present-tense verbs
        s = s.replaceAll( "\\bI think\\b",      name + " thinks" );
        s = s.replaceAll( "\\bI know\\b",       name + " knows" );
        s = s.replaceAll( "\\bI want\\b",       name + " wants" );
        s = s.replaceAll( "\\bI need\\b",       name + " needs" );
        s = s.replaceAll( "\\bI like\\b",       name + " likes" );
        s = s.replaceAll( "\\bI love\\b",       name + " loves" );
        s = s.replaceAll( "\\bI hate\\b",       name + " hates" );
        s = s.replaceAll( "\\bI feel\\b",       name + " feels" );
        s = s.replaceAll( "\\bI hope\\b",       name + " hopes" );
        s = s.replaceAll( "\\bI see\\b",        name + " sees" );
        s = s.replaceAll( "\\bI say\\b",        name + " says" );
        s = s.replaceAll( "\\bI mean\\b",       name + " means" );
        s = s.replaceAll( "\\bI get\\b",        name + " gets" );
        s = s.replaceAll( "\\bI wonder\\b",     name + " wonders" );
        s = s.replaceAll( "\\bI understand\\b", name + " understands" );
        s = s.replaceAll( "\\bI remember\\b",   name + " remembers" );
        s = s.replaceAll( "\\bI believe\\b",    name + " believes" );
        s = s.replaceAll( "\\bI find\\b",       name + " finds" );
        s = s.replaceAll( "\\bI guess\\b",      name + " guesses" );
        s = s.replaceAll( "\\bI suggest\\b",    name + " suggests" );
        s = s.replaceAll( "\\bI prefer\\b",     name + " prefers" );
        s = s.replaceAll( "\\bI wish\\b",       name + " wishes" );
        // catch-all I → name (may leave unconjugated verbs; acceptable)
        s = s.replaceAll( "\\bI\\b", name );
        // object / possessive pronouns
        s = s.replaceAll( "\\b[Mm]yself\\b", name );
        s = s.replaceAll( "\\b[Mm]ine\\b",   name + "'s" );
        s = s.replaceAll( "\\b[Mm]y\\b",     name + "'s" );
        s = s.replaceAll( "\\b[Mm]e\\b",     name );
        return s;
    }

    // Matches [REMEMBER:kw1,kw2|content text] — pipe separates keywords from content
    private static final java.util.regex.Pattern REMEMBER_TAG =
        java.util.regex.Pattern.compile(
            "\\[REMEMBER:([^|\\]]+)\\|([^\\]]+)\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE );

    // Matches [TIMER:5:reminder] (minutes) or [TIMER:4:40pm:reminder] (wall-clock)
    private static final java.util.regex.Pattern TIMER_TAG =
        java.util.regex.Pattern.compile(
            "\\[TIMER:(\\d+(?::\\d+(?:am|pm)?)?):([^\\]]+)\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE );

    private static final java.util.regex.Pattern WALL_CLOCK =
        java.util.regex.Pattern.compile(
            "(\\d{1,2}):(\\d{2})(am|pm)?",
            java.util.regex.Pattern.CASE_INSENSITIVE );

    private static String[] extractTimerTag( final String raw )
    {
        if( raw == null ) return null;
        final java.util.regex.Matcher m = TIMER_TAG.matcher( raw );
        return m.find() ? new String[]{ m.group(1), m.group(2).trim() } : null;
    }

    private static long parseTimerDurationMs( final String value )
    {
        final String v = value.trim();
        // Pure integer → whole minutes (cap at 1440 = 24 h; larger values are LLM math errors)
        try
        {
            final long mins = Long.parseLong( v );
            if( mins < 1 || mins > 1440 ) return -1L;
            return mins * 60_000L;
        }
        catch( final NumberFormatException ignored ) {}
        // Wall-clock: H:MMam / H:MMpm / HH:MM
        final java.util.regex.Matcher m = WALL_CLOCK.matcher( v );
        if( m.matches() )
        {
            int hour = Integer.parseInt( m.group(1) );
            final int minute = Integer.parseInt( m.group(2) );
            final String ampm = m.group(3);
            if( "pm".equalsIgnoreCase( ampm ) && hour != 12 ) hour += 12;
            else if( "am".equalsIgnoreCase( ampm ) && hour == 12 ) hour = 0;
            final java.util.Calendar now = java.util.Calendar.getInstance();
            final java.util.Calendar target = (java.util.Calendar) now.clone();
            target.set( java.util.Calendar.HOUR_OF_DAY, hour );
            target.set( java.util.Calendar.MINUTE, minute );
            target.set( java.util.Calendar.SECOND, 0 );
            target.set( java.util.Calendar.MILLISECOND, 0 );
            long delta = target.getTimeInMillis() - now.getTimeInMillis();
            if( delta <= 0 ) delta += 24L * 60 * 60 * 1000; // roll to next day if past
            return delta;
        }
        return -1L;
    }

    private void setMascotTimer( final String timeValue, final String reminder )
    {
        final long durationMs = parseTimerDurationMs( timeValue );
        if( durationMs <= 0 ) return;
        final long endMs = System.currentTimeMillis() + durationMs;
        javax.swing.SwingUtilities.invokeLater( () ->
        {
            if( timerBubble == null )
            {
                timerBubble = new com.group_finity.mascot.assistant.AssistantBubble(
                    this::handleReply, getImageSet() );
                activeBubbles.add( timerBubble );
            }
            timerBubble.showCountdown( endMs, reminder, getBounds() );
        });
    }

    private static String sanitizeResponse( final String raw )
    {
        if( raw == null ) return "(no response)";
        // Split into sentences/lines and stop at the first injection marker
        final String[] lines = raw.split( "(?<=[.!?])\\s+|\\n" );
        final StringBuilder clean = new StringBuilder();
        for( final String line : lines )
        {
            final String lower = line.toLowerCase();
            if( lower.contains( "##" )
                || lower.matches( ".*\\bu003[ce]\\b.*" ) )
                break; // stop here — everything after is injected
            if( clean.length() > 0 ) clean.append( " " );
            clean.append( line.trim() );
        }
        final String result = clean.toString().trim();
        return result.isEmpty() ? raw.split( "[.!?]" )[0].trim() : result;
    }

    private String findActionByKeyword( final String userText,
                                        final com.group_finity.mascot.config.Configuration cfg )
    {
        final String[] inputWords = userText.toLowerCase( ).split( "[^a-z0-9]+" );
        String bestMatch = null;
        int    bestScore = 0;

        for( final String name : cfg.getBehaviorNames( ) )
        {
            if( name.contains( "/" ) ) continue;
            if( cfg.isBehaviorHidden( name ) ) continue;

            final String[] nameWords = name.toLowerCase( ).split( "[^a-z0-9]+" );
            int score = 0;
            for( final String iw : inputWords )
            {
                if( iw.isEmpty( ) ) continue;
                for( final String nw : nameWords )
                {
                    if( nw.equals( iw ) )         score += 4; // exact
                    else if( nw.contains( iw ) )  score += 2; // partial
                    else if( iw.contains( nw ) )  score += 2; // partial reverse
                    else if( iw.length( ) >= 4 && nw.length( ) >= 4
                             && editDistance( iw, nw ) <= 2 ) score += 3; // fuzzy
                }
            }
            if( score > bestScore )
            {
                bestScore = score;
                bestMatch = name;
            }
        }
        return bestScore > 0 ? bestMatch : null;
    }

    /** Levenshtein edit distance - used for fuzzy keyword matching of misheard words. */
    private static int editDistance( final String a, final String b )
    {
        final int la = a.length( ), lb = b.length( );
        final int[] prev = new int[ lb + 1 ], curr = new int[ lb + 1 ];
        for( int j = 0; j <= lb; j++ ) prev[j] = j;
        for( int i = 1; i <= la; i++ )
        {
            curr[0] = i;
            for( int j = 1; j <= lb; j++ )
            {
                if( a.charAt( i-1 ) == b.charAt( j-1 ) ) curr[j] = prev[j-1];
                else curr[j] = 1 + Math.min( prev[j-1], Math.min( prev[j], curr[j-1] ) );
            }
            System.arraycopy( curr, 0, prev, 0, lb + 1 );
        }
        return prev[lb];
    }

    /**
     * Extract [ACTION:BehaviorName] tag from an LLM response.
     * Returns the behavior name string, or null if no tag is present.
     * Validates that the behavior actually exists in the mascot's config.
     */
    private String extractActionTag( final String response )
    {
        final java.util.regex.Matcher m =
            java.util.regex.Pattern.compile( "\\[ACTION:([^\\]]+)\\]" ).matcher( response );
        if( !m.find( ) ) return null;
        final String name = m.group( 1 ).trim( );
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( getImageSet( ) );
        if( cfg == null ) return null;
        // Exact match first
        if( cfg.hasBehavior( name ) ) return name;
        // Case-insensitive fallback
        for( final String bname : cfg.getBehaviorNames( ) )
            if( bname.equalsIgnoreCase( name ) ) return bname;
        return null;
    }

    private void fireActionFromResponse( final String raw )
    {
        final String behaviorName = extractActionTag( raw );
        if( behaviorName != null )
        {
            final com.group_finity.mascot.config.Configuration cfg =
                Main.getInstance().getConfiguration( getImageSet() );
            if( cfg != null )
                javax.swing.SwingUtilities.invokeLater( () -> tryRunBehavior( behaviorName, cfg ) );
        }

        final String[] timerParts = extractTimerTag( raw );
        if( timerParts != null )
            setMascotTimer( timerParts[0], timerParts[1] );

        // Bare [word] tags — attempt behavior lookup as shorthand for [ACTION:Word].
        // Tries the word as-is, then with the first letter capitalised. Silent on miss.
        if( raw != null )
        {
            final com.group_finity.mascot.config.Configuration bareCfg =
                Main.getInstance().getConfiguration( getImageSet() );
            if( bareCfg != null )
            {
                final java.util.regex.Matcher bm =
                    java.util.regex.Pattern.compile( "\\[([a-zA-Z_]+)\\]" ).matcher( raw );
                while( bm.find() )
                {
                    final String word = bm.group( 1 );
                    final String cap  = Character.toUpperCase( word.charAt( 0 ) )
                                      + word.substring( 1 ).toLowerCase( java.util.Locale.ROOT );
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( tryRunBehavior( word, bareCfg ) != null
                                && tryRunBehavior( cap, bareCfg ) != null )
                            log.warning( "[Action] No behavior found for bare tag: [" + word + "]" );
                    } );
                }
            }
        }

        // Parse any [REMEMBER:kw1,kw2|content] tags and persist them permanently.
        if( raw != null )
        {
            final java.util.regex.Matcher rm = REMEMBER_TAG.matcher( raw );
            while( rm.find() )
            {
                final String[] kwParts = rm.group(1).split( "[,\\s]+" );
                final java.util.List<String> kws = new java.util.ArrayList<>();
                for( final String kw : kwParts )
                    if( !kw.isBlank() ) kws.add( kw.trim().toLowerCase( java.util.Locale.ROOT ) );
                final String content = rm.group(2).trim();
                if( !kws.isEmpty() && !content.isEmpty() )
                {
                    com.group_finity.mascot.assistant.MascotMemory
                        .forImageSet( getImageSet() )
                        .addPermanentMemory( kws, content );
                    log.info( "[Memory] Permanent memory saved: " + kws + " -> " + content );
                }
            }
        }
    }

    /**
     * Attempt to set this mascot's behavior by name, same as the right-click menu does -
     * conditions are intentionally bypassed for direct/manual invocation.
     * Returns null on success, or a user-facing message on failure.
     */
    private String tryRunBehavior( final String name,
                                   final com.group_finity.mascot.config.Configuration cfg )
    {
        log.info( "tryRunBehavior: " + name );
        try
        {
            final com.group_finity.mascot.behavior.Behavior b = cfg.buildBehavior( name );
            if( b == null ) return "I don't know how to do that.";
            setBehavior( b );
            return null;
        }
        catch( com.group_finity.mascot.exception.BehaviorInstantiationException
             | com.group_finity.mascot.exception.CantBeAliveException e )
        {
            log.log( Level.WARNING, "Assistant could not trigger behavior: " + name, e );
            return "Something went wrong trying to do that.";
        }
    }

    /**
     * Wraps the raw user text for the Ollama prompt.
     * When the question is about the active window, appends the window title
     * directly into the user turn so the model can answer concretely.
     */
    private String buildUserMessage( final String userText )
    {
        final String trimmed = userText.trim();
        final String lower   = trimmed.toLowerCase();

        final boolean asksAboutWindow =
            lower.contains( "watching" )   ||
            lower.contains( "playing" )    ||
            lower.contains( "doing" )      ||
            lower.contains( "working on" ) ||
            lower.contains( "looking at" );

        log.info( "[Assistant] buildUserMessage — asksAboutWindow=" + asksAboutWindow
            + " lastActiveWindowTitle=\"" + lastActiveWindowTitle + "\"" );
        if( asksAboutWindow && lastActiveWindowTitle != null && !lastActiveWindowTitle.isEmpty() )
        {
            return "“" + trimmed + "”"
                + " [The active window title is: \"" + lastActiveWindowTitle + "\"."
                + " Use this to infer what the user is watching, playing, or doing"
                + " and answer their question naturally. Do not repeat the raw title.]";
        }

        return "“" + trimmed + "”";
    }

    /**
     * Build the system prompt sent to Ollama for every request.
     * Combines the mascot's <Personality> tag (if present) with live context:
     * current behavior, time of day, and the active foreground window title.
     */
    private String buildSystemPrompt( final com.group_finity.mascot.config.Configuration cfg,
                                      final String mascotName )
    {
        return buildSystemPrompt( cfg, mascotName, null );
    }

    private String buildSystemPrompt( final com.group_finity.mascot.config.Configuration cfg,
                                      final String mascotName,
                                      final String userText )
    {
        final String personalityBase = getPersonality( cfg, mascotName );

        // Use the pre-dialog snapshot — by the time we build the prompt the
        // input dialog has focus and getActiveWindowTitle() would return itself.
        final String activeTitle = lastActiveWindowTitle;
        final String windowContext = ( activeTitle != null && !activeTitle.isEmpty( ) )
            ? "\nThe user's foreground window title is: \"" + activeTitle + "\". "
              + "Use this to infer what the user is doing or watching — "
              + "extract the meaningful part (e.g. video title, app name, document name) "
              + "and refer to that naturally. Do not quote or repeat the raw title string."
            : "";

        final com.group_finity.mascot.assistant.MascotMemory memory =
            com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() );

        final String peerCtx = com.group_finity.mascot.assistant.MascotSpeechRegistry
            .buildContext( getImageSet() );

        final java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat( "EEEE, MMMM d yyyy 'at' h:mm a" );
        final String timeStr = sdf.format( new java.util.Date() );
        final String cachedPlace =
            com.group_finity.mascot.assistant.WeatherTool.getCachedPlaceName();
        final String cachedWx =
            com.group_finity.mascot.assistant.WeatherTool.getCachedResult();
        final String envCtx = "\n[Current time: " + timeStr + ".]"
            + ( cachedPlace != null ? "\n[User location: " + cachedPlace + ".]" : "" )
            + ( cachedWx   != null ? "\n[Current weather: " + cachedWx + ".]" : "" );

        final String permBlock = userText != null
            ? memory.buildPermanentMemoryBlock( userText ) : "";

        final String speechRule = getSpeechRule( cfg );
        final String result = personalityBase
            + memory.buildMemoryBlock()
            + permBlock
            + ( peerCtx.isEmpty() ? "" : "\n\nOther desktop mascots present:" + peerCtx )
            + envCtx
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( speechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + speechRule )
            + "\n- Reply in ONE sentence. Two at most if truly necessary. Never more."
            + "\n- Answer only what was asked. Do not volunteer extra information."
            + "\n- No greetings, no sign-offs, no filler words."
            + "\n- Speak your response directly — do not wrap it in quotation marks."
            + "\n- When asked about time or weather: you MUST state the actual values from context (e.g. the real temperature, the real time). Express them in your character voice, but the real numbers must appear in your answer. Do not replace them with metaphor or fictional equivalents."
            + "\n- To set a timer: append [TIMER:VALUE:reminder text]. VALUE = whole minutes for durations ([TIMER:15:check laundry]), OR the exact clock time the user stated for specific times ([TIMER:5:30pm:alarm] when user says 'at 5:30pm'). For clock times: write the time EXACTLY as the user said it — do NOT convert to minutes, do NOT compute anything. Java handles the math."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent — not spoken. Omit it when nothing fits naturally."
            + "\n- To save something permanently for future conversations: append [REMEMBER:keyword1,keyword2|the fact to remember]. Use short, specific keywords the user is likely to mention again. The tag is silent."
            + windowContext
            + "\n---"
            + "\nRespond to the user\'s message now.";
        return withSpeechReminder( result, speechRule );
    }


    /**
     * Fires an unprompted one-liner reacting to the user's current active window.
     * Called from tick() on a random cadence. Ollama runs on a background thread.
     */
    /**
     * Called when the user clicks a bubble message and submits a reply.
     * context is the original trigger (transcript/window title), may be null.
     * Called from VoiceCommandListener poll thread or EDT — safe either way.
     */
    private void handleReply( final String userText,
                              final com.group_finity.mascot.assistant.AssistantBubble.Message message )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : "mascot";

        final String system = buildSystemPrompt( cfg, mascotName, userText );

        // Build prompt from full conversation thread
        final StringBuilder userMsg = new StringBuilder();
        if( message != null && message.context != null && !message.context.isEmpty() )
            userMsg.append( "[Original context: " ).append( message.context ).append( "]\n" );
        if( message != null && message.thread.size() > 1 )
        {
            userMsg.append( "Conversation so far:\n" );
            for( final String[] turn : message.thread )
                userMsg.append( turn[0] ).append( ": " ).append( turn[1] ).append( "\n" );
        }
        userMsg.append( "User reply: " ).append( userText );

        com.group_finity.mascot.assistant.ChatLog.append( "User", userText );

        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;

        javax.swing.SwingUtilities.invokeLater( () ->
        {
            ensureFreshBubble();
            final java.awt.Rectangle b = getBounds();
            if( b != null ) assistantBubble.showThinking( b );
        });

        new Thread( () ->
        {
            client.generate( system, userMsg.toString(), new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    fireActionFromResponse( raw );
                    final String text = applyPersonaRewrites( stripActionTag( raw ) );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName, text );
                    // Append mascot reply to thread for next chained reply
                    if( assistantBubble != null && message != null )
                        assistantBubble.appendReplyToThread( message, text );
                    if( !isEphemeralQuery( userText ) )
                        com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                            .recordUserExchange( userText, text );
                    maybeSummarizeMemory();
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = getBounds();
                        if( b != null && assistantBubble != null )
                            assistantBubble.showResponse( text, b );
                    });
                }
                @Override public void onError( final String error )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( assistantBubble != null ) assistantBubble.dismiss();
                    });
                }
            });
        }, "reply-thread" ).start();
    }

    private String getPersonalityName( )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String n = cfg != null ? cfg.getInformation( "Name" ) : null;
        return ( n != null && !n.trim().isEmpty() ) ? n.trim() : getImageSet();
    }

    // ── Inter-mascot communication ────────────────────────────────────────────

    private void registerPeerListener()
    {
        final Mascot self = this;
        peerListener = new com.group_finity.mascot.assistant.MascotSpeechRegistry.PeerListener()
        {
            @Override public String getImageSet() { return self.getImageSet(); }
            @Override public void onPeerSpeech( final String speakerName,
                                                final String speakerImageSet,
                                                final String text,
                                                final int chainDepth )
            {
                if( !assistantMode ) return;
                if( activeDialog != null ) return;
                firePeerReaction( speakerName, speakerImageSet, text, chainDepth );
            }
        };
        com.group_finity.mascot.assistant.MascotSpeechRegistry.register( peerListener );
    }

    /**
     * React to something another mascot just said.
     * Called on the peer-reaction scheduler thread; dispatches Ollama async.
     * chainDepth controls how far along the exchange chain we are.
     */
    private void firePeerReaction( final String speakerName,
                                   final String speakerImageSet,
                                   final String speakerText,
                                   final int chainDepth )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : getImageSet();
        final String personality = getPersonality( cfg, mascotName );

        final String peerCtx = com.group_finity.mascot.assistant.MascotSpeechRegistry
            .buildContext( getImageSet() );

        final com.group_finity.mascot.assistant.MascotMemory memory =
            com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() );
        final String peerTone = memory.getPeerTone( speakerName );

        // Peer reactions use a lightweight memory block (facts + tone only, no full
        // exchange history) to keep the system prompt short and reduce prefill cost.
        final String peerSpeechRule = getSpeechRule( cfg );
        final String system = withSpeechReminder( personality
            + memory.buildLightMemoryBlock( speakerName )
            + ( peerCtx.isEmpty() ? "" : "\n\nOther desktop mascots present:" + peerCtx )
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( peerSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + peerSpeechRule )
            + "\n- Reply in ONE sentence. 15 words maximum."
            + "\n- You are reacting to something another mascot just said to you."
            + "\n- Address " + speakerName + " only as \"" + speakerName + "\" — no other names or nicknames for them."
            + "\n- Your emotional tone toward " + speakerName + " is: " + peerTone + "."
            + "\n- Stay fully in character. No greetings, no filler."
            + "\n- Speak your response directly — do not wrap it in quotation marks."
            + "\n- Output ONLY your own words. Never prefix with another character's name or a dialogue tag (e.g. never write \"2B: ...\" or \"Holo says: ...\")."
            + "\n- End with a complete sentence. Never trail off with an ellipsis or open fragment."
            + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
            + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
            + "\n---", peerSpeechRule );

        final String prompt = speakerName + " just said: \""
            + speakerText + "\". Reply to them in one sentence, in a tone that is " + peerTone + ".";

        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;

        javax.swing.SwingUtilities.invokeLater( () ->
        {
            ensureFreshBubble();
            final java.awt.Rectangle b = getBounds();
            if( b != null ) assistantBubble.showThinking( b );
        });

        final String ctx = "[Reacting to " + speakerName + "] " + speakerText;

        client.generate( system, prompt, new OllamaClient.Callback()
        {
            @Override public void onResponse( final String raw )
            {
                fireActionFromResponse( raw );
                final String text = trimToFirstSentence( applyPersonaRewrites( stripActionTag( raw ) ) );
                if( isTrivialAcknowledgement( text ) )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( assistantBubble != null ) assistantBubble.dismiss();
                    });
                    return;
                }
                com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(to: " + speakerName + ")", text );
                com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                    .recordPeerExchange( speakerName, speakerText, text );
                maybeSummarizeMemory();
                com.group_finity.mascot.assistant.MascotSpeechRegistry
                    .record( getImageSet(), mascotName, text, chainDepth );
                javax.swing.SwingUtilities.invokeLater( () ->
                {
                    final java.awt.Rectangle b = getBounds();
                    if( b != null && assistantBubble != null )
                        assistantBubble.showResponse( text, ctx, b );
                });
            }
            @Override public void onError( final String error )
            {
                javax.swing.SwingUtilities.invokeLater( () ->
                {
                    if( assistantBubble != null ) assistantBubble.dismiss();
                });
            }
        });
    }

    private void registerVoiceCommand()
    {
        final com.group_finity.mascot.assistant.VoiceCommandListener vcl =
            com.group_finity.mascot.assistant.VoiceCommandListener.getInstance();
        vcl.register( getPersonalityName(), this::handleNameTrigger );
        final String alias = getVoiceTrigger();
        if( alias != null ) vcl.register( alias, this::handleNameTrigger );
    }

    private void unregisterVoiceCommand()
    {
        final com.group_finity.mascot.assistant.VoiceCommandListener vcl =
            com.group_finity.mascot.assistant.VoiceCommandListener.getInstance();
        vcl.unregister( getPersonalityName() );
        final String alias = getVoiceTrigger();
        if( alias != null ) vcl.unregister( alias );
    }

    private String getVoiceTrigger()
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String v = cfg != null ? cfg.getInformation( "VoiceTrigger" ) : null;
        return ( v != null && !v.trim().isEmpty() ) ? v.trim() : null;
    }

    /**
     * Called when the mascot's name is detected in the mic stream.
     * Receives the VCL transcript so the mic does not need to be re-transcribed.
     * Transcribes system audio separately for context, then calls Ollama.
     */
    private void handleNameTrigger( final String vclTranscript )
    {
        log.info( "[Voice] Name trigger fired for " + getImageSet() );

        // Ensure the shared audio buffer is running
        synchronized( AUDIO_LOCK )
        {
            if( audioBuffer == null )
            {
                audioBuffer = new com.group_finity.mascot.assistant.AudioTranscriptBuffer();
                if( !audioBuffer.start() )
                {
                    log.warning( "[Voice] Failed to start audio buffer for name trigger." );
                    audioBuffer = null;
                }
            }
        }

        final com.group_finity.mascot.assistant.AudioTranscriptBuffer buf = audioBuffer;
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : getImageSet();
        final String personality = getPersonality( cfg, mascotName );
        final String ntSpeechRule = getSpeechRule( cfg );

        final java.text.SimpleDateFormat ntSdf =
            new java.text.SimpleDateFormat( "EEEE, MMMM d yyyy 'at' h:mm a" );
        final String ntTimeStr = ntSdf.format( new java.util.Date() );
        final String ntPlace =
            com.group_finity.mascot.assistant.WeatherTool.getCachedPlaceName();
        final String ntCachedWx =
            com.group_finity.mascot.assistant.WeatherTool.getCachedResult();
        final String ntEnvCtx = "\n[Current time: " + ntTimeStr + ".]"
            + ( ntPlace   != null ? "\n[User location: " + ntPlace + ".]"       : "" )
            + ( ntCachedWx != null ? "\n[Current weather: " + ntCachedWx + ".]" : "" );

        final String system = withSpeechReminder( personality
            + ntEnvCtx
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( ntSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + ntSpeechRule )
            + "\n- Reply in ONE sentence. Two at most if truly necessary."
            + "\n- The user just called your name — respond to them directly."
            + "\n- Be brief and in-character. No greetings, no filler."
            + "\n- Speak your response directly — do not wrap it in quotation marks."
            + "\n- To set a timer: append [TIMER:VALUE:reminder text]. VALUE = whole minutes ([TIMER:15:check laundry]) or the exact clock time the user said ([TIMER:5:30pm:alarm] for 'at 5:30pm'). For clock times: copy it exactly, do NOT convert to minutes."
            + "\n- When asked about time or weather: you MUST state the actual values from context (e.g. the real temperature, the real time). Express them in your character voice, but the real numbers must appear in your answer. Do not replace them with metaphor or fictional equivalents."
            + "\n---", ntSpeechRule );

        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;

        new Thread( () ->
        {
            // ── Step 1: Dispatch behavior immediately from the mic transcript ──────
            // vclTranscript is already transcribed by VCL — no Whisper call needed.
            // Don't wait for system audio; keyword commands must feel instant.
            final com.group_finity.mascot.config.Configuration trigCfg =
                Main.getInstance().getConfiguration( getImageSet() );
            final boolean[] behaviorDispatched = { false };
            if( trigCfg != null )
            {
                final String behaviorMatch = findActionByKeyword( vclTranscript, trigCfg );
                if( behaviorMatch != null )
                {
                    behaviorDispatched[0] = true;
                    javax.swing.SwingUtilities.invokeLater(
                        () -> tryRunBehavior( behaviorMatch, trigCfg ) );
                }
            }

            // ── Step 2: System audio transcription (skip if behavior matched) ──────
            // buf.transcribe() blocks on WhisperProcess lock — expensive.
            // If the user issued a pure action command, skip it to avoid stalling
            // the Whisper server while the mascot is already executing the behavior.
            final String sysTranscript = ( buf != null && !behaviorDispatched[0] )
                ? buf.transcribe() : null;
            final boolean hasSys = sysTranscript != null && !sysTranscript.isBlank();

            // Strip system audio words from the mic transcript so video/speaker
            // bleed doesn't pollute what the user actually said.
            final String cleanUser = hasSys
                ? com.group_finity.mascot.assistant.AudioTranscriptBuffer
                    .stripOverlap( sysTranscript, vclTranscript )
                : vclTranscript;
            final boolean hasUser = cleanUser != null && !cleanUser.isBlank();

            // ── Step 3: Build Ollama prompt ───────────────────────────────────────
            final String userPrompt;
            final String memoryContext;

            if( hasSys && hasUser )
            {
                userPrompt   = "You were just called by name."
                    + " Here is what was happening in the last few seconds: \""
                    + sysTranscript + "\"."
                    + " The user said: \"" + cleanUser + "\"."
                    + " Respond to the user directly.";
                memoryContext = sysTranscript + " | User: " + cleanUser;
            }
            else if( hasSys )
            {
                userPrompt   = "You were just called by name."
                    + " Here is what was happening: \"" + sysTranscript + "\"."
                    + " Respond to the user directly.";
                memoryContext = sysTranscript;
            }
            else if( hasUser )
            {
                userPrompt   = "You were just called by name."
                    + " The user said: \"" + cleanUser + "\"."
                    + " Respond to them.";
                memoryContext = cleanUser;
            }
            else
            {
                userPrompt   = "The user just called your name with no other audio context."
                    + " Acknowledge them briefly, in character.";
                memoryContext = "[name trigger, no system audio]";
            }

            // ── Step 4: Guard against concurrent Ollama inference ─────────────────
            // ollamaClient is shared. If another request (fireAudioReaction, etc.)
            // is already running, skip the Ollama call — the behavior already fired.
            // Request is queued via OllamaClient — no manual claiming needed.

            javax.swing.SwingUtilities.invokeLater( () ->
            {
                ensureFreshBubble();
                final java.awt.Rectangle b = getBounds();
                if( b != null ) assistantBubble.showThinking( b );
            });

            if( hasUser && isScreenQuery( cleanUser ) )
            {
                com.group_finity.mascot.assistant.ChatLog.append( "User(voice)", cleanUser );
                handleScreenQuery( cleanUser, system, client );
                return;
            }

            com.group_finity.mascot.assistant.ChatLog.append( "User(voice)",
                hasUser ? cleanUser : "[name trigger]" );
            final String wxCtx = weatherContext( cleanUser != null ? cleanUser : "" );
            client.generate( system, userPrompt + wxCtx, new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    fireActionFromResponse( raw );
                    final String text = applyPersonaRewrites( stripActionTag( raw ) );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName, text );
                    if( !isEphemeralQuery( memoryContext ) )
                        com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                            .recordUserExchange( "[Called by name] " + memoryContext, text );
                    maybeSummarizeMemory();
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = getBounds();
                        if( b != null && assistantBubble != null )
                            assistantBubble.showResponse( text, memoryContext, b );
                    });
                }
                @Override public void onError( final String error )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( assistantBubble != null ) assistantBubble.dismiss();
                    });
                }
            });
        }, "name-trigger" ).start();
    }

    /**
     * Ensures the shared AudioTranscriptBuffer is started, then
     * transcribes the last ~15s of audio and fires a personality reaction.
     * Called from tick() — runs Whisper + Ollama on a background thread.
     */
    private void fireAudioReaction()
    {
        synchronized( AUDIO_LOCK )
        {
            if( audioBuffer == null )
            {
                audioBuffer = new com.group_finity.mascot.assistant.AudioTranscriptBuffer();
                if( !audioBuffer.start() )
                {
                    log.warning( "[Audio] Failed to start capture — audio reactions disabled." );
                    audioBuffer = null;
                    return;
                }
            }
        }

        final com.group_finity.mascot.assistant.AudioTranscriptBuffer buf = audioBuffer;
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : getImageSet();
        final String personality = getPersonalityQuick( cfg, mascotName );
        final String audioSpeechRule = getSpeechRule( cfg );

        final String audioPeerCtx = com.group_finity.mascot.assistant.MascotSpeechRegistry
            .buildContext( getImageSet() );

        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;
        final String vModel = Main.getInstance().getProperties()
            .getProperty( "VisionModel", "moondream" );

        // Snapshot window title now — focus may shift during Whisper's run
        final String windowTitleSnapshot = lastActiveWindowTitle;

        // Whisper runs first on background thread — only show bubble if transcript is good
        new Thread( () ->
        {
            final com.group_finity.mascot.assistant.AudioTranscriptBuffer.TranscriptResult result =
                buf.transcribeWithSource();
            if( result == null || result.transcript == null || result.transcript.length() < 8 )
            {
                log.info( "[Audio] Nothing useful heard, skipping." );
                return;
            }
            final String transcript = result.transcript;
            final String source     = result.source; // may be null
            log.info( "[Audio] Transcript: " + transcript
                + ( source != null ? " (source: " + source + ")" : "" ) );

            if( isAudioTranscriptDuplicate( transcript, lastAudioTranscriptText ) )
            {
                log.info( "[Audio] Near-duplicate transcript — skipping reaction." );
                return;
            }
            lastAudioTranscriptText = transcript;

            // Attempt screen capture — enriches the reaction with visual context
            String capturedBase64 = null;
            try { capturedBase64 = captureScreenBase64(); }
            catch( final Exception e )
            { log.fine( "[Audio] Screen capture failed, falling back to audio-only: " + e.getMessage() ); }
            final boolean hasScreen = capturedBase64 != null;

            final String screenRule = hasScreen
                ? "\n- You overheard this audio AND have a snapshot of the user's screen."
                  + "\n- React to the combination — let what you heard and what you see inform each other."
                : "\n- This is an unprompted reaction to overheard audio only. You cannot see the screen."
                  + "\n- React only to what was said.";

            final String system = withSpeechReminder( personality
                + ( audioPeerCtx.isEmpty() ? "" : "\n\nOther desktop mascots present:" + audioPeerCtx )
                + "\n\n---"
                + "\nRULES (override everything else):"
                + ( audioSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + audioSpeechRule )
                + "\n- Reply in ONE sentence. 15 words maximum."
                + screenRule
                + "\n- Be brief, natural, in-character. No greetings, no filler."
                + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
                + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
                + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
                + "\n---", audioSpeechRule );

            // Good transcript — now show thinking and send to Ollama
            javax.swing.SwingUtilities.invokeLater( () ->
            {
                ensureFreshBubble();
                final java.awt.Rectangle b = getBounds();
                if( b != null ) assistantBubble.showThinking( b );
            });

            final boolean isBrowser =
                com.group_finity.mascot.assistant.AudioSessionUtil.BROWSER.equals( source );

            // For browsers, include the page/tab title so Ollama knows what's playing
            final String tabContext;
            if( isBrowser && windowTitleSnapshot != null && !windowTitleSnapshot.isEmpty() )
                tabContext = " The browser tab title is: \"" + windowTitleSnapshot + "\".";
            else
                tabContext = "";

            final String userSpeech = result.userSpeech; // may be null

            final String sourceHint = source != null ? " from " + source : "";
            final String mediaHint  = isBrowser
                ? " This is likely a video, stream, or music track playing in a browser."
                  + " The transcript may be speech, dialogue, commentary, or song lyrics."
                  + tabContext
                : "";
            final String userHint = userSpeech != null
                ? " The user also said: \"" + userSpeech + "\"."
                : "";
            final String reactionScope = userSpeech != null
                ? " React naturally to both what was playing and what the user said."
                : " Refer to whoever is speaking or what is playing as \"they\" or \"them\" — not \"you\".";
            final String prompt =
                "You just overheard this audio" + sourceHint + ": \"" + transcript + "\"."
                + userHint
                + mediaHint
                + " Make one short, in-character reaction."
                + reactionScope
                + " Do not repeat what was said verbatim.";

            final String audioContext = source != null
                ? "[Overheard audio from " + source + "] " + transcript
                : "[Overheard audio] " + transcript;

            // Guard: skip if a name-trigger or other Ollama request is already in flight.
            // Request is queued via OllamaClient — no manual claiming needed.

            // Summarization needs ~200-300 tokens: 5 facts + TONE + PEER_TONE lines.
            // The default 80-token cap cuts off the response before TONE: is reached.
            final OllamaClient.Callback audioCb = new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    fireActionFromResponse( raw );
                    final String text = trimToFirstSentence( applyPersonaRewrites( stripActionTag( raw ) ) );
                    final String audioSource = source != null ? source : "audio";
                    com.group_finity.mascot.assistant.ChatLog.append( audioSource,
                        "\"" + transcript.substring( 0, Math.min( 300, transcript.length() ) ) + "\"" );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(to: " + audioSource + ")", text );
                    final String sourcePrefix = source != null ? " from " + source : "";
                    final String userNote = userSpeech != null
                        ? " [User: " + userSpeech.substring( 0, Math.min( 120, userSpeech.length() ) ) + "]"
                        : "";
                    com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                        .addFact( "[Observed] Heard" + sourcePrefix + ": "
                            + transcript.substring( 0, Math.min( 150, transcript.length() ) )
                            + userNote
                            + " | Reaction: " + text );
                    com.group_finity.mascot.assistant.MascotSpeechRegistry
                        .record( getImageSet(), mascotName, text, 0 );
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = getBounds();
                        if( b != null && assistantBubble != null )
                            assistantBubble.showResponse( text, audioContext, b );
                    });
                }
                @Override public void onError( final String error )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( assistantBubble != null ) assistantBubble.dismiss();
                    });
                }
            };

            if( hasScreen )
                client.generateWithImage( system, prompt, capturedBase64, vModel, audioCb );
            else
                client.generate( system, prompt, audioCb );
        }, "audio-reaction" ).start();
    }

    private void fireSpontaneousComment( final String windowTitle )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( getImageSet( ) );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : getImageSet();
        final String personality = getPersonality( cfg, mascotName );

        final String spontSpeechRule = getSpeechRule( cfg );
        final String peerCtx = com.group_finity.mascot.assistant.MascotSpeechRegistry
            .buildContext( getImageSet() );

        final String system = withSpeechReminder( getPersonalityQuick( cfg, mascotName )
            + ( peerCtx.isEmpty() ? "" : "\n\nOther desktop mascots present:" + peerCtx )
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( spontSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + spontSpeechRule )
            + "\n- Reply in ONE sentence. 15 words maximum."
            + "\n- This is an unprompted observation. Be brief and natural."
            + "\n- No greetings, no questions, no filler."
            + "\n- Do not reuse or echo words directly from the window title."
            + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
            + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
            + "\n---", spontSpeechRule );

        final String audioCtxSpont = audioSnapshotContext();
        final String prompt =
            "The user's active window is: \"" + windowTitle + "\"."
            + ( audioCtxSpont.isEmpty() ? "" : " " + audioCtxSpont + "." )
            + " Make one short in-character observation, addressing the user directly as \"you\" — not in third person."
            + " Do not repeat the raw title string.";

        // All Swing calls must be on the EDT. fireSpontaneousComment runs on the
        // manager thread, so dispatch bubble creation and showThinking via invokeLater.
        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;

        javax.swing.SwingUtilities.invokeLater( ( ) ->
        {
            ensureFreshBubble();
            final java.awt.Rectangle b = getBounds( );
            if( b != null ) assistantBubble.showThinking( b );
        } );

        client.generate( system, prompt, new OllamaClient.Callback( )
        {
            @Override public void onResponse( final String raw )
            {
                fireActionFromResponse( raw );
                final String text = trimToFirstSentence( applyPersonaRewrites( stripActionTag( raw ) ) );
                com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(to: " + windowTitle + ")", text );
                com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                    .addFact( "[Observed] Window: " + windowTitle + " | Reaction: " + text );
                com.group_finity.mascot.assistant.MascotSpeechRegistry
                    .record( getImageSet(), mascotName, text, 0 );
                final String wCtx = "[Active window] " + windowTitle;
                javax.swing.SwingUtilities.invokeLater( ( ) ->
                {
                    final java.awt.Rectangle b = getBounds( );
                    if( b != null && assistantBubble != null )
                        assistantBubble.showResponse( text, wCtx, b );
                } );
            }
            @Override public void onError( final String error )
            {
                javax.swing.SwingUtilities.invokeLater( ( ) ->
                {
                    if( assistantBubble != null ) assistantBubble.dismiss( );
                } );
            }
        } );
    }
    private void fireVisionReaction()
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : getImageSet();
        final String personality = getPersonalityQuick( cfg, mascotName );
        final String visionSpeechRule = getSpeechRule( cfg );

        final String peerCtx = com.group_finity.mascot.assistant.MascotSpeechRegistry
            .buildContext( getImageSet() );

        final String system = withSpeechReminder( personality
            + ( peerCtx.isEmpty() ? "" : "\n\nOther desktop mascots present:" + peerCtx )
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( visionSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + visionSpeechRule )
            + "\n- Reply in ONE sentence. 15 words maximum."
            + "\n- This is an unprompted glance at the user's screen. Be brief, natural, in-character."
            + "\n- No greetings, no questions, no filler."
            + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
            + "\n---", visionSpeechRule );

        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;
        final String vModel = Main.getInstance().getProperties()
            .getProperty( "VisionModel", "moondream" );

        new Thread( () ->
        {
            try
            {
                final String base64 = captureScreenBase64();
                final String audioCtxVision = audioSnapshotContext();
                final String prompt =
                    "You just glanced at the user's screen."
                    + ( audioCtxVision.isEmpty() ? "" : " " + audioCtxVision + "." )
                    + " Make one brief, in-character observation about what you see."
                    + " Address the user directly as \"you\". One sentence only.";

                javax.swing.SwingUtilities.invokeLater( () ->
                {
                    ensureFreshBubble();
                    final java.awt.Rectangle b = getBounds();
                    if( b != null ) assistantBubble.showThinking( b );
                });

                client.generateWithImage( system, prompt, base64, vModel,
                    new OllamaClient.Callback()
                    {
                        @Override public void onResponse( final String raw )
                        {
                            fireActionFromResponse( raw );
                            final String text = trimToFirstSentence( applyPersonaRewrites( stripActionTag( raw ) ) );
                            com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(screen glance)", text );
                            com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() )
                                .addFact( "[Observed] Screen glance | Reaction: " + text );
                            com.group_finity.mascot.assistant.MascotSpeechRegistry
                                .record( getImageSet(), mascotName, text, 0 );
                            javax.swing.SwingUtilities.invokeLater( () ->
                            {
                                final java.awt.Rectangle b = getBounds();
                                if( b != null && assistantBubble != null )
                                    assistantBubble.showResponse( text, "[Screen glance]", b );
                            });
                        }
                        @Override public void onError( final String error )
                        {
                            javax.swing.SwingUtilities.invokeLater( () ->
                            {
                                if( assistantBubble != null ) assistantBubble.dismiss();
                            });
                        }
                    });
            }
            catch( final Exception e )
            {
                log.warning( "[Vision] Spontaneous capture failed: " + e.getMessage() );
            }
        }, "vision-reaction" ).start();
    }

    /**
     * Returns the title of the current foreground window, or an empty string if unavailable.
     */
    private String getActiveWindowTitle( )
    {
        try
        {
            final com.group_finity.mascot.environment.Environment env =
                com.group_finity.mascot.NativeFactory.getInstance( ).getEnvironment( );
            if( env == null ) return "";
            final String title = env.getForegroundWindowTitle( );
            return title != null ? title.trim( ) : "";
        }
        catch( final Exception e )
        {
            return "";
        }
    }

    /**
     * Returns the personality string for this mascot.
     * Reads <Personality> from the mascot's Information XML block if present;
     * falls back to a generic desktop-assistant prompt.
     */
    /** Returns the SpeechRule line for injection into RULES, or empty string if not set. */
    private static String getSpeechRule( final com.group_finity.mascot.config.Configuration cfg )
    {
        if( cfg == null ) return "";
        final String r = cfg.getInformation( "SpeechRule" );
        return ( r != null && !r.isBlank() ) ? r.trim() : "";
    }

    /** Appends a speech rule reminder after the closing --- for recency reinforcement. */
    private static String withSpeechReminder( final String system, final String speechRule )
    {
        if( speechRule.isEmpty() ) return system;
        return system + "\nFinal reminder: " + speechRule;
    }

    private String getPersonality( final com.group_finity.mascot.config.Configuration cfg,
                                   final String mascotName )
    {
        if( cfg != null )
        {
            final String p = cfg.getInformation( "Personality" );
            if( p != null && !p.trim( ).isEmpty( ) )
                return p.trim( );
        }
        return "You are " + mascotName + ", a small desktop mascot assistant living on the user's screen. "
             + "You are helpful, cheerful, and a little quirky. "
             + "You are aware that you are a tiny animated character on their desktop.";
    }

    private String getPersonalityQuick( final com.group_finity.mascot.config.Configuration cfg,
                                        final String mascotName )
    {
        if( cfg != null )
        {
            final String brief = cfg.getInformation( "PersonalityBrief" );
            if( brief != null && !brief.trim().isEmpty() )
                return brief.trim();
        }
        return getPersonality( cfg, mascotName );
    }

    private static String trimToFirstSentence( final String text )
    {
        if( text == null || text.length() < 10 ) return text;
        final java.util.regex.Matcher m =
            java.util.regex.Pattern.compile( "[.!?](?:\\s|$)" ).matcher( text );
        while( m.find() )
        {
            if( m.start() >= 5 )
                return text.substring( 0, m.end() ).trim();
        }
        return text;
    }

    /** True if the text is a bare non-answer (Noted. / Acknowledged. / etc.) that adds nothing. */
    private static boolean isTrivialAcknowledgement( final String text )
    {
        if( text == null ) return false;
        final String norm = text.trim().replaceAll( "[.!?,\"']", "" ).toLowerCase();
        return norm.equals( "noted" )
            || norm.equals( "acknowledged" )
            || norm.equals( "understood" )
            || norm.equals( "affirmative" )
            || norm.equals( "confirmed" )
            || norm.equals( "copy that" )
            || norm.equals( "roger" )
            || norm.equals( "indeed" );
    }

    void tick( )
    {
        if( isAnimating( ) )
        {
            if( getBehavior( ) != null )
            {
                try
                {
                    getBehavior( ).next( );
                }
                catch( final CantBeAliveException e )
                {
                    log.log( Level.SEVERE, "Fatal Error.", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "CouldNotGetNextBehaviourErrorMessage" ), e );
                    dispose( );
                }

                // Dynamic tint: re-evaluate expressions each tick so tint tracks sensors
                // even when the Tint action is no longer the active action.
                if( tintVarMap != null && ( tintTargetVar != null || tintColorVar != null ) )
                {
                    if( tintTargetVar != null )
                    {
                        try
                        {
                            tintTargetVar.initFrame( );
                            double t = ( (Number) tintTargetVar.get( tintVarMap ) ).doubleValue( );
                            tintTargetOpacity = (float) Math.max( 0.0, Math.min( 1.0, t ) );
                        }
                        catch( Exception e ) { /* ignore */ }
                    }
                    if( tintColorVar != null )
                    {
                        try
                        {
                            tintColorVar.initFrame( );
                            Object hexObj = tintColorVar.get( tintVarMap );
                            if( hexObj == null ) throw new Exception( "null color" );
                            String hex = hexObj.toString( );
                            if( hex.startsWith( "#" ) ) hex = hex.substring( 1 );
                            if( hex.length( ) >= 6 )
                            {
                                tintTargetR = Math.max( 0, Math.min( 255, Integer.parseInt( hex.substring( 0, 2 ), 16 ) ) );
                                tintTargetG = Math.max( 0, Math.min( 255, Integer.parseInt( hex.substring( 2, 4 ), 16 ) ) );
                                tintTargetB = Math.max( 0, Math.min( 255, Integer.parseInt( hex.substring( 4, 6 ), 16 ) ) );
                            }
                        }
                        catch( Exception e ) { /* ignore */ }
                        // Lerp current channels toward target, then rebuild tintColor
                        tintCurrentR += ( tintTargetR - tintCurrentR ) * tintLerpFactor;
                        tintCurrentG += ( tintTargetG - tintCurrentG ) * tintLerpFactor;
                        tintCurrentB += ( tintTargetB - tintCurrentB ) * tintLerpFactor;
                        tintColor = new java.awt.Color( (int) tintCurrentR, (int) tintCurrentG, (int) tintCurrentB );
                    }
                }
                // Lerp current opacity toward target
                if( Math.abs( tintCurrentOpacity - tintTargetOpacity ) > 0.001f )
                    tintCurrentOpacity += ( tintTargetOpacity - tintCurrentOpacity ) * tintLerpFactor;
                else
                    tintCurrentOpacity = tintTargetOpacity;

                // Track last known good on-screen position for recovery.
                final java.awt.Rectangle b = getBounds( );
                if( b != null && environment.getScreen( ).toRectangle( ).intersects( b ) )
                {
                    if( savedAnchor == null ) savedAnchor = new Point();
                    savedAnchor.x = anchor.x;
                    savedAnchor.y = anchor.y;
                    savedBehaviorName = getCurrentBehaviorName( );
                }

                setTime( getTime( ) + 1 );
            }
            
            if( debugWindow != null )
            {
                debugWindow.setBehaviour( behavior.toString( ).substring( 9, behavior.toString( ).length( ) - 1 ).replaceAll( "([a-z])(IE)?([A-Z])", "$1 $2 $3" ).replaceAll( "  ", " " ) );
                debugWindow.setShimejiX( anchor.x );
                debugWindow.setShimejiY( anchor.y );
                debugWindow.setDeltaX( lastDeltaX );
                debugWindow.setDeltaY( lastDeltaY );
                
                Area activeWindow = environment.getActiveIE( );
                debugWindow.setWindowTitle( environment.getActiveIETitle( ) );
                debugWindow.setWindowX( activeWindow.getLeft( ) );
                debugWindow.setWindowY( activeWindow.getTop( ) );
                debugWindow.setWindowWidth( activeWindow.getWidth( ) );
                debugWindow.setWindowHeight( activeWindow.getHeight( ) );
                
                Area workArea = environment.getWorkArea( );
                debugWindow.setEnvironmentX( workArea.getLeft( ) );
                debugWindow.setEnvironmentY( workArea.getTop( ) );
                debugWindow.setEnvironmentWidth( workArea.getWidth( ) );
                debugWindow.setEnvironmentHeight( workArea.getHeight( ) );
                debugWindow.setCpuTemp( environment.getCpuTemp( ) );
                debugWindow.setCpuLoad( environment.getCpuLoad( ) );
                debugWindow.setGpuTemp( environment.getGpuTemp( ) );
                debugWindow.setGpuLoad( environment.getGpuLoad( ) );
                debugWindow.setRamLoad( environment.getRamLoad( ) );
                debugWindow.setBatteryLevel( environment.getBatteryLevel( ) );
                debugWindow.setPopulation( getTotalCount( ), getCount( ) );
                if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
                {
                    com.group_finity.mascot.action.Action act = ( (com.group_finity.mascot.behavior.UserBehavior)behavior ).getAction( );
                    // Drill into ComplexAction (Sequence/Select) to get the active leaf action
                    while( act instanceof com.group_finity.mascot.action.ComplexAction )
                    {
                        com.group_finity.mascot.action.Action child = ( (com.group_finity.mascot.action.ComplexAction)act ).getCurrentChildAction( );
                        if( child == null ) break;
                        act = child;
                    }
                    if( act instanceof com.group_finity.mascot.action.ActionBase )
                    {
                        com.group_finity.mascot.action.ActionBase ab = (com.group_finity.mascot.action.ActionBase)act;
                        try
                        {
                            int remaining;
                            if( act instanceof com.group_finity.mascot.action.Move )
                            {
                                // For Move/Walk: live distance-to-target / velocity countdown
                                remaining = ( (com.group_finity.mascot.action.Move)act ).getEstimatedDuration( );
                            }
                            else
                            {
                                int duration = ab.getDuration( );
                                if( duration == Integer.MAX_VALUE )
                                {
                                    // No Duration attr: try animation pose sum (covers Animate type)
                                    try
                                    {
                                        com.group_finity.mascot.animation.Animation anim = ab.getAnimation( );
                                        duration = ( anim != null ) ? anim.getDuration( ) : Integer.MAX_VALUE;
                                    }
                                    catch( Exception ex2 ) { }
                                }
                                remaining = ( duration == Integer.MAX_VALUE )
                                    ? Integer.MAX_VALUE
                                    : duration - ab.getTime( );
                            }
                            debugWindow.setActionTimer( remaining );
                        }
                        catch( com.group_finity.mascot.exception.VariableException ex ) { }
                    }
                }
            }
        }

        // ── Behavior tooltip overlay ──────────────────────────────────────────
        if( tooltipEnabled && isAnimating( ) )
        {
            final String bname = getCurrentBehaviorName( );
            final java.awt.Rectangle bounds = getBounds( );
            if( bname != null && bounds != null )
            {
                // Compute the action timer the same way the debug window does
                int remaining = Integer.MAX_VALUE;
                if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
                {
                    com.group_finity.mascot.action.Action act =
                        ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getAction( );
                    while( act instanceof com.group_finity.mascot.action.ComplexAction )
                    {
                        com.group_finity.mascot.action.Action child =
                            ( (com.group_finity.mascot.action.ComplexAction) act ).getCurrentChildAction( );
                        if( child == null ) break;
                        act = child;
                    }
                    if( act instanceof com.group_finity.mascot.action.ActionBase )
                    {
                        com.group_finity.mascot.action.ActionBase ab =
                            (com.group_finity.mascot.action.ActionBase) act;
                        try
                        {
                            if( act instanceof com.group_finity.mascot.action.Move )
                            {
                                remaining = ( (com.group_finity.mascot.action.Move) act ).getEstimatedDuration( );
                            }
                            else
                            {
                                int duration = ab.getDuration( );
                                if( duration == Integer.MAX_VALUE )
                                {
                                    try
                                    {
                                        com.group_finity.mascot.animation.Animation anim = ab.getAnimation( );
                                        duration = ( anim != null ) ? anim.getDuration( ) : Integer.MAX_VALUE;
                                    }
                                    catch( Exception ignored ) { }
                                }
                                remaining = ( duration == Integer.MAX_VALUE )
                                    ? Integer.MAX_VALUE
                                    : duration - ab.getTime( );
                            }
                        }
                        catch( com.group_finity.mascot.exception.VariableException ignored ) { }
                    }
                }
                if( mascotTooltip == null )
                    mascotTooltip = new MascotTooltip( );
                mascotTooltip.update( getBehaviorHistory( ), bname, remaining, bounds, anchor.x, anchor.y );
            }
        }
        else if( !tooltipEnabled && mascotTooltip != null )
        {
            mascotTooltip.dispose( );
            mascotTooltip = null;
        }

        // ── Assistant overlay repositioning + spontaneous comments ──────────────
        // Reposition runs unconditionally so Say-action bubbles follow the mascot
        // even when assistant mode is off.
        {
            final java.awt.Rectangle ab = getBounds( );
            if( ab != null )
            {
                // Prune fully despawned bubbles; reposition the live ones — single pass.
                java.util.List<com.group_finity.mascot.assistant.AssistantBubble> dead = null;
                for( final com.group_finity.mascot.assistant.AssistantBubble b : activeBubbles )
                {
                    if( b.isFullyDespawned() )
                    {
                        if( dead == null ) dead = new java.util.ArrayList<>();
                        dead.add( b );
                    }
                    else
                        b.reposition( ab );
                }
                if( dead != null )
                {
                    activeBubbles.removeAll( dead );
                    if( dead.contains( assistantBubble ) ) assistantBubble = null;
                    if( dead.contains( timerBubble ) )     timerBubble     = null;
                    final java.util.List<com.group_finity.mascot.assistant.AssistantBubble> toDispose = dead;
                    javax.swing.SwingUtilities.invokeLater( () -> toDispose.forEach(
                        com.group_finity.mascot.assistant.AssistantBubble::dispose ) );
                }
                if( activeDialog != null )
                    activeDialog.reposition( ab );
            }
        }
        if( assistantMode )
        {

            // Spontaneous window comment — counter advances every tick regardless of bubble
            // visibility. AssistantBubble stacks messages so a visible bubble is not a blocker.
            // Only gate the actual fire on dialogFree to avoid interrupting active user input.
            final boolean dialogFree = activeDialog == null;
            if( spontaneousNextTrigger < 0 )
            {
                final int r = SPONTANEOUS_MAX_TICKS - SPONTANEOUS_MIN_TICKS;
                spontaneousNextTrigger = SPONTANEOUS_MIN_TICKS
                    + Math.max( spontaneousRng.nextInt(r), spontaneousRng.nextInt(r) );
            }
            spontaneousTickCounter++;
            if( spontaneousTickCounter >= spontaneousNextTrigger && dialogFree )
            {
                spontaneousTickCounter = 0;
                final int range = SPONTANEOUS_MAX_TICKS - SPONTANEOUS_MIN_TICKS;
                spontaneousNextTrigger = SPONTANEOUS_MIN_TICKS
                    + Math.max( spontaneousRng.nextInt( range ),
                                spontaneousRng.nextInt( range ) );
                // Lazily init client so spontaneous comments work even if
                // the user has never manually opened the input dialog.
                if( ollamaClient == null ) ollamaClient = createOllamaClient();

                // GetWindowTextW sends WM_GETTEXT cross-process via SendMessage and can
                // block if the foreground app is momentarily busy. Move off the tick thread
                // so it can't hold up animations — same pattern as fireVisionReaction().
                new Thread( () ->
                {
                    final String title = getActiveWindowTitle();
                    log.info( "[Spontaneous] tick fired — title=\"" + title
                        + "\" last=\"" + lastSpontaneousTitle + "\"" );
                    if( title != null && !title.isEmpty()
                            && !title.equals( lastSpontaneousTitle ) )
                    {
                        final long now = System.currentTimeMillis();
                        final long last = globalSpontaneousLastFiredMs.get();
                        if( now - last >= GLOBAL_SPONTANEOUS_COOLDOWN_MS
                                && globalSpontaneousLastFiredMs.compareAndSet( last, now ) )
                        {
                            lastSpontaneousTitle = title;
                            fireSpontaneousComment( title );
                        }
                        else
                        {
                            log.info( "[Spontaneous] Global cooldown active — skipping for " + getImageSet() );
                        }
                    }
                }, "spontaneous-title-fetch" ).start();
            }

            // Audio transcript reaction — independent cadence, not gated on bubbleFree
            // (AssistantBubble stacks messages so a visible bubble is fine)
            // Start capture eagerly so the buffer is full before the first tick fires.
            if( audioBuffer == null )
            {
                synchronized( AUDIO_LOCK )
                {
                    if( audioBuffer == null )
                    {
                        audioBuffer = new com.group_finity.mascot.assistant.AudioTranscriptBuffer();
                        if( !audioBuffer.start() )
                        {
                            log.warning( "[Audio] Failed to start capture." );
                            audioBuffer = null;
                        }
                    }
                }
            }
            if( audioNextTrigger < 0 )
            {
                final int ar = AUDIO_MAX_TICKS - AUDIO_MIN_TICKS;
                audioNextTrigger = AUDIO_MIN_TICKS
                    + Math.max( spontaneousRng.nextInt(ar), spontaneousRng.nextInt(ar) );
            }
            audioTickCounter++;
            if( audioTickCounter >= audioNextTrigger )
            {
                audioTickCounter = 0;
                // Max of two rolls: usually longer, occasionally short
                final int audioRange = AUDIO_MAX_TICKS - AUDIO_MIN_TICKS;
                audioNextTrigger = AUDIO_MIN_TICKS
                    + Math.max( spontaneousRng.nextInt( audioRange ),
                                spontaneousRng.nextInt( audioRange ) );
                log.info( "[Audio] Tick fired for " + getImageSet() );
                if( audioBuffer != null )
                {
                    final long nowA = System.currentTimeMillis();
                    if( nowA - globalAudioLastFiredMs.get() >= GLOBAL_AUDIO_COOLDOWN_MS )
                    {
                        globalAudioLastFiredMs.set( nowA );
                        fireAudioReaction();
                    }
                    else
                    {
                        log.info( "[Audio] Global cooldown active — skipping for " + getImageSet() );
                    }
                }
            }

            // Vision screen-glance reaction — gated on dialogFree (don't interrupt user)
            if( visionNextTrigger < 0 )
            {
                final int vr = VISION_MAX_TICKS - VISION_MIN_TICKS;
                visionNextTrigger = VISION_MIN_TICKS
                    + Math.max( spontaneousRng.nextInt(vr), spontaneousRng.nextInt(vr) );
            }
            visionTickCounter++;
            if( visionTickCounter >= visionNextTrigger && dialogFree )
            {
                visionTickCounter = 0;
                final int visionRange = VISION_MAX_TICKS - VISION_MIN_TICKS;
                visionNextTrigger = VISION_MIN_TICKS
                    + Math.max( spontaneousRng.nextInt( visionRange ),
                                spontaneousRng.nextInt( visionRange ) );
                log.info( "[Vision] Spontaneous tick fired for " + getImageSet() );
                final long nowV = System.currentTimeMillis();
                if( nowV - globalVisionLastFiredMs.get() >= GLOBAL_VISION_COOLDOWN_MS )
                {
                    globalVisionLastFiredMs.set( nowV );
                    fireVisionReaction();
                }
                else
                {
                    log.info( "[Vision] Global cooldown active — skipping for " + getImageSet() );
                }
            }
        }
    }

    private void applyTintToScratch( java.awt.image.BufferedImage src )
    {
        int w = src.getWidth( );
        int h = src.getHeight( );
        if( tintScratchBuf == null || tintScratchBuf.getWidth( ) != w || tintScratchBuf.getHeight( ) != h )
        {
            tintScratchBuf    = new java.awt.image.BufferedImage( w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE );
            tintScratchNative = NativeFactory.getInstance( ).newNativeImage( tintScratchBuf );
        }
        java.awt.Graphics2D g = tintScratchBuf.createGraphics( );
        try
        {
            g.setComposite( java.awt.AlphaComposite.Src );
            g.drawImage( src, 0, 0, null );
            g.setComposite( java.awt.AlphaComposite.SrcAtop );
            g.setColor( new java.awt.Color(
                tintColor.getRed( ), tintColor.getGreen( ), tintColor.getBlue( ),
                Math.max( 0, Math.min( 255, (int)( tintCurrentOpacity * 255 ) ) ) ) );
            g.fillRect( 0, 0, w, h );
        }
        finally
        {
            g.dispose( );
        }
        tintScratchNative.updatePixels( tintScratchBuf );
    }

    public void apply( )
    {
        if (isAnimating())
        {
            // currentScale is updated directly by Scale.tick() — nothing to do here

            // Make sure there's an image
            if (getImage() != null)
            {
                MascotImage displayImage = getImage();
                java.awt.Rectangle bounds = getBounds();

                if( Math.abs( currentScale - 1.0 ) > 0.005 )
                {
                    if( scalables == null )
                    {
                        // Cap at 16 frames to bound memory use
                        scalables = new java.util.LinkedHashMap<MascotImage, com.group_finity.mascot.image.ScalableNativeImage>( 16, 0.75f, true )
                        {
                            protected boolean removeEldestEntry( java.util.Map.Entry<MascotImage, com.group_finity.mascot.image.ScalableNativeImage> eldest )
                            { return size() > 16; }
                        };
                    }

                    // When rounded scale steps, kick off builds for ALL cached animation frames at
                    // once. Without this, each frame builds lazily as it first becomes active,
                    // so frames cycle through different scales for up to one full animation cycle.
                    double roundedScale = Math.round( currentScale * 10.0 ) / 10.0;
                    if( roundedScale != lastDisplayScale )
                    {
                        for( com.group_finity.mascot.image.ScalableNativeImage sni : scalables.values() )
                            sni.get( roundedScale );
                        lastDisplayScale = roundedScale;
                    }

                    com.group_finity.mascot.image.ScalableNativeImage scalable =
                        scalables.computeIfAbsent( getImage(), img -> new com.group_finity.mascot.image.ScalableNativeImage( img, getImageSet() ) );

                    MascotImage scaled = scalable.get( currentScale );
                    if( scaled != null )
                    {
                        lastScaledImage = scaled;
                        lastBaseImageHeight = scalable.getSource( ).getSize( ).height;
                        lastBaseImageWidth  = scalable.getSource( ).getSize( ).width;
                        displayImage = scaled;
                        int w  = displayImage.getSize().width;
                        int h  = displayImage.getSize().height;
                        bounds = new java.awt.Rectangle( getAnchor().x - renderCX( w ), getAnchor().y - renderCY( ), w, h );
                    }
                    else if( lastScaledImage != null )
                    {
                        // Still building new scale — hold last good frame, no flicker
                        displayImage = lastScaledImage;
                        int w  = displayImage.getSize().width;
                        int h  = displayImage.getSize().height;
                        bounds = new java.awt.Rectangle( getAnchor().x - renderCX( w ), getAnchor().y - renderCY( ), w, h );
                    }
                }
                else
                {
                    scalables = null;
                    lastScaledImage = null;
                    lastBaseImageHeight = -1;
                    lastBaseImageWidth  = -1;
                    lastDisplayScale = 0.0;
                }

                // Set Images — apply tint overlay if active
                if( tintColor != null && tintCurrentOpacity > 0.001f )
                {
                    java.awt.image.BufferedImage src = displayImage.getBufferedImage( );
                    if( src != null )
                    {
                        applyTintToScratch( src );
                        getWindow( ).setImage( tintScratchNative );
                    }
                    else
                    {
                        getWindow( ).setImage( displayImage.getImage( ) );
                    }
                }
                else
                {
                    getWindow( ).setImage( displayImage.getImage( ) );
                }

                // Display
                if (!getWindow().asComponent().isVisible())
                {
                    getWindow().asComponent().setVisible(true);
                }

                // Reposition and redraw atomically — prevents one-frame glitch when ImageAnchor changes.
                // setBounds is called after so the Swing component stays in sync for layout/bounds queries.
                getWindow().updateImage(bounds);
                getWindow().asComponent().setBounds(bounds);
            }
            else
            {
                if( getWindow().asComponent().isVisible() )
                {
                    getWindow().asComponent().setVisible(false);
                }
            }
            
            // play sound if requested
            if( !Sounds.isMuted( ) && sound != null && Sounds.contains( sound ) )
            {
                synchronized( log )
                {
                    Clip clip = Sounds.getSound( sound );
                    if( !clip.isRunning( ) )
                    {
                        clip.stop( );
                        clip.setMicrosecondPosition( 0 );
                        clip.start( );
                    }
                }
            }
        }
    }

    /**
     * Show a text bubble with arbitrary text — callable from action classes (e.g. Say).
     * Safe to call from any thread; dispatches to EDT internally.
     */
    /** Called from SettingsWindow.Done — refreshes bubble display settings. */
    /**
     * If enough exchanges have accumulated, ask Ollama to distill them into
     * a few key facts and update the memory. Runs on a background thread.
     */
    /**
     * Validates a tone string from summarization output.
     * Rejects single-word pronouns, articles, verbs, and other non-adjective garbage
     * that LLMs sometimes emit when they don't follow the format instruction.
     * A valid tone is 2-6 words, contains no banned pronouns as the entire value,
     * and contains at least one letter beyond a single word.
     */
    /** Parse a "Name:tone" or "Name: tone" string and apply it to memory if valid. */
    private void parsePeerToneLine( final String payload,
                                     final com.group_finity.mascot.assistant.MascotMemory memory )
    {
        final int colon = payload.indexOf( ':' );
        if( colon <= 0 ) return;
        final String peerName = payload.substring( 0, colon ).trim();
        final String peerTone = payload.substring( colon + 1 ).trim().toLowerCase();
        if( peerName.isEmpty() ) return;
        if( peerName.equalsIgnoreCase( getImageSet() ) ) return;
        if( isValidTone( peerTone ) )
        {
            memory.setPeerTone( peerName, peerTone );
            log.warning( "[Memory] Peer tone set: " + peerName + " = " + peerTone );
        }
        else
        {
            log.warning( "[Memory] Rejected invalid peer tone for "
                + peerName + ": \"" + peerTone + "\"" );
        }
    }

    private static boolean isValidTone( final String tone )
    {
        if( tone == null || tone.isBlank() ) return false;
        final String t = tone.trim().toLowerCase();
        // Must be at least 2 words OR a single word that's clearly an adjective (>=5 chars)
        final String[] words = t.split( "\\s+" );
        if( words.length == 1 && words[0].length() < 5 ) return false;
        if( words.length > 6 ) return false;  // too long — probably a sentence slipped through
        // Reject if the entire value is a pronoun or article
        final java.util.Set<String> banned = new java.util.HashSet<>( java.util.Arrays.asList(
            "i","me","my","mine","myself","you","your","yours","yourself",
            "he","him","his","himself","she","her","hers","herself",
            "it","its","itself","we","us","our","ours","ourselves",
            "they","them","their","theirs","themselves",
            "this","that","these","those","a","an","the","is","are","was","were"
        ) );
        if( words.length == 1 && banned.contains( words[0] ) ) return false;
        // Must contain at least one actual letter sequence (not just punctuation/numbers)
        return t.matches( ".*[a-z]{2,}.*" );
    }

    private void maybeSummarizeMemory()
    {
        final com.group_finity.mascot.assistant.MascotMemory memory =
            com.group_finity.mascot.assistant.MascotMemory.forImageSet( getImageSet() );
        if( !memory.needsSummarization() ) return;
        // Do NOT reset here — reset only on successful Ollama response.
        // Resetting before the call means a failed/errored summarization silently
        // resets the counter and summarization won't retry for another 20 interactions.

        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : "mascot";

        final String currentFacts = memory.buildMemoryBlock();
        final String personality  = ( cfg != null && cfg.getInformation( "Personality" ) != null )
            ? cfg.getInformation( "Personality" ) : "";
        final String personalitySnip = personality.isEmpty() ? ""
            : personality.substring( 0, Math.min( 300, personality.length() ) );

        if( ollamaClient == null ) ollamaClient = createOllamaClient();
        final OllamaClient client = ollamaClient;

        new Thread( () ->
        {
            final String peerContext = memory.buildAllPeerMemoryBlocks();
            final boolean hasPeers    = !peerContext.isEmpty();

            final String system = "You are a memory distillation assistant. "
                + "Given a memory block and conversation history, extract EXACTLY 5 concise "
                + "factual observations about the user. No more than 5. One per line, no bullet points, "
                + "no numbering. Keep each fact under 12 words. "
                + "Also output a 2-4 word adjective phrase describing the emotional tone of the relationship "
                + "toward the human user. The tone must be an adjective phrase, for example: "
                + "\"cautiously warm\", \"coldly respectful\", \"openly hostile\", \"warmly amused\", "
                + "\"guarded but curious\", \"deeply loyal\", \"wary and distant\". "
                + "Never output a pronoun, verb, or sentence as a tone. "
                + ( hasPeers
                    ? "For each peer mascot listed, also output a 2-4 word adjective tone phrase "
                      + "describing how this mascot feels toward that peer. "
                      + "Examples: \"grudging respect\", \"dismissive rivalry\", \"wary tolerance\", \"playful contempt\". "
                      + "Never output a pronoun, verb, article, or single word as a tone. "
                    : "" )
                + "Respond ONLY in this exact format with no extra commentary:\n"
                + "FACTS:\n<one fact per line>\nTONE:<adjective phrase>"
                + ( hasPeers
                    ? "\nPEER_TONE:<name>:<adjective phrase>"
                      + "\nExample: PEER_TONE:Hornet:grudging respect"
                      + "\nOne PEER_TONE line per peer. The name and tone must be on the SAME line as PEER_TONE:."
                    : "" );

            final java.util.List<com.group_finity.mascot.assistant.MascotMemory.PermanentMemory>
                permMems = memory.getPermanentMemories();
            final StringBuilder permBlock = new StringBuilder();
            if( !permMems.isEmpty() )
            {
                permBlock.append( "Permanent memories (things this mascot chose to remember forever):\n" );
                for( final com.group_finity.mascot.assistant.MascotMemory.PermanentMemory pm : permMems )
                    permBlock.append( "- " ).append( pm.content ).append( "\n" );
            }

            final String prompt = "Mascot name: " + mascotName + "\n"
                + ( personalitySnip.isEmpty() ? ""
                    : "Mascot personality: " + personalitySnip + "\n" )
                + "Current memory:\n" + currentFacts + "\n"
                + ( permBlock.length() > 0 ? permBlock.toString() : "" )
                + ( hasPeers ? peerContext + "\n" : "" )
                + "Distill into key facts and tone.";

            // Summary needs 300 tokens: 5 facts + TONE + PEER_TONE lines.
            // Default 80-token cap cuts off before TONE: is reached.
            client.generate( system, prompt, 300, new OllamaClient.Callback()
            {
                @Override public void onResponse( final String text )
                {
                    memory.resetSummarizationCounter();
                    log.warning( "[Memory] Raw summary response for " + getImageSet()
                        + ":\n" + text );

                    // ── Parse line by line — avoids indexOf("TONE:") matching "PEER_TONE:" ──
                    boolean inFacts = false;
                    boolean foundTone = false;
                    boolean inPeerToneBlock = false;
                    for( final String rawLine : text.split( "\n" ) )
                    {
                        final String line = rawLine.trim();
                        if( line.isEmpty() ) continue;

                        if( line.equalsIgnoreCase( "FACTS:" ) || line.startsWith( "FACTS:" ) )
                        {
                            inFacts = true;
                            // Handle facts on the same line as "FACTS:" keyword
                            final String inline = line.substring( 6 ).trim().replaceFirst( "^[-*]\\s*", "" );
                            if( !inline.isEmpty() ) memory.addFact( inline );
                            continue;
                        }
                        // TONE: must be an exact prefix, not inside PEER_TONE:
                        if( !line.startsWith( "PEER_TONE:" ) && line.startsWith( "TONE:" ) )
                        {
                            inFacts = false;
                            foundTone = true;
                            final String tone = line.substring( 5 ).trim().toLowerCase();
                            if( isValidTone( tone ) )
                                memory.setTone( tone );
                            else
                                log.warning( "[Memory] Rejected invalid user tone: \"" + tone + "\"" );
                            continue;
                        }
                        if( line.startsWith( "PEER_TONE:" ) )
                        {
                            inFacts = false;
                            final String payload = line.substring( 10 ).trim();
                            if( payload.isEmpty() )
                            {
                                // Model put "PEER_TONE:" on its own line — following lines are
                                // "Name: tone" entries until we hit a blank or unrecognised line.
                                inPeerToneBlock = true;
                            }
                            else
                            {
                                // Inline form: "PEER_TONE:Name:tone"
                                parsePeerToneLine( payload, memory );
                            }
                            continue;
                        }
                        // Inside a PEER_TONE block — lines are "Name: tone" or "Name - tone"
                        if( inPeerToneBlock )
                        {
                            if( line.isEmpty() ) { inPeerToneBlock = false; continue; }
                            // Accept "Name: tone", "Name - tone", or "Name tone" (space-separated)
                            final int sep = line.indexOf( ':' ) >= 0 ? line.indexOf( ':' )
                                          : line.indexOf( '-' ) >= 0 ? line.indexOf( '-' ) : -1;
                            if( sep > 0 )
                                parsePeerToneLine( line.substring(0, sep).trim()
                                    + ":" + line.substring( sep + 1 ).trim(), memory );
                            else
                            {
                                // No separator — likely end of block (e.g. "Holo" alone means truncated)
                                log.warning( "[Memory] Peer tone block ended unexpectedly at: \"" + line + "\"" );
                                inPeerToneBlock = false;
                            }
                            continue;
                        }
                        if( inFacts )
                        {
                            final String fact = line.replaceFirst( "^[-*\\d.]+\\s*", "" ).trim();
                            if( !fact.isEmpty() ) memory.addFact( fact );
                        }
                    }
                    if( !foundTone )
                        log.warning( "[Memory] Summary response had no TONE: line — raw: " + text );
                    log.warning( "[Memory] Summarized for " + getImageSet() );
                }
                @Override public void onError( final String error )
                {
                    log.warning( "[Memory] Summarization failed: " + error );
                }
            });
        }, "memory-summarize" ).start();
    }

    public void applyBubbleSettings()
    {
        for( final com.group_finity.mascot.assistant.AssistantBubble b : activeBubbles )
            b.applySettings();
        // Reset client so next request uses the newly selected model
        ollamaClient = null;
    }

    /**
     * Ensure assistantBubble is ready for a new Thinking/response cycle.
     * Must be called on the EDT. Creates a fresh bubble when the current one
     * already has a visible AI response so each exchange gets its own window
     * and its own chronological timestamp for inter-mascot vertical stacking.
     */
    private void ensureFreshBubble()
    {
        if( assistantBubble == null || assistantBubble.hasActiveResponse() )
        {
            assistantBubble = new com.group_finity.mascot.assistant.AssistantBubble(
                this::handleReply, getImageSet() );
            activeBubbles.add( assistantBubble );
        }
    }

    private static boolean isTimeQuery( final String text )
    {
        final String t = text.toLowerCase();
        return ( t.contains( "time" ) && ( t.contains( "what" ) || t.contains( "current" ) || t.contains( "what's" ) ) )
            || t.equals( "time?" ) || t.equals( "time" );
    }

    /** Returns true for queries whose answers are ephemeral — should not be recorded in memory. */
    private static boolean isEphemeralQuery( final String text )
    {
        if( isWeatherQuery( text ) || isTimeQuery( text ) || isScreenQuery( text ) ) return true;
        final String t = text.toLowerCase();
        return t.contains( "timer" ) || t.contains( "reminder" ) || t.contains( "alarm" )
            || t.contains( "remind me" ) || t.contains( "set a " );
    }

    private static boolean isScreenQuery( final String text )
    {
        final String t = text.toLowerCase();
        return t.contains( "my screen" )
            || t.contains( "looking at" )
            || t.contains( "what do you see" )
            || t.contains( "what's on" )
            || t.contains( "what is on" )
            || t.contains( "can you see" )
            || t.contains( "see my" )
            || t.contains( "on screen" );
    }

    private static boolean isWeatherQuery( final String text )
    {
        final String t = text.toLowerCase();
        return t.contains( "weather" )
            || t.contains( "temperature" )
            || t.contains( "forecast" )
            || t.contains( "raining" )
            || t.contains( "snowing" )
            || t.contains( "sunny" )
            || t.contains( "humid" )
            || t.contains( "degrees" )
            || ( t.contains( "outside" ) && ( t.contains( "like" )
                || t.contains( "cold" ) || t.contains( "hot" ) || t.contains( "warm" ) ) );
    }

    /** Returns a weather context string to append to the user message, or "" if not applicable. */
    private String weatherContext( final String userText )
    {
        if( !isWeatherQuery( userText ) ) return "";
        final String location = Main.getInstance().getProperties()
            .getProperty( "WeatherLocation", "auto" );
        // Run fetch on a daemon thread with a hard cap so HTTPS hangs never block the Ollama call.
        // If the fetch completes in the background after the timeout, the result is cached
        // and the next call returns instantly from the cache.
        final java.util.concurrent.FutureTask<String> task =
            new java.util.concurrent.FutureTask<>(
                () -> com.group_finity.mascot.assistant.WeatherTool.fetch( location ) );
        final Thread t = new Thread( task, "weather-fetch" );
        t.setDaemon( true );
        t.start();
        try
        {
            final String wx = task.get( 10, java.util.concurrent.TimeUnit.SECONDS );
            return wx != null ? "\n\n[Current weather: " + wx + "]" : "";
        }
        catch( final Exception e )
        {
            log.warning( "[Weather] fetch timed out or failed: " + e.getMessage() );
            return "";
        }
    }

    /** Capture the primary screen, scale to max 1024px wide, return base64-encoded PNG. */
    private static String captureScreenBase64() throws java.awt.AWTException, java.io.IOException
    {
        final java.awt.Rectangle screen = new java.awt.Rectangle(
            java.awt.Toolkit.getDefaultToolkit().getScreenSize() );
        final java.awt.image.BufferedImage full =
            new java.awt.Robot().createScreenCapture( screen );

        final int maxW = 1024;
        java.awt.image.BufferedImage img = full;
        if( full.getWidth() > maxW )
        {
            final int h = (int)( full.getHeight() * (double) maxW / full.getWidth() );
            img = new java.awt.image.BufferedImage( maxW, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB );
            final java.awt.Graphics2D g = img.createGraphics();
            g.drawImage( full, 0, 0, maxW, h, null );
            g.dispose();
        }

        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write( img, "png", baos );
        return java.util.Base64.getEncoder().encodeToString( baos.toByteArray() );
    }

    /**
     * Handles a screen-content query: captures a screenshot, sends it to the
     * vision model (llava by default), and shows the response in the bubble.
     * Must be called after thinking is already showing.
     */
    private void handleScreenQuery( final String userText,
                                    final String system,
                                    final OllamaClient client )
    {
        final String visionModel = Main.getInstance().getProperties()
            .getProperty( "VisionModel", "moondream" );
        new Thread( () ->
        {
            try
            {
                final String base64 = captureScreenBase64();
                final String audioCtxVision = audioSnapshotContext();
                final String enrichedUserText = userText
                    + ( audioCtxVision.isEmpty() ? "" : "\n" + audioCtxVision );
                client.generateWithImage( system, enrichedUserText, base64, visionModel,
                    new OllamaClient.Callback()
                    {
                        @Override
                        public void onResponse( final String raw )
                        {
                            final String display = applyPersonaRewrites( stripActionTag( sanitizeResponse( raw ) ) );
                            com.group_finity.mascot.assistant.ChatLog.append( getImageSet() + "(screen glance)", display );
                            SwingUtilities.invokeLater( () ->
                            {
                                if( assistantBubble != null )
                                    assistantBubble.showResponse( display, getBounds() );
                            });
                        }

                        @Override
                        public void onError( final String message )
                        {
                            SwingUtilities.invokeLater( () ->
                            {
                                if( assistantBubble != null )
                                    assistantBubble.showError( message, getBounds() );
                            });
                        }
                    });
            }
            catch( final Exception e )
            {
                log.warning( "[Vision] Screen capture failed: " + e.getMessage() );
                SwingUtilities.invokeLater( () ->
                {
                    if( assistantBubble != null )
                        assistantBubble.showError( "Screen capture failed.", getBounds() );
                });
            }
        }, "screen-capture-thread" ).start();
    }

    /**
     * Returns a non-empty "[Audio context: ...]" label if a recent system audio
     * transcript is available, otherwise returns an empty string.
     * Non-blocking — reads the volatile field populated by fireAudioReaction.
     */
    private static String audioSnapshotContext()
    {
        final String t = com.group_finity.mascot.assistant.AudioTranscriptBuffer.lastSysTranscript;
        return ( t != null && !t.isBlank() ) ? "[Audio context: " + t.trim() + "]" : "";
    }

    /** Create OllamaClient using the model selected in Settings. */
    private static OllamaClient createOllamaClient()
    {
        final String model = Main.getInstance().getProperties()
            .getProperty( "OllamaModel", OllamaClient.DEFAULT_MODEL );
        final String endpoint = Main.getInstance().getProperties()
            .getProperty( "OllamaEndpoint", "http://localhost:11434/api/generate" );
        return new OllamaClient( endpoint, model );
    }

    public void say( final String text )
    {
        javax.swing.SwingUtilities.invokeLater( ( ) ->
        {
            if( assistantBubble == null )
            {
                assistantBubble = new com.group_finity.mascot.assistant.AssistantBubble( this::handleReply, getImageSet() );
                activeBubbles.add( assistantBubble );
            }
            final java.awt.Rectangle b = getBounds( );
            if( b != null )
                // showSay() replaces any previous Say bubble; leaves Ollama bubbles untouched
                assistantBubble.showSay( text, b );
        } );
    }

    public void dispose( )
    {
        log.log( Level.INFO, "destroy mascot ({0})", this );
        
        if( debugWindow != null )
        {
            debugWindow.setVisible( false );
            debugWindow = null;
        }

        if( mascotTooltip != null )
        {
            mascotTooltip.dispose( );
            mascotTooltip = null;
        }

        for( final com.group_finity.mascot.assistant.AssistantBubble b : activeBubbles )
            b.dispose();
        activeBubbles.clear();
        assistantBubble = null;

        if( activeDialog != null )
        {
            activeDialog.close( );
            activeDialog = null;
        }

        if( dragDelayTimer != null )
        {
            dragDelayTimer.stop( );
            dragDelayTimer    = null;
            pendingPressEvent = null;
        }

        // Persist the current dynamic scale so it's restored on next spawn.
        // Only write if scale differs meaningfully from 1.0 to keep the
        // properties file clean for mascots that were never scaled.
        if( Math.abs( currentScale - 1.0 ) > 0.005 )
            Main.getInstance( ).getProperties( ).setProperty(
                "Scale." + getImageSet( ), String.valueOf( currentScale ) );

        animating = false;
        Main.getInstance( ).getProperties( ).remove( "DisabledBehaviours.mascot" + id );
        Main.getInstance( ).getProperties( ).remove( "ManualOnly.mascot" + id );
        Main.getInstance( ).getProperties( ).remove( "FloorEnabled.mascot" + id );
        Main.getInstance( ).getProperties( ).remove( "Tooltip.mascot" + id );
unregisterVoiceCommand();
        com.group_finity.mascot.assistant.MascotSpeechRegistry.unregister( getImageSet() );
        Main.getInstance( ).getProperties( ).remove( "AssistantMode.mascot" + id );
        for( String key : new String[]{ "Breeding", "Transients", "Transformation", "Throwing", "Sounds", "Multiscreen", "ScreenLoop" } )
            Main.getInstance( ).getProperties( ).remove( key + ".mascot" + id );
        getWindow( ).dispose( );
        affordances.clear( );
        if( getManager( ) != null )
        {
            getManager( ).remove( Mascot.this );
        }
    }
        
    private void refreshCursor( Point position )
    {
        boolean useHand = false;
        for( final Hotspot hotspot : hotspots )
        {
            if( hotspot.contains( this, position ) && 
                Main.getInstance( ).getConfiguration( imageSet ).isBehaviorEnabled( hotspot.getBehaviour( ), this ) )
            {
                useHand = true;
                break;
            }
        }

        refreshCursor( useHand );
    }
    
    private void refreshCursor( Boolean useHand )
    {
        getWindow( ).asComponent( ).setCursor( Cursor.getPredefinedCursor( useHand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR ) );
    }

    public Manager getManager( )
    {
        return manager;
    }

    public void setManager( final Manager manager )
    {
        this.manager = manager;
    }

    public Point getAnchor( )
    {
        return anchor;
    }

    public void setAnchor( Point anchor )
    {
        this.lastDeltaX = anchor.x - this.anchor.x;
        this.lastDeltaY = anchor.y - this.anchor.y;
        this.anchor = anchor;
    }

    public void setAnchorXY( final int x, final int y )
    {
        this.lastDeltaX = x - this.anchor.x;
        this.lastDeltaY = y - this.anchor.y;
        this.anchor.x = x;
        this.anchor.y = y;
    }

    public Point getSavedAnchor( )
    {
        return savedAnchor;
    }

    public String getSavedBehaviorName( )
    {
        return savedBehaviorName;
    }

    public MascotImage getImage( )
    {
        return image;
    }

    public void setImage( final MascotImage image )
    {
        this.image = image;
    }

    public boolean isLookRight( )
    {
        return lookRight;
    }

    public void setLookRight( final boolean lookRight )
    {
        this.lookRight = lookRight;
    }

    public void setRenderAnchor( final int x, final int y )
    {
        this.renderAnchorX = x;
        this.renderAnchorY = y;
    }

    /** Compute the screen-pixel cx for the current image and facing direction.
     *  When a scaled image is active, derives cx from the actual displayed image width to
     *  avoid a mismatch with the stale-cache scale. */
    private int renderCX( final int imageWidth )
    {
        int base;
        if( lastScaledImage != null && lastBaseImageWidth > 0 )
            base = (int) Math.round( (double) renderAnchorX * getGlobalScaling( ) * imageWidth / lastBaseImageWidth );
        else
            base = (int) Math.round( renderAnchorX * getGlobalScaling( ) * currentScale );
        return isLookRight( ) ? ( imageWidth - base ) : base;
    }

    /** Compute the screen-pixel cy for the current render anchor.
     *  When a scaled image is active, derives cy from the actual displayed image height to
     *  avoid the mismatch between raw currentScale and ScalableNativeImage's rounded scale. */
    private int renderCY( )
    {
        if( lastScaledImage != null && lastBaseImageHeight > 0 )
        {
            int h = lastScaledImage.getSize( ).height;
            return (int) Math.round( (double) renderAnchorY * getGlobalScaling( ) * h / lastBaseImageHeight );
        }
        return (int) Math.round( renderAnchorY * getGlobalScaling( ) * currentScale );
    }

    public Rectangle getBounds( )
    {
        if( getImage( ) != null )
        {
            final int w    = getImage( ).getSize( ).width;
            final int h    = getImage( ).getSize( ).height;
            final int left = getAnchor( ).x - renderCX( w );
            final int top  = getAnchor( ).y - renderCY( );

            return new Rectangle( left, top, w, h );
        }
        else
        {
            // as we have no image let's return what we were last frame
            return getWindow( ).asComponent( ).getBounds( );
        }
    }

    public int getTime( )
    {
        return time;
    }

    private void setTime( final int time )
    {
        this.time = time;
    }

    public Behavior getBehavior( )
    {
        return behavior;
    }

    /**
     * Returns false if the current action has Draggable="false", meaning it
     * should not be interrupted by external triggers (hotkeys, right-click menu).
     * Internal transitions from UserBehavior (natural completion, fall, etc.) bypass this.
     */
    /**
     * Returns true if the mascot is currently running a jump behavior
     * (behavior name contains "jump", case-insensitive) AND a Jump action
     * is active somewhere in the action tree.
     * Excludes Fall/Thrown which run ManualJump internally as a stomp sub-action.
     * Safe to call from any thread.
     */
    public boolean isJumping( )
    {
        if( !( behavior instanceof com.group_finity.mascot.behavior.UserBehavior ) ) return false;
        String name = ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getName( );
        if( name == null || !name.toLowerCase( ).contains( "jump" ) ) return false;
        com.group_finity.mascot.action.Action root =
            ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getAction( );
        return isJumpAction( root );
    }

    private boolean isJumpAction( final com.group_finity.mascot.action.Action action )
    {
        if( action == null ) return false;
        if( action instanceof com.group_finity.mascot.action.Jump ) return true;
        if( action instanceof com.group_finity.mascot.action.ComplexAction )
        {
            com.group_finity.mascot.action.Action child =
                ( (com.group_finity.mascot.action.ComplexAction) action ).getCurrentChildAction( );
            return isJumpAction( child );
        }
        return false;
    }

    public boolean isCurrentActionInterruptable( )
    {
        if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
        {
            com.group_finity.mascot.action.Action action =
                ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getAction( );
            if( action instanceof com.group_finity.mascot.action.ActionBase )
            {
                try
                {
                    return ( (com.group_finity.mascot.action.ActionBase) action ).isDraggable( );
                }
                catch( com.group_finity.mascot.exception.VariableException ignored ) { }
            }
        }
        return true;
    }

    /** Ring buffer of the last N behavior names, oldest-first. */
    private static final int BEHAVIOR_HISTORY_MAX = 5;
    private final java.util.ArrayDeque<String> behaviorHistory = new java.util.ArrayDeque<>( BEHAVIOR_HISTORY_MAX );

    /**
     * Returns the behavior history as an array, oldest first, newest last.
     * The current behavior is NOT included.
     */
    public String[] getBehaviorHistory()
    {
        return behaviorHistory.toArray( new String[0] );
    }

    /** Compatibility shim - returns the most recent history entry, or null. */
    public String getPreviousBehaviorName()
    {
        return behaviorHistory.isEmpty( ) ? null : behaviorHistory.peekLast( );
    }

    public void setBehavior( final Behavior behavior ) throws CantBeAliveException
    {
        if( behavior == null )
        {
            log.log( Level.WARNING, "setBehavior called with null for {0} — ignoring", this );
            return;
        }
        final String current = getCurrentBehaviorName();
        if( current != null )
        {
            if( behaviorHistory.size( ) >= BEHAVIOR_HISTORY_MAX )
                behaviorHistory.pollFirst( );
            behaviorHistory.addLast( current );
        }
        this.behavior = behavior;
        this.behavior.init( this );
    }

    public int getCount( )
    {
        return manager != null ? getManager( ).getCount( imageSet ) : 0;
    }

    public int getTotalCount( )
    {
        return manager != null ? getManager( ).getCount( ) : 0;
    }

    public boolean isAnimating( )
    {
        return animating && !paused;
    }

    private void setAnimating( final boolean animating )
    {
        this.animating = animating;
    }

    private TranslucentWindow getWindow( )
    {
        return this.window;
    }

    public MascotEnvironment getEnvironment( )
    {
        return environment;
    }

    /**
     * Applies the always-on-top state based on the global setting AND the per-imageset override.
     * Global off overrides everything. Global on respects per-imageset opt-out.
     */
    public void applyAlwaysOnTop( )
    {
        final java.util.Properties props = Main.getInstance( ).getProperties( );
        final boolean globalOnTop = Boolean.parseBoolean( props.getProperty( "AlwaysOnTop", "true" ) );
        final boolean perImageSet = Boolean.parseBoolean(
            props.getProperty( "AlwaysOnTop.imageset." + getImageSet( ), "true" ) );
        getWindow( ).setAlwaysOnTop( globalOnTop && perImageSet );
    }

    public ArrayList<String> getAffordances( )
    {
	return affordances;
    }

    public ArrayList<Hotspot> getHotspots( )
    {
	return hotspots;
    }

    public void setCurrentScale( double scale ) { this.currentScale = scale; }
    public double getCurrentScale( )            { return currentScale; }

    /** Called by Tint.tick() each tick to update the color and drive the target opacity.
     *  Lerp toward target is handled in Mascot.tick(), not here. */
    public void setTintTarget( java.awt.Color color, float target, float lerpFactor )
    {
        this.tintColor        = color;
        this.tintTargetOpacity = Math.max( 0f, Math.min( 1f, target ) );
        this.tintLerpFactor   = Math.max( 0.001f, Math.min( 1f, lerpFactor ) );
    }

    /** Called by Tint.init() when Target and/or Color are script expressions.
     *  Either var may be null (static). Stores them so Mascot.tick() re-evaluates every tick. */
    public void setTintExpr( com.group_finity.mascot.script.Variable opacityVar,
                             com.group_finity.mascot.script.Variable colorVar,
                             com.group_finity.mascot.script.VariableMap varMap,
                             java.awt.Color initialColor, float lerpFactor )
    {
        this.tintTargetVar  = opacityVar;
        this.tintColorVar   = colorVar;
        this.tintVarMap     = varMap;
        this.tintColor      = initialColor;
        this.tintLerpFactor = Math.max( 0.001f, Math.min( 1f, lerpFactor ) );
        // Seed lerp channels to initial color so the first render has no jump
        this.tintCurrentR = this.tintTargetR = initialColor.getRed( );
        this.tintCurrentG = this.tintTargetG = initialColor.getGreen( );
        this.tintCurrentB = this.tintTargetB = initialColor.getBlue( );
    }

    public void snapClearTint( )
    {
        tintColor          = null;
        tintCurrentOpacity = 0f;
        tintTargetOpacity  = 0f;
        tintTargetVar      = null;
        tintColorVar       = null;
        tintVarMap         = null;
        tintCurrentR = 255f; tintTargetR = 255f;
        tintCurrentG = 0f;   tintTargetG = 0f;
        tintCurrentB = 0f;   tintTargetB = 0f;
        tintScratchBuf     = null;
        tintScratchNative  = null;
    }

    public float getTintCurrentOpacity( ) { return tintCurrentOpacity; }

    /** Store a transient value in the per-mascot script scratchpad. */
    public void setUserData( final String key, final Object value )
    {
        if( value == null ) userData.remove( key );
        else userData.put( key, value );
    }
    /** Retrieve a value from the per-mascot script scratchpad, or null if absent. */
    public Object getUserData( final String key ) { return userData.get( key ); }

    /** Last horizontal pixel delta applied in setAnchor. Reflects movement speed this tick. */
    public int getLastDeltaX( ) { return lastDeltaX; }
    /** Last vertical pixel delta applied in setAnchor. */
    public int getLastDeltaY( ) { return lastDeltaY; }

    /** Consume the queued jump X nudge (returns offset and resets to 0). */
    public int consumeJumpTargetXOffset( )
    {
        int v = jumpTargetXOffset;
        jumpTargetXOffset = 0;
        return v;
    }
    /** Queue a one-shot nudge to the active Jump's targetX. Called from JNH thread. */
    public void addJumpTargetXOffset( int dx ) { jumpTargetXOffset += dx; }

    public boolean isManualOnly( )          { return manualOnly; }
    public boolean isFloorEnabled( )        { return floorEnabled; }

    /** True when Screen Loop is enabled for this mascot's image set. */
    public boolean isScreenLoop( )
    {
        final java.util.Properties props = Main.getInstance( ).getProperties( );
        final String perImageSet = props.getProperty( "ScreenLoop.imageset." + getImageSet( ) );
        if( perImageSet != null )
            return Boolean.parseBoolean( perImageSet );
        return Boolean.parseBoolean( props.getProperty( "ScreenLoop", "false" ) );
    }
    public void setManualOnly( boolean v )  { manualOnly = v; }

    public boolean isPinnedLocation( )         { return pinnedLocation; }
    public void setPinnedLocation( boolean v ) { pinnedLocation = v; }
    public void setPinnedKey( String key )     { pinnedKey = key; }

    /** Returns the name of the current UserBehavior, or null if unavailable. */
    public String getCurrentBehaviorName( )
    {
        if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
            return ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getName( );
        if( behavior instanceof com.group_finity.mascot.behavior.HoldLastStepBehavior )
            return ( (com.group_finity.mascot.behavior.HoldLastStepBehavior) behavior ).getName( );
        return null;
    }

    /**
     * Saves this mascot's current anchor, direction, and behavior name into the
     * "PinnedMascots" property so it is restored on next launch.
     * Writes through to disk immediately via updateConfigFile().
     */
    public void savePinnedLocation( )
    {
        final String behaviorName = getCurrentBehaviorName( );
        final String entry = imageSet + "|"
            + ( behaviorName != null ? behaviorName : "" ) + "|"
            + getAnchor( ).x + "|"
            + getAnchor( ).y + "|"
            + isLookRight( );

        // Each mascot gets its own property slot keyed by its unique id so that
        // multiple pinned mascots (even of the same imageSet) coexist cleanly.
        pinnedKey = "PinnedMascot." + id;
        Main.getInstance( ).getProperties( ).setProperty( pinnedKey, entry );
        Main.getInstance( ).updateConfigFile( );
    }

    /**
     * Removes this mascot's saved location entry and writes through to disk.
     */
    public void removePinnedLocation( )
    {
        // Use pinnedKey (the key that was actually written) rather than the
        // current runtime id, which changes between sessions.
        final String key = pinnedKey != null ? pinnedKey : "PinnedMascot." + id;
        Main.getInstance( ).getProperties( ).remove( key );
        pinnedKey = null;
        Main.getInstance( ).updateConfigFile( );
    }

    public void setImageSet( final String set )
    {
        imageSet = set;
    }

    public String getImageSet( )
    {
        return imageSet;
    }

    public String getSound( )
    {
        return sound;
    }

    public void setSound( final String name )
    {
        sound = name;
    }
    
    public boolean isPaused( )
    {
        return paused;
    }
    
    public void setPaused( final boolean paused )
    {
        this.paused = paused;
    }

    public boolean isDragging( )
    {
        return dragging;
    }

    public void setDragging( final boolean isDragging )
    {
        dragging = isDragging;
    }

    public boolean isHotspotClicked( )
    {
        return cursor != null;
    }
    
    public Point getCursorPosition( )
    {
        return cursor;
    }

    public void setCursorPosition( final Point point )
    {
        cursor = point;
        
        if( point == null )
            refreshCursor( false );
        else
            refreshCursor( point );
    }

    public VariableMap getVariables( )
    {
        if( variables == null )
            variables = new VariableMap( );
        return variables;
    }
}
