package com.group_finity.mascot.assistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

/**
 * A compact input dialog for talking to an assistant-mode mascot.
 *
 * Uses an undecorated JDialog (not JWindow) so that it properly receives
 * keyboard focus on Windows. JWindow is focus-impaired on Windows — it can
 * never become the focused window in the AWT focus cycle, so typing is
 * impossible without the JDialog workaround.
 *
 * Position: prefers above the mascot, falls back to below if the mascot is
 * near the top of the screen.
 *
 * Dismisses on: Enter / Send button (submits), Escape (cancels), or clicking
 * anywhere outside the dialog (focus lost with a short guard delay to avoid
 * false dismissals from the initial focus handoff).
 */
public class AssistantInputDialog
{
    // ── Appearance ────────────────────────────────────────────────────────────
    private static final Color BG     = new Color( 245, 245, 245 );
    private static final Color BORDER = new Color( 120, 120, 120 );
    private static final Color BTN_BG = new Color(  60, 120, 200 );
    private static final Color BTN_FG = Color.WHITE;
    private static final int   W      = 280;
    private static final int   Y_GAP  = 8;

    /** Milliseconds after show() before windowLostFocus is armed.
     *  Prevents the dialog from self-closing during the initial focus handoff. */
    private static final int FOCUS_ARM_DELAY_MS = 400;

    public interface SubmitCallback
    {
        void onSubmit( String userText );
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final JDialog    dialog;
    private final JTextField field;
    private boolean          focusArmed = false;
    private boolean          closed     = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    public AssistantInputDialog( final Rectangle      mascotBounds,
                                 final String         mascotName,
                                 final SubmitCallback callback )
    {
        // Undecorated JDialog with null owner — floats freely, receives focus properly
        dialog = new JDialog( (Frame) null, false );
        dialog.setUndecorated( true );
        dialog.setAlwaysOnTop( true );
        dialog.setBackground( new Color( 0, 0, 0, 0 ) );

        // ── Layout ────────────────────────────────────────────────────────
        final JPanel root = new JPanel( new BorderLayout( 6, 0 ) );
        root.setOpaque( false );
        root.setBorder( new EmptyBorder( 0, 0, 0, 0 ) );

        final String hint = "Talk to " + mascotName + "...";

        field = new JTextField( 18 );
        field.setFont( new Font( Font.SANS_SERIF, Font.PLAIN, 13 ) );
        field.setForeground( Color.GRAY );
        field.setText( hint );
        field.addFocusListener( new FocusAdapter()
        {
            @Override public void focusGained( final FocusEvent e )
            {
                if( field.getText().equals( hint ) )
                {
                    field.setText( "" );
                    field.setForeground( Color.BLACK );
                }
            }
            @Override public void focusLost( final FocusEvent e )
            {
                if( field.getText().isEmpty() )
                {
                    field.setForeground( Color.GRAY );
                    field.setText( hint );
                }
            }
        });

        // Use a custom-painted JPanel instead of JButton to bypass NimROD LAF
        // button chrome, which ignores setBorderPainted/setBorder entirely.
        final JPanel sendBtn = new JPanel( new java.awt.GridBagLayout() )
        {
            @Override protected void paintComponent( final Graphics g )
            {
                final Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
                g2.setColor( getBackground() );
                g2.fillRoundRect( 0, 0, getWidth(), getHeight(), 6, 6 );
                g2.dispose();
            }
        };
        sendBtn.setBackground( BTN_BG );
        sendBtn.setOpaque( false );
        sendBtn.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );
        sendBtn.setBorder( new EmptyBorder( 6, 10, 6, 10 ) );

        final JLabel sendLabel = new JLabel( "Send" );
        sendLabel.setFont( new Font( Font.SANS_SERIF, Font.BOLD, 12 ) );
        sendLabel.setForeground( BTN_FG );
        sendBtn.add( sendLabel );

        root.add( field,   BorderLayout.CENTER );
        root.add( sendBtn, BorderLayout.EAST   );
        dialog.setContentPane( root );
        dialog.getRootPane().setOpaque( false );

        // ── Sizing and position ───────────────────────────────────────────
        dialog.pack();
        dialog.setSize( W, dialog.getHeight() );
        positionDialog( mascotBounds );

        // ── Submit action ─────────────────────────────────────────────────
        final Runnable submit = () ->
        {
            final String text = field.getText().trim();
            if( text.isEmpty() || text.equals( hint ) ) return;
            close();
            callback.onSubmit( text );
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
        field.addActionListener( e -> submit.run() ); // Enter key

        // Escape — cancel without submitting
        final KeyStroke esc = KeyStroke.getKeyStroke( KeyEvent.VK_ESCAPE, 0 );
        root.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW ).put( esc, "close" );
        root.getActionMap().put( "close", new AbstractAction()
        {
            @Override public void actionPerformed( ActionEvent e ) { close(); }
        });

        // Click outside — close after focus-arm delay has elapsed
        dialog.addWindowFocusListener( new WindowAdapter()
        {
            @Override public void windowLostFocus( final WindowEvent e )
            {
                if( focusArmed )
                    close();
                // If not yet armed, the loss is from the initial show() handoff; ignore it.
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void show()
    {
        focusArmed = false;
        dialog.setVisible( true );
        // Request focus on the text field once the dialog is shown
        SwingUtilities.invokeLater( () ->
        {
            dialog.toFront();
            field.requestFocusInWindow();
        });
        // Arm the focus-loss listener after a short delay so the initial
        // focus handoff from the Shimeji window doesn't immediately close us
        final Timer armTimer = new Timer( FOCUS_ARM_DELAY_MS, e -> focusArmed = true );
        armTimer.setRepeats( false );
        armTimer.start();
    }

    /** Reposition the dialog relative to the mascot. Call every tick. */
    public void reposition( final Rectangle mascotBounds )
    {
        if( !closed && dialog.isVisible() )
            positionDialog( mascotBounds );
    }

    public void close()
    {
        if( closed ) return;
        closed = true;
        dialog.setVisible( false );
        dialog.dispose();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void positionDialog( final Rectangle mascotBounds )
    {
        final Rectangle screen = getScreenBounds( mascotBounds );
        final int h = dialog.getHeight();
        int x = mascotBounds.x + mascotBounds.width / 2 - W / 2;

        // Prefer above the mascot; fall back to below if there isn't room
        int y = mascotBounds.y - h - Y_GAP;
        if( y < screen.y )
            y = mascotBounds.y + mascotBounds.height + Y_GAP;

        // Clamp horizontally
        x = Math.max( screen.x, Math.min( x, screen.x + screen.width - W ) );
        dialog.setLocation( x, y );
    }

    private static Rectangle getScreenBounds( final Rectangle r )
    {
        final int cx = r.x + r.width  / 2;
        final int cy = r.y + r.height / 2;
        for( final GraphicsDevice gd :
             GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices() )
        {
            final Rectangle b = gd.getDefaultConfiguration().getBounds();
            if( b.contains( cx, cy ) ) return b;
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                                  .getDefaultScreenDevice()
                                  .getDefaultConfiguration()
                                  .getBounds();
    }
}
