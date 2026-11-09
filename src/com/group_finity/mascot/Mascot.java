package com.group_finity.mascot;

import static com.group_finity.mascot.assistant.AssistantShared.ollamaClient;
import static com.group_finity.mascot.assistant.AssistantShared.audioBuffer;
import static com.group_finity.mascot.assistant.AssistantShared.AUDIO_LOCK;
import static com.group_finity.mascot.assistant.AssistantShared.lastAudioTranscriptText;
import static com.group_finity.mascot.assistant.AssistantShared.DEFAULT_VISION_MODEL;
import static com.group_finity.mascot.assistant.AssistantShared.QUICK_REACTION_STYLE_RULES;
import static com.group_finity.mascot.assistant.AssistantShared.situationContext;
import static com.group_finity.mascot.assistant.AssistantShared.isKnownImageSet;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_SPONTANEOUS_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_AUDIO_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_VISION_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_USER_SPEECH_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalSpontaneousLastFiredMs;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalAudioLastFiredMs;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalVisionLastFiredMs;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalUserSpeechLastFiredMs;
import static com.group_finity.mascot.assistant.ReplyText.isAudioTranscriptDuplicate;
import static com.group_finity.mascot.assistant.ReplyText.stripActionTag;
import static com.group_finity.mascot.assistant.ReplyText.rewriteFirstPerson;
import static com.group_finity.mascot.assistant.ReplyText.extractTimerTag;
import static com.group_finity.mascot.assistant.ReplyText.parseTimerDurationMs;
import static com.group_finity.mascot.assistant.ReplyText.sanitizeResponse;
import static com.group_finity.mascot.assistant.ReplyText.editDistance;
import static com.group_finity.mascot.assistant.ReplyText.trimToFirstSentence;
import static com.group_finity.mascot.assistant.ReplyText.isTrivialAcknowledgement;
import static com.group_finity.mascot.assistant.ReplyText.polishUtterance;
import static com.group_finity.mascot.assistant.ReplyText.isIncompleteUtterance;
import static com.group_finity.mascot.assistant.ReplyText.isEphemeralQuery;
import static com.group_finity.mascot.assistant.ReplyText.isScreenQuery;
import static com.group_finity.mascot.assistant.ReplyText.isWeatherQuery;
import static com.group_finity.mascot.assistant.ReplyText.isValidTone;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
public class Mascot implements com.group_finity.mascot.assistant.AssistantHost
{
    private static final long serialVersionUID = 1L;

    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private static AtomicInteger lastId = new AtomicInteger( );

    final int id;

    String imageSet = "";
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
    // SHARED across every mascot instance, not per-clone — a scaled bitmap for a given
    // source frame is identical no matter which mascot is displaying it, so clones of
    // the same character (or any two mascots reusing a frame) reuse one built
    // ScalableNativeImage instead of each clone rebuilding and holding its own copy.
    // Bounded LRU; always accessed from the tick thread today (scale building isn't
    // offloaded to a pool), so a synchronized wrapper is enough — no need for a full
    // concurrent map. Cap raised from the old per-instance 16 to 128 since this now
    // covers every scaled frame across the whole mascot population, not just one clone's.
    private static final java.util.Map<MascotImage, com.group_finity.mascot.image.ScalableNativeImage> scalables =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<MascotImage, com.group_finity.mascot.image.ScalableNativeImage>( 128, 0.75f, true )
            {
                protected boolean removeEldestEntry( java.util.Map.Entry<MascotImage, com.group_finity.mascot.image.ScalableNativeImage> eldest )
                { return size() > 128; }
            } );
    // Frames still waiting to be rebuilt for the current roundedScale — drained a few
    // per tick instead of all at once, so one scale-bucket change can't spike apply().
    // Stays per-instance: different mascots can be mid-transition on different frames
    // even though the built ScalableNativeImage objects themselves are now shared.
    private java.util.ArrayDeque<MascotImage> pendingScaleBuild = null;
    // Every frame THIS mascot instance has displayed while scaled — small, bounded by
    // the character's own animation set. Used only to scope pendingScaleBuild to this
    // mascot's own frames now that scalables itself is a shared, population-wide map.
    private final java.util.LinkedHashSet<MascotImage> myScaledFrames = new java.util.LinkedHashSet<>();
    private static final int SCALE_BUILDS_PER_TICK = 2;
    // Source image dimensions (base image at globalScaling).
    // Used by renderCX/renderCY to compute anchor pixel offsets from actual displayed image size
    // instead of raw currentScale, avoiding a mismatch when the cache is stale.
    private int lastBaseImageHeight = -1;
    private int lastBaseImageWidth  = -1;

    // Render dirty-check: the image + bounds last composited to the layered window.
    // apply() skips the UpdateLayeredWindow/setBounds when nothing changed, so a mascot
    // standing still doesn't re-composite its window 25x/sec (the per-tick render is the
    // dominant TickWatch cost and what balloons under LLM GPU/CPU contention).
    private MascotImage    lastAppliedImage  = null;
    private java.awt.Rectangle lastAppliedBounds = null;

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

    // Tint compositing (Java2D draw+fill) is the expensive part of a tinted mascot's
    // apply() — not the native blit, which is already off-thread. Composited results
    // are now cached SHARED across every mascot instance (see tintCache below), not
    // held per-instance — clones reacting to the same sensor-driven tint (e.g. every
    // campfire clone tracking the same CPU-load value) converge on nearly identical
    // color/opacity, so the composite is computed once and reused instead of every
    // clone separately re-running Java2D each tick.

    // Tint compositing is the expensive part of a tinted mascot's apply() — not the
    // native blit, which is already off-thread. Moved here for the same reason: don't
    // let one CPU-reactive mascot's re-composite stall the shared tick loop. Single-
    // flight per mascot; stale requests are dropped, never queued, so a slow composite
    // can't build a backlog.
    // Sized off the cores actually reserved for this process (see
    // ProcessPriorityUtil), not the system-wide core count. Once
    // reserveCoresForSelf() pins the whole JVM to N cores, every thread in
    // this process — including this pool — is confined there too; sizing
    // against availableProcessors() would oversubscribe the real budget and
    // cause exactly the self-contention this pool was meant to avoid.
    private static int tintPoolSize()
    {
        final int reserved = com.group_finity.mascot.environment.ProcessPriorityUtil.reservedCoreCount();
        if( reserved <= 0 )
            return Math.max( 2, Math.min( 4, Runtime.getRuntime().availableProcessors() / 2 ) );
        // Leave headroom for the tick thread + EDT + GC sharing the same reserved cores.
        return Math.max( 1, reserved - 1 );
    }

    private static final ExecutorService TINT_POOL = Executors.newFixedThreadPool(
        tintPoolSize(),
        r -> { Thread t = new Thread( r, "mascot-tint" ); t.setDaemon( true ); return t; }
    );

    // Cache key for a composited tint result: source frame identity + quantized
    // color/opacity. Color and opacity are lerped independently per mascot instance,
    // so two clones converging on "the same" sensor-driven tint will rarely land on
    // bit-identical floats — quantizing (nearest 8 per color channel, nearest 4 alpha
    // step out of 255) trades an imperceptible banding risk for a much higher cache-hit
    // rate across clones. Composites cached this way are immutable once built — never
    // mutated in place — which is what makes sharing them across mascots safe.
    private static final class TintKey
    {
        final java.awt.image.BufferedImage src;
        final int colorBucket;
        final int alphaBucket;

        TintKey( java.awt.image.BufferedImage s, java.awt.Color c, float opacity )
        {
            src = s;
            final int r = Math.round( c.getRed( )   / 8f ) * 8;
            final int g = Math.round( c.getGreen( ) / 8f ) * 8;
            final int b = Math.round( c.getBlue( )  / 8f ) * 8;
            colorBucket = ( r << 16 ) | ( g << 8 ) | b;
            final int alpha = Math.max( 0, Math.min( 255, Math.round( opacity * 255 ) ) );
            alphaBucket = ( alpha / 4 ) * 4;
        }

        @Override
        public boolean equals( Object o )
        {
            if( !( o instanceof TintKey ) ) return false;
            TintKey k = (TintKey) o;
            return src == k.src && colorBucket == k.colorBucket && alphaBucket == k.alphaBucket;
        }

        @Override
        public int hashCode( )
        {
            return ( System.identityHashCode( src ) * 31 + colorBucket ) * 31 + alphaBucket;
        }
    }

    // SHARED across every mascot instance. ConcurrentHashMap because, unlike scalables,
    // this IS touched from multiple TINT_POOL worker threads concurrently — computeIfAbsent
    // is atomic per-key here, so two clones racing on the same quantized tint state build
    // it once, not twice. Bounded with a crude full-clear instead of true LRU: simpler to
    // keep correct under concurrent writers, and a clear only costs one recompute cycle
    // for whatever was evicted, not a correctness problem.
    private static final int TINT_CACHE_CAP = 200;
    private static final java.util.concurrent.ConcurrentHashMap<TintKey, com.group_finity.mascot.image.NativeImage> tintCache =
        new java.util.concurrent.ConcurrentHashMap<>( );

    private static com.group_finity.mascot.image.NativeImage buildTintComposite(
        final java.awt.image.BufferedImage src, final java.awt.Color color, final float opacity )
    {
        final int w = src.getWidth( );
        final int h = src.getHeight( );
        final java.awt.image.BufferedImage buf =
            new java.awt.image.BufferedImage( w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE );
        final java.awt.Graphics2D g = buf.createGraphics( );
        try
        {
            g.setComposite( java.awt.AlphaComposite.Src );
            g.drawImage( src, 0, 0, null );
            g.setComposite( java.awt.AlphaComposite.SrcAtop );
            g.setColor( new java.awt.Color(
                color.getRed( ), color.getGreen( ), color.getBlue( ),
                Math.max( 0, Math.min( 255, (int)( opacity * 255 ) ) ) ) );
            g.fillRect( 0, 0, w, h );
        }
        finally
        {
            g.dispose( );
        }
        final com.group_finity.mascot.image.NativeImage nativeImg = NativeFactory.getInstance( ).newNativeImage( buf );
        nativeImg.updatePixels( buf );
        return nativeImg;
    }

    private static final class TintRequest {
        final java.awt.image.BufferedImage src;
        final java.awt.Color color;
        final float opacity;
        final java.awt.Rectangle bounds;
        TintRequest( java.awt.image.BufferedImage s, java.awt.Color c, float o, java.awt.Rectangle b ) {
            src = s; color = c; opacity = o; bounds = b;
        }
    }

    private final AtomicBoolean tintRenderInFlight = new AtomicBoolean( false );
    private final AtomicReference<TintRequest> pendingTintRequest = new AtomicReference<>();

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
    boolean pinnedLocation = false;

    // ── General-purpose script scratchpad ────────────────────────────────────────
    // Allows XML/JS scripts to store transient per-mascot values (e.g. jump target block X).
    private final java.util.Map<String, Object> userData = new java.util.HashMap<>( );

    // ── Movement velocity tracking ───────────────────────────────────────────────
    // Net pixels moved this tick, computed once in tick() around the behavior step so XML
    // can read movement speed (e.g. horizontal speed at jump time). Robust to physics
    // sub-stepping and trailing 0-velocity poses; not tracked by individual setAnchor* calls.
    private int lastDeltaX = 0;
    private int lastDeltaY = 0;

    // ── Jump air-steer ────────────────────────────────────────────────────────
    // Written by HotkeyManager on a directional key-press, consumed once by
    // Jump.getTargetX() each tick. Volatile so the JNH thread and tick thread
    // see it without locking.
    private volatile int jumpTargetXOffset = 0;

    // ── Manual-only mode ──────────────────────────────────────────────────────
    // When true, buildNextBehavior only allows Fall/Dragged/Thrown/Stand/GrabWall.
    boolean manualOnly = false;

    // ── Assistant mode ─────────────────────────────────────────────────────────
    // When true, left-clicking the mascot opens a chat input box instead of
    // triggering the normal drag behavior. Responses come from a local Ollama LLM.
    boolean assistantMode = false;
    com.group_finity.mascot.assistant.AssistantBubble assistantBubble = null;
    // 2B-only live system readout (console/log stream projected ahead of her).
    // Null for every other image set and whenever assistant mode is off.
    com.group_finity.mascot.assistant.ConsoleReadout consoleReadout = null;
    // Dedicated bubble for the active countdown timer / fired reminder. Separate from
    // assistantBubble so it can maintain its fixed stacking position independently.
    volatile com.group_finity.mascot.assistant.AssistantBubble timerBubble = null;
    // All bubbles this mascot has ever created — each AI response gets its own entry so
    // the chronological stacking system can weave them between other mascots' messages.
    // CopyOnWriteArrayList: Manager thread iterates for reposition, EDT adds entries.
    final java.util.concurrent.CopyOnWriteArrayList<com.group_finity.mascot.assistant.AssistantBubble>
        activeBubbles = new java.util.concurrent.CopyOnWriteArrayList<>();
    com.group_finity.mascot.assistant.AssistantInputDialog activeDialog = null;
    // Strong reference — the registry only holds a WeakReference, so without this the listener gets GC'd
    private com.group_finity.mascot.assistant.MascotSpeechRegistry.PeerListener peerListener = null;
    // Ollama request rate-limiting is handled inside OllamaClient (queue-based).

    // Click-vs-hold detection: a press shorter than CLICK_MAX_MS with movement
    // under CLICK_MAX_MOVE_PX is treated as a click (opens chat); longer = drag.
    private long  assistantPressTime  = 0;
    private Point assistantPressPoint = null;

    /** Foreground window title captured just before the input dialog opens,
     *  so the mascot knows what the user was looking at when they spoke to it. */
    String lastActiveWindowTitle = "";

    // ── Spontaneous window-aware comments ─────────────────────────────────────
    /** Minimum ticks between spontaneous comments (~40 ticks/sec, so 1800 = ~45s). */
    // Window comment cadence: anywhere from ~30s to ~3min, biased toward longer
    private static final int SPONTANEOUS_MIN_TICKS  = 1_200; // ~30s floor
    private static final int SPONTANEOUS_MAX_TICKS  = 7_200; // ~3min ceiling
    private int  spontaneousTickCounter = 0;
    private int  spontaneousNextTrigger = -1; // -1 = randomize on first tick
    private final java.util.Random spontaneousRng = new java.util.Random();
    private static final int SALIENCE_CHECK_TICKS = 50; // ~2s: salience-mode poll for a fresh salient event
    private long lastReactedSalientStamp = 0;           // last SituationModel salient stamp we acted on
    /** Title that triggered the last spontaneous comment - avoid repeating. */
    private volatile String lastSpontaneousTitle = "";

    // Audio reaction cadence: anywhere from ~45s to ~4min, biased toward longer
    private static final int AUDIO_MIN_TICKS  = 1_800; // ~45s floor
    private static final int AUDIO_MAX_TICKS  = 9_600; // ~4min ceiling
    private int  audioTickCounter  = 0;
    private int  audioNextTrigger  = -1; // -1 = randomize on first tick

    // Vision screen-glance cadence: anywhere from ~2min to ~7.5min, biased toward longer
    private static final int VISION_MIN_TICKS  = 4_800; // ~2min floor
    private static final int VISION_MAX_TICKS  = 18_000; // ~7.5min ceiling
    private int  visionTickCounter  = 0;
    private int  visionNextTrigger  = -1; // -1 = randomize on first tick


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
    boolean floorEnabled = true;

    // ── Behavior tooltip overlay ──────────────────────────────────────────────
    // When true, a small floating label above this mascot shows its current
    // behavior name and remaining action timer ticks.
    boolean tooltipEnabled = false;
    MascotTooltip mascotTooltip = null;
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

    JPopupMenu activePopup = null;
    // Screen bounds of the open popup, captured on show. The global native-mouse dismiss
    // listener uses this to ignore presses INSIDE the popup (so the volume slider and menu
    // items work) and only force-close on presses outside it.
    volatile java.awt.Rectangle activePopupBounds = null;
    NativeMouseListener popupDismissListener = null;
    
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

        // Start the background drive indexer (idempotent across mascots)
        if( assistantMode ) com.group_finity.mascot.assistant.DriveIndexTool.ensureStarted();

        // Start the situational synthesis model -- Layer A, rule-based, one shared
        // instance across all mascots (idempotent). LLM-free: only samples + logs.
        if( assistantMode ) com.group_finity.mascot.assistant.SituationModel.ensureStarted();

        // Mascots flagged for it get a live console/log readout projected ahead of
        // them (2B by default; opt-in for any via <ConsoleReadout>true</ConsoleReadout>).
        if( assistantMode && wantsConsoleReadout() )
            consoleReadout = new com.group_finity.mascot.assistant.ConsoleReadout( this );

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
                    new MascotPopupMenu( Mascot.this ).show( event.getX( ), event.getY( ) );
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

    // ── Assistant mode ────────────────────────────────────────────────────────


    private void handleTextInput( final String userText, final String mascotName, final com.group_finity.mascot.config.Configuration cfg )
    {
        new com.group_finity.mascot.assistant.AssistantInput( this ).handleTextInput( userText, mascotName, cfg );
    }

    private void openAssistantInput( )
    {
        new com.group_finity.mascot.assistant.AssistantInput( this ).openAssistantInput( );
    }

    String applyPersonaRewrites( final String text )
    {
        return new com.group_finity.mascot.assistant.AssistantPrompts( this ).applyPersonaRewrites( text );
    }

    /**
     * Whether this mascot should get a live {@link com.group_finity.mascot.assistant.ConsoleReadout}
     * (the faint console/log stream projected ahead of it). Purely config-driven: opt in per
     * image set with {@code <ConsoleReadout>true</ConsoleReadout>} in the {@code <Information>}
     * block (2B carries this in its own XML). No mascot is hardcoded here — the rule lives with
     * the mascot, so adding the readout to any character is an XML edit, never a code change.
     */
    boolean wantsConsoleReadout( )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( getImageSet( ) );
        if( cfg == null ) return false;
        final String flag = cfg.getInformation( "ConsoleReadout" );
        return flag != null && "true".equalsIgnoreCase( flag.trim( ) );
    }


    private void setMascotTimer( final String timeValue, final String reminder )
    {
        new com.group_finity.mascot.assistant.AssistantActions( this ).setMascotTimer( timeValue, reminder );
    }

    String findActionByKeyword( final String userText, final com.group_finity.mascot.config.Configuration cfg )
    {
        return new com.group_finity.mascot.assistant.AssistantActions( this ).findActionByKeyword( userText, cfg );
    }

    private String extractActionTag( final String response )
    {
        return new com.group_finity.mascot.assistant.AssistantActions( this ).extractActionTag( response );
    }

    void fireActionFromResponse( final String raw )
    {
        new com.group_finity.mascot.assistant.AssistantActions( this ).fireActionFromResponse( raw );
    }

    String tryRunBehavior( final String name, final com.group_finity.mascot.config.Configuration cfg )
    {
        return new com.group_finity.mascot.assistant.AssistantActions( this ).tryRunBehavior( name, cfg );
    }

    String buildUserMessage( final String userText )
    {
        return new com.group_finity.mascot.assistant.AssistantPrompts( this ).buildUserMessage( userText );
    }

    /**
     * Build the system prompt sent to Ollama for every request.
     * Combines the mascot's <Personality> tag (if present) with live context:
     * current behavior, time of day, and the active foreground window title.
     */
    String buildSystemPrompt( final com.group_finity.mascot.config.Configuration cfg,
                                      final String mascotName )
    {
        return buildSystemPrompt( cfg, mascotName, null );
    }

    String buildSystemPrompt( final com.group_finity.mascot.config.Configuration cfg, final String mascotName, final String userText )
    {
        return new com.group_finity.mascot.assistant.AssistantPrompts( this ).buildSystemPrompt( cfg, mascotName, userText );
    }


    @Override
    public void handleReply( final String userText, final com.group_finity.mascot.assistant.AssistantBubble.Message message )
    {
        new com.group_finity.mascot.assistant.AssistantInput( this ).handleReply( userText, message );
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

    private void firePeerReaction( final String speakerName, final String speakerImageSet, final String speakerText, final int chainDepth )
    {
        new com.group_finity.mascot.assistant.AssistantReplies( this ).firePeerReaction( speakerName, speakerImageSet, speakerText, chainDepth );
    }

    void registerVoiceCommand()
    {
        final com.group_finity.mascot.assistant.VoiceCommandListener vcl =
            com.group_finity.mascot.assistant.VoiceCommandListener.getInstance();
        vcl.register( getPersonalityName(), this::handleNameTrigger );
        final String alias = getVoiceTrigger();
        if( alias != null ) vcl.register( alias, this::handleNameTrigger );
        vcl.registerSpeechListener( getPersonalityName(), this::fireUserSpeechReaction );
    }

    void unregisterVoiceCommand()
    {
        final com.group_finity.mascot.assistant.VoiceCommandListener vcl =
            com.group_finity.mascot.assistant.VoiceCommandListener.getInstance();
        vcl.unregister( getPersonalityName() );
        final String alias = getVoiceTrigger();
        if( alias != null ) vcl.unregister( alias );
        vcl.unregisterSpeechListener( getPersonalityName() );
    }

    private String getVoiceTrigger()
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( getImageSet() );
        final String v = cfg != null ? cfg.getInformation( "VoiceTrigger" ) : null;
        return ( v != null && !v.trim().isEmpty() ) ? v.trim() : null;
    }

    private void handleNameTrigger( final String vclTranscript )
    {
        new com.group_finity.mascot.assistant.AssistantReplies( this ).handleNameTrigger( vclTranscript );
    }

    private void fireAudioReaction( )
    {
        new com.group_finity.mascot.assistant.AssistantReactions( this ).fireAudioReaction( );
    }

    private void fireUserSpeechReaction( final String micText )
    {
        new com.group_finity.mascot.assistant.AssistantReactions( this ).fireUserSpeechReaction( micText );
    }

    private void fireSpontaneousComment( final String windowTitle )
    {
        new com.group_finity.mascot.assistant.AssistantReactions( this ).fireSpontaneousComment( windowTitle );
    }
    private void fireVisionReaction( )
    {
        new com.group_finity.mascot.assistant.AssistantReactions( this ).fireVisionReaction( );
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

    static String getSpeechRule( final com.group_finity.mascot.config.Configuration cfg )
    {
        return com.group_finity.mascot.assistant.AssistantPrompts.getSpeechRule( cfg );
    }


    static String withSpeechReminder( final String system, final String speechRule )
    {
        return com.group_finity.mascot.assistant.AssistantPrompts.withSpeechReminder( system, speechRule );
    }

    /**
     * Fused situational read (Layer C) — one line summarizing what the user is doing
     * (state/activity/audio/time + recent change), drawn from the shared SituationModel.
     * Empty when the model is inactive, so callers can append it unconditionally.
     */

    static String situationBackground( )
    {
        return com.group_finity.mascot.assistant.AssistantPrompts.situationBackground( );
    }

    String getPersonality( final com.group_finity.mascot.config.Configuration cfg, final String mascotName )
    {
        return new com.group_finity.mascot.assistant.AssistantPrompts( this ).getPersonality( cfg, mascotName );
    }

    String getPersonalityQuick( final com.group_finity.mascot.config.Configuration cfg, final String mascotName )
    {
        return new com.group_finity.mascot.assistant.AssistantPrompts( this ).getPersonalityQuick( cfg, mascotName );
    }

    void tick( )
    {
        if( isAnimating( ) )
        {
            if( getBehavior( ) != null )
            {
                // Net displacement this tick, captured around the whole behavior step so it is
                // correct regardless of how the action moved: a single pose-velocity step (walk),
                // many physics sub-steps (Fall/Jump/Thrown), or a trailing 0-velocity pose that
                // would otherwise zero it. Individual setAnchor* calls no longer track delta.
                final int tickStartX = anchor.x;
                final int tickStartY = anchor.y;
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
                lastDeltaX = anchor.x - tickStartX;
                lastDeltaY = anchor.y - tickStartY;

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
            
            if( debugWindow != null && behavior != null )
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
                    {
                        b.setMascotRef( getAnchor( ).x, isLookRight( ) );
                        b.reposition( ab );
                    }
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
            if( com.group_finity.mascot.assistant.SituationModel.isActive() )
            {
                // ── Salience-triggered proactivity (activity-aware) ──
                // Speak when a FRESH salient signal lands — a real change (window switch,
                // audio start/stop, state flip) OR an ambient pulse during sustained
                // activity (incl. a long video — "focused" but audio playing). The 2-min
                // global cooldown is the spacing floor; only genuine idle and silent
                // deep-work produce no signal, so the companion goes quiet there.
                spontaneousTickCounter++;
                if( spontaneousTickCounter >= SALIENCE_CHECK_TICKS && dialogFree )
                {
                    spontaneousTickCounter = 0;
                    final long stamp = com.group_finity.mascot.assistant.SituationModel
                        .getInstance().salientStamp();
                    if( stamp > lastReactedSalientStamp )
                    {
                        // Consume this event so we attempt it at most once, even if the
                        // cooldown blocks the actual comment (no pile-up after a burst).
                        lastReactedSalientStamp = stamp;
                        if( ollamaClient == null ) ollamaClient = createOllamaClient();
                        // Title fetch can block on a busy foreground app — off the tick thread.
                        new Thread( () ->
                        {
                            final String title = getActiveWindowTitle();
                            if( title == null || title.isEmpty() ) return;
                            final long now = System.currentTimeMillis();
                            final long last = globalSpontaneousLastFiredMs.get();
                            if( now - last >= GLOBAL_SPONTANEOUS_COOLDOWN_MS
                                    && globalSpontaneousLastFiredMs.compareAndSet( last, now ) )
                            {
                                lastSpontaneousTitle = title;
                                log.info( "[Spontaneous] salience-triggered (" + getImageSet() + ")" );
                                fireSpontaneousComment( title );
                            }
                            else
                            {
                                log.info( "[Spontaneous] salient event but global cooldown active — skipping for "
                                    + getImageSet() );
                            }
                        }, "spontaneous-title-fetch" ).start();
                    }
                }
            }
            else
            {
                // ── Legacy timer-driven fallback (SituationModel disabled) ──
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
                    if( ollamaClient == null ) ollamaClient = createOllamaClient();
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

    // Drains queued tint-composite requests for this mascot, one at a time. Checks the
    // shared tintCache before doing any Java2D work — if another mascot (very often a
    // clone of this same character reacting to the same sensor value) already built a
    // composite for this quantized (frame, color, opacity) combination, this is just a
    // hashmap lookup, not a re-composite. Single-flight per mascot via tintRenderInFlight
    // so this mascot's own dispatch never overlaps itself; only the newest request
    // matters — if falling behind, in-between frames are dropped rather than queued.
    private void drainTintQueue()
    {
        try
        {
            TintRequest req;
            while ( ( req = pendingTintRequest.getAndSet( null ) ) != null )
            {
                final TintRequest r = req;
                if( tintCache.size() > TINT_CACHE_CAP ) tintCache.clear();
                com.group_finity.mascot.image.NativeImage composited = tintCache.computeIfAbsent(
                    new TintKey( r.src, r.color, r.opacity ),
                    k -> buildTintComposite( r.src, r.color, r.opacity ) );
                getWindow().setImage( composited );
                getWindow().updateImage( r.bounds );
            }
        }
        finally
        {
            tintRenderInFlight.set( false );
            // Catch the race where a new request landed after the loop's last check but
            // before tintRenderInFlight cleared.
            if ( pendingTintRequest.get() != null && tintRenderInFlight.compareAndSet( false, true ) )
            {
                TINT_POOL.submit( this::drainTintQueue );
            }
        }
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
                    myScaledFrames.add( getImage() );

                    // When rounded scale steps, queue every frame THIS mascot has used for
                    // rebuild and drain a small budget of it each tick, instead of blocking
                    // one tick on the whole set. Deliberately scoped to myScaledFrames, not
                    // scalables.keySet() — scalables is now shared across every mascot, so
                    // its full keyset would include frames belonging to other characters
                    // entirely. Frames not yet rebuilt fall back to lastScaledImage below
                    // (stale-but-correct), the same path already used while a scale is
                    // "still building".
                    double roundedScale = Math.round( currentScale * 10.0 ) / 10.0;
                    if( roundedScale != lastDisplayScale )
                    {
                        pendingScaleBuild = new java.util.ArrayDeque<>( myScaledFrames );
                        lastDisplayScale = roundedScale;
                    }
                    if( pendingScaleBuild != null )
                    {
                        for( int i = 0; i < SCALE_BUILDS_PER_TICK && !pendingScaleBuild.isEmpty(); i++ )
                        {
                            MascotImage frame = pendingScaleBuild.poll();
                            com.group_finity.mascot.image.ScalableNativeImage sni = scalables.get( frame );
                            if( sni != null ) sni.get( roundedScale );
                        }
                        if( pendingScaleBuild.isEmpty() ) pendingScaleBuild = null;
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
                    // scalables itself is shared now — do NOT null it here, other mascots
                    // (including other clones of this same character) may still be using
                    // the cached ScalableNativeImage entries it holds. Only this instance's
                    // own tracking state resets.
                    pendingScaleBuild = null;
                    myScaledFrames.clear();
                    lastScaledImage = null;
                    lastBaseImageHeight = -1;
                    lastBaseImageWidth  = -1;
                    lastDisplayScale = 0.0;
                }

                publishCollision( displayImage, bounds );

                final boolean tintActive = tintColor != null && tintCurrentOpacity > 0.001f;
                final boolean visible    = getWindow().asComponent().isVisible();

                // Dirty-check: when this is the SAME frame at the SAME position as last apply
                // (and no animated tint is overlaying it), the layered window already shows
                // exactly this — re-running UpdateLayeredWindow/setBounds is pure waste. Skipping
                // it for the many static mascots is what removes the per-tick render pile-up.
                // (Tint is sensor-animated, so a tinted mascot must always re-composite.)
                final boolean unchanged = !tintActive && visible
                    && displayImage == lastAppliedImage
                    && bounds.equals( lastAppliedBounds );

                if( !unchanged )
                {
                    // Display visibility toggle stays synchronous — cheap, and shouldn't wait
                    // on either async path below.
                    if (!visible)
                    {
                        getWindow().asComponent().setVisible(true);
                    }

                    // Reposition immediately so hit-testing/collision (which reads the Swing
                    // component's bounds) stays live with the tick loop even though the pixel
                    // update for a tinted mascot may now lag by up to one composite cycle —
                    // the same one-cycle tradeoff already accepted for the async blit below.
                    getWindow().asComponent().setBounds(bounds);

                    // Set Images — apply tint overlay if active
                    if( tintActive )
                    {
                        java.awt.image.BufferedImage src = displayImage.getBufferedImage( );
                        if( src != null )
                        {
                            // Tint compositing (Java2D draw+fill) is expensive and was the
                            // actual bottleneck for CPU/sensor-reactive mascots — moved off
                            // the tick thread. Snapshot color/opacity now since they're
                            // mutated on the tick thread and would race if read later on
                            // the worker.
                            pendingTintRequest.set( new TintRequest( src, tintColor, tintCurrentOpacity, bounds ) );
                            if ( tintRenderInFlight.compareAndSet( false, true ) )
                            {
                                TINT_POOL.submit( this::drainTintQueue );
                            }
                        }
                        else
                        {
                            getWindow( ).setImage( displayImage.getImage( ) );
                            getWindow().updateImage(bounds);
                        }
                    }
                    else
                    {
                        getWindow( ).setImage( displayImage.getImage( ) );
                        getWindow().updateImage(bounds);
                    }

                    // Remember what we composited. A tinted frame is never cached (it animates
                    // every tick), so the next tick always re-composites it.
                    lastAppliedImage  = tintActive ? null : displayImage;
                    lastAppliedBounds = bounds;
                }
            }
            else
            {
                com.group_finity.mascot.environment.CollisionRegistry.remove( id );
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
                        final float vol = getImageSetVolume( );  // 0.0 (mute) .. 1.0 (full)
                        if( vol > 0f )
                        {
                            applyClipGain( clip, sound, vol );
                            clip.stop( );
                            clip.setMicrosecondPosition( 0 );
                            clip.start( );
                            // Tell the situational model we just made noise, so its audio
                            // signal (WASAPI loopback) doesn't read our own SFX as the user's.
                            com.group_finity.mascot.assistant.SituationModel.noteSelfAudio( );
                        }
                    }
                }
            }
        }
    }

    private void parsePeerToneLine( final String payload, final com.group_finity.mascot.assistant.MascotMemory memory )
    {
        new com.group_finity.mascot.assistant.AssistantMemory( this ).parsePeerToneLine( payload, memory );
    }

    /** True if {@code name} matches a real loaded image set (case-insensitive). Used to
     *  reject hallucinated / non-existent peer names before they enter peerTones. */

    void maybeSummarizeMemory( )
    {
        new com.group_finity.mascot.assistant.AssistantMemory( this ).maybeSummarizeMemory( );
    }

    public void applyBubbleSettings()
    {
        for( final com.group_finity.mascot.assistant.AssistantBubble b : activeBubbles )
            b.applySettings();
        // Reset client so next request uses the newly selected model
        ollamaClient = null;
    }

    void ensureFreshBubble( )
    {
        new com.group_finity.mascot.assistant.AssistantSupport( this ).ensureFreshBubble( );
    }

    String weatherContext( final String userText )
    {
        return new com.group_finity.mascot.assistant.AssistantPrompts( this ).weatherContext( userText );
    }

    static String captureScreenBase64( ) throws java.awt.AWTException, java.io.IOException
    {
        return com.group_finity.mascot.assistant.AssistantSupport.captureScreenBase64( );
    }

    void handleScreenQuery( final String userText, final String system, final OllamaClient client )
    {
        new com.group_finity.mascot.assistant.AssistantReplies( this ).handleScreenQuery( userText, system, client );
    }

    static String audioSnapshotContext( )
    {
        return com.group_finity.mascot.assistant.AssistantPrompts.audioSnapshotContext( );
    }

    static OllamaClient createOllamaClient( )
    {
        return com.group_finity.mascot.assistant.AssistantSupport.createOllamaClient( );
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

        if( consoleReadout != null ) { consoleReadout.dispose(); consoleReadout = null; }

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

        // Voice and peer-speech registrations are keyed by image set / name, shared
        // by all mascots of this set. Only tear them down when this is the last one;
        // otherwise hand them to a surviving clone (its earlier registration may have
        // been overwritten by ours, so re-registering keeps reactions alive).
        Mascot survivor = null;
        if( getManager( ) != null )
        {
            for( final Mascot m : getManager( ).getMascotList( ) )
            {
                if( m != this && getImageSet( ).equals( m.getImageSet( ) ) )
                {
                    survivor = m;
                    break;
                }
            }
        }
        if( survivor == null )
        {
            unregisterVoiceCommand( );
            com.group_finity.mascot.assistant.MascotSpeechRegistry.unregister( getImageSet( ) );
        }
        else if( survivor.assistantMode )
        {
            survivor.registerPeerListener( );
            survivor.registerVoiceCommand( );
        }
        for( String key : new String[]{ "Breeding", "Transients", "Transformation", "Throwing", "Sounds", "Multiscreen", "ScreenLoop" } )
            Main.getInstance( ).getProperties( ).remove( key + ".mascot" + id );
        com.group_finity.mascot.environment.CollisionRegistry.remove( id );
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
        this.anchor = anchor;
    }

    public void setAnchorXY( final int x, final int y )
    {
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

    @Override
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

    @Override
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

    void setAnimating( final boolean animating )
    {
        this.animating = animating;
    }

    TranslucentWindow getWindow( )
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

    /** Net horizontal pixels moved during the last tick (any movement: walk, fall, throw, drag). */
    public int getLastDeltaX( ) { return lastDeltaX; }
    /** Net vertical pixels moved during the last tick (any movement: walk, fall, throw, drag). */
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

    // ── Collision (other mascots treat this one like a window) ────────────────
    private boolean collisionOn      = false;
    private int     collisionChecked = 0;

    /** True when Collision is enabled for this mascot's image set (per-set key, else global). */
    public boolean isCollisionEnabled( )
    {
        final java.util.Properties props = Main.getInstance( ).getProperties( );
        final String perImageSet = props.getProperty( "Collision.imageset." + getImageSet( ) );
        if( perImageSet != null )
            return Boolean.parseBoolean( perImageSet );
        return Boolean.parseBoolean( props.getProperty( "Collision", "false" ) );
    }

    /** Publish (or withdraw) this mascot's opaque-pixel rectangle in screen coordinates. */
    private void publishCollision( final MascotImage img, final java.awt.Rectangle bounds )
    {
        if( collisionChecked-- <= 0 )            // re-read the setting ~twice a second
        {
            collisionOn = isCollisionEnabled( );
            collisionChecked = 15;
        }
        if( !collisionOn || img == null || bounds == null )
        {
            com.group_finity.mascot.environment.CollisionRegistry.remove( id );
            return;
        }
        final java.awt.Rectangle ob =
            com.group_finity.mascot.environment.CollisionRegistry.cachedOpaqueBounds( img.getBufferedImage( ) );
        if( ob == null )
        {
            com.group_finity.mascot.environment.CollisionRegistry.remove( id );
            return;
        }
        com.group_finity.mascot.environment.CollisionRegistry.publish( id,
            new java.awt.Rectangle( bounds.x + ob.x, bounds.y + ob.y, ob.width, ob.height ) );
    }

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

    // AssistantHost: state accessors for the assistant classes (fields stay here; Mascot's own code uses them directly)
    @Override public com.group_finity.mascot.assistant.AssistantBubble assistantBubble( ) { return assistantBubble; }
    @Override public void setAssistantBubble( final com.group_finity.mascot.assistant.AssistantBubble b ) { assistantBubble = b; }
    @Override public com.group_finity.mascot.assistant.AssistantInputDialog activeDialog( ) { return activeDialog; }
    @Override public void setActiveDialog( final com.group_finity.mascot.assistant.AssistantInputDialog d ) { activeDialog = d; }
    @Override public String lastActiveWindowTitle( ) { return lastActiveWindowTitle; }
    @Override public java.util.List<com.group_finity.mascot.assistant.AssistantBubble> activeBubbles( ) { return activeBubbles; }

    // AssistantHost: configuration of this mascot's image set (same lookup the assistant classes used inline)
    @Override
    public com.group_finity.mascot.config.Configuration getConfiguration( )
    {
        return Main.getInstance( ).getConfiguration( getImageSet( ) );
    }

    // AssistantHost: countdown bubble, created and registered on first use
    @Override
    public com.group_finity.mascot.assistant.AssistantBubble timerBubble( )
    {
        if( timerBubble == null )
        {
            timerBubble = new com.group_finity.mascot.assistant.AssistantBubble(
                this::handleReply, getImageSet( ) );
            activeBubbles.add( timerBubble );
        }
        return timerBubble;
    }

    @Override
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

    /** Per-image-set playback volume, 0.0 (mute) .. 1.0 (full). Read live at playback from
     *  {@code Volume.imageset.<name>} (percent, default 100). */
    private float getImageSetVolume( )
    {
        try
        {
            int pct = Integer.parseInt(
                Main.getInstance( ).getProperties( ).getProperty( "Volume.imageset." + getImageSet( ), "100" ).trim( ) );
            if( pct < 0 ) pct = 0;
            if( pct > 100 ) pct = 100;
            return pct / 100f;
        }
        catch( final Exception e )
        {
            return 1f;
        }
    }

    /** Sets the shared clip's MASTER_GAIN to its baked pose dB plus this image set's volume
     *  attenuation, just before play. Safe with shared/cached clips because the !isRunning()
     *  guard means two mascots never drive the same clip instance at once. {@code vol} is in (0,1]. */
    private void applyClipGain( final javax.sound.sampled.Clip clip, final String soundKey, final float vol )
    {
        try
        {
            final javax.sound.sampled.FloatControl gain =
                (javax.sound.sampled.FloatControl) clip.getControl( javax.sound.sampled.FloatControl.Type.MASTER_GAIN );
            float db = Sounds.getBaseGain( soundKey ) + (float)( 20.0 * Math.log10( vol ) );
            db = Math.max( gain.getMinimum( ), Math.min( gain.getMaximum( ), db ) );
            gain.setValue( db );
        }
        catch( final Exception e ) { /* leave gain unchanged on any control issue */ }
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
