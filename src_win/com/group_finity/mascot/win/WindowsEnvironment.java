package com.group_finity.mascot.win;

import com.group_finity.mascot.Main;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

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
 *  1. Shared EnumWindows scan on a background WindowScanner daemon (decoupled from the
 *     tick, June 2026): one scan publishes an immutable snapshot all mascots read, instead
 *     of each running their own scan (12 mascots → 1 EnumWindows). Off-tick, so a slow scan
 *     under load (CPU spike + window churn) can't stall rendering — mascots read ≤~40ms-stale.
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

    // ── Shared scan snapshot ──────────────────────────────────────────────────
    // Decoupled from the tick (June 2026): a background WindowScanner daemon runs the
    // EnumWindows walk on its own cadence and publishes an IMMUTABLE Snapshot via the
    // single volatile below, so a slow scan (CPU spike + window churn) can never delay
    // the animation tick. Mascots read snapshot once into a local on the manager thread —
    // four parallel lists captured atomically, so a mid-tick republish can't tear them.
    private static final class Snapshot
    {
        final List<Pointer>   handles;
        final List<Rectangle> rects;
        final List<IEResult>  viability;
        final List<Integer>   exStyles;
        Snapshot( List<Pointer> h, List<Rectangle> r, List<IEResult> v, List<Integer> e )
        { handles = h; rects = r; viability = v; exStyles = e; }
        static final Snapshot EMPTY = new Snapshot(
            java.util.Collections.<Pointer>emptyList(),   java.util.Collections.<Rectangle>emptyList(),
            java.util.Collections.<IEResult>emptyList(),  java.util.Collections.<Integer>emptyList() );
    }
    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    // Background scanner. Started idempotently from Manager.tick via ensureScanner().
    private static volatile boolean scannerStarted = false;
    private static final long SCAN_INTERVAL_MS = 40;   // ~25Hz; self-paces slower when a scan is slow

    /**
     * Set of monitor rectangles (rcMonitor bounds) that have a fullscreen
     * application covering them this tick. Populated in beginTick().
     * Used by getWorkAreaRect() to return full-screen bounds instead of
     * the work area (which excludes the taskbar) when a fullscreen app is present.
     */
    private static volatile Set<Rectangle> fullscreenMonitors = new HashSet<>( );

    // Reusable native structs — manager thread only (single-threaded tick loop).
    private static final RECT            reuseOut   = new RECT( );
    private static final RECT            reuseIn    = new RECT( );
    private static final LongByReference reuseFlags = new LongByReference( );

    /** Nanoseconds spent in the raw EnumWindows native walk on the last scan.
     *  Read by Manager's tick watchdog (informational now that the scan is off-tick).
     *  Single writer (the scanner thread), benign read elsewhere. */
    public static volatile long lastEnumWindowsNs = 0L;

    /**
     * Idempotent: start the background window-scanner daemon. Called every tick from
     * Manager (cheap volatile check after the first). The first call runs one scan
     * synchronously so the very first tick already has a populated snapshot, then hands
     * off to the daemon. Decouples the EnumWindows walk from the animation tick — a slow
     * scan under load no longer stalls rendering; mascots just read a ≤~40ms-stale snapshot.
     */
    public static void ensureScanner( )
    {
        if( scannerStarted ) return;
        startScanner( );
    }

    private static synchronized void startScanner( )
    {
        if( scannerStarted ) return;
        try { runScan( ); } catch( Throwable t ) { /* best-effort first populate */ }
        final Thread t = new Thread( WindowsEnvironment::scanLoop, "WindowScanner" );
        t.setDaemon( true );
        t.start( );
        scannerStarted = true;
    }

    private static void scanLoop( )
    {
        while( true )
        {
            try { runScan( ); }
            catch( Throwable th ) { log.log( Level.FINE, "window scan error", th ); }
            // Fixed pause between scans → self-pacing: a 200ms scan under load simply
            // yields a lower scan rate that tick, never a stalled tick.
            try { Thread.sleep( SCAN_INTERVAL_MS ); }
            catch( InterruptedException e ) { Thread.currentThread( ).interrupt( ); return; }
        }
    }

    /** The shared EnumWindows scan. Runs only on the scanner thread (and once, synchronously,
     *  on first ensureScanner()), so its reusable native structs stay single-threaded. */
    private static void runScan( )
    {
        final List<Pointer>   handles   = new ArrayList<>( 64 );
        final List<Rectangle> rects     = new ArrayList<>( 64 );
        final List<IEResult>  viability = new ArrayList<>( 64 );
        final List<Integer>   exStyles  = new ArrayList<>( 64 );

        final long enumStartNs = System.nanoTime();
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
        lastEnumWindowsNs = System.nanoTime() - enumStartNs;

        // Detect which monitors have a fullscreen application this tick.
        // A window is fullscreen if it covers the full monitor bounds (rcMonitor)
        // and is not a shell/desktop window.
        final Set<Rectangle> fsMonitors = new HashSet<>( );
        for( int i = 0; i < handles.size( ); i++ )
        {
            final Pointer hwnd = handles.get( i );
            // Skip synthetic sentinels
            if( com.sun.jna.Pointer.createConstant( -1L ).equals( hwnd ) ) continue;
            // Skip minimized windows
            if( User32.INSTANCE.IsIconic( hwnd ) != 0 ) continue;
            // Skip shell/desktop windows that always cover the monitor
            final char[] cls = new char[ 256 ];
            User32.INSTANCE.GetClassNameW( hwnd, cls, 256 );
            final String className = new String( cls ).trim( ).replaceAll( "\0.*", "" );
            if( className.equals( "Progman" )       // desktop
             || className.equals( "WorkerW" )        // desktop wallpaper worker
             || className.equals( "Shell_TrayWnd" )  // taskbar
             || className.equals( "Shell_SecondaryTrayWnd" ) // secondary taskbar
             || className.equals( "DV2ControlHost" ) // start menu
             || className.equals( "Windows.UI.Core.CoreWindow" ) ) // UWP shell chrome
                continue;
            final Rectangle wr = rects.get( i );
            if( wr == null || wr.width <= 0 || wr.height <= 0 ) continue;
            // Must have WS_POPUP style — real fullscreen apps (games, video players)
            // use popup windows. Regular windows with chrome won't match.
            final int style = User32.INSTANCE.GetWindowLongW( hwnd, User32.GWL_STYLE );
            final int WS_POPUP = 0x80000000;
            if( ( style & WS_POPUP ) == 0 ) continue;
            // Find which monitor this window belongs to
            final com.group_finity.mascot.win.jna.POINT.ByValue pt2 =
                new com.group_finity.mascot.win.jna.POINT.ByValue( );
            pt2.x = wr.x + wr.width / 2;
            pt2.y = wr.y + wr.height / 2;
            final Pointer hMon = User32.INSTANCE.MonitorFromPoint( pt2, 2 );
            if( hMon == null || hMon.equals( com.sun.jna.Pointer.NULL ) ) continue;
            final com.group_finity.mascot.win.jna.MONITORINFO mi =
                new com.group_finity.mascot.win.jna.MONITORINFO( );
            mi.cbSize = new NativeLong( mi.size( ) );
            mi.write( );
            if( !User32.INSTANCE.GetMonitorInfoW( hMon, mi ) ) continue;
            mi.read( );
            final Rectangle monRect = new Rectangle(
                mi.rcMonitor.left, mi.rcMonitor.top,
                mi.rcMonitor.right - mi.rcMonitor.left,
                mi.rcMonitor.bottom - mi.rcMonitor.top );
            // Window must cover the full monitor bounds (1px tolerance).
            if( wr.x     <= monRect.x     + 1 &&
                wr.y     <= monRect.y     + 1 &&
                wr.x + wr.width  >= monRect.x + monRect.width  - 1 &&
                wr.y + wr.height >= monRect.y + monRect.height - 1 )
            {
                fsMonitors.add( monRect );
            }
        }
        fullscreenMonitors = fsMonitors;

        // Also treat any browser-reported fullscreen tabs as fullscreen monitors.
        // The extension POSTs the browser window's screen rect to /fullscreen;
        // we find which monitor that rect belongs to and add it to the set.
        com.group_finity.mascot.VideoAreaServer vas2 =
            com.group_finity.mascot.VideoAreaServer.getInstance();
        if( vas2 != null )
        {
            for( java.awt.Rectangle br : vas2.getFullscreenAreas() )
            {
                final com.group_finity.mascot.win.jna.POINT.ByValue bpt =
                    new com.group_finity.mascot.win.jna.POINT.ByValue( );
                bpt.x = br.x + br.width  / 2;
                bpt.y = br.y + br.height / 2;
                final Pointer bMon = User32.INSTANCE.MonitorFromPoint( bpt, 2 );
                if( bMon == null || bMon.equals( Pointer.NULL ) ) continue;
                final com.group_finity.mascot.win.jna.MONITORINFO bmi =
                    new com.group_finity.mascot.win.jna.MONITORINFO( );
                bmi.cbSize = new NativeLong( bmi.size( ) );
                bmi.write( );
                if( !User32.INSTANCE.GetMonitorInfoW( bMon, bmi ) ) continue;
                bmi.read( );
                fsMonitors.add( new Rectangle(
                    bmi.rcMonitor.left, bmi.rcMonitor.top,
                    bmi.rcMonitor.right  - bmi.rcMonitor.left,
                    bmi.rcMonitor.bottom - bmi.rcMonitor.top ) );
            }
            // Re-publish with browser fullscreen additions
            fullscreenMonitors = fsMonitors;
        }
        // They use a null handle (no real HWND) and are marked IEResult.IE so
        // findNearestIEFromSnapshot treats them identically to real windows.
        com.group_finity.mascot.VideoAreaServer vas =
            com.group_finity.mascot.VideoAreaServer.getInstance();
        if( vas != null )
        {
            for( java.awt.Rectangle sr : vas.getSyntheticAreas() )
            {
                handles.add( com.sun.jna.Pointer.createConstant( -1L ) ); // synthetic sentinel
                rects.add( sr );
                viability.add( IEResult.IE );
                exStyles.add( 0 );
            }
        }

        // Publish atomically — one volatile write makes all four lists visible together,
        // so a reader on the manager thread can never see a torn/mismatched set.
        snapshot = new Snapshot( handles, rects, viability, exStyles );
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
    /** Rect of the synthetic IE that won the last nearest-IE selection, or null. */
    private java.awt.Rectangle synthActiveRect = null;
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
        final Snapshot        snap      = snapshot;   // one volatile read — consistent set
        final List<Pointer>   handles   = snap.handles;
        final List<Rectangle> rects     = snap.rects;
        final List<IEResult>  viability = snap.viability;
        final List<Integer>   exStyles  = snap.exStyles;

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
            // Synthetic rects bypass occlusion — they represent content drawn ON TOP
            // of a browser window (e.g. a video player), so they're intentionally
            // inside another window's bounds.
            final boolean isSynthetic = com.sun.jna.Pointer.createConstant(-1L).equals( handles.get(i) );
            boolean occluded = false;
            if( !isSynthetic )
            {
                for( int j = 0; j < i; j++ )
                {
                    if( ( exStyles.get( j ) & User32.WS_EX_LAYERED ) != 0 ) continue;
                    if( rects.get( j ).contains( sampleX, r.y ) ) { occluded = true; break; }
                }
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

        // Append synthetic video areas from browser extension
        com.group_finity.mascot.VideoAreaServer vas =
            com.group_finity.mascot.VideoAreaServer.getInstance();
        if( vas != null )
        {
            for( java.awt.Rectangle r : vas.getSyntheticAreas() )
            {
                Area a = new Area();
                a.set( r );
                a.setVisible( true );
                allIEAreas.add( a );
            }
        }

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
                final com.sun.jna.Pointer SEN = com.sun.jna.Pointer.createConstant( -1L );
                Rectangle r = SEN.equals( activeIEobject ) ? null : getIERect( activeIEobject );
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
            // Check if this is a synthetic sentinel handle
            final com.sun.jna.Pointer SENTINEL = com.sun.jna.Pointer.createConstant( -1L );
            Rectangle r;
            if( SENTINEL.equals( activeIEobject ) )
            {
                // Find the synthetic rect that matches the selected sentinel
                // by finding the nearest one to mascotAnchor (mirrors findNearest logic)
                r = null;
                final Snapshot            snap = snapshot;   // one volatile read — h and rr stay paired
                List<com.sun.jna.Pointer> h    = snap.handles;
                List<Rectangle>           rr   = snap.rects;
                double bestDist = Double.MAX_VALUE;
                for( int i = 0; i < h.size(); i++ ) {
                    if( !SENTINEL.equals( h.get(i) ) ) continue;
                    Rectangle sr = rr.get(i);
                    int dx = Math.max(sr.x - mascotAnchor.x, Math.max(0, mascotAnchor.x - (sr.x+sr.width)));
                    int dy = Math.max(sr.y - mascotAnchor.y, Math.max(0, mascotAnchor.y - (sr.y+sr.height)));
                    double dist = Math.sqrt(dx*dx + dy*dy);
                    if( dist < bestDist ) { bestDist = dist; r = sr; }
                }
            }
            else
            {
                r = getIERect( activeIEobject );
            }
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
        final com.sun.jna.Pointer SENTINEL = com.sun.jna.Pointer.createConstant( -1L );
        if( SENTINEL.equals( activeIEobject ) )
        {
            com.group_finity.mascot.VideoAreaServer vas =
                com.group_finity.mascot.VideoAreaServer.getInstance();
            if( vas != null )
            {
                String site = vas.getSiteIdForRect( activeIE.toRectangle() );
                if( !site.isEmpty() ) return site;
            }
            return "Video Area";
        }
        final char[] title = new char[ 1024 ];
        final int len = User32.INSTANCE.GetWindowTextW( activeIEobject, title, 1024 );
        return new String( title, 0, len );
    }

    @Override
    public String getForegroundWindowTitle( )
    {
        try
        {
            final Pointer hwnd = User32.INSTANCE.GetForegroundWindow( );
            if( hwnd == null ) return "";
            final char[] title = new char[ 1024 ];
            final int len = User32.INSTANCE.GetWindowTextW( hwnd, title, 1024 );
            return new String( title, 0, len );
        }
        catch( final Exception e ) { return ""; }
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
                    final Rectangle monRect = new Rectangle(
                        mi.rcMonitor.left, mi.rcMonitor.top,
                        mi.rcMonitor.right  - mi.rcMonitor.left,
                        mi.rcMonitor.bottom - mi.rcMonitor.top );

                    // If a fullscreen app is covering this monitor, treat the
                    // full monitor bounds as the work area so the taskbar is
                    // ignored and the floor sits at the real bottom of the screen.
                    if( fullscreenMonitors.contains( monRect ) )
                        return monRect;

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
