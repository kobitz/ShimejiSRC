package com.group_finity.mascot.assistant;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Decides, from the window's CURRENT state, the next physical thing a mascot must do to reach a window
 * goal. Pure: no mascot, no Win32. The director calls {@link #next} again after every step, so the plan
 * adapts when the window changes underneath it (pressing "restore" on a maximized window changes its size
 * and position, the user may move it, ...).
 *
 * Goals: send a window to another monitor (optionally maximizing it there), or just maximize / restore /
 * minimize it. Everything happens with the mascot's body: jump at the title-bar button, grab the bottom
 * corner nearest to it, carry the window across the floor, put it down.
 */
public final class WindowErrandPlan
{
    private WindowErrandPlan() { }

    public enum Kind { PRESS, GRAB, CARRY, RELEASE, DONE, FAIL }

    /** What to end up with. {@code toMonitor} null = no move; {@code finalOp} one of MAXIMIZE, RESTORE, MINIMIZE or null. */
    public static final class Goal
    {
        public final Rectangle toMonitor;
        public final WindowControl.Op finalOp;
        /** Snap goal: the exact rectangle the window ends up in (carried there, then thrown into place). */
        public final Rectangle region;

        public Goal( final Rectangle toMonitor, final WindowControl.Op finalOp )
        {
            this( toMonitor, finalOp, null );
        }

        public Goal( final Rectangle toMonitor, final WindowControl.Op finalOp, final Rectangle region )
        {
            this.toMonitor = toMonitor;
            this.finalOp = finalOp;
            this.region = region;
        }
    }

    public static final class State
    {
        public final WindowControl.Info window;   // fresh, null = gone
        public final Rectangle mascot;
        public final Rectangle fromMonitor;       // monitor the window is on
        public final boolean held;
        public final boolean heldLeft;            // which bottom corner is held
        public final double scale;

        public State( final WindowControl.Info window, final Rectangle mascot, final Rectangle fromMonitor,
                      final boolean held, final boolean heldLeft, final double scale )
        {
            this.window = window; this.mascot = mascot; this.fromMonitor = fromMonitor;
            this.held = held; this.heldLeft = heldLeft; this.scale = scale;
        }
    }

    public static final class Step
    {
        public final Kind kind;
        /** PRESS: the operation and where the button is. {@code direct}: no jump, just apply (nothing to jump at). */
        public final WindowControl.Op op;
        public final Point at;
        public final boolean direct;
        /** GRAB: the corner point to stand at (screen coords) and which corner. CARRY: mascot x to walk to + final window rect. */
        public final boolean left;
        public final int walkX;
        public final Rectangle windowTarget;

        Step( final Kind k, final WindowControl.Op op, final Point at, final boolean direct, final boolean left, final int walkX, final Rectangle t )
        {
            this.kind = k; this.op = op; this.at = at; this.direct = direct; this.left = left; this.walkX = walkX; this.windowTarget = t;
        }
        static Step of( final Kind k ) { return new Step( k, null, null, false, false, 0, null ); }
        @Override public String toString() { return kind + ( op != null ? ":" + op : "" ); }
    }

    static boolean onTarget( final Rectangle win, final Rectangle mon )
    {
        return mon.contains( win.x + win.width / 2, win.y + win.height / 2 );
    }

    public static Step next( final Goal g, final State s )
    {
        final WindowControl.Info w = s.window;
        if( w == null ) return Step.of( Kind.FAIL );

        if( w.minimized )
        {
            // the taskbar is not something a mascot can jump at: bring it back directly
            if( g.finalOp == WindowControl.Op.MINIMIZE && g.toMonitor == null ) return Step.of( Kind.DONE );
            return new Step( Kind.PRESS, WindowControl.Op.RESTORE, null, true, false, 0, null );
        }

        if( g.region != null ) return toRegion( g.region, w, s );
        if( g.toMonitor == null ) return opOnly( g.finalOp, w, s );

        final boolean there = onTarget( w.bounds, g.toMonitor );
        if( s.held && there ) return Step.of( Kind.RELEASE );

        if( !there )
        {
            if( w.maximized )   // no free corner to grab: hit the window button, then it can be carried
                return press( WindowControl.Op.RESTORE, w, s );
            final Rectangle target = AgentLayout.toMonitor( w.bounds, s.fromMonitor, g.toMonitor );
            if( !s.held )
            {
                final boolean left = Math.abs( s.mascot.x + s.mascot.width / 2 - w.bounds.x )
                                   <= Math.abs( s.mascot.x + s.mascot.width / 2 - ( w.bounds.x + w.bounds.width ) );
                final int cx = left ? w.bounds.x : w.bounds.x + w.bounds.width;
                return new Step( Kind.GRAB, null, new Point( cx, w.bounds.y + w.bounds.height ), false, left, cx, target );
            }
            final int walkX = s.heldLeft ? target.x : target.x + target.width;
            return new Step( Kind.CARRY, null, null, false, s.heldLeft, walkX, target );
        }

        // on the right monitor and free
        if( g.finalOp == WindowControl.Op.MAXIMIZE && !w.maximized ) return press( WindowControl.Op.MAXIMIZE, w, s );
        return Step.of( Kind.DONE );
    }

    /** Snap / centre: grab the window, walk the held corner to the region's matching corner, throw it into place. */
    private static Step toRegion( final Rectangle region, final WindowControl.Info w, final State s )
    {
        final boolean atRegion = !w.maximized && Math.abs( w.bounds.x - region.x ) <= 16 && Math.abs( w.bounds.y - region.y ) <= 16;
        if( atRegion && !s.held ) return Step.of( Kind.DONE );
        if( w.maximized && !s.held ) return press( WindowControl.Op.RESTORE, w, s );   // no free corner to grab on a maximized window
        if( !s.held )
        {
            final boolean left = Math.abs( s.mascot.x + s.mascot.width / 2 - w.bounds.x )
                               <= Math.abs( s.mascot.x + s.mascot.width / 2 - ( w.bounds.x + w.bounds.width ) );
            final int cx = left ? w.bounds.x : w.bounds.x + w.bounds.width;
            return new Step( Kind.GRAB, null, new Point( cx, w.bounds.y + w.bounds.height ), false, left, cx, region );
        }
        final int heldX = s.heldLeft ? w.bounds.x : w.bounds.x + w.bounds.width;
        final int walkX = s.heldLeft ? region.x : region.x + region.width;
        if( Math.abs( heldX - walkX ) <= 24 ) return Step.of( Kind.RELEASE );
        return new Step( Kind.CARRY, null, null, false, s.heldLeft, walkX, region );
    }

    private static Step opOnly( final WindowControl.Op op, final WindowControl.Info w, final State s )
    {
        if( op == null ) return Step.of( Kind.DONE );
        switch( op )
        {
            case MAXIMIZE: return w.maximized ? Step.of( Kind.DONE ) : press( op, w, s );
            case RESTORE:  return w.maximized ? press( op, w, s ) : Step.of( Kind.DONE );
            case MINIMIZE: return press( op, w, s );
            default:       return Step.of( Kind.DONE );
        }
    }

    private static Step press( final WindowControl.Op op, final WindowControl.Info w, final State s )
    {
        final CaptionButtons.Button b = op == WindowControl.Op.MINIMIZE ? CaptionButtons.Button.MINIMIZE : CaptionButtons.Button.MAXIMIZE;
        return new Step( Kind.PRESS, op, CaptionButtons.center( w.bounds, b, s.scale, w.maximized ), false, false, 0, null );
    }
}
