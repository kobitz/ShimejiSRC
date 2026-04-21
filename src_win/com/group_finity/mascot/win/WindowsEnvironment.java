package com.group_finity.mascot.win;

import com.group_finity.mascot.Main;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.environment.Environment;
import com.group_finity.mascot.win.jna.Dwmapi;
import com.group_finity.mascot.win.jna.Gdi32;
import com.group_finity.mascot.win.jna.MONITORINFO;
import com.group_finity.mascot.win.jna.RECT;
import com.group_finity.mascot.win.jna.User32;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

import java.util.logging.*;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 *
 * PERFORMANCE OPTIMIZATIONS:
 *
 *  1. Shared EnumWindows scan: Manager.tick() calls WindowsEnvironment.beginTick() ONCE
 *     before the mascot loop. All mascots read from that snapshot instead of each running
 *     their own scan. 12 mascots → 1 EnumWindows/tick instead of 12.
 *
 *  2. Per-window work (DwmGetWindowAttribute, IsZoomed, IsIconic, GetWindowTextW,
 *     GetWindowLongW) done once per window per tick in the shared scan, not per mascot.
 *
 *  3. Thread-confined reusable RECT/LongByReference for the scan loop — no per-window
 *     allocation of native structs.
 *
 *  4. Area object pool in allIEAreas: existing Area instances recycled across ticks.
 *
 *  5. ieCache bounded LRU (max 256) prevents unbounded growth over long sessions.
 *
 *  6. IE_SCAN_INTERVAL = 1 is affordable now that cost is O(windows), not
 *     O(windows × mascots).
 */
public class WindowsEnvironment extends Environment
{
    // ── Shared (static) state ────────────────────────────────────────────────

    // LRU-bounded isIE() result cache (TRUE entries only).
    // FALSE results are never cached so newly-created windows get a fresh check each tick.
    private static final int IE_CACHE_MAX = 256;
    private static final LinkedHashMap<Pointer, Boolean> ieCache =
        new LinkedHashMap<Pointer, Boolean>( IE_CACHE_MAX, 0.75f, true )
        {
            @Override
            protected boolean removeEldestEntry( java.util.Map.Entry<Pointer, Boolean> eldest )
            {
                return size( ) > IE_CACHE_MAX;
            }
        };

    private static String[]  windowTitles          = null;
    private static String[]  windowTitlesBlacklist = null;

    private static final Logger log = Logger.getLogger( Environment.class.getName( ) );

    private enum IEResult { INVALID, NOT_IE, IE_OUT_OF_BOUNDS, IE }

    // ── Shared scan snapshot (written once per tick by beginTick) ─────────────
    // All fields are written before sharedHandles so readers can use sharedHandles
    // as a publication guard: if sharedHandles is non-null the rest are ready.

    private static List<Pointer>   sharedHandles   = new ArrayList<>( );
    private static List<Rectangle> sharedRects     = new ArrayList<>( );
    private static List<IEResult>  sharedViability = new ArrayList<>( );
    private static List<Integer>   sharedExStyles  = new ArrayList<>( );

    // Reusable native structs — manager thread only (single-threaded tick loop).
    private static final RECT            reuseOut   = new RECT( );
    private static final RECT            reuseIn    = new RECT( );
    private static final LongByReference reuseFlags = new LongByReference( );

    /**
     * Called ONCE by Manager.tick() at the top of each tick, before the mascot loop.
     * Runs the single shared EnumWindows scan for this tick.
     */
    public static void beginTick( )
    {
        final List<Pointer>   handles   = new ArrayList<>( 64 );
        final List<Rectangle> rects     = new ArrayList<>( 64 );
        final List<IEResult>  viability = new ArrayList<>( 64 );
        final List<Integer>   exStyles  = new ArrayList<>( 64 );

        User32.INSTANCE.EnumWindows( new User32.WNDENUMPROC( )
        {
            @Override
            public boolean callback( Pointer ie, Pointer data )
            {
                if( User32.INSTANCE.IsWindowVisible( ie ) == 0 )
                    return true;

                // getIERectReuse uses static RECT structs — safe here (single thread, non-reentrant)
                Rectangle r = getIERectReuse( ie );
                handles.add( ie );
                rects.add( r );
                viability.add( computeViabilityReuse( ie, r ) );
                exStyles.add( User32.INSTANCE.GetWindowLongW( ie, User32.GWL_EXSTYLE ) );
                return true;
            }
        }, null );

        // Publish — all lists written before sharedHandles (the guard)
        sharedRects     = rects;
        sharedViability = viability;
        sharedExStyles  = exStyles;
        sharedHandles   = handles;
    }

    // ── isIE / viability ─────────────────────────────────────────────────────

    private static boolean isIE( final Pointer ie )
    {
        final Boolean cached = ieCache.get( ie );
        if( Boolean.TRUE.equals( cached ) )
            return true;

        final char[] title = new char[ 1024 ];
        final int titleLength = User32.INSTANCE.GetWindowTextW( ie, title, 1024 );
        final String ieTitle = new String( title, 0, titleLength );

        if( ieTitle.isEmpty( ) || ieTitle.equals( "Program Manager" ) )
            return false;

        boolean blacklistInUse = false;
        if( windowTitlesBlacklist == null )
            windowTitlesBlacklist = Main.getInstance( ).getProperties( )
                .getProperty( "InteractiveWindowsBlacklist", "" ).split( "/" );
        for( String t : windowTitlesBlacklist )
        {
            if( !t.trim( ).isEmpty( ) )
            {
                blacklistInUse = true;
                if( ieTitle.contains( t ) )
                    return false;
            }
        }

        boolean whitelistInUse = false;
        if( windowTitles == null )
            windowTitles = Main.getInstance( ).getProperties( )
                .getProperty( "InteractiveWindows", "" ).split( "/" );
        for( String t : windowTitles )
        {
            if( !t.trim( ).isEmpty( ) )
            {
                whitelistInUse = true;
                if( ieTitle.contains( t ) )
                {
                    ieCache.put( ie, true );
                    return true;
                }
            }
        }

        if( whitelistInUse || !blacklistInUse )
            return false;

        ieCache.put( ie, true );
        return true;
    }

    /** Reuses static structs — manager thread only. rect already computed by caller. */
    private static IEResult computeViabilityReuse( final Pointer ie, final Rectangle r )
    {
        reuseFlags.setValue( 0 );
        NativeLong result = Dwmapi.INSTANCE.DwmGetWindowAttribute(
            ie, Dwmapi.DWMWA_CLOAKED, reuseFlags, 8 );
        if( result.longValue( ) != 0x80070057 &&
            ( result.longValue( ) != 0 || reuseFlags.getValue( ) != 0 ) )
            return IEResult.NOT_IE;

        if( User32.INSTANCE.IsZoomed( ie ) != 0 )
            return IEResult.INVALID;

        if( isIE( ie ) && User32.INSTANCE.IsIconic( ie ) == 0 )
        {
            if( r != null && r.intersects( getScreenRect( ) ) )
                return IEResult.IE;
            return IEResult.IE_OUT_OF_BOUNDS;
        }

        return IEResult.NOT_IE;
    }

    /** Allocating viability check — kept for the restoreAllIEs fallback path only. */
    private static IEResult isViableIE( final Pointer ie )
    {
        if( User32.INSTANCE.IsWindowVisible( ie ) == 0 )
            return IEResult.NOT_IE;

        LongByReference flagsRef = new LongByReference( );
        NativeLong result = Dwmapi.INSTANCE.DwmGetWindowAttribute(
            ie, Dwmapi.DWMWA_CLOAKED, flagsRef, 8 );
        if( result.longValue( ) != 0x80070057 &&
            ( result.longValue( ) != 0 || flagsRef.getValue( ) != 0 ) )
            return IEResult.NOT_IE;

        if( User32.INSTANCE.IsZoomed( ie ) != 0 )
            return IEResult.INVALID;

        if( isIE( ie ) && User32.INSTANCE.IsIconic( ie ) == 0 )
        {
            Rectangle r = getIERect( ie );
            if( r != null && r.intersects( getScreenRect( ) ) )
                return IEResult.IE;
            return IEResult.IE_OUT_OF_BOUNDS;
        }

        return IEResult.NOT_IE;
    }

    // ── Window geometry ───────────────────────────────────────────────────────

    /** Uses static RECT objects — manager thread only. Returns a fresh Rectangle. */
    private static Rectangle getIERectReuse( final Pointer ie )
    {
        User32.INSTANCE.GetWindowRect( ie, reuseOut );
        if( getWindowRgnBox( ie, reuseIn ) == User32.ERROR )
        {
            reuseIn.left   = 0;
            reuseIn.top    = 0;
            reuseIn.right  = reuseOut.right  - reuseOut.left;
            reuseIn.bottom = reuseOut.bottom - reuseOut.top;
        }
        return new Rectangle(
            reuseOut.left + reuseIn.left,
            reuseOut.top  + reuseIn.top,
            reuseIn.Width( ),
            reuseIn.Height( ) );
    }

    /** Allocating version — for one-off calls outside the shared scan. */
    private static Rectangle getIERect( final Pointer ie )
    {
        final RECT out = new RECT( );
        User32.INSTANCE.GetWindowRect( ie, out );
        final RECT in = new RECT( );
        if( getWindowRgnBox( ie, in ) == User32.ERROR )
        {
            in.left   = 0;
            in.top    = 0;
            in.right  = out.right  - out.left;
            in.bottom = out.bottom - out.top;
        }
        return new Rectangle( out.left + in.left, out.top + in.top,
                               in.Width( ), in.Height( ) );
    }

    private static int getWindowRgnBox( final Pointer window, final RECT rect )
    {
        Pointer hRgn = Gdi32.INSTANCE.CreateRectRgn( 0, 0, 0, 0 );
        try
        {
            if( User32.INSTANCE.GetWindowRgn( window, hRgn ) == User32.ERROR )
                return User32.ERROR;
            Gdi32.INSTANCE.GetRgnBox( hRgn, rect );
            return 1;
        }
        finally
        {
            Gdi32.INSTANCE.DeleteObject( hRgn );
        }
    }

    private static boolean moveIE( final Pointer ie, final Rectangle rect )
    {
        if( ie == null ) return false;
        final RECT out = new RECT( );
        User32.INSTANCE.GetWindowRect( ie, out );
        final RECT in = new RECT( );
        if( getWindowRgnBox( ie, in ) == User32.ERROR )
        {
            in.left = 0; in.top = 0;
            in.right  = out.right  - out.left;
            in.bottom = out.bottom - out.top;
        }
        User32.INSTANCE.MoveWindow( ie,
            rect.x - in.left, rect.y - in.top,
            rect.width  + out.Width()  - in.Width(),
            rect.height + out.Height() - in.Height(),
            1 );
        return true;
    }

    private static void restoreAllIEs( )
    {
        User32.INSTANCE.EnumWindows( new User32.WNDENUMPROC( )
        {
            int offset = 25;
            @Override
            public boolean callback( Pointer ie, Pointer data )
            {
                if( isViableIE( ie ) == IEResult.IE_OUT_OF_BOUNDS )
                {
                    final RECT wa   = new RECT( );
                    User32.INSTANCE.SystemParametersInfoW( User32.SPI_GETWORKAREA, 0, wa, 0 );
                    final RECT rect = new RECT( );
                    User32.INSTANCE.GetWindowRect( ie, rect );
                    rect.OffsetRect( wa.left + offset - rect.left,
                                     wa.top  + offset - rect.top );
                    User32.INSTANCE.MoveWindow( ie, rect.left, rect.top,
                                                rect.Width( ), rect.Height( ), 1 );
                    User32.INSTANCE.BringWindowToTop( ie );
                    offset += 25;
                }
                return true;
            }
        }, null );
    }

    // ── Per-instance state ────────────────────────────────────────────────────

    private final Area activeIE  = new Area( );
    private final Area workArea  = new Area( );

    private Pointer activeIEobject = null;
    private boolean activeIELocked = false;

    // Reused across ticks — Area objects recycled, not re-allocated.
    private final List<Area> allIEAreas = new ArrayList<>( );

    // IE_SCAN_INTERVAL = 1: every tick. Affordable now that scan cost is shared.
    private static final int IE_SCAN_INTERVAL = 1;

    private static final java.util.concurrent.atomic.AtomicInteger instanceCounter =
        new java.util.concurrent.atomic.AtomicInteger( 0 );

    private int ticksSinceIEScan = IE_SCAN_INTERVAL +
        ( instanceCounter.getAndIncrement( ) % IE_SCAN_INTERVAL );

    // ── Per-mascot window selection ───────────────────────────────────────────

    /**
     * Reads the shared snapshot to find the nearest/floor IE window for this mascot.
     * No native calls — all data was computed in beginTick().
     */
    private Pointer findNearestIEFromSnapshot( final Point anchor )
    {
        final List<Pointer>   handles   = sharedHandles;
        final List<Rectangle> rects     = sharedRects;
        final List<IEResult>  viability = sharedViability;
        final List<Integer>   exStyles  = sharedExStyles;

        Rectangle mascotScreen = null;
        for( Rectangle sr : screenRects.values( ) )
            if( sr.contains( anchor ) ) { mascotScreen = sr; break; }
        if( mascotScreen == null ) mascotScreen = getScreenRect( );

        Pointer bestFloor        = null;
        int     bestFloorTop     = Integer.MIN_VALUE;
        Pointer bestFallback     = null;
        double  bestFallbackDist = Double.MAX_VALUE;

        final int n = handles.size( );
        for( int i = 0; i < n; i++ )
        {
            if( viability.get( i ) != IEResult.IE ) continue;
            Rectangle r = rects.get( i );
            if( !r.intersects( mascotScreen ) ) continue;

            int     sampleX  = Math.max( r.x, Math.min( anchor.x, r.x + r.width - 1 ) );
            boolean occluded = false;
            for( int j = 0; j < i; j++ )
            {
                if( ( exStyles.get( j ) & User32.WS_EX_LAYERED ) != 0 ) continue;
                if( rects.get( j ).contains( sampleX, r.y ) ) { occluded = true; break; }
            }
            if( occluded ) continue;

            boolean coversX  = anchor.x >= r.x - 4 && anchor.x <= r.x + r.width + 4;
            boolean belowTop = r.y <= anchor.y;
            if( coversX && belowTop )
            {
                if( r.y > bestFloorTop ) { bestFloorTop = r.y; bestFloor = handles.get( i ); }
                continue;
            }

            int dx = Math.max( r.x - anchor.x, Math.max( 0, anchor.x - ( r.x + r.width  ) ) );
            int dy = Math.max( r.y - anchor.y, Math.max( 0, anchor.y - ( r.y + r.height ) ) );
            double dist = Math.sqrt( (double)(dx * dx + dy * dy) );
            if( dist < bestFallbackDist ) { bestFallbackDist = dist; bestFallback = handles.get( i ); }
        }

        // Rebuild allIEAreas, recycling existing Area objects
        int areaIdx = 0;
        for( int i = 0; i < n; i++ )
        {
            if( viability.get( i ) != IEResult.IE ) continue;
            Rectangle r = rects.get( i );
            if( !r.intersects( mascotScreen ) ) continue;

            int     sampleX  = r.x + r.width / 2;
            boolean occluded = false;
            for( int j = 0; j < i; j++ )
            {
                if( ( exStyles.get( j ) & User32.WS_EX_LAYERED ) != 0 ) continue;
                if( rects.get( j ).contains( sampleX, r.y ) ) { occluded = true; break; }
            }
            if( occluded ) continue;

            Area a;
            if( areaIdx < allIEAreas.size( ) ) a = allIEAreas.get( areaIdx );
            else { a = new Area( ); allIEAreas.add( a ); }
            a.set( r );
            a.setVisible( true );
            areaIdx++;
        }
        while( allIEAreas.size( ) > areaIdx )
            allIEAreas.remove( allIEAreas.size( ) - 1 );

        return bestFloor != null ? bestFloor : bestFallback;
    }

    // ── tickForMascot ─────────────────────────────────────────────────────────

    @Override
    public void tickForMascot( final Point mascotAnchor )
    {
        super.tick( );

        if( activeIELocked )
        {
            if( activeIEobject != null )
            {
                Rectangle r = getIERect( activeIEobject );
                if( r != null )
                {
                    activeIE.setVisible( r.intersects( getScreen( ).toRectangle( ) ) );
                    activeIE.set( r );
                }
            }
            return;
        }

        ticksSinceIEScan++;
        if( ticksSinceIEScan >= IE_SCAN_INTERVAL )
        {
            ticksSinceIEScan = 0;
            workArea.set( getWorkAreaRect( mascotAnchor ) );
            // Read from the shared snapshot populated by beginTick() this tick
            activeIEobject = findNearestIEFromSnapshot( mascotAnchor );
        }

        if( activeIEobject == null )
        {
            activeIE.setVisible( false );
            activeIE.set( new Rectangle( -1, -1, 0, 0 ) );
        }
        else
        {
            Rectangle r = getIERect( activeIEobject );
            if( r == null )
            {
                activeIE.setVisible( false );
                activeIE.set( new Rectangle( -1, -1, 0, 0 ) );
            }
            else
            {
                activeIE.setVisible( r.intersects( getScreen( ).toRectangle( ) ) );
                activeIE.set( r );
            }
        }
    }

    // ── Carry lock ────────────────────────────────────────────────────────────

    @Override public void lockActiveIE( )   { activeIELocked = true;  }
    @Override public void unlockActiveIE( ) { activeIELocked = false; }

    // ── Environment overrides ─────────────────────────────────────────────────

    @Override public void dispose( ) { }

    @Override
    public void moveActiveIE( final Point point )
    {
        if( activeIEobject == null ) return;
        final int w = activeIE.getWidth( );
        final int h = activeIE.getHeight( );
        moveIE( activeIEobject, new Rectangle( point.x, point.y, w, h ) );
        activeIE.set( new Rectangle( point.x, point.y, w, h ) );
    }

    @Override public void restoreIE( )     { restoreAllIEs( );  }
    @Override public Area getWorkArea( )   { return workArea;   }
    @Override public Area getActiveIE( )   { return activeIE;   }
    @Override public List<Area> getAllIE( ) { return allIEAreas; }

    @Override
    public String getActiveIETitle( )
    {
        if( activeIEobject == null ) return "";
        final char[] title = new char[ 1024 ];
        final int len = User32.INSTANCE.GetWindowTextW( activeIEobject, title, 1024 );
        return new String( title, 0, len );
    }

    private static Rectangle getWorkAreaRect( final Point mascotPos )
    {
        try
        {
            final com.group_finity.mascot.win.jna.POINT.ByValue pt =
                new com.group_finity.mascot.win.jna.POINT.ByValue( );
            pt.x = mascotPos.x;
            pt.y = mascotPos.y;
            final Pointer hMonitor = User32.INSTANCE.MonitorFromPoint( pt, 2 );
            if( hMonitor != null && !hMonitor.equals( Pointer.NULL ) )
            {
                final com.group_finity.mascot.win.jna.MONITORINFO mi =
                    new com.group_finity.mascot.win.jna.MONITORINFO( );
                mi.cbSize = new NativeLong( mi.size( ) );
                mi.write( );
                if( User32.INSTANCE.GetMonitorInfoW( hMonitor, mi ) )
                {
                    mi.read( );
                    boolean isPrimary = ( mi.dwFlags.intValue( ) & 1 ) != 0;
                    if( isPrimary )
                    {
                        final RECT rect = new RECT( );
                        User32.INSTANCE.SystemParametersInfoW( User32.SPI_GETWORKAREA, 0, rect, 0 );
                        return new Rectangle( rect.left, rect.top,
                                              rect.right - rect.left, rect.bottom - rect.top );
                    }
                    return new Rectangle(
                        mi.rcWork.left, mi.rcWork.top,
                        mi.rcWork.right - mi.rcWork.left, mi.rcWork.bottom - mi.rcWork.top );
                }
            }
        }
        catch( Exception e ) { /* fall through */ }

        final RECT rect = new RECT( );
        User32.INSTANCE.SystemParametersInfoW( User32.SPI_GETWORKAREA, 0, rect, 0 );
        return new Rectangle( rect.left, rect.top,
                               rect.right - rect.left, rect.bottom - rect.top );
    }

    @Override
    public void refreshCache( )
    {
        ieCache.clear( );
        windowTitles          = null;
        windowTitlesBlacklist = null;
    }
}
