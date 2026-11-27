package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
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

    /** Window handle -> id of the mascot carrying it. */
    private static final java.util.Map<Long, Integer> CARRIED = new ConcurrentHashMap<>();

    private AgentCarry() { }

    /** True while any window is being carried (cheap guard for the per-mascot scan). */
    public static boolean anyCarried() { return !CARRIED.isEmpty(); }

    /**
     * True if this window is being carried by the mascot with this id. Only the carrier hides it from its
     * own surface scan (it must not stand on or climb what it holds); every other mascot still sees the
     * window and rides along as it moves, exactly as when the user drags it.
     */
    public static boolean isCarriedBy( final long handle, final int ownerId )
    {
        if( CARRIED.isEmpty() ) return false;
        final Integer o = CARRIED.get( handle );
        return o != null && o == ownerId;
    }

    /** Mark/unmark a window as held by a mascot (used by the visible window errand's hold). */
    static void markCarried( final long handle, final int ownerId, final boolean on )
    {
        if( on ) CARRIED.put( handle, ownerId ); else CARRIED.remove( handle );
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
