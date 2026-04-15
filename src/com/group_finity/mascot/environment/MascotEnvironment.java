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

    private final boolean multiscreen;

    public MascotEnvironment( Mascot mascot )
    {
        this.mascot = mascot;
        impl = NativeFactory.getInstance( ).getEnvironment( );
        impl.init( );
        multiscreen = Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "Multiscreen", "true" ) );
    }

    public void tick( )
    {
        impl.tickForMascot( mascot.getAnchor( ) );
        if( currentWorkArea == null || currentWorkArea == impl.getWorkArea( ) )
            currentWorkArea = new Area( );
        currentWorkArea.set( impl.getWorkArea( ).toRectangle( ) );
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
        return getWorkArea( false );
    }

    public Area getWorkArea( Boolean ignoreSettings )
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
        
        if( currentWorkArea != null && !multiscreen && !currentWorkArea.toRectangle( ).intersects( activeIE.toRectangle( ) ) )
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
        return getCeiling( false );
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
        return getFloor( false );
    }

    public Border getFloor( boolean ignoreSeparator )
    {
        if( getActiveIE( ).getTopBorder( ).isOn( mascot.getAnchor( ) ) )
        {
            return getActiveIE( ).getTopBorder( );
        }
        if( getWorkArea( ).getBottomBorder( ).isOn( mascot.getAnchor( ) ) )
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
        return getWall( false );
    }

    public Border getWall( boolean ignoreSeparator )
    {
        if( mascot.isLookRight( ) )
        {
            if( getActiveIE( ).getLeftBorder( ).isOn( mascot.getAnchor( ) ) )
            {
                return getActiveIE( ).getLeftBorder( );
            }

            if( getWorkArea( ).getRightBorder( ).isOn( mascot.getAnchor( ) ) )
            {
                if( !ignoreSeparator || isScreenLeftRight( ) )
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
                if( !ignoreSeparator || isScreenLeftRight( ) )
                {
                    return getWorkArea( ).getLeftBorder( );
                }
            }
        }

        return NotOnBorder.INSTANCE;
    }

    public void moveActiveIE( Point point )
    {
        impl.moveActiveIE( point );
    }

    public void restoreIE( )
    {
        impl.restoreIE( );
    }
    
    public void refreshWorkArea( )
    {
        getWorkArea( true );
    }

    /**
     * System sensor readings via LibreHardwareMonitor web server.
     * LHM must be running with Remote Web Server enabled (port 8085).
     * All values return -1 if unavailable.
     *
     * Usage in XML conditions:
     *   mascot.environment.cpuTemp      - CPU Core Average (°C)
     *   mascot.environment.cpuLoad      - CPU Total load (%)
     *   mascot.environment.gpuTemp      - GPU Core temperature (°C)
     *   mascot.environment.gpuLoad      - GPU Core load (%)
     *   mascot.environment.ramLoad      - Total RAM usage (%)
     *   mascot.environment.batteryLevel - Battery charge level (%)
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

    private boolean isScreenTopBottom( )
    {
        return impl.isScreenTopBottom( mascot.getAnchor( ) );
    }

    private boolean isScreenLeftRight( )
    {
        return impl.isScreenLeftRight( mascot.getAnchor( ) );
    }
}
