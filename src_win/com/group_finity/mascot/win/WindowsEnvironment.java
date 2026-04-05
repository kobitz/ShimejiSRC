package com.group_finity.mascot.win;

import com.group_finity.mascot.Main;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Modified for per-mascot independent window tracking:
 *  - Each mascot gets its own WindowsEnvironment instance (via NativeFactoryImpl)
 *  - tickForMascot() finds the nearest visible, unoccluded interactive window
 *  - A carry-lock prevents re-scanning while a mascot is carrying a window
 */
class WindowsEnvironment extends Environment
{
    // ── Shared (static) state ────────────────────────────────────────────────

    // isIE() result cache, keyed by HWND. Only TRUE results are cached permanently;
    // negatives are re-evaluated every time so newly-created windows aren't missed.
    private static final HashMap<Pointer, Boolean> ieCache = new LinkedHashMap<>();

    private static String[]  windowTitles          = null;
    private static String[]  windowTitlesBlacklist = null;

    private static final Logger log = Logger.getLogger( Environment.class.getName( ) );

    private enum IEResult { INVALID, NOT_IE, IE_OUT_OF_BOUNDS, IE }

    // ── Per-instance state (one per mascot) ──────────────────────────────────

    // The Area exposed to the rest of the engine via getActiveIE()
    private final Area activeIE = new Area();
    private final Area workArea = new Area();

    // The actual HWND of the window activeIE describes
    private Pointer activeIEobject = null;

    // Carry lock: set by WalkWithIE/FallWithIE/ThrowIE while they hold a window.
    // While locked, tickForMascot() skips the nearest-window search.
    private boolean activeIELocked = false;

    // Throttle: only re-run the expensive EnumWindows scan every N ticks.
    // Between scans the previous result is reused. Window positions change
    // slowly enough (~320ms between scans at 40ms/tick) that this is invisible.
    private static final int IE_SCAN_INTERVAL = 4;
    // Counter cycles atomically so each new instance starts at a different
    // offset, spreading scans across ticks rather than all firing together.
    private static final java.util.concurrent.atomic.AtomicInteger instanceCounter =
        new java.util.concurrent.atomic.AtomicInteger( 0 );
    // Start at IE_SCAN_INTERVAL so the very first tick always runs a full scan
    // (workArea and activeIE must be populated before anything reads them).
    // The modulo offset staggers multiple mascots so they don't all scan on tick 0.
    private int ticksSinceIEScan = IE_SCAN_INTERVAL +
        ( instanceCounter.getAndIncrement( ) % IE_SCAN_INTERVAL );

    // ── isIE / isViableIE ────────────────────────────────────────────────────

    private static boolean isIE( final Pointer ie )
    {
        // Only cache TRUE results. FALSE is re-evaluated each call so windows
        // that weren't ready on the first check (e.g. ToyBox) get a fair shot.
        final Boolean cached = ieCache.get( ie );
        if( Boolean.TRUE.equals( cached ) )
            return true;

        final char[] title = new char[ 1024 ];
        final int titleLength = User32.INSTANCE.GetWindowTextW( ie, title, 1024 );
        final String ieTitle = new String( title, 0, titleLength );

        if( ieTitle.isEmpty( ) || ieTitle.equals( "Program Manager" ) )
            return false;

        // Blacklist takes precedence
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

        // Whitelist
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

    private static IEResult isViableIE( final Pointer ie )
    {
        if( User32.INSTANCE.IsWindowVisible( ie ) == 0 )
            return IEResult.NOT_IE;

        // Skip cloaked windows (metro apps that are technically "visible" but hidden)
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

    // ── Window geometry helpers ───────────────────────────────────────────────

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
            in.left   = 0;
            in.top    = 0;
            in.right  = out.right  - out.left;
            in.bottom = out.bottom - out.top;
        }
        User32.INSTANCE.MoveWindow( ie,
            rect.x - in.left,
            rect.y - in.top,
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

    // ── Per-mascot window detection ───────────────────────────────────────────

    /**
     * Find the interactive window whose top surface is the best floor for the
     * mascot's anchor point, considering z-order occlusion.
     *
     * A single EnumWindows pass collects all handles + rects in z-order.
     *
     * Selection priority:
     *   1. FLOOR candidate: window whose top edge is at or below the anchor Y,
     *      AND whose X range covers the anchor X (i.e. the mascot is standing
     *      on it). Among these, prefer the one with the highest top edge
     *      (smallest top Y ≤ anchor.y) — that's the actual surface underfoot.
     *      Horizontally adjacent windows (e.g. a box the mascot walks into)
     *      are excluded because their X range doesn't cover anchor.x.
     *   2. FALLBACK: if no floor candidate exists (mascot is mid-air or near
     *      a window edge), fall back to the Euclidean-nearest viable window,
     *      same as the original behaviour.
     *
     * WS_EX_LAYERED windows (transparent overlays like shimeji itself) are
     * skipped in the occlusion check because they don't actually block anything.
     */
    private Pointer findNearestIE( final Point anchor )
    {
        final List<Pointer>   handles = new ArrayList<>();
        final List<Rectangle> rects   = new ArrayList<>();

        User32.INSTANCE.EnumWindows( new User32.WNDENUMPROC( )
        {
            @Override
            public boolean callback( Pointer ie, Pointer data )
            {
                if( User32.INSTANCE.IsWindowVisible( ie ) != 0 )
                {
                    Rectangle r = getIERect( ie );
                    handles.add( ie );
                    rects.add( r != null ? r : new Rectangle( 0, 0, 0, 0 ) );
                }
                return true;
            }
        }, null );

        // Determine which screen the mascot is currently on.
        // Windows on other screens are not reachable and should be ignored.
        Rectangle mascotScreen = null;
        for( Rectangle sr : screenRects.values( ) )
        {
            if( sr.contains( anchor ) )
            {
                mascotScreen = sr;
                break;
            }
        }
        // Fallback: use primary screen rect if anchor isn't inside any screen
        // (e.g. dragged to a gap between monitors)
        if( mascotScreen == null )
            mascotScreen = getScreenRect( );

        // Pass 1: find the best floor surface — a window whose top edge is the
        // highest surface at or below the anchor, covering anchor.x.
        // This correctly ignores adjacent windows (boxes being walked into)
        // whose X range does not include the mascot's foot position.
        Pointer bestFloor    = null;
        int     bestFloorTop = Integer.MIN_VALUE; // highest top = largest Y ≤ anchor.y

        // Pass 2 fallback: Euclidean nearest (original behaviour, used when
        // no floor candidate exists — e.g. mascot mid-air near a window side).
        Pointer bestFallback     = null;
        double  bestFallbackDist = Double.MAX_VALUE;

        for( int i = 0; i < handles.size(); i++ )
        {
            Pointer ie = handles.get( i );
            if( isViableIE( ie ) != IEResult.IE ) continue;

            Rectangle r = rects.get( i );

            // Skip windows that are not on the same screen as the mascot
            if( !r.intersects( mascotScreen ) ) continue;

            // Occlusion check: is any opaque window higher in z-order covering
            // the sample point (anchor.x clamped to window) at this window's top?
            int sampleX = Math.max( r.x, Math.min( anchor.x, r.x + r.width - 1 ) );
            boolean occluded = false;
            for( int j = 0; j < i; j++ )
            {
                // Skip WS_EX_LAYERED windows — they are transparent overlays
                int exStyle = User32.INSTANCE.GetWindowLongW(
                    handles.get( j ), User32.GWL_EXSTYLE );
                if( ( exStyle & User32.WS_EX_LAYERED ) != 0 ) continue;
                if( rects.get( j ).contains( sampleX, r.y ) )
                {
                    occluded = true;
                    break;
                }
            }
            if( occluded ) continue;

            // ── Floor candidate check ─────────────────────────────────────
            // The window's top edge must be at or below the anchor Y, and the
            // window's X range must cover the anchor X (mascot is above it).
            // A small margin (4px) handles sub-pixel anchor placement at edges.
            boolean coversX  = anchor.x >= r.x - 4 && anchor.x <= r.x + r.width + 4;
            boolean belowTop = r.y <= anchor.y;
            if( coversX && belowTop )
            {
                // Among all floor candidates, prefer the highest top edge
                // (i.e. the surface the mascot is most immediately standing on).
                if( r.y > bestFloorTop )
                {
                    bestFloorTop = r.y;
                    bestFloor    = ie;
                }
                continue; // don't also consider this window as a fallback
            }

            // ── Fallback: Euclidean nearest ───────────────────────────────
            int dx = Math.max( r.x - anchor.x,
                     Math.max( 0, anchor.x - ( r.x + r.width  ) ) );
            int dy = Math.max( r.y - anchor.y,
                     Math.max( 0, anchor.y - ( r.y + r.height ) ) );
            double dist = Math.sqrt( (double)(dx * dx + dy * dy) );

            if( dist < bestFallbackDist )
            {
                bestFallbackDist = dist;
                bestFallback     = ie;
            }
        }

        // Floor candidate wins if found; otherwise fall back to Euclidean nearest.
        return bestFloor != null ? bestFloor : bestFallback;
    }

    // ── tickForMascot ─────────────────────────────────────────────────────────

    @Override
    public void tickForMascot( final Point mascotAnchor )
    {
        super.tick();

        if( activeIELocked )
        {
            // While carrying: just refresh activeIE from the real window position
            if( activeIEobject != null )
            {
                Rectangle r = getIERect( activeIEobject );
                if( r != null )
                {
                    activeIE.setVisible( r.intersects( getScreen().toRectangle() ) );
                    activeIE.set( r );
                }
            }
            return;
        }

        // Only run the expensive EnumWindows scan every IE_SCAN_INTERVAL ticks.
        // On off-ticks, just refresh the known window's position cheaply.
        ticksSinceIEScan++;
        if( ticksSinceIEScan >= IE_SCAN_INTERVAL )
        {
            ticksSinceIEScan = 0;
            workArea.set( getWorkAreaRect( mascotAnchor ) );
            activeIEobject = findNearestIE( mascotAnchor );
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
                activeIE.setVisible( r.intersects( getScreen().toRectangle() ) );
                activeIE.set( r );
            }
        }
    }

    // ── Carry lock ────────────────────────────────────────────────────────────

    @Override
    public void lockActiveIE( )
    {
        activeIELocked = true;
    }

    @Override
    public void unlockActiveIE( )
    {
        activeIELocked = false;
    }

    // ── Environment overrides ─────────────────────────────────────────────────

    @Override
    public void dispose( ) { }

    @Override
    public void moveActiveIE( final Point point )
    {
        if( activeIEobject == null ) return;
        final int w = activeIE.getWidth();
        final int h = activeIE.getHeight();
        moveIE( activeIEobject, new Rectangle( point.x, point.y, w, h ) );
        // Keep activeIE in sync immediately so carry actions see the new position
        activeIE.set( new Rectangle( point.x, point.y, w, h ) );
    }

    @Override
    public void restoreIE( )
    {
        restoreAllIEs();
    }

    @Override
    public Area getWorkArea( )
    {
        return workArea;
    }

    @Override
    public Area getActiveIE( )
    {
        return activeIE;
    }

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
                        // Always use SPI_GETWORKAREA for primary — proven reliable
                        final RECT rect = new RECT( );
                        User32.INSTANCE.SystemParametersInfoW( User32.SPI_GETWORKAREA, 0, rect, 0 );
                        return new Rectangle( rect.left, rect.top,
                                              rect.right - rect.left, rect.bottom - rect.top );
                    }
                    // Secondary monitor — use GetMonitorInfo rcWork
                    return new Rectangle(
                        mi.rcWork.left,
                        mi.rcWork.top,
                        mi.rcWork.right  - mi.rcWork.left,
                        mi.rcWork.bottom - mi.rcWork.top );
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
