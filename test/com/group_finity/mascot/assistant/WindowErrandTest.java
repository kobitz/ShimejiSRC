package com.group_finity.mascot.assistant;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plan + director for visible window errands, with a fake mascot/window stage. */
public class WindowErrandTest
{
    static int fails = 0;
    static void check( final boolean ok, final String what ) { if( !ok ) { fails++; System.out.println( "FAIL: " + what ); } }

    static final Rectangle M1 = new Rectangle( 0, 0, 1920, 1040 ), M2 = new Rectangle( 1920, 0, 1920, 1040 );
    static final List<Rectangle> MONS = Arrays.asList( M1, M2 );

    static WindowControl.Info win( final Rectangle b, final boolean min, final boolean max )
    {
        return new WindowControl.Info( 1L, "w", b, min, max );
    }

    /** A fake world: one window, one mascot; every stage call mutates it like the real thing would. */
    static final class Fake implements WindowErrand.Stage
    {
        WindowControl.Info w;
        Rectangle mascot = new Rectangle( 100, 900, 64, 128 );
        boolean held, heldLeft, taken, finished, userTakesOnCarry;
        final List<String> log = new ArrayList<>();
        Fake( final WindowControl.Info w ) { this.w = w; }
        public WindowControl.Info window() { return w; }
        public Rectangle mascot() { return mascot; }
        public boolean press( final WindowErrandPlan.Step s )
        {
            log.add( "press " + s.op + ( s.direct ? " direct" : "" ) );
            Rectangle b = w.bounds;
            switch( s.op )
            {
                case MAXIMIZE:
                {
                    final Rectangle mon = M2.contains( b.x + b.width / 2, b.y + b.height / 2 ) ? M2 : M1;   // maximizes where the window is
                    w = win( new Rectangle( mon.x - 8, mon.y - 8, mon.width + 16, mon.height + 16 ), false, true ); break;
                }
                case RESTORE:  w = win( w.maximized ? new Rectangle( 300, 200, 900, 600 ) : b, false, false ); break;
                case MINIMIZE: w = win( b, true, false ); break;
                default: break;
            }
            return true;
        }
        public boolean grab( final WindowErrandPlan.Step s ) { log.add( "grab " + ( s.left ? "L" : "R" ) ); held = true; heldLeft = s.left; return true; }
        public boolean carry( final WindowErrandPlan.Step s )
        {
            log.add( "carry" );
            if( userTakesOnCarry ) { taken = true; return false; }
            // window ends up with the held corner at the walk target, bottom at the floor
            final int x = heldLeft ? s.walkX : s.walkX - w.bounds.width;
            w = win( new Rectangle( x, 1040 - w.bounds.height, w.bounds.width, w.bounds.height ), false, false );
            return true;
        }
        public void release( final Rectangle t ) { log.add( "release" ); held = false; w = win( t, false, false ); }
        public boolean taken() { return taken; }
        public void finish() { finished = true; }
    }

    public static void main( final String[] args )
    {
        // caption button geometry: maximize button sits left of close, centre inside the title bar
        final Rectangle r = new Rectangle( 100, 100, 800, 600 );
        final Point max = CaptionButtons.center( r, CaptionButtons.Button.MAXIMIZE, 1.0, false );
        final Point close = CaptionButtons.center( r, CaptionButtons.Button.CLOSE, 1.0, false );
        check( max.x < close.x && close.x - max.x == 46, "buttons are 46px apart" );
        check( max.y > r.y && max.y < r.y + 40, "button inside title bar" );
        check( CaptionButtons.center( r, CaptionButtons.Button.MAXIMIZE, 2.0, false ).x < max.x, "scale moves it left" );

        // maximize a normal window: one press at the button
        Fake f = new Fake( win( r, false, false ) );
        check( WindowErrand.run( f, new WindowErrandPlan.Goal( null, WindowControl.Op.MAXIMIZE ), MONS, 1.0 ) == WindowErrand.Outcome.DONE, "maximize done" );
        check( f.log.equals( Arrays.asList( "press MAXIMIZE" ) ) && f.w.maximized && f.finished, "maximize log " + f.log );

        // already maximized -> nothing to do
        f = new Fake( win( M1, false, true ) );
        check( WindowErrand.run( f, new WindowErrandPlan.Goal( null, WindowControl.Op.MAXIMIZE ), MONS, 1.0 ) == WindowErrand.Outcome.DONE && f.log.isEmpty(), "no-op maximize" );

        // minimized window: restored directly (no taskbar to jump at)
        f = new Fake( win( r, true, false ) );
        WindowErrand.run( f, new WindowErrandPlan.Goal( null, WindowControl.Op.MAXIMIZE ), MONS, 1.0 );
        check( f.log.get( 0 ).equals( "press RESTORE direct" ), "minimized restored directly: " + f.log );

        // move to monitor 2: grab nearest corner, carry, release
        f = new Fake( win( new Rectangle( 200, 300, 800, 600 ), false, false ) );
        check( WindowErrand.run( f, new WindowErrandPlan.Goal( M2, null ), MONS, 1.0 ) == WindowErrand.Outcome.DONE, "move done" );
        check( f.log.equals( Arrays.asList( "grab L", "carry", "release" ) ), "move log " + f.log );
        check( M2.contains( f.w.bounds.x + f.w.bounds.width / 2, f.w.bounds.y + f.w.bounds.height / 2 ), "ended on monitor 2" );

        // mascot nearer the right corner grabs the right corner
        f = new Fake( win( new Rectangle( 200, 300, 800, 600 ), false, false ) );
        f.mascot = new Rectangle( 950, 900, 64, 128 );
        WindowErrand.run( f, new WindowErrandPlan.Goal( M2, null ), MONS, 1.0 );
        check( f.log.get( 0 ).equals( "grab R" ), "right corner chosen: " + f.log );

        // maximized window going to monitor 2 and staying maximized: window button first, then carry, then maximize
        f = new Fake( win( new Rectangle( M1.x - 8, M1.y - 8, M1.width + 16, M1.height + 16 ), false, true ) );
        check( WindowErrand.run( f, new WindowErrandPlan.Goal( M2, WindowControl.Op.MAXIMIZE ), MONS, 1.0 ) == WindowErrand.Outcome.DONE, "max move done" );
        check( f.log.get( 0 ).equals( "press RESTORE" ) && f.log.get( f.log.size() - 1 ).equals( "press MAXIMIZE" ), "max move log " + f.log );
        check( f.log.contains( "grab L" ) || f.log.contains( "grab R" ), "grabbed a corner after windowing: " + f.log );

        // the user takes the window mid-carry
        f = new Fake( win( new Rectangle( 200, 300, 800, 600 ), false, false ) );
        f.userTakesOnCarry = true;
        check( WindowErrand.run( f, new WindowErrandPlan.Goal( M2, null ), MONS, 1.0 ) == WindowErrand.Outcome.TAKEN && f.finished, "taken ends errand" );

        // window vanishes
        f = new Fake( null );
        check( WindowErrand.run( f, new WindowErrandPlan.Goal( null, WindowControl.Op.MAXIMIZE ), MONS, 1.0 ) == WindowErrand.Outcome.GONE, "gone" );

        // permission switch: per image set beats global, default on
        final java.util.Properties p = new java.util.Properties();
        check( AgentTools.windowInteractionAllowed( p, "Holo" ), "default on" );
        p.setProperty( "Throwing", "false" );
        check( !AgentTools.windowInteractionAllowed( p, "Holo" ), "global off" );
        p.setProperty( "Throwing.imageset.Holo", "true" );
        check( AgentTools.windowInteractionAllowed( p, "Holo" ) && !AgentTools.windowInteractionAllowed( p, "2B" ), "per set overrides" );

        if( fails > 0 ) { System.out.println( fails + " failure(s)" ); System.exit( 1 ); }
        System.out.println( "WindowErrandTest OK" );
    }
}
