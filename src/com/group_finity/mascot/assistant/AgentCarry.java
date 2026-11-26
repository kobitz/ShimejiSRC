package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared bookkeeping for a window a mascot is physically holding (see {@link WindowErrandStage}):
 * which handles are hidden from the mascots' window-surface scan so they don't try to stand on or
 * climb the window being carried, plus the hold-loop constants. There is no timed "carry around"
 * any more: windows only move as part of a visible errand.
 */
public final class AgentCarry
{
    public static final long STEP_MS = 33L;
    /** How far (px) the window may differ from where we last put it before we call it "moved by the user". */
    static final int MOVE_TOLERANCE = 8;

    private static final Set<Long> CARRIED = ConcurrentHashMap.newKeySet();

    private AgentCarry() { }

    /** True while this window handle is being carried (used by the window scan). */
    public static boolean isCarried( final long handle ) { return !CARRIED.isEmpty() && CARRIED.contains( handle ); }

    /** Hide/unhide a window from the mascots' surface scan (used by the visible window errand's hold). */
    static void markCarried( final long handle, final boolean on )
    {
        if( on ) CARRIED.add( handle ); else CARRIED.remove( handle );
    }

    /** Bounds of the screen the mascot is on (whole screen, not just work area: a window may sit over the taskbar). */
    public static Rectangle screenOf( final Rectangle mascot )
    {
        try
        {
            final java.awt.GraphicsDevice[] ds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            Rectangle best = null;
            long bestArea = -1;
            for( final java.awt.GraphicsDevice d : ds )
            {
                final Rectangle b = d.getDefaultConfiguration().getBounds();
                final Rectangle i = b.intersection( mascot );
                final long a = i.isEmpty() ? 0 : (long) i.width * i.height;
                if( a > bestArea ) { bestArea = a; best = b; }
            }
            if( best != null ) return best;
        }
        catch( final Throwable ignored ) { }
        return new Rectangle( 0, 0, 1920, 1080 );
    }
}
