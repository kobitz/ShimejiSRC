package com.group_finity.mascot.environment;

import com.group_finity.mascot.Main;
import java.awt.Point;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.NativeFactory;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */

public class MascotEnvironment
{
    private final Environment impl;

    private final Mascot mascot;

    private Area currentWorkArea;


    public MascotEnvironment( Mascot mascot )
    {
        this.mascot = mascot;
        impl = NativeFactory.getInstance( ).getEnvironment( );
        impl.init( );
    }

    public void tick( )
    {
        if( mascot.isScreenLoop( ) && !isMultiscreen( ) && !mascot.isDragging( ) && currentWorkArea != null
                && mascot.getUserData( "screenLoopTeleportedRight" ) == null )
        {
            // PRE-TICK outer-edge check: fires BEFORE tickForMascot, catching outer-edge
            // crossings one tick earlier so the mascot doesn't visually leave the taskbar.
            // Only applies to true outer edges — the inter-screen barrier is handled by
            // the post-tick workarea-switch detection below.
            // Skip if a teleport already fired this tick (flag already set) to prevent ping-pong.
            final int MARGIN = 4;
            final java.awt.Rectangle wa = currentWorkArea.toRectangle( );
            final java.awt.Point anchor = mascot.getAnchor( );

            final boolean rightIsOuter = !hasAdjacentScreenOnRight( wa );
            final boolean leftIsOuter  = !hasAdjacentScreenOnLeft( wa );

            // Destination is MARGIN+1 px inward so landing there won't re-trigger.
            final boolean crossedRight = rightIsOuter && anchor.x >= wa.x + wa.width - MARGIN;
            final boolean crossedLeft  = leftIsOuter  && anchor.x <= wa.x + MARGIN;

            if( crossedRight || crossedLeft )
            {
                final boolean wentRight = crossedRight;
                final int teleportX = wentRight
                    ? wa.x + MARGIN + 1
                    : wa.x + wa.width - MARGIN - 1;
                mascot.setAnchor( new java.awt.Point( teleportX, anchor.y ) );
                mascot.setUserData( "screenLoopTeleportedRight", wentRight );
            }
        }

                // Snapshot workarea BEFORE tickForMascot so we can detect a monitor switch.
        final java.awt.Rectangle prevWorkRect =
            ( currentWorkArea != null ) ? currentWorkArea.toRectangle( ) : null;

        impl.tickForMascot( mascot.getAnchor( ) );

        if( currentWorkArea == null || currentWorkArea == impl.getWorkArea( ) )
            currentWorkArea = new Area( );
        currentWorkArea.set( impl.getWorkArea( ).toRectangle( ) );

        // POST-TICK: if tickForMascot moved the workarea to a different monitor,
        // the mascot crossed the inter-screen barrier — teleport to opposite side.
        if( mascot.isScreenLoop( ) && !isMultiscreen( ) && !mascot.isDragging( ) && prevWorkRect != null )
        {
            final java.awt.Rectangle newWorkRect = currentWorkArea.toRectangle( );
            if( !newWorkRect.equals( prevWorkRect ) )
            {
                final java.awt.Point anchor = mascot.getAnchor( );
                final boolean wentRight = anchor.x >= prevWorkRect.x + prevWorkRect.width;
                final int MARGIN = 4;
                // Land MARGIN+1 px inward so the pre-tick check (threshold = MARGIN)
                // doesn't immediately re-trigger on the next frame.
                final int teleportX = wentRight
                    ? prevWorkRect.x + MARGIN + 1
                    : prevWorkRect.x + prevWorkRect.width - MARGIN - 1;
                mascot.setAnchor( new java.awt.Point( teleportX, anchor.y ) );
                currentWorkArea.set( prevWorkRect );
                if( mascot.getUserData( "screenLoopTeleportedRight" ) == null )
                    mascot.setUserData( "screenLoopTeleportedRight", wentRight );
            }
        }
    }

    public void lockActiveIE( )
    {
        impl.lockActiveIE( );
    }

    public void unlockActiveIE( )
    {
        impl.unlockActiveIE( );
    }

    public Area getWorkArea( )
    {
        // currentWorkArea is snapshotted each tick from the correct per-monitor
        // work area. Fall back to impl only if not yet initialized.
        if( currentWorkArea != null )
            return currentWorkArea;
        return impl.getWorkArea( );
    }

    public Area getActiveIE( )
    {
        Area activeIE = impl.getActiveIE( );
        
        if( currentWorkArea != null && !isMultiscreen( ) && !currentWorkArea.toRectangle( ).intersects( activeIE.toRectangle( ) ) )
            return new Area( );
        
        return activeIE;
    }

    /** All viable window rects on this mascot's screen.
     *  Accessible from scripts as: mascot.environment.allIE
     *  Returns a List&lt;Area&gt; — each Area has .left, .right, .top, .bottom, .width, .height. */
    public java.util.List<Area> getAllIE( )
    {
        return impl.getAllIE( );
    }
    
    public String getActiveIETitle( )
    {
        return impl.getActiveIETitle( );
    }

    public Border getCeiling( )
    {
        return getCeiling( isMultiscreen( ) );
    }

    public Border getCeiling( boolean ignoreSeparator )
    {
        if( getActiveIE( ).getBottomBorder( ).isOn( mascot.getAnchor( ) ) )
        {
            return getActiveIE( ).getBottomBorder( );
        }
        if( getWorkArea( ).getTopBorder( ).isOn( mascot.getAnchor( ) ) )
        {
            if ( !ignoreSeparator || isScreenTopBottom( ) )
            {
                    return getWorkArea( ).getTopBorder( );
            }
        }
        return NotOnBorder.INSTANCE;
    }

    public ComplexArea getComplexScreen( )
    {
        return impl.getComplexScreen( );
    }

    public Location getCursor( )
    {
        return impl.getCursor( );
    }

    public Border getFloor( )
    {
        return getFloor( isMultiscreen( ) );
    }

    public Border getFloor( boolean ignoreSeparator )
    {
        if( getActiveIE( ).getTopBorder( ).isOn( mascot.getAnchor( ) ) )
        {
            return getActiveIE( ).getTopBorder( );
        }
        if( mascot.isFloorEnabled( ) && getWorkArea( ).getBottomBorder( ).isOn( mascot.getAnchor( ) ) )
        {
            if( !ignoreSeparator || isScreenTopBottom( ) )
            {
                return getWorkArea( ).getBottomBorder( );
            }
        }
        return NotOnBorder.INSTANCE;
    }

    public Area getScreen( )
    {
        return impl.getScreen( );
    }

    public Border getWall( )
    {
        return getWall( isMultiscreen( ) );
    }

    public Border getWall( boolean ignoreSeparator )
    {
        // Screen loop: ALL workarea walls (outer edges and inter-screen barriers alike)
        // are invisible — the mascot teleports to the opposite side of its current screen.
        // Window borders always act normally.
        final boolean screenLoop = mascot.isScreenLoop( );

        if( mascot.isLookRight( ) )
        {
            if( getActiveIE( ).getLeftBorder( ).isOn( mascot.getAnchor( ) ) )
            {
                return getActiveIE( ).getLeftBorder( );
            }

            if( getWorkArea( ).getRightBorder( ).isOn( mascot.getAnchor( ) ) )
            {
                if( screenLoop )
                {
                    // Transparent — teleport fires via out-of-bounds check in UserBehavior.
                }
                else if( !ignoreSeparator || isScreenLeftRight( ) )
                {
                    return getWorkArea( ).getRightBorder( );
                }
            }
        }
        else
        {
            if( getActiveIE( ).getRightBorder( ).isOn( mascot.getAnchor( ) ) )
            {
                return getActiveIE( ).getRightBorder( );
            }

            if( getWorkArea( ).getLeftBorder( ).isOn( mascot.getAnchor( ) ) )
            {
                if( screenLoop )
                {
                    // Transparent — teleport fires via out-of-bounds check in UserBehavior.
                }
                else if( !ignoreSeparator || isScreenLeftRight( ) )
                {
                    return getWorkArea( ).getLeftBorder( );
                }
            }
        }

        return NotOnBorder.INSTANCE;
    }

    /**
     * Returns the X coordinate to teleport to when screen loop wraps the mascot.
     * If isMultiscreen( ) is on, teleports to the far edge of the entire virtual desktop.
     * Otherwise teleports to the opposite side of the current workarea.
     *
     * @param hitRight true if the mascot exited through the right border, false for left.
     */
    public int getScreenLoopTeleportX( boolean hitRight )
    {
        if( isMultiscreen( ) )
        {
            // Compute bounding box of all screens to get the full virtual desktop span.
            java.util.Collection<Area> screens = impl.getScreens( );
            int minLeft = Integer.MAX_VALUE;
            int maxRight = Integer.MIN_VALUE;
            for( Area s : screens )
            {
                if( s.getLeft( ) < minLeft )   minLeft  = s.getLeft( );
                if( s.getRight( ) > maxRight ) maxRight = s.getRight( );
            }
            if( minLeft == Integer.MAX_VALUE )
            {
                // Fallback: use workarea
                Area wa = getWorkArea( );
                return hitRight ? wa.getLeft( ) : wa.getRight( );
            }
            return hitRight ? minLeft : maxRight;
        }
        else
        {
            // Use the workarea for the teleport destination. getWorkArea() is snapshotted
            // at tick start from the mascot's home screen, so it still reflects the correct
            // monitor even after the anchor has drifted across the barrier.
            // Nudge one pixel inward from the workarea edge so the mascot doesn't land
            // exactly on the border pixel and get claimed by the adjacent screen.
            Area wa = getWorkArea( );
            return hitRight ? wa.getLeft( ) + 1 : wa.getRight( ) - 1;
        }
    }

    public void moveActiveIE( Point point )
    {
        impl.moveActiveIE( point );
    }

    public void restoreIE( )
    {
        impl.restoreIE( );
    }

    /**
     * System sensor readings. All values return -1 if unavailable. No admin required.
     *
     * Usage in XML conditions:
     *   mascot.environment.cpuTemp      - CPU temperature deg C via TempSensor.exe (requires admin; -1 if absent or no UAC)
     *   mascot.environment.cpuLoad      - CPU total load % via OperatingSystemMXBean
     *   mascot.environment.gpuTemp      - GPU temperature deg C via nvidia-smi (NVIDIA only)
     *   mascot.environment.gpuLoad      - GPU load % via nvidia-smi (NVIDIA only)
     *   mascot.environment.ramLoad      - RAM usage % via OperatingSystemMXBean
     *   mascot.environment.batteryLevel - Battery % via Win32 GetSystemPowerStatus
     */
    public double getCpuTemp( )      { return CpuTempMonitor.getInstance( ).getCpuTemp( );      }
    public double getCpuLoad( )      { return CpuTempMonitor.getInstance( ).getCpuLoad( );      }
    public double getGpuTemp( )      { return CpuTempMonitor.getInstance( ).getGpuTemp( );      }
    public double getGpuLoad( )      { return CpuTempMonitor.getInstance( ).getGpuLoad( );      }
    public double getBatteryLevel( ) { return CpuTempMonitor.getInstance( ).getBatteryLevel( ); }

    public double getRamLoad( )
    {
        try
        {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean( );
            long total = os.getTotalMemorySize( );
            long free  = os.getFreeMemorySize( );
            if( total <= 0 ) return -1;
            return ( total - free ) * 100.0 / total;
        }
        catch( Exception e )
        {
            return -1;
        }
    }

    /**
     * Real-time system audio RMS level (~0-32767 range).
     * Updated every ~40ms by WasapiLoopbackCapture. Returns 0 if not running.
     *
     * Usage in XML conditions:
     *   mascot.environment.audioLevel   - current speaker output energy
     */
    public int getAudioLevel( )
    {
        return com.group_finity.mascot.assistant.AudioTranscriptBuffer.currentSysRms;
    }

    /** Returns true if another screen begins exactly at the right edge of the given workarea. */
    private boolean hasAdjacentScreenOnRight( java.awt.Rectangle wa )
    {
        int rightEdge = wa.x + wa.width;
        for( Area s : impl.getScreens( ) )
        {
            java.awt.Rectangle r = s.toRectangle( );
            if( r.x == rightEdge ) return true;
        }
        return false;
    }

    /** Returns true if another screen ends exactly at the left edge of the given workarea. */
    private boolean hasAdjacentScreenOnLeft( java.awt.Rectangle wa )
    {
        int leftEdge = wa.x;
        for( Area s : impl.getScreens( ) )
        {
            java.awt.Rectangle r = s.toRectangle( );
            if( r.x + r.width == leftEdge ) return true;
        }
        return false;
    }

    /** Reads the per-imageset Multiscreen override, falling back to the global setting. */
    private boolean isMultiscreen( )
    {
        final java.util.Properties props = Main.getInstance( ).getProperties( );
        final String imageSet = mascot.getImageSet( );
        final String perSet = props.getProperty( "Multiscreen.imageset." + imageSet );
        if( perSet != null )
            return Boolean.parseBoolean( perSet );
        return Boolean.parseBoolean( props.getProperty( "Multiscreen", "true" ) );
    }

    private boolean isScreenTopBottom( )
    {
        return impl.isScreenTopBottom( mascot.getAnchor( ) );
    }

    private boolean isScreenLeftRight( )
    {
        return impl.isScreenLeftRight( mascot.getAnchor( ) );
    }
}
