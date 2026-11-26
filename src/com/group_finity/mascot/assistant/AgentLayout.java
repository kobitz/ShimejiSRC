package com.group_finity.mascot.assistant;

import java.awt.Rectangle;

/** Pure geometry for window snapping, kept apart from the OS code so it can be tested. */
public final class AgentLayout
{
    private AgentLayout() { }

    /** Target rectangle inside work area {@code wa} for a snap operation. */
    public static Rectangle target( final Rectangle wa, final WindowControl.Op op )
    {
        final int hw = wa.width / 2, hh = wa.height / 2;
        switch( op )
        {
            case LEFT:   return new Rectangle( wa.x, wa.y, hw, wa.height );
            case RIGHT:  return new Rectangle( wa.x + wa.width - hw, wa.y, hw, wa.height );
            case TOP:    return new Rectangle( wa.x, wa.y, wa.width, hh );
            case BOTTOM: return new Rectangle( wa.x, wa.y + wa.height - hh, wa.width, hh );
            case CENTER:
            {
                final int w = wa.width * 7 / 10, h = wa.height * 7 / 10;
                return new Rectangle( wa.x + ( wa.width - w ) / 2, wa.y + ( wa.height - h ) / 2, w, h );
            }
            default: return new Rectangle( wa );
        }
    }

    /** Index of the monitor holding most of {@code r} (nearest centre if none overlaps); -1 for an empty list. */
    public static int monitorIndex( final java.util.List<Rectangle> mons, final Rectangle r )
    {
        int best = -1;
        long bestArea = 0;
        for( int i = 0; i < mons.size(); i++ )
        {
            final Rectangle in = mons.get( i ).intersection( r );
            final long a = in.isEmpty() ? 0 : (long) in.width * in.height;
            if( a > bestArea ) { bestArea = a; best = i; }
        }
        if( best >= 0 ) return best;
        long bestD = Long.MAX_VALUE;
        final long cx = r.x + r.width / 2, cy = r.y + r.height / 2;
        for( int i = 0; i < mons.size(); i++ )
        {
            final Rectangle m = mons.get( i );
            final long dx = m.x + m.width / 2 - cx, dy = m.y + m.height / 2 - cy;
            if( dx * dx + dy * dy < bestD ) { bestD = dx * dx + dy * dy; best = i; }
        }
        return best;
    }

    /** The monitor rectangle for {@code r}, or {@code fallback} when there is no monitor list. */
    public static Rectangle screenFor( final java.util.List<Rectangle> mons, final Rectangle r, final Rectangle fallback )
    {
        final int i = monitorIndex( mons, r );
        return i < 0 ? fallback : mons.get( i );
    }

    /**
     * Which monitor the user means: "other/another/next/secondary" (the next one, wrapping), "second/2nd/2",
     * "third/3", "first/primary/main/1" (the one at the origin), "left" / "right" (neighbour of the window's
     * monitor). Returns an index into {@code mons} (sorted left to right), or -1 when there is no such monitor.
     */
    public static int pickMonitor( final java.util.List<Rectangle> mons, final Rectangle win, final String which )
    {
        final int n = mons.size();
        if( n == 0 ) return -1;
        final int cur = monitorIndex( mons, win );
        switch( which.toLowerCase( java.util.Locale.ROOT ).trim() )
        {
            case "other": case "another": case "next": case "secondary": return n > 1 ? ( cur + 1 ) % n : -1;
            case "second": case "2nd": case "2": return n > 1 ? 1 : -1;
            case "third": case "3rd": case "3": return n > 2 ? 2 : -1;
            case "left": return cur > 0 ? cur - 1 : -1;
            case "right": return cur < n - 1 ? cur + 1 : -1;
            case "first": case "1st": case "1": case "primary": case "main":
                for( int i = 0; i < n; i++ ) if( mons.get( i ).contains( 0, 0 ) ) return i;
                return 0;
            default: return -1;
        }
    }

    /**
     * Where {@code win} lands on monitor {@code to} when it leaves monitor {@code from}: same relative
     * position, same size (shrunk only if it would not fit), always fully inside {@code to}.
     */
    public static Rectangle toMonitor( final Rectangle win, final Rectangle from, final Rectangle to )
    {
        final int w = Math.min( win.width, to.width ), h = Math.min( win.height, to.height );
        final int slackX = from.width - win.width, slackY = from.height - win.height;
        final double fx = slackX > 0 ? Math.max( 0, Math.min( 1, (double) ( win.x - from.x ) / slackX ) ) : 0.5;
        final double fy = slackY > 0 ? Math.max( 0, Math.min( 1, (double) ( win.y - from.y ) / slackY ) ) : 0.5;
        return new Rectangle( to.x + (int) Math.round( fx * ( to.width - w ) ), to.y + (int) Math.round( fy * ( to.height - h ) ), w, h );
    }
}
