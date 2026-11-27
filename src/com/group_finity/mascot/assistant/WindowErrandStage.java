package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.group_finity.mascot.behavior.ErrandLeg;
import com.group_finity.mascot.behavior.UserBehavior;

/**
 * Production {@link WindowErrand.Stage}: the mascot's own {@code Jumping} / {@code Walk} / {@code Fall}
 * actions (so any image set works without XML changes), a {@link Hold} thread that keeps the grabbed
 * window at the mascot's feet (never the tick: moving another process's window can stall), and the
 * window API for the button press itself. Runs on the agent worker thread; mascot changes are
 * marshalled to the Swing thread.
 */
public final class WindowErrandStage implements WindowErrand.Stage
{
    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger( WindowErrandStage.class.getName() );
    private final AssistantHost host;
    private final WindowControl windows;
    private final long handle;
    private Hold hold;

    public WindowErrandStage( final AssistantHost host, final WindowControl windows, final long handle )
    {
        this.host = host; this.windows = windows; this.handle = handle;
    }

    @Override public WindowControl.Info window() { return windows.current( handle ); }
    @Override public Rectangle mascot() { return host.getBounds(); }

    // ---- legs (same mechanism as the file errand) --------------------------------------------

    private boolean leg( final String action, final Map<String, String> params, final long timeoutMs )
    {
        final CountDownLatch latch = new CountDownLatch( 1 );
        final boolean[] ok = { false };
        javax.swing.SwingUtilities.invokeLater( () ->
        {
            try
            {
                host.setBehavior( new ErrandLeg( action, params, host.getConfiguration(), r -> { ok[ 0 ] = r; latch.countDown(); } ) );
            }
            catch( final Throwable t ) { latch.countDown(); }
        } );
        try
        {
            final boolean done = latch.await( timeoutMs, TimeUnit.MILLISECONDS ) && ok[ 0 ];
            if( !done ) log.warning( "[WindowErrand] leg '" + action + "' " + params + " did not complete (action missing for this set, unreachable, or timed out)" );
            return done;
        }
        catch( final InterruptedException e ) { Thread.currentThread().interrupt(); return false; }
    }

    private boolean jumpTo( final int x, final int y )
    {
        final Map<String, String> p = new HashMap<>();
        p.put( "TargetX", Integer.toString( x ) );
        p.put( "TargetY", Integer.toString( y ) );
        return leg( "Jumping", p, 15_000 );
    }

    private boolean walkTo( final int x )
    {
        final Map<String, String> p = new HashMap<>();
        p.put( "TargetX", Integer.toString( x ) );
        if( leg( "Walk", p, 60_000 ) ) return true;
        leg( "Fall", new HashMap<>(), 8_000 );            // not on the floor? land first, then retry once
        return leg( "Walk", p, 60_000 );
    }

    private int floorY( final Rectangle near )
    {
        final Rectangle wa = AgentLayout.screenFor( windows.monitors(), near, new Rectangle( 0, 0, 1280, 720 ) );
        return wa.y + wa.height;
    }

    // ---- steps -------------------------------------------------------------------------------

    @Override
    public boolean press( final WindowErrandPlan.Step step )
    {
        log.info( "[WindowErrand] press " + step.op + ( step.direct ? " (direct)" : " at " + step.at ) );
        if( !step.direct && step.at != null )
            jumpTo( step.at.x, step.at.y );      // if the mascot can't get there the press still happens, just without the leap
        final WindowControl.Info cur = windows.current( handle );
        if( cur == null ) return false;
        final boolean ok = windows.apply( cur, step.op );
        leg( "Fall", new HashMap<>(), 8_000 );   // the button was up in the air: come back down
        return ok;
    }

    @Override
    public boolean grab( final WindowErrandPlan.Step step )
    {
        final int cx = step.at.x, cy = step.at.y;
        final Rectangle mb = host.getBounds();
        if( Math.abs( cy - floorY( mb ) ) <= 48 ) { if( !walkTo( cx ) ) return false; }
        else jumpTo( cx, cy );
        hold = new Hold( windows, handle, host.getId(), step.left, host::getBounds );
        hold.start();
        return true;
    }

    @Override
    public boolean carry( final WindowErrandPlan.Step step )
    {
        // The window is glued to the mascot's feet by Hold, so if the user picks the mascot up (or it
        // falls / is thrown) the window simply rides along. When the walk was interrupted, wait for the
        // mascot to come to rest, land it, and resume the walk from wherever it ended up.
        for( int attempt = 0; attempt < 4; attempt++ )
        {
            if( walkTo( step.walkX ) ) return !taken();
            if( taken() || !settle() ) return false;
        }
        return false;
    }

    /** After an interrupted leg: true once the user let go and the mascot is standing again. False = nothing to wait out. */
    private boolean settle()
    {
        try
        {
            Thread.sleep( 150 );                           // the interrupting drag registers just after the leg reports failure
            if( !host.isDragging() ) return false;         // not a drag: the leg genuinely failed
            final long end = System.currentTimeMillis() + 120_000;
            while( host.isDragging() && System.currentTimeMillis() < end && !taken() ) Thread.sleep( 100 );
            if( taken() ) return false;
            Rectangle last = host.getBounds();             // let the throw play out until it stops moving
            int still = 0;
            for( int i = 0; i < 100 && still < 8; i++ )
            {
                Thread.sleep( 100 );
                final Rectangle b = host.getBounds();
                still = ( b != null && b.equals( last ) ) ? still + 1 : 0;
                last = b;
            }
            leg( "Fall", new HashMap<>(), 8_000 );
            return !taken();
        }
        catch( final InterruptedException e ) { Thread.currentThread().interrupt(); return false; }
    }

    @Override
    public void release( final Rectangle target )
    {
        if( hold != null ) { hold.end(); hold = null; }
        final WindowControl.Info cur = windows.current( handle );
        if( cur == null ) return;
        // thrown into place: a quick ease-out from where it hung at the mascot's feet to its final rectangle
        final Rectangle a = cur.bounds;
        if( Math.abs( a.x - target.x ) + Math.abs( a.y - target.y ) + Math.abs( a.width - target.width ) + Math.abs( a.height - target.height ) > 24 )
        {
            try
            {
                final double dist = Math.hypot( target.x - a.x, target.y - a.y );
                // a long flight (another monitor) is a real throw: more frames, an arc over the straight line, constant-ish speed
                final boolean flight = dist > 400;
                final int steps = flight ? 26 : 12;
                final int arc = flight ? (int) Math.min( 260, dist / 4 ) : 0;
                for( int i = 1; i < steps; i++ )
                {
                    final double t = (double) i / steps, e = flight ? t * ( 2 - t ) : 1 - Math.pow( 1 - t, 3 );
                    final int lift = (int) Math.round( Math.sin( Math.PI * t ) * arc );
                    final Rectangle r = new Rectangle(
                        a.x + (int) Math.round( ( target.x - a.x ) * e ), a.y + (int) Math.round( ( target.y - a.y ) * e ) - lift,
                        a.width + (int) Math.round( ( target.width - a.width ) * e ), a.height + (int) Math.round( ( target.height - a.height ) * e ) );
                    if( !windows.place( cur, r ) ) return;
                    Thread.sleep( 18 );
                }
            }
            catch( final InterruptedException ie ) { Thread.currentThread().interrupt(); }
        }
        windows.place( cur, target );
    }

    @Override public boolean taken() { return hold != null && hold.taken; }

    @Override
    public void finish()
    {
        if( hold != null ) { hold.end(); hold = null; }
        javax.swing.SwingUtilities.invokeLater( () ->
        {
            try
            {
                final com.group_finity.mascot.config.Configuration c = host.getConfiguration();
                host.setBehavior( c.buildBehavior( c.getSchema().getString( UserBehavior.BEHAVIOURNAME_FALL ) ) );
            }
            catch( final Throwable t ) { /* the mascot is gone or mid-drag; nothing to hand back */ }
        } );
    }

    /** Keeps one bottom corner of the window at the mascot's feet. Ends if the user drags / maximizes / minimizes it. */
    static final class Hold implements Runnable
    {
        private final WindowControl wc;
        private final long handle;
        private final int owner;
        private final boolean left;
        private final java.util.function.Supplier<Rectangle> mascot;
        private volatile boolean stop = false;
        volatile boolean taken = false;
        private Rectangle last = null;

        Hold( final WindowControl wc, final long handle, final int owner, final boolean left, final java.util.function.Supplier<Rectangle> mascot )
        {
            this.owner = owner; this.wc = wc; this.handle = handle; this.left = left; this.mascot = mascot;
        }

        void start()
        {
            AgentCarry.markCarried( handle, owner, true );
            final Thread t = new Thread( this, "window-hold" );
            t.setDaemon( true );
            thread = t;
            t.start();
        }

        private Thread thread;

        void end()
        {
            stop = true;
            final Thread t = thread;
            if( t != null && t != Thread.currentThread() ) try { t.join( 300 ); } catch( final InterruptedException e ) { Thread.currentThread().interrupt(); }
            AgentCarry.markCarried( handle, owner, false );
        }

        @Override
        public void run()
        {
            try
            {
                while( !stop )
                {
                    final WindowControl.Info cur = wc.current( handle );
                    if( cur == null ) break;
                    if( last != null )
                    {
                        if( cur.minimized || cur.maximized ) { taken = true; break; }
                        if( cur.bounds.width == last.width && cur.bounds.height == last.height
                            && ( Math.abs( cur.bounds.x - last.x ) > AgentCarry.MOVE_TOLERANCE
                              || Math.abs( cur.bounds.y - last.y ) > AgentCarry.MOVE_TOLERANCE ) ) { taken = true; break; }
                    }
                    final Rectangle m = mascot.get();
                    if( m != null )
                    {
                        final int ax = m.x + m.width / 2, ay = m.y + m.height;
                        final Rectangle wa = AgentLayout.screenFor( wc.monitors(), m, AgentCarry.screenOf( m ) );
                        final int x = left ? ax : ax - cur.bounds.width;
                        final int y = Math.max( wa.y, ay - cur.bounds.height );
                        if( x != cur.bounds.x || y != cur.bounds.y )
                            if( !wc.moveTo( cur, x, y ) ) break;
                        last = new Rectangle( x, y, cur.bounds.width, cur.bounds.height );
                    }
                    Thread.sleep( AgentCarry.STEP_MS );
                }
            }
            catch( final InterruptedException ignored ) { }
            catch( final Throwable ignored ) { }
            finally { AgentCarry.markCarried( handle, owner, false ); }
        }
    }
}
