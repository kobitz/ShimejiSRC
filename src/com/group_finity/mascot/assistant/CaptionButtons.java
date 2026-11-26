package com.group_finity.mascot.assistant;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Where the minimize / maximize-restore / close buttons of a standard Windows title bar are, so a
 * mascot can jump at one. Pure geometry from the window rectangle (as Win32 reports it) and the DPI
 * scale. The numbers are the stock Windows 10/11 caption metrics (buttons 46 x 30 px at 100%, the
 * invisible 8 px resize border around the visible frame); apps that draw their own title bar may differ
 * by a few pixels, which is fine for "jump at the button" but is why the actual press goes through the
 * window API, not a click.
 */
public final class CaptionButtons
{
    private CaptionButtons() { }

    public enum Button { CLOSE, MAXIMIZE, MINIMIZE }

    static final int BUTTON_W = 46, BAR_H = 30, BORDER = 8;

    /** Centre of the button in screen coordinates. */
    public static Point center( final Rectangle win, final Button b, final double scale, final boolean maximized )
    {
        final int idx = b == Button.CLOSE ? 0 : b == Button.MAXIMIZE ? 1 : 2;
        final int x = win.x + win.width - (int) Math.round( ( BORDER + BUTTON_W * idx + BUTTON_W / 2.0 ) * scale );
        final int y = win.y + (int) Math.round( ( ( maximized ? BORDER : 1 ) + BAR_H / 2.0 ) * scale );
        return new Point( x, y );
    }
}
