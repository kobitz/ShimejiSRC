package com.group_finity.mascot.environment;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public abstract class Environment
{
    protected abstract Area getWorkArea( );

    public abstract Area getActiveIE( );

    /** Returns all viable interactive window areas on this mascot's screen.
     *  Used by scripts to clamp walk targets so mascots don't walk through windows.
     *  Returns an empty list on platforms that don't support window enumeration. */
    public List<Area> getAllIE( )
    {
        return Collections.emptyList( );
    }
        
    public abstract String getActiveIETitle( );

    /** Returns the title of the OS foreground window, regardless of what the mascot is sitting on. */
    public String getForegroundWindowTitle( ) { return ""; }

    public abstract void moveActiveIE( final Point point );

    public abstract void minimizeActiveIE( );

    /**
     * True when a real window stacked above the given mascot window completely covers
     * {@code r} (screen coordinates). Default: nothing obscures.
     */
    public boolean isObscuredByWindow( final java.awt.Component mascotWindow, final java.awt.Rectangle r )
    {
        return false;
    }

    public abstract void restoreIE( );
    
    public abstract void refreshCache( );
    
    public abstract void dispose( );

    protected static Rectangle screenRect = new Rectangle( new Point(0, 0), Toolkit.getDefaultToolkit( ).getScreenSize( ) );

    protected static Map<String, Rectangle> screenRects = new HashMap<String, Rectangle>( );
    
    private static final Thread thread = new Thread( )
    {
        @Override
        public void run( )
        {
            try
            {
                while( true )
                {
                    updateScreenRect( );
                    Thread.sleep( 5000 );
                }
            }
            catch( final InterruptedException e )
            {
            }
        }
    };

    public ComplexArea complexScreen = new ComplexArea( );

    public Area screen = new Area( );

    public Location cursor = new Location( );

    private static void updateScreenRect( )
    {
        Rectangle virtualBounds = new Rectangle( );

        Map<String, Rectangle> screenRects = new HashMap<String, Rectangle>( );

        final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment( );
        final GraphicsDevice[ ] gs = ge.getScreenDevices( );

        for( int j = 0; j < gs.length; j++ )
        {
            final GraphicsDevice gd = gs[ j ];
            screenRects.put( gd.getIDstring( ), gd.getDefaultConfiguration( ).getBounds( ) );
            virtualBounds = virtualBounds.union( gd.getDefaultConfiguration( ).getBounds( ) );
        }

        Environment.screenRects = screenRects;

        screenRect = virtualBounds;
    }

    protected static Rectangle getScreenRect( )
    {
        return screenRect;
    }

    // Last known pointer position, refreshed by a background poller. MouseInfo.getPointerInfo()
    // calls into native AWT code that waits on the AWT lock; called per mascot per tick on the
    // Manager thread it stalled the whole tick for 5s while the EDT was inside a long native
    // Window.setSize (watchdog dump, Nov 2026). Now only the poller can block; the tick just
    // reads the cache (at most ~15 ms stale, imperceptible).
    private static volatile Point cursorPos = new Point( 0, 0 );
    private static volatile boolean cursorPolled = false;

    private static Point readCursorNow( )
    {
        final java.awt.PointerInfo info = MouseInfo.getPointerInfo( );
        return info != null ? info.getLocation( ) : new Point( 0, 0 );
    }

    private static final Thread cursorThread = new Thread( "CursorPoller" )
    {
        @Override
        public void run( )
        {
            try
            {
                while( true )
                {
                    try { cursorPos = readCursorNow( ); }
                    catch( final RuntimeException e ) { /* keep last position */ }
                    Thread.sleep( 15 );
                }
            }
            catch( final InterruptedException e )
            {
            }
        }
    };

    private static Point getCursorPos( )
    {
        if( !cursorPolled )
        {
            // First call only: read synchronously so tick 1 has a real position, then hand over.
            cursorPolled = true;
            cursorPos = readCursorNow( );
            cursorThread.setDaemon( true );
            cursorThread.setPriority( Thread.NORM_PRIORITY );
            cursorThread.start( );
        }
        return cursorPos;
    }

    public void init( )
    {
        if( !thread.isAlive( ) )
        {
            thread.setDaemon( true );
            thread.setPriority( Thread.MIN_PRIORITY );
            thread.start( );
        }

        tick( );
    }

    public void tick( )
    {
        screen.set( Environment.getScreenRect( ) );
        complexScreen.set( screenRects );
        cursor.set( Environment.getCursorPos( ) );
    }

    /** Id of the mascot this environment instance serves (-1 until set); used to exclude its own collision box. */
    protected int ownerId = -1;
    public void setOwnerId( final int id ) { ownerId = id; }

    // Called once per tick per mascot with that mascot's anchor position.
    // Subclasses override to do per-mascot nearest-window detection.
    // Default falls back to tick() so non-Windows platforms are unaffected.
    public void tickForMascot( final java.awt.Point mascotAnchor )
    {
        tick( );
    }

    // Carry lock: while locked, tickForMascot skips window re-scanning so the
    // carried window isn't dropped mid-carry.
    public void lockActiveIE( ) { }
    public void unlockActiveIE( ) { }

    public Area getScreen( )
    {
        return screen;
    }

    public Collection<Area> getScreens( )
    {
        return complexScreen.getAreas( );
    }

    public ComplexArea getComplexScreen( )
    {
        return complexScreen;
    }

    public Location getCursor( )
    {
        return cursor;
    }

    public boolean isScreenTopBottom( final Point location )
    {
        int count = 0;

        for( Area area: getScreens( ) )
        {
            if( area.getTopBorder( ).isOn( location ) )
                ++count;
            if( area.getBottomBorder( ).isOn( location ) )
                ++count;
        }

        if( count==0 )
        {
            if( getWorkArea( ).getTopBorder( ).isOn( location ) )
                return true;
            if( getWorkArea( ).getBottomBorder( ).isOn( location ) )
                return true;
        }

        return count == 1;
    }

    public boolean isScreenLeftRight( final Point location )
    {
        int count = 0;

        for( Area area: getScreens( ) )
        {   
            if( area.getLeftBorder( ).isOn( location ) )
                ++count;
            if( area.getRightBorder( ).isOn( location ) )
                ++count;
        }

        if( count == 0 )
        {
            if( getWorkArea( ).getLeftBorder( ).isOn( location ) )
                return true;
            if( getWorkArea( ).getRightBorder( ).isOn( location ) )
                return true;
        }

        return count == 1;
    }
}
