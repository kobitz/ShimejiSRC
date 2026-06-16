package com.group_finity.mascot.assistant;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;

/**
 * A 2B-only "system readout": the live console/log stream rendered as faint
 * Ubuntu-Mono text projected ahead of her in her facing direction — a YoRHa unit
 * jacked into the machine. Independent of the normal speech-bubble stacking
 * (separate window, never registered with the bubble layout), so it neither
 * pushes nor clips other bubbles.
 *
 * <ul>
 *   <li>Content: {@link ConsoleTap} — the same lines a Debug.bat console would
 *       show (logger stream + raw prints).</li>
 *   <li>Shows the last {@link #MAX_LINES} lines, newest at the bottom.</li>
 *   <li>No background; white Ubuntu-Mono with a soft shadow for legibility.</li>
 *   <li>Master 50% opacity, with a per-line recency gradient and a fade-in on
 *       new lines (the bubbles' fade feel applied to a streaming readout).</li>
 *   <li>Font size = 50% of {@code BubbleFontSize}.</li>
 * </ul>
 */
public final class ConsoleReadout
{
    private static final int   MAX_LINES   = 5;
    private static final float MASTER_ALPHA = 0.5f;   // 50% opacity ceiling
    private static final int   TICK_MS     = 33;      // ~30fps follow + fade
    private static final int   GAP         = 6;       // px between sprite edge and text
    private static final int   PAD         = 4;       // inner text padding
    private static final float POS_LERP    = 0.35f;   // position smoothing toward target
    private static final float FADE_LERP   = 0.30f;   // alpha smoothing toward target

    private final Mascot   mascot;
    private final JWindow  window;
    private final ReadoutPanel panel;
    private final Font     font;
    private final Timer    timer;

    private float curX = -9999, curY = -9999;
    private boolean placed = false;
    private long lastSeenVersion = -1;
    private volatile int maxTextW = Integer.MAX_VALUE;  // width cap (2x sprite), set per tick

    /** One rendered line: its text and its current (animating) alpha 0..MASTER_ALPHA. */
    private static final class Entry
    {
        final String text;
        float alpha;
        float target;
        Entry( final String text, final float alpha ) { this.text = text; this.alpha = alpha; }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** One painted visual line (an entry may wrap into two). Carries its entry's alpha. */
    private static final class Row
    {
        final String text;
        final float  alpha;
        Row( final String text, final float alpha ) { this.text = text; this.alpha = alpha; }
    }

    // Rebuilt each tick from entries via word-wrap; read by the panel on the same (EDT) thread.
    private final List<Row> rows = new ArrayList<>();

    public ConsoleReadout( final Mascot mascot )
    {
        this.mascot = mascot;
        ConsoleTap.install();   // idempotent — ensures the stream exists

        float bubbleFont = 14f;
        try { bubbleFont = Float.parseFloat(
            Main.getInstance().getProperties().getProperty( "BubbleFontSize", "14" ) ); }
        catch( final Exception ignored ) { }
        this.font = loadMono().deriveFont( Font.PLAIN, bubbleFont * 0.5f );

        panel = new ReadoutPanel();
        panel.setOpaque( false );
        panel.setFont( font );

        window = new JWindow();
        window.setAlwaysOnTop( true );
        window.setFocusable( false );
        window.setFocusableWindowState( false );
        window.setBackground( new Color( 0, 0, 0, 0 ) );
        window.setContentPane( panel );
        window.setLocation( -9999, -9999 );

        timer = new Timer( TICK_MS, e -> update() );
        timer.start();
    }

    private static Font loadMono()
    {
        // Bundled in conf/ (a classpath root) like Ubuntu-R/B; fall back to a
        // logical monospaced font if the resource is missing.
        try
        {
            final java.io.InputStream is =
                ConsoleReadout.class.getResourceAsStream( "/UbuntuMono-R.ttf" );
            if( is != null )
                try( is ) { return Font.createFont( Font.TRUETYPE_FONT, is ); }
        }
        catch( final Exception ignored ) { }
        return new Font( Font.MONOSPACED, Font.PLAIN, 12 );
    }

    // ── Per-tick follow + fade ────────────────────────────────────────────────
    private void update()
    {
        Rectangle b;
        try { b = mascot.getBounds(); }
        catch( final Exception ex ) { window.setVisible( false ); return; }
        if( b == null ) { window.setVisible( false ); return; }

        final boolean contentChanged = reconcile();
        final boolean alphaMoving    = animate();

        if( entries.isEmpty() )
        {
            if( window.isVisible() ) window.setVisible( false );
            return;
        }

        // Width capped at twice her sprite width; longer lines truncate (see paint).
        final int maxPanelW = Math.max( 40, b.width * 2 );
        maxTextW = maxPanelW - PAD * 2;

        final FontMetrics fm = panel.getFontMetrics( font );
        final int lineH = fm.getHeight();

        // Word-wrap each entry to at most two visual lines (the 2nd ellipsis-truncated).
        rows.clear();
        int textW = 0;
        for( final Entry en : entries )
            for( final String line : wrap( en.text, fm, maxTextW ) )
            {
                rows.add( new Row( line, en.alpha ) );
                textW = Math.max( textW, fm.stringWidth( line ) );
            }
        final int panelW = textW + PAD * 2;
        final int panelH = rows.size() * lineH + PAD * 2;

        // Project ahead of her facing, pulled in by half her width and up by half
        // her height, then clamped to the screen exactly like the chat bubbles.
        final boolean right = mascot.isLookRight();
        final int closer = b.width / 2;
        final int up     = b.height / 2;
        int targetX = right ? ( b.x + b.width + GAP - closer )
                            : ( b.x - panelW - GAP + closer );
        int targetY = b.y + b.height / 2 - panelH / 2 - up;
        final Rectangle screen = getScreenBounds( b );
        targetX = Math.max( screen.x, Math.min( targetX, screen.x + screen.width  - panelW ) );
        targetY = Math.max( screen.y, Math.min( targetY, screen.y + screen.height - panelH ) );

        if( !placed ) { curX = targetX; curY = targetY; placed = true; }
        else
        {
            curX += ( targetX - curX ) * POS_LERP;
            curY += ( targetY - curY ) * POS_LERP;
        }

        if( panel.getWidth() != panelW || panel.getHeight() != panelH )
            window.setSize( panelW, panelH );
        window.setLocation( Math.round( curX ), Math.round( curY ) );

        if( !window.isVisible() ) window.setVisible( true );
        if( contentChanged || alphaMoving ) panel.repaint();
    }

    /** The screen rectangle containing the mascot's centre — used to clamp the
     *  readout to screen edges the same way {@code AssistantBubble} does. */
    private Rectangle getScreenBounds( final Rectangle r )
    {
        final int cx = r.x + r.width / 2, cy = r.y + r.height / 2;
        try
        {
            for( final java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices() )
            {
                final Rectangle b = gd.getDefaultConfiguration().getBounds();
                if( b.contains( cx, cy ) ) return b;
            }
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        }
        catch( final Exception e )
        {
            final java.awt.Dimension d = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle( 0, 0, d.width, d.height );
        }
    }

    /** Truncate a line with an ellipsis so it fits {@code maxW} pixels. */
    private static String fit( final String s, final FontMetrics fm, final int maxW )
    {
        if( maxW <= 0 || fm.stringWidth( s ) <= maxW ) return s;
        final int ellW = fm.stringWidth( "…" );
        int end = s.length();
        while( end > 0 && fm.stringWidth( s.substring( 0, end ) ) + ellW > maxW ) end--;
        return s.substring( 0, end ) + "…";
    }

    /** Wrap a line to at most two visual rows at word boundaries; the overflow row
     *  is ellipsis-truncated. So a long line gets one extra wrapped row before the
     *  ellipsis kicks in, rather than being clipped on the first row. */
    private static List<String> wrap( final String s, final FontMetrics fm, final int maxW )
    {
        final List<String> out = new ArrayList<>( 2 );
        if( maxW <= 0 || fm.stringWidth( s ) <= maxW ) { out.add( s ); return out; }

        final String[] words = s.split( " " );
        String l1 = "";
        int i = 0;
        while( i < words.length )
        {
            final String cand = l1.isEmpty() ? words[ i ] : l1 + " " + words[ i ];
            if( fm.stringWidth( cand ) <= maxW ) { l1 = cand; i++; }
            else break;
        }

        if( l1.isEmpty() )
        {
            // First token alone exceeds the width — break it mid-word.
            int end = s.length();
            while( end > 1 && fm.stringWidth( s.substring( 0, end ) ) > maxW ) end--;
            l1 = s.substring( 0, end );
            final String rem = s.substring( end ).trim();
            out.add( l1 );
            if( !rem.isEmpty() ) out.add( fit( rem, fm, maxW ) );
            return out;
        }

        out.add( l1 );
        final StringBuilder rem = new StringBuilder();
        for( ; i < words.length; i++ )
        {
            if( rem.length() > 0 ) rem.append( ' ' );
            rem.append( words[ i ] );
        }
        if( rem.length() > 0 ) out.add( fit( rem.toString(), fm, maxW ) );
        return out;
    }

    /** Pull the latest lines and rebuild {@link #entries}, carrying alpha across
     *  for lines that persist and starting new lines faded out. Returns true if
     *  the set of lines changed. */
    private boolean reconcile()
    {
        final long v = ConsoleTap.version();
        if( v == lastSeenVersion && !entries.isEmpty() ) return false;
        lastSeenVersion = v;

        final List<String> target = ConsoleTap.lastLines( MAX_LINES ); // oldest..newest
        final List<Entry> rebuilt = new ArrayList<>( target.size() );
        final boolean[] used = new boolean[ entries.size() ];
        boolean changed = target.size() != entries.size();

        for( int i = 0; i < target.size(); i++ )
        {
            final String text = target.get( i );
            Entry carried = null;
            for( int j = 0; j < entries.size(); j++ )
            {
                if( !used[ j ] && entries.get( j ).text.equals( text ) )
                {
                    carried = entries.get( j );
                    used[ j ] = true;
                    break;
                }
            }
            if( carried == null ) { carried = new Entry( text, 0f ); changed = true; }
            // Recency gradient: newest brightest, older dimmer (floor 0.4 of master).
            final int ageFromNewest = ( target.size() - 1 ) - i;
            final float g = Math.max( 0.4f, 1.0f - 0.15f * ageFromNewest );
            carried.target = MASTER_ALPHA * g;
            rebuilt.add( carried );
        }
        entries.clear();
        entries.addAll( rebuilt );
        return changed;
    }

    /** Step every entry's alpha toward its target. Returns true while any moved. */
    private boolean animate()
    {
        boolean moving = false;
        for( final Entry en : entries )
        {
            final float d = en.target - en.alpha;
            if( Math.abs( d ) > 0.004f )
            {
                en.alpha += d * FADE_LERP;
                moving = true;
            }
            else en.alpha = en.target;
        }
        return moving;
    }

    public void dispose()
    {
        if( timer != null ) timer.stop();
        if( window != null )
        {
            window.setVisible( false );
            window.dispose();
        }
        entries.clear();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    private final class ReadoutPanel extends JPanel
    {
        @Override protected void paintComponent( final Graphics g )
        {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON );
            g2.setRenderingHint( RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON );
            g2.setFont( font );
            final FontMetrics fm = g2.getFontMetrics();
            final int lineH = fm.getHeight();
            int y = PAD + fm.getAscent();
            for( final Row r : rows )            // oldest..newest, top..bottom (wrapped)
            {
                final float a = Math.max( 0f, Math.min( 1f, r.alpha ) );
                if( a > 0.01f )
                {
                    final int sa = Math.round( 200 * a );  // shadow
                    g2.setColor( new Color( 0, 0, 0, sa ) );
                    g2.drawString( r.text, PAD + 1, y + 1 );
                    final int ta = Math.round( 255 * a );  // white text
                    g2.setColor( new Color( 235, 235, 235, ta ) );
                    g2.drawString( r.text, PAD, y );
                }
                y += lineH;
            }
            g2.dispose();
        }
    }
}
