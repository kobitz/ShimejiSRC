package com.group_finity.mascot.assistant;

import com.group_finity.mascot.Main;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Floating assistant text above a mascot, styled to match MascotTooltip.
 *
 * Clicking any message opens an inline reply input below the bubble.
 * A red X button top-right dismisses everything.
 *
 * All calls must be on the EDT.
 */
public class AssistantBubble
{
    // ── Appearance (read from properties at construction; call applySettings() to refresh) ──
    private static final int   MAX_MESSAGES   = 5;
    private static final int   Y_GAP          = 8;

    // These are loaded from Main properties and may be changed via Settings
    private int   bubbleWidth    = 180;
    private float fontSize       = 14f;
    private String fontName      = null;  // null = use UBUNTU_R
    private boolean showBubbleBg = false;

    private static final int   SHADOW_R          = 3;
    private static final int   SHADOW_DX         = 1;
    private static final int   SHADOW_DY         = 1;
    private static final int   SHADOW_PAD        = SHADOW_R + Math.max( SHADOW_DX, SHADOW_DY );
    private static final int   SHADOW_ALPHA_FULL = 140;
    private static final int   BUBBLE_H_PAD      = 2;  // extra px between text and bubble edge
    private static final int   MSG_V_PAD         = 5;  // internal vertical padding per bubble (multi-bubble mode)
    private static final int   BUBBLE_GAP        = 5;  // gap between stacked bubbles
    private static final int   SEP_PAD           = 3;  // padding above/below separator line (flat mode)
    private static final int   INTER_BUBBLE_GAP  = 4;  // minimum gap enforced between sibling bubbles
    // Sentinel timestamp for active timer bubbles — higher than any real millis value so the
    // timer bubble is always treated as "newest" by the stacking system (= closest to mascot).
    private static final long  TIMER_TIMESTAMP   = Long.MAX_VALUE - 1;

    // Registry of all live bubbles so each can avoid overlapping its siblings.
    // CopyOnWriteArrayList: ManagerThread iterates, EDT adds/removes — both safe.
    private static final List<AssistantBubble> ALL_BUBBLES = new CopyOnWriteArrayList<>();

    // One shared 16ms EDT timer drives all bubble lerp animations.
    // Replaces per-bubble timers so the OS gets one batch of window moves per frame
    // instead of N separate native calls each triggering its own compositing update.
    // Stops itself when no bubble needs moving; restarted (via invokeLater) when any
    // bubble gets a new target. coalesce=true prevents event queue pile-up.
    private static final Timer    SHARED_MOVER;
    private static final Runnable MOVER_STARTER;
    static {
        SHARED_MOVER = new Timer( 16, e -> {
            boolean anyMoving = false;
            for( final AssistantBubble b : ALL_BUBBLES )
                if( b.animateStep() ) anyMoving = true;
            if( !anyMoving ) ( (Timer) e.getSource() ).stop();
        });
        SHARED_MOVER.setCoalesce( true );
        MOVER_STARTER = () -> { if( !SHARED_MOVER.isRunning() ) SHARED_MOVER.start(); };
    }

    // X button
    private static final int   X_BTN_R    = 8;  // radius
    private static final Color X_BTN_BG   = new Color( 200, 60, 60 );
    private static final Color X_BTN_FG   = Color.WHITE;

    // Reply input styling (matches AssistantInputDialog)
    private static final Color INPUT_BG   = new Color( 245, 245, 245 );
    private static final Color BTN_BG     = new Color(  60, 120, 200 );
    private static final Color BTN_FG     = Color.WHITE;

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final int   FADE_DELAY_MS    = 5_000;
    private static final int   FADE_DURATION_MS = 10_000;
    private static final float MIN_ALPHA        = 0.01f;
    private static final int   FADE_TICK_MS     = 50;

    // ── Reply callback ────────────────────────────────────────────────────────
    public interface ReplyCallback
    {
        /** Called when the user submits a reply.
         *  @param userText  what the user typed
         *  @param message   the full Message being replied to (contains thread + context) */
        void onReply( String userText, Message message );
    }

    // ── Per-message state ─────────────────────────────────────────────────────
    public static final class Message
    {
        public final String text;
        public final String context;
        public float  alpha  = 1.0f;
        public Timer  timer  = null;
        public int    yStart = 0;
        public int    yEnd   = 0;

        /** Conversation thread: each entry is {role, text}. Grows with each reply. */
        public final java.util.List<String[]> thread = new java.util.ArrayList<>();

        /** True when this message was produced by the Say XML action (not Ollama). */
        public boolean fromSay   = false;
        /** True when this message is a timer countdown or fired reminder. */
        public boolean fromTimer = false;
        /** True once the countdown has ended and the reminder text is showing. */
        public boolean timerFired = false;
        /** Live-updated text (e.g. countdown); when non-null, overrides text for display. */
        public volatile String displayText = null;

        /** Returns the text to render — displayText when set, text otherwise. */
        public String getDisplayText() { return displayText != null ? displayText : text; }

        Message( final String text, final String context )
        {
            this.text    = text;
            this.context = context;
            // Seed thread with mascot's opening line
            thread.add( new String[]{ "mascot", text } );
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final JWindow   window;
    private final TextPanel panel;
    private final JDialog   replyDialog;
    private final JTextField replyField;
    private       boolean   replyFocusArmed = false;

    private volatile boolean thinking = false;

    private final List<Message> messages = new ArrayList<>();

    /** Tiny overlay window for the X dismiss button. */
    private final JWindow xWindow;
    private Message selectedMessage = null;  // message being replied to

    private ReplyCallback replyCallback = null;

    // Chronological index for Y-stacking: every showResponse() stamps this with
    // System.currentTimeMillis(). positionWindow() finds the immediately newer sibling
    // (next higher timestamp) and keeps this bubble's bottom above that sibling's midpoint.
    // Cleared on showThinking() so Thinking... always sits at natural position.
    // hasAiMessages tracks whether any non-Say messages are still visible; Say-only
    // bubbles (stale timestamp) are excluded from the index so they don't drag each other.
    // Both fields are volatile so the Manager thread can read without locking.
    private final String ownerImageSet;
    private volatile long    lastResponseTimestamp = 0L;
    private volatile boolean hasAiMessages         = false;
    private volatile boolean hasSayMessage         = false;
    // Timer state — managed by showCountdown/fireTimerAlarm/clearTimerState on EDT.
    private volatile boolean timerActive            = false;
    private          Message timerMsg               = null;
    private          Timer   countdownTicker        = null;
    private          long    timerEndMs             = 0L;    // absolute fire time
    private          String  timerReminder          = null;
    private          boolean timerPaused            = false;
    private          long    timerPausedRemainingMs = 0L;    // remaining ms at pause

    private Rectangle lastBounds  = null;
    private boolean   bubbleAbove = true;  // tail points down when true
    private int       lastMascotCX = Integer.MIN_VALUE;   // mascot center X for tail tracking; MIN_VALUE = unset
    private int       lastMascotCY = Integer.MIN_VALUE;   // mascot center Y for proximity checks
    // Cached from layout() so paintComponent() never recomputes line counts mid-frame
    private int       cachedBubbleContentH = 0;

    // Smooth window movement
    private static final float LERP      = 0.2f; // fraction of distance to close each tick
    private static final float LERP_TAIL = 0.4f; // faster lerp for mascot pos — leads window during movement
    private static final float SNAP_PX   = 1.0f; // snap to target within this many pixels
    private float   curX           = -9999f;
    private float   curY           = -9999f;
    private float   smoothMascotCX = -9999f;
    private boolean everPositioned = false;
    private volatile int     targetX     = 0;
    private volatile int     targetY     = 0;
    private volatile boolean needsMove   = false;  // signals SHARED_MOVER to animate this bubble
    private Rectangle lastScreen = null;
    private Timer     screenChangeTimer = null;

    // ── Inner panel ───────────────────────────────────────────────────────────
    private final class TextPanel extends JPanel
    {
        int maxWidth = bubbleWidth;

        TextPanel() { setOpaque( false ); }

        private Color textColor( final float alpha )
        {
            return new Color( 255, 255, 255, Math.round( 255 * alpha ) );
        }

        private Color shadowColor( final float alpha )
        {
            final int a = Math.round( SHADOW_ALPHA_FULL * alpha );
            return new Color( 0, 0, 0, a );
        }

        private void drawShadowed( final Graphics2D g2, final String str,
                                   final int x, final int baseline,
                                   final Color tCol, final Color sCol )
        {
            if( str == null || str.isEmpty() ) return;
            // Outline (4 cardinal positions) + drop shadow (offset copy) via plain drawString.
            // Glyph-cache friendly — far cheaper than TextLayout + stroke.
            g2.setColor( sCol );
            g2.drawString( str, x - 1, baseline     );  // outline left
            g2.drawString( str, x + 1, baseline     );  // outline right
            g2.drawString( str, x,     baseline - 1 );  // outline top
            g2.drawString( str, x,     baseline + 1 );  // outline bottom
            g2.drawString( str, x + 2, baseline + 2 );  // drop shadow
            g2.setColor( tCol );
            g2.drawString( str, x, baseline );
        }

        /** Draws wrapped centered text; returns the new baseline after last line. */
        private int drawWrappedShadowed( final Graphics2D g2, final String text,
                                         final int startBaseline,
                                         final Color tCol, final Color sCol )
        {
            return drawWrappedShadowed( g2, text, startBaseline, getWidth() / 2, tCol, sCol );
        }

        private int drawWrappedShadowed( final Graphics2D g2, final String text,
                                         final int startBaseline, final int centerX,
                                         final Color tCol, final Color sCol )
        {
            if( text == null || text.isEmpty() ) return startBaseline;
            final FontMetrics fm = g2.getFontMetrics();
            final int lineH  = fm.getHeight();
            final int avail  = maxWidth - SHADOW_PAD * 2 - BUBBLE_H_PAD * 2;
            final int midX   = centerX;
            int baseline     = startBaseline;
            final StringBuilder line = new StringBuilder();
            for( final String word : text.split( " ", -1 ) )
            {
                final String test = line.length() == 0 ? word : line + " " + word;
                if( fm.stringWidth( test ) > avail && line.length() > 0 )
                {
                    final int cx = midX - fm.stringWidth( line.toString() ) / 2;
                    drawShadowed( g2, line.toString(), cx, baseline, tCol, sCol );
                    baseline += lineH;
                    line.setLength( 0 );
                    line.append( word );
                }
                else
                {
                    if( line.length() > 0 ) line.append( ' ' );
                    line.append( word );
                }
            }
            if( line.length() > 0 )
            {
                final int cx = midX - fm.stringWidth( line.toString() ) / 2;
                drawShadowed( g2, line.toString(), cx, baseline, tCol, sCol );
                baseline += lineH;
            }
            return baseline;
        }

        @Override protected void paintComponent( final Graphics g )
        {
            final Graphics2D g2 = (Graphics2D) g.create();
            // Explicitly clear to transparent each frame — on secondary monitors Windows does not
            // reliably pre-clear the graphics context, causing frames to accumulate and making
            // AlphaComposite-based fades invisible (bubble stays opaque until setVisible(false)).
            g2.setComposite( AlphaComposite.getInstance( AlphaComposite.CLEAR ) );
            g2.fillRect( 0, 0, getWidth(), getHeight() );
            g2.setComposite( AlphaComposite.getInstance( AlphaComposite.SRC_OVER ) );
            g2.setRenderingHint( RenderingHints.KEY_TEXT_ANTIALIASING,
                                 RenderingHints.VALUE_TEXT_ANTIALIAS_ON );
            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON );
            g2.setFont( getFont() );

            final FontMetrics fm = g2.getFontMetrics();
            final int lineH = fm.getHeight();
            final int tailOffset = showBubbleBg ? ( bubbleAbove ? 2 : 10 ) : 0;
            int baseline = fm.getAscent() + SHADOW_PAD + tailOffset;

            // Count visible messages and find boundary indices for tail placement
            int visCount = 0;
            int firstVisIdx = -1, lastVisIdx = -1;
            for( int i = 0; i < messages.size(); i++ )
            {
                if( messages.get(i).alpha > 0f )
                {
                    if( firstVisIdx < 0 ) firstVisIdx = i;
                    lastVisIdx = i;
                    visCount++;
                }
            }

            // bubbleContentH: height of the bubble portion only (excludes Thinking... space).
            // Cached from layout() so paintComponent never recomputes line counts each frame.
            final int bubbleContentH = ( cachedBubbleContentH > 0
                && showBubbleBg && thinking && visCount > 0 )
                ? cachedBubbleContentH : getHeight();

            final boolean multiBubble = showBubbleBg && !thinking && visCount > 1;
            boolean textAlreadyDrawn = false;

            // ── Bubble background ──────────────────────────────────────
            if( showBubbleBg && !messages.isEmpty() )
            {
                final int w      = getWidth();
                final int tailW  = multiBubble ? 8 : 10;
                final int tailH  = 10;
                final int r      = multiBubble ? 10 : 12;
                // Tip tracks mascot anchor directly: window lags behind mascot during
                // movement, so the tip naturally shifts off-center, driving the blend.
                final int rawTailX = ( lastMascotCX != Integer.MIN_VALUE && window != null
                                        && smoothMascotCX > -9000f )
                    ? Math.max( r + tailW, Math.min( w - r - tailW,
                        Math.round( smoothMascotCX ) - window.getX() ) )
                    : w / 2;
                final float blend = Math.max( -1f, Math.min( 1f,
                    ( rawTailX - w / 2f ) / tailW ) );
                final int tailX = Math.max( r + tailW, Math.min( w - r - tailW,
                    rawTailX - Math.round( blend * 16 ) ) );

                if( !multiBubble )
                {
                    // ── Single bubble ──────────────────────────────────
                    float maxAlpha = 0f;
                    for( final Message msg : messages )
                        if( msg.alpha > maxAlpha ) maxAlpha = msg.alpha;

                    final int h = bubbleContentH;

                    final java.awt.geom.Path2D.Float bubblePath = buildBubblePath(
                        w, h, r, tailX, tailW, tailH, bubbleAbove, blend );

                    // Use explicit composite extraAlpha (not baked-in Color alpha) —
                    // same pattern as text shadow rendering which works on all monitors.
                    final Composite prevComp1 = g2.getComposite();
                    g2.setComposite( AlphaComposite.getInstance( AlphaComposite.SRC_OVER, maxAlpha ) );

                    // Simple drop shadow: one fill at offset, white fill covers interior.
                    // Avoids Area.subtract() + 28-shape loop which causes GC spikes at 60fps.
                    g2.setColor( new Color( 0, 0, 0, SHADOW_ALPHA_FULL ) );
                    g2.fill( bubblePath.createTransformedShape(
                        java.awt.geom.AffineTransform.getTranslateInstance( SHADOW_DX + 2, SHADOW_DY + 2 ) ) );

                    g2.setColor( Color.WHITE );
                    g2.fill( bubblePath );
                    g2.setComposite( prevComp1 );
                }
                else
                {
                    // ── Per-message bubbles ────────────────────────────
                    textAlreadyDrawn = true;
                    int yOff = bubbleAbove ? 0 : tailH + 2;

                    for( int i = 0; i < messages.size(); i++ )
                    {
                        final Message msg = messages.get(i);
                        if( msg.alpha <= 0f ) continue;

                        final boolean tailBelow = bubbleAbove  && ( i == lastVisIdx );
                        final boolean tailAbove = !bubbleAbove && ( i == firstVisIdx );
                        final boolean hasTail   = tailBelow || tailAbove;
                        final int lines = countWrappedLines( fm, msg.getDisplayText() );
                        final int textH = fm.getAscent() + fm.getDescent()
                                          + lineH * Math.max( 0, lines - 1 );
                        final int bH = SHADOW_PAD * 2 + MSG_V_PAD * 2 + textH + 4
                                       + ( hasTail ? tailH : 0 );

                        // Per-bubble width and centered horizontal offset
                        final int bW    = measureMsgWidth( fm, msg.getDisplayText() );
                        final int xOff2 = ( getWidth() - bW ) / 2;

                        // Tail X in bubble-local coordinates
                        final int rawLocalTailX = ( lastMascotCX != Integer.MIN_VALUE && window != null
                                                    && smoothMascotCX > -9000f )
                            ? Math.max( r + tailW, Math.min( bW - r - tailW,
                                Math.round( smoothMascotCX ) - window.getX() - xOff2 ) )
                            : bW / 2;

                        final float blendMulti = Math.max( -1f, Math.min( 1f,
                            ( rawLocalTailX - bW / 2f ) / tailW ) );
                        final int localTailX = Math.max( r + tailW, Math.min( bW - r - tailW,
                            rawLocalTailX - Math.round( blendMulti * 16 ) ) );
                        final java.awt.geom.Path2D.Float path = hasTail
                            ? buildBubblePath( bW, bH, r, localTailX, tailW, tailH, tailBelow, blendMulti )
                            : buildRoundedRectPath( bW, bH, r );

                        final float alpha     = msg.alpha;
                        final float drawAlpha = selectedMessage == msg
                            ? Math.max( alpha, 0.85f ) : alpha;

                        // Explicit composite extraAlpha — same pattern as text shadow, works on all monitors.
                        final Composite prevBg2 = g2.getComposite();
                        g2.setComposite( AlphaComposite.getInstance( AlphaComposite.SRC_OVER, drawAlpha ) );

                        final java.awt.geom.AffineTransform yAt =
                            java.awt.geom.AffineTransform.getTranslateInstance( xOff2, yOff );
                        final Shape translatedPath = path.createTransformedShape( yAt );

                        // Simple drop shadow + white fill (covers interior shadow).
                        g2.setColor( new Color( 0, 0, 0, SHADOW_ALPHA_FULL ) );
                        g2.fill( java.awt.geom.AffineTransform.getTranslateInstance(
                            xOff2 + SHADOW_DX + 2, yOff + SHADOW_DY + 2 )
                            .createTransformedShape( path ) );

                        g2.setColor( Color.WHITE );
                        g2.fill( translatedPath );
                        g2.setComposite( prevBg2 );

                        // Text: AlphaComposite works for text rendering on all monitors
                        final Composite prevComp2 = g2.getComposite();
                        g2.setComposite( AlphaComposite.getInstance( AlphaComposite.SRC_OVER, drawAlpha ) );

                        // Text inside bubble (coordinates in panel space)
                        final int textCenterX  = xOff2 + bW / 2;
                        final int textBaseline = yOff + SHADOW_PAD + MSG_V_PAD
                                                 + fm.getAscent()
                                                 + ( tailAbove ? tailH : 0 );
                        msg.yStart = textBaseline - fm.getAscent();

                        if( selectedMessage == msg )
                        {
                            g2.setColor( new Color( 128, 128, 128, 60 ) );
                            g2.fillRoundRect( xOff2 + SHADOW_PAD + 2, msg.yStart,
                                bW - SHADOW_PAD * 2 - 4,
                                lineH * lines, 4, 4 );
                        }

                        final int localEnd = drawWrappedShadowed(
                            g2, msg.getDisplayText(), textBaseline, textCenterX, textColor( 1.0f ), shadowColor( 1.0f ) );
                        msg.yEnd = localEnd;

                        g2.setComposite( prevComp2 );

                        yOff += bH + BUBBLE_GAP;
                    }
                }
            }

            boolean anyMsgDrawn = false;
            if( !textAlreadyDrawn )
            {
                boolean firstDrawn = false;
                for( final Message msg : messages )
                {
                    if( msg.alpha <= 0f ) continue;

                    final float drawAlpha = selectedMessage == msg
                        ? Math.max( msg.alpha, 0.85f ) : msg.alpha;
                    final Composite prevComp3 = g2.getComposite();
                    g2.setComposite( AlphaComposite.getInstance( AlphaComposite.SRC_OVER, drawAlpha ) );

                    // Separator line between messages (flat/no-bg mode)
                    if( !showBubbleBg && firstDrawn )
                    {
                        final int prevBottom = baseline - lineH + fm.getDescent();
                        final int sepY = prevBottom + SEP_PAD;
                        final int sepW = ( getWidth() - SHADOW_PAD * 2 ) / 2;
                        final int sepX = SHADOW_PAD + ( getWidth() - SHADOW_PAD * 2 ) / 4;
                        g2.setColor( shadowColor( 1.0f ) );
                        g2.fillRoundRect( sepX + SHADOW_DX, sepY + SHADOW_DY, sepW, 2, 2, 2 );
                        g2.setColor( Color.WHITE );
                        g2.fillRoundRect( sepX, sepY, sepW, 2, 2, 2 );
                        baseline = sepY + 2 + SEP_PAD + fm.getAscent();
                    }
                    firstDrawn = true;
                    anyMsgDrawn = true;

                    msg.yStart = baseline - fm.getAscent();

                    if( selectedMessage == msg )
                    {
                        g2.setColor( new Color( 128, 128, 128, 60 ) );
                        g2.fillRoundRect( SHADOW_PAD, msg.yStart,
                            getWidth() - SHADOW_PAD * 2,
                            fm.getHeight() * countWrappedLines( fm, msg.getDisplayText() ),
                            4, 4 );
                    }

                    baseline = drawWrappedShadowed( g2, msg.getDisplayText(), baseline, textColor( 1.0f ), shadowColor( 1.0f ) );
                    msg.yEnd = baseline;

                    g2.setComposite( prevComp3 );
                }
            }

            if( thinking )
            {
                if( showBubbleBg && visCount > 0 )
                {
                    // Outside the bubble — drawn below bubble content, no bg
                    final int thinkBaseline = bubbleContentH + SHADOW_PAD + fm.getAscent();
                    drawWrappedShadowed( g2, "Thinking...", thinkBaseline,
                        textColor( 1.0f ), shadowColor( 1.0f ) );
                }
                else
                {
                    // Flat mode or no existing messages — inline after messages
                    if( anyMsgDrawn && !showBubbleBg )
                    {
                        final int prevBottom = baseline - lineH + fm.getDescent();
                        final int sepY = prevBottom + SEP_PAD;
                        final int sepW = ( getWidth() - SHADOW_PAD * 2 ) / 2;
                        final int sepX = SHADOW_PAD + ( getWidth() - SHADOW_PAD * 2 ) / 4;
                        g2.setColor( shadowColor( 1.0f ) );
                        g2.fillRoundRect( sepX + SHADOW_DX, sepY + SHADOW_DY, sepW, 2, 2, 2 );
                        g2.setColor( Color.WHITE );
                        g2.fillRoundRect( sepX, sepY, sepW, 2, 2, 2 );
                        baseline = sepY + 2 + SEP_PAD + fm.getAscent();
                    }
                    drawWrappedShadowed( g2, "Thinking...", baseline,
                        textColor( 1.0f ), shadowColor( 1.0f ) );
                }
            }

            g2.dispose();
        }

        int countWrappedLines( final FontMetrics fm, final String text )
        {
            if( text == null || text.isEmpty() ) return 1;
            int lines = 1, lineW = 0;
            final int avail = maxWidth - SHADOW_PAD * 2 - BUBBLE_H_PAD * 2;
            for( final String word : text.split( " ", -1 ) )
            {
                final int ww = fm.stringWidth( word + " " );
                if( lineW + ww > avail && lineW > 0 ) { lines++; lineW = 0; }
                lineW += ww;
            }
            return lines;
        }

        /** Returns the pixel width a bubble needs for this message's text. */
        int measureMsgWidth( final FontMetrics fm, final String text )
        {
            if( text == null || text.isEmpty() ) return 60;
            final int avail = bubbleWidth - SHADOW_PAD * 2 - BUBBLE_H_PAD * 2;
            int lineW = 0, maxLineW = 0;
            for( final String word : text.split( " ", -1 ) )
            {
                final int ww = fm.stringWidth( word + " " );
                if( lineW + ww > avail && lineW > 0 )
                {
                    maxLineW = Math.max( maxLineW, lineW );
                    lineW = ww;
                }
                else lineW += ww;
            }
            maxLineW = Math.max( maxLineW, lineW );
            return Math.max( 60, Math.min( maxLineW + SHADOW_PAD * 2 + 6, bubbleWidth ) );
        }

        /** Returns the message at panel-relative y, or null. */
        Message messageAt( final int y )
        {
            for( final Message msg : messages )
                if( msg.alpha > 0f && y >= msg.yStart && y < msg.yEnd )
                    return msg;
            return null;
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /** Reload display settings from Main properties. Call after settings change. */
    public void applySettings()
    {
        final java.util.Properties p = Main.getInstance().getProperties();
        bubbleWidth    = Integer.parseInt( p.getProperty( "BubbleWidth",    "180" ) );
        fontSize       = Float.parseFloat( p.getProperty( "BubbleFontSize", "14" ) );
        fontName       = p.getProperty( "BubbleFontName", "" );
        if( fontName != null && fontName.isEmpty() ) fontName = null;
        showBubbleBg   = Boolean.parseBoolean( p.getProperty( "BubbleBackground", "false" ) );
        if( panel != null )
        {
            panel.maxWidth = bubbleWidth;
            panel.setFont( buildFont() );
            panel.repaint();
        }
        if( replyDialog != null )
            replyDialog.setSize( bubbleWidth, replyDialog.getHeight() );
    }

    private java.awt.Font buildFont()
    {
        if( fontName != null )
            return new java.awt.Font( fontName, java.awt.Font.PLAIN, (int) fontSize );
        return Main.UBUNTU_B.deriveFont( java.awt.Font.PLAIN, fontSize );
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    public AssistantBubble( final ReplyCallback replyCallback, final String ownerImageSet )
    {
        this.replyCallback  = replyCallback;
        this.ownerImageSet  = ownerImageSet;
        ALL_BUBBLES.add( this );

        applySettings();

        panel = new TextPanel();
        panel.setFont( buildFont() );
        panel.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );

        window = new JWindow();
        window.setAlwaysOnTop( true );
        window.setFocusable( false );
        window.setFocusableWindowState( false );
        window.setBackground( new Color( 0, 0, 0, 0 ) );
        window.setLocation( -9999, -9999 ); // keep off-screen until first layout
        window.setContentPane( panel );

        // ── X overlay window ──────────────────────────────────────────────
        xWindow = new JWindow();
        xWindow.setAlwaysOnTop( true );
        xWindow.setFocusable( false );
        xWindow.setFocusableWindowState( false );
        xWindow.setBackground( new Color( 0, 0, 0, 0 ) );
        final int xd = X_BTN_R * 2 + 2;
        xWindow.setSize( xd, xd );

        final JPanel xPanel = new JPanel() {
            @Override protected void paintComponent( final Graphics g ) {
                final Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
                g2.setColor( X_BTN_BG );
                g2.fillOval( 1, 1, xd - 2, xd - 2 );
                g2.setColor( X_BTN_FG );
                g2.setFont( getFont().deriveFont( Font.BOLD, 9f ) );
                final FontMetrics xfm = g2.getFontMetrics();
                final String lbl = "x";
                g2.drawString( lbl, xd/2 - xfm.stringWidth(lbl)/2 - 1, xd/2 + xfm.getAscent()/2 - 2 );
                g2.dispose();
            }
        };
        xPanel.setOpaque( false );
        xPanel.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );
        xWindow.setContentPane( xPanel );
        xPanel.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( final MouseEvent e ) {
                if( selectedMessage != null ) {
                    final Message toRemove = selectedMessage;
                    // Timer messages need full state teardown (stops ticker too).
                    if( toRemove.fromTimer )
                    {
                        clearTimerState();
                        if( lastBounds != null ) layout( lastBounds );
                        if( messages.isEmpty() && !thinking ) window.setVisible( false );
                        return;
                    }
                    closeReplyDialog();
                    if( toRemove.timer != null ) { toRemove.timer.stop(); toRemove.timer = null; }
                    messages.remove( toRemove );
                    recheckAiMessages();
                    if( lastBounds != null ) layout( lastBounds );
                    if( messages.isEmpty() && !thinking ) window.setVisible( false );
                }
            }
        });

        // ── Click handler ─────────────────────────────────────────────────
        panel.addMouseListener( new MouseAdapter()
        {
            @Override public void mousePressed( final MouseEvent e )
            {
                final Point p = e.getPoint();

                // Hit-test messages
                final Message hit = panel.messageAt( p.y );
                if( hit != null )
                {
                    // Active countdown — click toggles pause/resume; X button dismisses.
                    if( hit.fromTimer && !hit.timerFired )
                    {
                        if( !timerPaused )
                        {
                            pauseCountdown();
                            selectedMessage = hit;
                            positionXWindow();
                            xWindow.setVisible( true );
                            panel.repaint();
                        }
                        else
                        {
                            resumeCountdown();
                            selectedMessage = null;
                            xWindow.setVisible( false );
                            panel.repaint();
                        }
                        return;
                    }
                    // Fired reminder — click-to-dismiss, no reply dialog.
                    if( hit.fromTimer )
                    {
                        if( hit.timer != null ) { hit.timer.stop(); hit.timer = null; }
                        messages.remove( hit );
                        recheckAiMessages();
                        if( lastBounds != null ) layout( lastBounds );
                        if( messages.isEmpty() && !thinking ) window.setVisible( false );
                        return;
                    }
                    selectedMessage = hit;
                    panel.repaint();
                    openReplyDialog( hit );
                }
            }
        });

        // ── Reply dialog (JDialog for focus) ──────────────────────────────
        replyDialog = new JDialog( (Frame) null, false );
        replyDialog.setUndecorated( true );
        replyDialog.setAlwaysOnTop( true );
        replyDialog.setBackground( new Color( 0, 0, 0, 0 ) );

        final JPanel replyRoot = new JPanel( new BorderLayout( 6, 0 ) );
        replyRoot.setOpaque( false );

        replyField = new JTextField( 14 );
        replyField.setFont( new Font( Font.SANS_SERIF, Font.PLAIN, 13 ) );
        replyField.setBackground( INPUT_BG );
        replyField.setForeground( Color.BLACK );
        replyField.setBorder( BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder( new Color( 120, 120, 120 ), 1 ),
            new EmptyBorder( 4, 6, 4, 6 ) ) );

        final JPanel sendBtn = new JPanel( new GridBagLayout() )
        {
            @Override protected void paintComponent( final Graphics g )
            {
                final Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                     RenderingHints.VALUE_ANTIALIAS_ON );
                g2.setColor( getBackground() );
                g2.fillRoundRect( 0, 0, getWidth(), getHeight(), 6, 6 );
                g2.dispose();
            }
        };
        sendBtn.setBackground( BTN_BG );
        sendBtn.setOpaque( false );
        sendBtn.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );
        sendBtn.setBorder( new EmptyBorder( 5, 8, 5, 8 ) );

        final JLabel sendLabel = new JLabel( "Reply" );
        sendLabel.setFont( new Font( Font.SANS_SERIF, Font.BOLD, 12 ) );
        sendLabel.setForeground( BTN_FG );
        sendBtn.add( sendLabel );

        replyRoot.add( replyField, BorderLayout.CENTER );
        replyRoot.add( sendBtn,   BorderLayout.EAST   );
        replyDialog.setContentPane( replyRoot );
        replyDialog.getRootPane().setOpaque( false );
        replyDialog.pack();
        replyDialog.setSize( bubbleWidth, replyDialog.getHeight() );

        final Runnable submit = () ->
        {
            final String text = replyField.getText().trim();
            if( text.isEmpty() ) return;
            final Message replying = selectedMessage;
            if( replying != null ) replying.thread.add( new String[]{ "user", text } );
            closeReplyDialog();
            if( replyCallback != null && replying != null )
                replyCallback.onReply( text, replying );
        };

        sendBtn.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( final MouseEvent e ) { submit.run(); }
            @Override public void mouseEntered( final MouseEvent e ) { sendBtn.setBackground( BTN_BG.brighter() ); sendBtn.repaint(); }
            @Override public void mouseExited(  final MouseEvent e ) { sendBtn.setBackground( BTN_BG );           sendBtn.repaint(); }
        });
        sendLabel.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( final MouseEvent e ) { submit.run(); }
            @Override public void mouseEntered( final MouseEvent e ) { sendBtn.setBackground( BTN_BG.brighter() ); sendBtn.repaint(); }
            @Override public void mouseExited(  final MouseEvent e ) { sendBtn.setBackground( BTN_BG );           sendBtn.repaint(); }
        });
        replyField.addActionListener( e -> submit.run() );

        final KeyStroke esc = KeyStroke.getKeyStroke( KeyEvent.VK_ESCAPE, 0 );
        replyRoot.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW ).put( esc, "close" );
        replyRoot.getActionMap().put( "close", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) { closeReplyDialog(); }
        });

        replyDialog.addWindowFocusListener( new WindowAdapter() {
            @Override public void windowLostFocus( final WindowEvent e ) {
                if( replyFocusArmed ) closeReplyDialog();
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Show a response with optional trigger context for replies. */
    public void showResponse( final String text, final String context,
                              final Rectangle mascotBounds )
    {
        thinking = false;
        lastResponseTimestamp = System.currentTimeMillis();
        final Message msg = new Message(
            text.isEmpty() ? "(no response)" : text, context );
        addMessage( msg, mascotBounds );
        startFadeTimer( msg, mascotBounds );
    }

    /** Append a mascot reply to a message's thread (for chained replies). */
    public void appendReplyToThread( final Message msg, final String mascotText )
    {
        if( msg != null ) msg.thread.add( new String[]{ "mascot", mascotText } );
    }

    /** Convenience overload — no context. */
    public void showResponse( final String text, final Rectangle mascotBounds )
    {
        showResponse( text, null, mascotBounds );
    }

    /**
     * Show a bubble from the Say XML action.
     * Replaces any existing Say-action bubbles for this mascot so only
     * the most recent Say is visible at a time. Ollama/assistant bubbles
     * (fromSay=false) are left untouched.
     */
    public void showSay( final String text, final Rectangle mascotBounds )
    {
        thinking = false;
        // Remove any existing Say messages — stop their timers cleanly
        final java.util.Iterator<Message> it = messages.iterator();
        while( it.hasNext() )
        {
            final Message m = it.next();
            if( m.fromSay )
            {
                if( m.timer != null ) { m.timer.stop(); m.timer = null; }
                if( selectedMessage == m ) closeReplyDialog();
                it.remove();
            }
        }
        final Message msg = new Message(
            text.isEmpty() ? "(no response)" : text, "[Said by mascot] " + text );
        msg.fromSay = true;
        addMessage( msg, mascotBounds );
        startFadeTimer( msg, mascotBounds );
    }

    public void showThinking( final Rectangle mascotBounds )
    {
        thinking = true;
        lastResponseTimestamp = 0L;
        layout( mascotBounds );
    }

    public void showListening( final Rectangle mascotBounds )
    {
        showResponse( "Listening...", null, mascotBounds );
    }

    public void showError( final String message, final Rectangle mascotBounds )
    {
        showResponse( "⚠ " + message, null, mascotBounds );
    }

    /** True when this bubble has at least one visible AI response (not just thinking/Say). */
    public boolean hasActiveResponse() { return hasAiMessages && !thinking; }

    /** Returns the live bubble owned by the given imageSet, or null if none. */
    public static AssistantBubble forImageSet( final String imageSet )
    {
        if( imageSet == null ) return null;
        for( final AssistantBubble b : ALL_BUBBLES )
            if( imageSet.equals( b.ownerImageSet ) ) return b;
        return null;
    }

    // Last layout dimensions — skip full layout in reposition() when size is stable.
    private int lastLayoutW = -1;
    private int lastLayoutH = -1;

    public void reposition( final Rectangle mascotBounds )
    {
        if( window == null || !window.isVisible() ) return;
        // Fast path: if window size hasn't changed, just update the target position
        // without re-running layout() (scratch BufferedImage, font metrics, setSize, etc.).
        // reposition() is called every 40ms tick; layout() is expensive.
        final int curW = window.getWidth();
        final int curH = window.getHeight();
        if( curW == lastLayoutW && curH == lastLayoutH && lastLayoutW > 0 )
        {
            positionWindow( mascotBounds, curW, curH );
            if( replyDialog.isVisible() ) positionReplyDialog( mascotBounds );
            if( xWindow.isVisible() )     positionXWindow();
            return;
        }
        layout( mascotBounds );
        if( replyDialog.isVisible() ) positionReplyDialog( mascotBounds );
        if( xWindow.isVisible() )     positionXWindow();
    }

    public void dismiss()
    {
        xWindow.setVisible( false );
        closeReplyDialog();
        thinking = false;
        clearTimerState();
        needsMove = false;
        curX = -9999f; curY = -9999f;
        everPositioned = false;
        for( final Message msg : messages )
            if( msg.timer != null ) { msg.timer.stop(); msg.timer = null; }
        messages.clear();
        selectedMessage = null;
        if( screenChangeTimer != null ) { screenChangeTimer.stop(); screenChangeTimer = null; }
        window.setVisible( false );
    }

    public void dispose()
    {
        ALL_BUBBLES.remove( this );
        dismiss();
        replyDialog.dispose();
        xWindow.dispose();
        window.dispose();
    }

    public boolean isVisible()  { return window != null && window.isVisible(); }
    public boolean isThinking() { return window != null && window.isVisible() && thinking; }

    /** Returns this bubble's current target screen rect for sibling collision queries. Null if hidden. */
    public Rectangle getTargetBounds()
    {
        if( !everPositioned || window == null || !window.isVisible() ) return null;
        return new Rectangle( targetX, targetY, lastLayoutW, lastLayoutH );
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void openReplyDialog( final Message msg )
    {
        replyField.setText( "" );
        replyFocusArmed = false;
        if( lastBounds != null ) positionReplyDialog( lastBounds );
        replyDialog.setVisible( true );
        positionXWindow();
        xWindow.setVisible( true );
        SwingUtilities.invokeLater( () ->
        {
            replyDialog.toFront();
            replyField.requestFocusInWindow();
        });
        final Timer armTimer = new Timer( 400, e -> replyFocusArmed = true );
        armTimer.setRepeats( false );
        armTimer.start();
    }

    private void closeReplyDialog()
    {
        replyFocusArmed = false;
        selectedMessage = null;
        replyDialog.setVisible( false );
        xWindow.setVisible( false );
        panel.repaint();
    }

    /**
     * Build a seamless Path2D combining rounded rect + tail as one shape.
     * No seams, works correctly at any opacity.
     */
    private static java.awt.geom.Path2D.Float buildBubblePath(
        final int w, final int h, final int r,
        final int tailX, final int tailW, final int tailH,
        final boolean bubbleAbove, final float tailBlend )
    {
        final java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        final int pad  = 2;
        // Tip slides continuously: blend -1..0..1 maps left-corner..center..right-corner
        final int tipX = tailX + Math.round( tailBlend * ( tailW / 2f ) );
        if( bubbleAbove )
        {
            final int top = pad, bot = h - tailH - pad;
            final int left = pad, right = w - pad;
            p.moveTo( left + r, top );
            p.lineTo( right - r, top );
            p.quadTo( right, top, right, top + r );
            p.lineTo( right, bot - r );
            p.quadTo( right, bot, right - r, bot );
            p.lineTo( tailX + tailW/2, bot );
            p.lineTo( tipX,            h - pad );
            p.lineTo( tailX - tailW/2, bot );
            p.lineTo( left + r, bot );
            p.quadTo( left, bot, left, bot - r );
            p.lineTo( left, top + r );
            p.quadTo( left, top, left + r, top );
        }
        else
        {
            final int top = tailH + pad, bot = h - pad;
            final int left = pad, right = w - pad;
            p.moveTo( left + r, top );
            p.lineTo( tailX - tailW/2, top );
            p.lineTo( tipX,            pad );
            p.lineTo( tailX + tailW/2, top );
            p.lineTo( right - r, top );
            p.quadTo( right, top, right, top + r );
            p.lineTo( right, bot - r );
            p.quadTo( right, bot, right - r, bot );
            p.lineTo( left + r, bot );
            p.quadTo( left, bot, left, bot - r );
            p.lineTo( left, top + r );
            p.quadTo( left, top, left + r, top );
        }
        p.closePath();
        return p;
    }

    private static java.awt.geom.Path2D.Float buildRoundedRectPath(
        final int w, final int h, final int r )
    {
        final java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        final int pad = 2;
        p.moveTo( pad + r, pad );
        p.lineTo( w - pad - r, pad );
        p.quadTo( w - pad, pad, w - pad, pad + r );
        p.lineTo( w - pad, h - pad - r );
        p.quadTo( w - pad, h - pad, w - pad - r, h - pad );
        p.lineTo( pad + r, h - pad );
        p.quadTo( pad, h - pad, pad, h - pad - r );
        p.lineTo( pad, pad + r );
        p.quadTo( pad, pad, pad + r, pad );
        p.closePath();
        return p;
    }

    /** Position the X overlay at top-right of the selected message, above the bubble. */
    private void positionXWindow()
    {
        if( selectedMessage == null ) return;
        final int xd  = X_BTN_R * 2 + 2;
        // Convert message yStart from panel coords to screen coords
        final int screenX = window.getX() + window.getWidth() - X_BTN_R;
        final int screenY = window.getY() + selectedMessage.yStart - X_BTN_R - 4;
        xWindow.setLocation( screenX, screenY );
    }

    private void positionReplyDialog( final Rectangle mascotBounds )
    {
        final Rectangle screen  = getScreenBounds( mascotBounds );
        final int       dlgH    = replyDialog.getHeight();
        final int       bubbleY = window.getY();
        final int       bubbleH = window.getHeight();

        // Place just below the bubble
        int x = window.getX();
        int y = bubbleY + bubbleH + 2;

        if( y + dlgH > screen.y + screen.height )
            y = bubbleY - dlgH - 2;

        x = Math.max( screen.x, Math.min( x, screen.x + screen.width - bubbleWidth ) );
        replyDialog.setLocation( x, y );
    }

    private void addMessage( final Message msg, final Rectangle mascotBounds )
    {
        if( !msg.fromSay )
        {
            // AI responses go above any Say messages — find the first Say message and
            // insert before it so the visual order is: AI responses, then Say messages.
            int insertIdx = messages.size();
            for( int i = 0; i < messages.size(); i++ )
            {
                if( messages.get( i ).fromSay ) { insertIdx = i; break; }
            }
            messages.add( insertIdx, msg );
            hasAiMessages = true;
        }
        else
        {
            messages.add( msg );
            hasSayMessage = true;
            if( lastResponseTimestamp == 0L )
                lastResponseTimestamp = System.currentTimeMillis();
        }
        while( messages.size() > MAX_MESSAGES )
        {
            final Message old = messages.remove( 0 );
            if( old.timer != null ) { old.timer.stop(); old.timer = null; }
            if( selectedMessage == old ) closeReplyDialog();
        }
        layout( mascotBounds );
    }

    private void recheckAiMessages()
    {
        boolean hasAi = false, hasSay = false;
        for( final Message m : messages )
        {
            if( m.fromSay )  hasSay = true;
            else             hasAi  = true;  // AI responses and timer messages both count
        }
        hasAiMessages = hasAi;
        hasSayMessage = hasSay;
        if( !hasAi && !hasSay ) lastResponseTimestamp = 0L;
        // If the timer message was removed externally (e.g. X button), clear the stale ref.
        if( timerMsg != null && !messages.contains( timerMsg ) )
        {
            timerMsg    = null;
            timerActive = false;
        }
    }

    private void startFadeTimer( final Message msg, final Rectangle mascotBounds )
    {
        final int  charCount    = msg.getDisplayText().length();
        final long delayMs      = Math.max( FADE_DELAY_MS, charCount * 250L );
        // Phase 1: immediately fade 1.0 → 0.8 over FADE_DURATION_MS
        final long fadeInEndMs  = System.currentTimeMillis() + FADE_DURATION_MS;
        // Phase 2: hold at 0.8 for delayMs
        final long holdEndMs    = fadeInEndMs + delayMs;
        // Phase 3: fade 0.8 → 0 over FADE_DURATION_MS
        final long fadeOutEndMs = holdEndMs + FADE_DURATION_MS;

        msg.timer = new Timer( FADE_TICK_MS, e ->
        {
            // Don't fade selected message while reply dialog is open
            if( msg == selectedMessage && replyDialog.isVisible() ) return;

            final long now = System.currentTimeMillis();
            final float prevAlpha = msg.alpha;

            if( now < fadeInEndMs )
            {
                // Phase 1: 1.0 → 0.5
                final float t = (float)( now - ( fadeInEndMs - FADE_DURATION_MS ) ) / FADE_DURATION_MS;
                msg.alpha = 1.0f - t * 0.5f;
            }
            else if( now < holdEndMs )
            {
                // Phase 2: hold at 0.5 — skip repaint, nothing visible changes
                msg.alpha = 0.5f;
            }
            else if( now < fadeOutEndMs )
            {
                // Phase 3: 0.5 → 0
                final float t = (float)( now - holdEndMs ) / FADE_DURATION_MS;
                msg.alpha = 0.5f - t * ( 0.5f - MIN_ALPHA );
            }
            else
            {
                msg.alpha = MIN_ALPHA;
            }

            if( msg.alpha <= MIN_ALPHA )
            {
                msg.alpha = 0f;
                msg.timer.stop();
                msg.timer = null;
                messages.remove( msg );
                if( selectedMessage == msg ) closeReplyDialog();
                recheckAiMessages();
                if( lastBounds != null && !messages.isEmpty() )
                    layout( lastBounds );
            }

            // Only repaint if alpha actually changed — avoids 20 repaints/sec
            // during the hold phase where alpha is frozen at 0.5.
            if( msg.alpha != prevAlpha || msg.timer == null )
                panel.repaint();

            if( messages.isEmpty() && !thinking )
                window.setVisible( false );
        });
        msg.timer.start();
    }

    // ── Timer / countdown ─────────────────────────────────────────────────────

    /**
     * Show a live countdown for the given end time, displaying the reminder text
     * alongside. Replaces any existing timer message on this bubble.
     * Fades to 20% alpha after 3s and holds there until the alarm fires.
     * Must be called on the EDT.
     */
    public void showCountdown( final long endMs, final String reminder,
                               final Rectangle mascotBounds )
    {
        clearTimerState();

        timerActive             = true;
        timerPaused             = false;
        timerEndMs              = endMs;
        timerReminder           = reminder;
        lastResponseTimestamp   = TIMER_TIMESTAMP;
        hasAiMessages           = true;

        final Message msg = new Message( formatCountdown( endMs, reminder ), null );
        msg.fromTimer = true;
        msg.alpha     = 1.0f;
        messages.add( msg );
        timerMsg = msg;

        if( mascotBounds != null ) layout( mascotBounds );

        // Fade to 20% after 3 seconds and hold until the alarm fires.
        final Timer fadeDown = new Timer( 3000, e -> {
            if( timerMsg == msg && msg.alpha > 0.2f )
            { msg.alpha = 0.2f; panel.repaint(); }
            ( (Timer) e.getSource() ).stop();
        });
        fadeDown.setRepeats( false );
        fadeDown.start();

        startCountdownTicker( msg, endMs, reminder );
    }

    private void startCountdownTicker( final Message msg, final long endMs,
                                       final String reminder )
    {
        if( countdownTicker != null ) { countdownTicker.stop(); }
        countdownTicker = new Timer( 1000, e -> {
            if( timerMsg != msg ) { ( (Timer) e.getSource() ).stop(); return; }
            if( System.currentTimeMillis() >= endMs )
            {
                ( (Timer) e.getSource() ).stop();
                fireTimerAlarm( reminder );
            }
            else
            {
                msg.displayText = formatCountdown( endMs, reminder );
                panel.repaint();
            }
        });
        countdownTicker.start();
    }

    private void pauseCountdown()
    {
        if( !timerActive || timerPaused || timerMsg == null ) return;
        timerPaused = true;
        timerPausedRemainingMs = Math.max( 1000L, timerEndMs - System.currentTimeMillis() );
        if( countdownTicker != null ) { countdownTicker.stop(); countdownTicker = null; }
    }

    private void resumeCountdown()
    {
        if( !timerActive || !timerPaused || timerMsg == null ) return;
        timerPaused = false;
        timerEndMs  = System.currentTimeMillis() + timerPausedRemainingMs;
        timerMsg.displayText = formatCountdown( timerEndMs, timerReminder );
        timerMsg.alpha = 1.0f;
        panel.repaint();
        startCountdownTicker( timerMsg, timerEndMs, timerReminder );
        final Message msg = timerMsg;
        final Timer fadeDown = new Timer( 3000, e -> {
            if( timerMsg == msg && msg.alpha > 0.2f )
            { msg.alpha = 0.2f; panel.repaint(); }
            ( (Timer) e.getSource() ).stop();
        });
        fadeDown.setRepeats( false );
        fadeDown.start();
    }

    /** Called on EDT when the countdown reaches zero. Replaces countdown text with reminder. */
    private void fireTimerAlarm( final String reminder )
    {
        timerActive           = false;
        countdownTicker       = null;
        if( timerMsg == null ) return;
        timerMsg.displayText  = reminder;
        timerMsg.timerFired   = true;
        timerMsg.alpha        = 1.0f;
        // Real timestamp so the reminder participates in normal chronological stacking.
        lastResponseTimestamp = System.currentTimeMillis();
        if( lastBounds != null ) layout( lastBounds );
        panel.repaint();
    }

    /** Remove all timer messages and stop the countdown ticker. */
    private void clearTimerState()
    {
        if( countdownTicker != null ) { countdownTicker.stop(); countdownTicker = null; }
        timerActive  = false;
        timerPaused  = false;
        timerEndMs   = 0L;
        timerReminder = null;
        timerPausedRemainingMs = 0L;
        if( timerMsg == null ) return;
        if( selectedMessage == timerMsg ) closeReplyDialog();
        if( timerMsg.timer != null ) { timerMsg.timer.stop(); timerMsg.timer = null; }
        messages.remove( timerMsg );
        timerMsg = null;
        recheckAiMessages();
    }

    public boolean isTimerActive() { return timerActive; }

    /** Safe to call from any thread — reads only volatile fields. */
    public boolean isFullyDespawned()
    {
        return !window.isVisible() && !thinking && !timerActive;
    }

    private static String formatCountdown( final long endMs, final String reminder )
    {
        final long remaining = Math.max( 0L, endMs - System.currentTimeMillis() );
        final long secs      = ( remaining + 999L ) / 1000L;
        final long mm        = secs / 60;
        final long ss        = secs % 60;
        return "[" + mm + ":" + String.format( "%02d", ss ) + "] " + reminder;
    }

    private void layout( final Rectangle mascotBounds )
    {
        lastBounds = mascotBounds;
        // Obtain FontMetrics without allocating a BufferedImage every tick.
        // Toolkit.getFontMetrics is the lightweight path; it uses a shared
        // offscreen context and does not allocate image memory.
        @SuppressWarnings("deprecation")
        final FontMetrics fm = java.awt.Toolkit.getDefaultToolkit()
            .getFontMetrics( panel.getFont() );

        final int lineH = fm.getHeight();

        int msgLines = 0;
        int visCount = 0;
        for( final Message msg : messages )
            if( msg.alpha > 0f )
            {
                msgLines += panel.countWrappedLines( fm, msg.getDisplayText() );
                visCount++;
            }
        int totalLines = msgLines + ( thinking ? 1 : 0 );
        if( totalLines == 0 ) totalLines = 1;

        // Measure the widest line across all visible messages to fit the bubble
        final int MIN_WIDTH = 60;
        int measuredW = MIN_WIDTH;
        final int avail = bubbleWidth - SHADOW_PAD * 2 - BUBBLE_H_PAD * 2;
        for( final Message msg : messages )
        {
            if( msg.alpha <= 0f ) continue;
            int lineW = 0;
            for( final String word : msg.getDisplayText().split( " ", -1 ) )
            {
                final int ww = fm.stringWidth( word + " " );
                if( lineW + ww > avail && lineW > 0 )
                {
                    measuredW = Math.max( measuredW, lineW + SHADOW_PAD * 2 + 6 );
                    lineW = ww;
                }
                else lineW += ww;
            }
            measuredW = Math.max( measuredW, lineW + SHADOW_PAD * 2 + 6 );
        }
        if( thinking )
            measuredW = Math.max( measuredW, fm.stringWidth( "Thinking..." ) + SHADOW_PAD * 2 + 6 );
        final int winW = Math.min( measuredW, bubbleWidth );
        // Set maxWidth BEFORE recount — countWrappedLines uses panel.maxWidth.
        // Without this, line counts are stale (from previous layout's width),
        // causing winH to be wrong and leaving blank space or clipping text.
        panel.maxWidth = winW;

        // Recount lines now that maxWidth is correct.
        msgLines = 0;
        visCount = 0;
        for( final Message msg : messages )
            if( msg.alpha > 0f )
            {
                msgLines += panel.countWrappedLines( fm, msg.getDisplayText() );
                visCount++;
            }
        totalLines = msgLines + ( thinking ? 1 : 0 );
        if( totalLines == 0 ) totalLines = 1;

        // Pre-compute bubbleAbove so tailExtra and text offset are correct
        if( mascotBounds != null )
        {
            final Rectangle screen = getScreenBounds( mascotBounds );
            final int tentativeH = SHADOW_PAD * 2 + fm.getAscent() + fm.getDescent()
                                   + lineH * ( totalLines - 1 ) + 4 + 10;
            bubbleAbove = ( mascotBounds.y - tentativeH - Y_GAP ) >= screen.y;
        }
        final int tailExtra = showBubbleBg ? 12 : 0; // room for tail + 2px clearance
        final int winH;
        if( showBubbleBg && !thinking && visCount > 1 )
        {
            // Mirror paintComponent's yOff logic exactly so height matches what gets drawn
            final int tailH = 10;
            int firstVis = -1, lastVis = -1;
            for( int i = 0; i < messages.size(); i++ )
                if( messages.get(i).alpha > 0f )
                { if( firstVis < 0 ) firstVis = i; lastVis = i; }

            int totalH = bubbleAbove ? 0 : tailH + 2;
            boolean firstMsg = true;
            for( int i = 0; i < messages.size(); i++ )
            {
                final Message msg = messages.get(i);
                if( msg.alpha <= 0f ) continue;
                if( !firstMsg ) totalH += BUBBLE_GAP;
                firstMsg = false;
                final boolean hasTail = bubbleAbove ? ( i == lastVis ) : ( i == firstVis );
                final int lines = panel.countWrappedLines( fm, msg.getDisplayText() );
                totalH += SHADOW_PAD * 2 + MSG_V_PAD * 2 + 4
                          + fm.getAscent() + fm.getDescent()
                          + lineH * Math.max( 0, lines - 1 )
                          + ( hasTail ? tailH : 0 );
            }
            winH = totalH;
            cachedBubbleContentH = winH;
        }
        else if( showBubbleBg && thinking && visCount > 0 )
        {
            // Bubble for messages only; Thinking... floats as plain text below
            final int bubbleH = SHADOW_PAD * 2 + fm.getAscent() + fm.getDescent()
                                + lineH * ( Math.max( 1, msgLines ) - 1 ) + 4 + tailExtra;
            winH = bubbleH + SHADOW_PAD + lineH;
            cachedBubbleContentH = bubbleH;
        }
        else
        {
            int numSeps = 0;
            if( !showBubbleBg )
            {
                if( visCount > 1 ) numSeps += visCount - 1;
                if( thinking && visCount > 0 ) numSeps += 1;
            }
            final int sepExtra = numSeps * ( SEP_PAD * 2 + 2 );
            winH = SHADOW_PAD * 2 + fm.getAscent() + fm.getDescent()
                   + lineH * ( totalLines - 1 ) + 4 + tailExtra + sepExtra;
            cachedBubbleContentH = winH;
        }

        // Only call setSize when dimensions actually changed — avoids native resize + repaint spam.
        if( winW != window.getWidth() || winH != window.getHeight() )
        {
            window.setSize( winW, winH );
            // Windows may reposition the native window during a resize; re-anchor immediately
            // so the lerp doesn't fight an OS-default position on the next frame.
            if( everPositioned && window.isVisible() )
                window.setLocation( (int) curX, (int) curY );
        }
        lastLayoutW = winW;
        lastLayoutH = winH;
        positionWindow( mascotBounds, winW, winH );
        panel.repaint();

        if( replyDialog.isVisible() ) positionReplyDialog( mascotBounds );
        if( xWindow.isVisible() )     positionXWindow();

        final Rectangle currentScreen = getScreenBounds( mascotBounds );
        if( !currentScreen.equals( lastScreen ) )
        {
            lastScreen = currentScreen;
            if( screenChangeTimer != null ) screenChangeTimer.stop();
            screenChangeTimer = new Timer( 300, e ->
            {
                final boolean wasVisible = window.isVisible();
                window.setVisible( false );
                if( wasVisible ) window.setVisible( true );
                // Re-apply AFTER setVisible(true) — on secondary monitors the native peer
                // is re-created during show, resetting per-pixel transparency. Calling
                // setBackground on the hidden window (old order) didn't survive that reset.
                window.setBackground( new Color( 0, 0, 0, 0 ) );
                window.repaint();
                screenChangeTimer = null;
            });
            screenChangeTimer.setRepeats( false );
            screenChangeTimer.start();
        }
    }

    private void positionWindow( final Rectangle mascotBounds, final int w, final int h )
    {
        if( window == null ) return;
        final Rectangle screen  = getScreenBounds( mascotBounds );
        final int mascotCX      = mascotBounds.x + mascotBounds.width / 2;
        final int mascotCY      = mascotBounds.y + mascotBounds.height / 2;
        lastMascotCX            = mascotCX;
        lastMascotCY            = mascotCY;
        int winX = mascotCX - w / 2;
        int winY = mascotBounds.y - h - Y_GAP;
        bubbleAbove = true;
        if( winY < screen.y )
        {
            winY = mascotBounds.y + mascotBounds.height + Y_GAP;
            bubbleAbove = false;
        }
        winX = Math.max( screen.x, Math.min( winX, screen.x + screen.width  - w ) );
        winY = Math.max( screen.y, Math.min( winY, screen.y + screen.height - h ) );

        // Stacking: AI-response and Say bubbles participate in chronological ordering.
        // Thinking bubbles stay fixed at natural position but act as obstacles
        // that response/Say bubbles must stack away from.
        if( !thinking && ( hasAiMessages || hasSayMessage ) )
        {
            // Consistent visual ordering: oldest always at top (small Y), newest at bottom (large Y).
            // bubbleAbove=true:  newer closer to mascot (larger Y); find newerBubble, push older above it.
            // bubbleAbove=false: mascot at top; oldest closest to mascot (small Y), newest farthest (large Y);
            //                    find olderBubble and push this (newer) bubble below it.
            // Proximity-gated: only mascots within 512px participate.
            if( bubbleAbove )
            {
                AssistantBubble newerBubble = null;
                long newerTime = Long.MAX_VALUE;
                for( final AssistantBubble other : ALL_BUBBLES )
                {
                    if( other == this ) continue;
                    if( other.thinking || ( !other.hasAiMessages && !other.hasSayMessage ) ) continue;
                    if( other.lastResponseTimestamp <= 0L ) continue;
                    if( Math.abs( mascotCX - other.lastMascotCX ) > 512
                            || Math.abs( mascotCY - other.lastMascotCY ) > 256 ) continue;
                    if( other.lastResponseTimestamp > lastResponseTimestamp
                            && other.lastResponseTimestamp < newerTime )
                    {
                        newerTime   = other.lastResponseTimestamp;
                        newerBubble = other;
                    }
                }
                if( newerBubble != null )
                {
                    final Rectangle ob = newerBubble.getTargetBounds();
                    if( ob != null && winY + h / 2 > ob.y )
                        winY = Math.max( screen.y, ob.y - h / 2 );
                }
            }
            else
            {
                AssistantBubble olderBubble = null;
                long olderTime = 0L;
                for( final AssistantBubble other : ALL_BUBBLES )
                {
                    if( other == this ) continue;
                    if( other.thinking || ( !other.hasAiMessages && !other.hasSayMessage ) ) continue;
                    if( other.lastResponseTimestamp <= 0L ) continue;
                    if( Math.abs( mascotCX - other.lastMascotCX ) > 512
                            || Math.abs( mascotCY - other.lastMascotCY ) > 256 ) continue;
                    if( other.lastResponseTimestamp < lastResponseTimestamp
                            && other.lastResponseTimestamp > olderTime )
                    {
                        olderTime   = other.lastResponseTimestamp;
                        olderBubble = other;
                    }
                }
                if( olderBubble != null )
                {
                    final Rectangle ob = olderBubble.getTargetBounds();
                    if( ob != null && winY + h / 2 < ob.y + ob.height )
                        winY = Math.min( screen.y + screen.height - h, ob.y + ob.height - h / 2 );
                }
            }

            // Collision push: push this bubble away from overlapping siblings.
            // Thinking bubbles are fixed obstacles — this bubble always yields.
            // For AI/Say siblings, the older one yields away from the mascot.
            for( final AssistantBubble other : ALL_BUBBLES )
            {
                if( other == this ) continue;
                if( !other.thinking && !other.hasAiMessages && !other.hasSayMessage ) continue;
                if( Math.abs( mascotCX - other.lastMascotCX ) > 512
                        || Math.abs( mascotCY - other.lastMascotCY ) > 256 ) continue;
                final Rectangle ob = other.getTargetBounds();
                if( ob == null ) continue;
                if( winY + h <= ob.y || winY >= ob.y + ob.height ) continue;
                if( winX + w <= ob.x || winX >= ob.x + ob.width ) continue;
                if( other.thinking )
                {
                    // Thinking stays fixed; this bubble always moves away from it.
                    if( bubbleAbove )
                    {
                        winY = ob.y - h - INTER_BUBBLE_GAP;
                        winY = Math.max( screen.y, winY );
                    }
                    else
                    {
                        winY = ob.y + ob.height + INTER_BUBBLE_GAP;
                        winY = Math.min( screen.y + screen.height - h, winY );
                    }
                    continue;
                }
                // bubbleAbove=true:  older yields upward (away from mascot).
                // bubbleAbove=false: newer yields downward (away from mascot), keeping oldest near mascot.
                final boolean thisYields = bubbleAbove
                    ? ( lastResponseTimestamp < other.lastResponseTimestamp
                        || ( lastResponseTimestamp == other.lastResponseTimestamp
                             && System.identityHashCode( this ) < System.identityHashCode( other ) ) )
                    : ( lastResponseTimestamp > other.lastResponseTimestamp
                        || ( lastResponseTimestamp == other.lastResponseTimestamp
                             && System.identityHashCode( this ) < System.identityHashCode( other ) ) );
                if( thisYields )
                {
                    if( bubbleAbove )
                    {
                        winY = ob.y - h - INTER_BUBBLE_GAP;
                        winY = Math.max( screen.y, winY );
                    }
                    else
                    {
                        winY = ob.y + ob.height + INTER_BUBBLE_GAP;
                        winY = Math.min( screen.y + screen.height - h, winY );
                    }
                }
            }
        }

        targetX = winX;
        targetY = winY;

        // Teleport on first show, or when the window was hidden after messages faded.
        // In both cases setVisible(true) must be called before the shared mover can animate it.
        if( !everPositioned || !window.isVisible() )
        {
            everPositioned = true;
            curX = winX; curY = winY;
            smoothMascotCX = mascotCX;
            window.setLocation( winX, winY );
            window.setVisible( true );
            window.setLocation( winX, winY ); // re-apply after native peer creation
            return;
        }
        needsMove = true;
        if( !SHARED_MOVER.isRunning() )
            SwingUtilities.invokeLater( MOVER_STARTER );
    }

    // Called by SHARED_MOVER on EDT each frame. Returns true while still in motion.
    private boolean animateStep()
    {
        if( !needsMove || window == null || !window.isVisible() ) return false;
        final float dx = targetX - curX;
        final float dy = targetY - curY;
        if( Math.abs(dx) < SNAP_PX && Math.abs(dy) < SNAP_PX )
        {
            curX = targetX; curY = targetY;
            smoothMascotCX = lastMascotCX;
            needsMove = false;
            window.setLocation( targetX, targetY );
            return false;
        }
        curX += dx * LERP;
        curY += dy * LERP;
        smoothMascotCX += ( lastMascotCX - smoothMascotCX ) * LERP_TAIL;
        final int newX = Math.round( curX );
        final int newY = Math.round( curY );
        if( newX != window.getX() || newY != window.getY() )
        {
            window.setLocation( newX, newY );
            panel.repaint();
        }
        return true;
    }

    // Cache screen bounds — avoid iterating all GraphicsDevices every tick.
    private Rectangle cachedScreenBounds = null;
    private int       cachedScreenCX     = Integer.MIN_VALUE;
    private int       cachedScreenCY     = Integer.MIN_VALUE;

    private Rectangle getScreenBounds( final Rectangle r )
    {
        final int cx = r.x + r.width / 2;
        final int cy = r.y + r.height / 2;
        if( cachedScreenBounds != null
            && cx == cachedScreenCX && cy == cachedScreenCY )
            return cachedScreenBounds;
        cachedScreenCX = cx;
        cachedScreenCY = cy;
        for( final GraphicsDevice gd :
             GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices() )
        {
            final Rectangle b = gd.getDefaultConfiguration().getBounds();
            if( b.contains( cx, cy ) ) { cachedScreenBounds = b; return b; }
        }
        cachedScreenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                  .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return cachedScreenBounds;
    }
}
