package com.group_finity.mascot;

import javax.swing.JPanel;
import javax.swing.JWindow;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A lightweight, always-on-top floating tooltip that follows a mascot and
 * displays a stack of recent behavior names above the current action.
 *
 * Layout (oldest at top, newest just above current):
 *
 *   "History[0] >"          ← oldest, 10% opacity
 *   "History[1] >"          ← 28% opacity
 *   "History[2] >"          ← 46% opacity
 *   "History[3] >"          ← 64% opacity
 *   "History[4] >"          ← 82% opacity   (newest history / "last action")
 *   "CurrentAction : Timer" ← 100% opacity
 *
 * Up to MAX_HISTORY (5) previous entries are shown; older ones are clipped.
 * The ">" and ":" always sit at the same X column = mascot anchorX.
 *
 * Font size: BASE_FONT_SIZE * universalScale ("Scaling" property).
 */
public class MascotTooltip
{
    private static final float BASE_FONT_SIZE = 36f;
    private static final int   MAX_HISTORY    = 5;

    /** Opacity for each history slot, index 0 = oldest (top), index 4 = newest. */
    private static final float[] HISTORY_ALPHA = { 0.05f, 0.10f, 0.25f, 0.50f, 0.75f };

    // ── Action file logger ────────────────────────────────────────────────────
    private static final String LOG_PATH = "shimeji_action_log.txt";
    private static final DateTimeFormatter LOG_FMT =
        DateTimeFormatter.ofPattern( "HH:mm:ss.SSS" );
    private String lastLoggedBehavior = null;

    private static void writeLog( final String line )
    {
        try ( BufferedWriter bw = new BufferedWriter( new FileWriter( LOG_PATH, true ) ) )
        {
            bw.write( line );
            bw.newLine( );
        }
        catch( IOException ignored ) {}
    }

    private static final int   SHADOW_R  = 2;
    private static final int   SHADOW_DX = 1;
    private static final int   SHADOW_DY = 1;
    private static final int   Y_OFFSET  = 6;

    /** Full-brightness shadow alpha (0-255) used at alpha = 1.0. */
    private static final int SHADOW_ALPHA_FULL = 120;

    private JWindow              window;
    private final TextPanel      panel;
    private GraphicsConfiguration currentGC = null;

    // ── Inner panel ──────────────────────────────────────────────────────────
    private static final class TextPanel extends JPanel
    {
        /** History entries, oldest first (index 0), newest last. Max MAX_HISTORY entries. */
        String[] history        = new String[0];
        String   currentLine    = "";
        int      separatorOffsetX = 0;
        int      shadowPad      = SHADOW_R + Math.max( SHADOW_DX, SHADOW_DY );

        TextPanel( ) { setOpaque( false ); }

        private static Color textColor( final float alpha )
        {
            // White→grey as alpha drops, plus transparency
            final int brightness = Math.round( 150 + 105 * alpha ); // 255 at 1.0, 160 at 0.0
            final int a          = Math.round( 25 + 230 * alpha );  // 255 at 1.0, ~25 (10%) at 0.0
            return new Color( brightness, brightness, brightness, a );
        }

        private static Color shadowColor( final float alpha )
        {
            final int brightness = Math.round( 180 * ( 1.0f - alpha ) ); // 180 (light grey) at 0.0, 0 (black) at 1.0
            final int a          = Math.round( SHADOW_ALPHA_FULL * alpha );
            return new Color( brightness, brightness, brightness, a );
        }

        private void drawShadowed( final Graphics2D g2, final String str,
                                   final int x, final int baseline,
                                   final Color tCol, final Color sCol )
        {
            g2.setColor( sCol );
            for( int dy = -SHADOW_R; dy <= SHADOW_R; dy++ )
                for( int dx = -SHADOW_R; dx <= SHADOW_R; dx++ )
                    if( dx * dx + dy * dy <= SHADOW_R * SHADOW_R )
                        g2.drawString( str, x + dx + SHADOW_DX, baseline + dy + SHADOW_DY );
            g2.setColor( tCol );
            g2.drawString( str, x, baseline );
        }

        @Override
        protected void paintComponent( final Graphics g )
        {
            final Graphics2D g2 = (Graphics2D) g.create( );
            g2.setRenderingHint( RenderingHints.KEY_TEXT_ANTIALIASING,
                                 RenderingHints.VALUE_TEXT_ANTIALIAS_ON );
            g2.setFont( getFont( ) );

            final FontMetrics fm = g2.getFontMetrics( );
            final int lineH      = fm.getAscent( ) + fm.getDescent( ) + fm.getLeading( );
            // Bottom of the window = current-action row baseline
            // Row index from top: 0..history.length-1 are history, history.length is current
            final int totalRows  = history.length + 1;
            // baseline for row i (0-based from top)
            // row 0 baseline = shadowPad + ascent
            // row totalRows-1 = current
            final int baselineForRow0 = fm.getAscent( ) + shadowPad;

            // ── History rows (oldest at top) ─────────────────────────────────
            for( int i = 0; i < history.length; i++ )
            {
                final String name = history[i];
                if( name == null || name.isEmpty( ) ) continue;

                // Map history index to alpha array.
                // history[0] is oldest. We want oldest to be most transparent.
                // If fewer than MAX_HISTORY entries, shift into the bottom of the alpha array.
                final int alphaIdx = ( MAX_HISTORY - history.length ) + i;
                final float alpha  = ( alphaIdx >= 0 && alphaIdx < HISTORY_ALPHA.length )
                                     ? HISTORY_ALPHA[ alphaIdx ]
                                     : HISTORY_ALPHA[ HISTORY_ALPHA.length - 1 ];

                final Color tCol = textColor( alpha );
                final Color sCol = shadowColor( alpha );

                final int baseline = baselineForRow0 + lineH * i;
                final int arrowX   = separatorOffsetX;
                final int nameX    = arrowX - fm.stringWidth( name + " " );

                drawShadowed( g2, name, nameX, baseline, tCol, sCol );
                drawShadowed( g2, " >", arrowX - fm.stringWidth( " " ), baseline, tCol, sCol );
            }

            // ── Current-action row (full opacity / full contrast) ────────────
            final Color tFull = textColor( 1.0f );
            final Color sFull = shadowColor( 1.0f );
            if( !currentLine.isEmpty( ) )
            {
                final int baseline = baselineForRow0 + lineH * history.length;
                final int splitIdx = currentLine.indexOf( " : " );
                final String leftPart  = splitIdx >= 0 ? currentLine.substring( 0, splitIdx ) : currentLine;
                final String rightPart = splitIdx >= 0 ? currentLine.substring( splitIdx )    : "";

                final int colonX = separatorOffsetX;
                final int leftX  = colonX - fm.stringWidth( leftPart );

                drawShadowed( g2, leftPart, leftX,  baseline, tFull, sFull );
                if( !rightPart.isEmpty( ) )
                    drawShadowed( g2, rightPart, colonX, baseline, tFull, sFull );
            }

            g2.dispose( );
        }
    }

    // ── Constructor ──────────────────────────────────────────────────────────
    public MascotTooltip( )
    {
        panel = new TextPanel( );
        panel.setFont( Main.UBUNTU_R.deriveFont( Font.PLAIN, BASE_FONT_SIZE ) );
        window = buildWindow( GraphicsEnvironment
            .getLocalGraphicsEnvironment( )
            .getDefaultScreenDevice( )
            .getDefaultConfiguration( ) );
    }

    private JWindow buildWindow( final GraphicsConfiguration gc )
    {
        final boolean wasVisible = ( window != null && window.isVisible( ) );
        if( window != null )
        {
            window.setVisible( false );
            window.dispose( );
        }
        currentGC = gc;
        final JWindow w = new JWindow( gc );
        w.setAlwaysOnTop( true );
        w.setFocusable( false );
        w.setFocusableWindowState( false );
        w.setBackground( new Color( 0, 0, 0, 0 ) );
        w.setContentPane( panel );
        w.setSize( 1, 1 );
        if( wasVisible ) w.setVisible( true );
        return w;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Update the displayed text and reposition the tooltip above the mascot.
     *
     * @param history      Behavior history, oldest first, newest last (up to MAX_HISTORY).
     *                     May be empty but not null.
     * @param behaviorName Current behavior name (never null — caller guards)
     * @param timerValue   Remaining action ticks, or Integer.MAX_VALUE for infinity
     * @param mascotBounds Screen-space bounding rect of the mascot sprite
     * @param anchorX      Mascot anchor X in screen coordinates (the ":" sits here)
     */
    public void update( final String[]  history,
                        final String    behaviorName,
                        final int       timerValue,
                        final Rectangle mascotBounds,
                        final int       anchorX,
                        final int       anchorY )
    {
        // ── Universal scale → font size ────────────────────────────────────
        double universalScale = 1.0;
        try
        {
            final String sv = Main.getInstance( ).getProperties( ).getProperty( "Scaling", "1.0" );
            universalScale = Double.parseDouble( sv );
        }
        catch( NumberFormatException ignored ) { }

        final float fontSize = (float)( BASE_FONT_SIZE * universalScale );
        panel.setFont( Main.UBUNTU_R.deriveFont( Font.PLAIN, fontSize ) );

        final int shadowPad = SHADOW_R + Math.max( SHADOW_DX, SHADOW_DY );
        panel.shadowPad = shadowPad;

        // ── Clamp history to MAX_HISTORY, oldest first ─────────────────────
        final int    hLen    = Math.min( history.length, MAX_HISTORY );
        final String[] hist  = new String[ hLen ];
        // take the LAST hLen entries (most recent)
        System.arraycopy( history, history.length - hLen, hist, 0, hLen );
        panel.history = hist;

        // ── Current line ───────────────────────────────────────────────────
        final String timerStr = ( timerValue == Integer.MAX_VALUE ) ? "\u221e" : String.valueOf( timerValue );
        final String currLine = behaviorName + " : " + timerStr;
        panel.currentLine = currLine;

        // ── Log behavior change to file ────────────────────────────────────
        if( !behaviorName.equals( lastLoggedBehavior ) )
        {
            lastLoggedBehavior = behaviorName;
            final String ts = LocalDateTime.now( ).format( LOG_FMT );
            final StringBuilder sb = new StringBuilder( );
            sb.append( ts ).append( "\t" ).append( behaviorName );
            sb.append( "\t" ).append( anchorX ).append( "," ).append( anchorY );
            sb.append( "\t[" );
            for( int _i = 0; _i < history.length; _i++ )
            {
                if( _i > 0 ) sb.append( ", " );
                sb.append( history[ _i ] );
            }
            sb.append( "]" );
            writeLog( sb.toString( ) );
        }

        // ── Measure all rows to size window and find separatorOffsetX ──────
        final FontMetrics fm = panel.getFontMetrics( panel.getFont( ) );
        final int lineH      = fm.getAscent( ) + fm.getDescent( ) + fm.getLeading( );
        final int totalRows  = hLen + 1;

        // Max left-width = widest left-of-separator text across all rows
        int maxLeftW = 0;
        for( final String h : hist )
        {
            if( h == null || h.isEmpty( ) ) continue;
            final int w = fm.stringWidth( h + " " ); // name + space before ">"
            if( w > maxLeftW ) maxLeftW = w;
        }
        // current-action left part
        final int    splitIdx  = currLine.indexOf( " : " );
        final String leftPart  = splitIdx >= 0 ? currLine.substring( 0, splitIdx ) : currLine;
        final String rightPart = splitIdx >= 0 ? currLine.substring( splitIdx )    : "";
        final int currLeftW    = fm.stringWidth( leftPart );
        if( currLeftW > maxLeftW ) maxLeftW = currLeftW;

        // Max right-width
        int maxRightW = fm.stringWidth( ">" );  // history suffix
        final int currRightW = fm.stringWidth( rightPart );
        if( currRightW > maxRightW ) maxRightW = currRightW;

        final int leftRoom  = maxLeftW  + shadowPad + 2;
        final int rightRoom = maxRightW + shadowPad + 2;
        final int winW      = leftRoom + rightRoom;
        final int winH      = shadowPad * 2
                              + fm.getAscent( ) + fm.getDescent( )
                              + lineH * ( totalRows - 1 )
                              + 6;

        panel.separatorOffsetX = leftRoom;

        // ── Find screen and check GC ───────────────────────────────────────
        final int mascotCX = mascotBounds.x + mascotBounds.width  / 2;
        final int mascotCY = mascotBounds.y + mascotBounds.height / 2;

        GraphicsConfiguration targetGC = null;
        Rectangle screen = null;
        for( final GraphicsDevice gd :
             GraphicsEnvironment.getLocalGraphicsEnvironment( ).getScreenDevices( ) )
        {
            final GraphicsConfiguration gc = gd.getDefaultConfiguration( );
            final Rectangle b = gc.getBounds( );
            if( b.contains( mascotCX, mascotCY ) )
            {
                targetGC = gc;
                screen   = b;
                break;
            }
        }
        if( targetGC == null )
        {
            targetGC = GraphicsEnvironment
                .getLocalGraphicsEnvironment( )
                .getDefaultScreenDevice( )
                .getDefaultConfiguration( );
            screen = targetGC.getBounds( );
        }

        // Rebuild the window on the correct GC if the screen changed
        if( targetGC != currentGC )
            window = buildWindow( targetGC );

        window.setSize( winW, winH );
        panel.repaint( );

        // ── Position: ":" column at anchorX ───────────────────────────────
        int tx = anchorX - leftRoom;
        int ty = mascotBounds.y - winH - Y_OFFSET;

        if( ty < screen.y )
            ty = mascotBounds.y + mascotBounds.height + Y_OFFSET;

        tx = Math.max( screen.x, Math.min( tx, screen.x + screen.width - winW ) );

        window.setLocation( tx, ty );

        if( !window.isVisible( ) )
            window.setVisible( true );
    }

    public void hide( )    { window.setVisible( false ); }

    public void dispose( )
    {
        window.setVisible( false );
        window.dispose( );
    }
}
