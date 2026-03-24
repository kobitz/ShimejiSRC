package com.group_finity.mascot.action;

import java.awt.Point;
import java.util.List;
import java.util.logging.Logger;

import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 *
 * LiveTarget parameter: when set to "cursor" or "ie", the jump target is
 * re-sampled from the environment every tick instead of being frozen at
 * action init time. This allows the mascot to home in on a moving cursor
 * or active window mid-air.
 *
 * Valid values for LiveTarget:
 *   "cursor"  - tracks the mouse cursor
 *   "ie"      - tracks the centre of the active window
 *   (absent)  - original behaviour: target locked at init
 */
public class Jump extends ActionBase
{
    private static final Logger log = Logger.getLogger( Jump.class.getName( ) );

    public static final String PARAMETER_TARGETX  = "TargetX";
    private static final int   DEFAULT_TARGETX    = 0;

    public static final String PARAMETER_TARGETY  = "TargetY";
    private static final int   DEFAULT_TARGETY    = 0;

    // A Pose Attribute is already named Velocity
    public static final String PARAMETER_VELOCITY = "VelocityParam";
    private static final double DEFAULT_VELOCITY  = 20.0;

    /**
     * When set, overrides TargetX/TargetY with a live environment source
     * every tick.  Accepted values (case-insensitive): "cursor", "ie".
     */
    public static final String PARAMETER_LIVE_TARGET = "LiveTarget";
    private static final String DEFAULT_LIVE_TARGET  = "";

    public static final String VARIABLE_VELOCITYX = "VelocityX";
    public static final String VARIABLE_VELOCITYY = "VelocityY";

    // Resolved once in init() so we don't parse the string every tick
    private enum LiveMode { NONE, CURSOR, IE }
    private LiveMode liveMode = LiveMode.NONE;

    public Jump( java.util.ResourceBundle schema, final List<Animation> animations, final VariableMap context )
    {
        super( schema, animations, context );
    }

    @Override
    public void init( final com.group_finity.mascot.Mascot mascot ) throws VariableException
    {
        super.init( mascot );
        String raw = eval( getSchema( ).getString( PARAMETER_LIVE_TARGET ), String.class, DEFAULT_LIVE_TARGET ).trim( ).toLowerCase( );
        switch( raw )
        {
            case "cursor": liveMode = LiveMode.CURSOR; break;
            case "ie":     liveMode = LiveMode.IE;     break;
            default:       liveMode = LiveMode.NONE;   break;
        }
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        final int targetX = getTargetX( );
        final int targetY = getTargetY( );

        final double distanceX = targetX - getMascot( ).getAnchor( ).x;
        final double distanceY = targetY - getMascot( ).getAnchor( ).y - Math.abs( distanceX ) / 2;

        final double distance = Math.sqrt( distanceX * distanceX + distanceY * distanceY );

        return super.hasNext( ) && ( distance != 0 );
    }

    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        final int targetX = getTargetX( );
        final int targetY = getTargetY( );

        getMascot( ).setLookRight( getMascot( ).getAnchor( ).x < targetX );

        final double distanceX = targetX - getMascot( ).getAnchor( ).x;
        final double distanceY = targetY - getMascot( ).getAnchor( ).y - Math.abs( distanceX ) / 2;

        final double distance = Math.sqrt( distanceX * distanceX + distanceY * distanceY );

        final double velocity = getVelocity( );

        if( distance != 0 )
        {
            final int velocityX = (int)( velocity * distanceX / distance );
            final int velocityY = (int)( velocity * distanceY / distance );

            putVariable( getSchema( ).getString( VARIABLE_VELOCITYX ), velocity * distanceX / distance );
            putVariable( getSchema( ).getString( VARIABLE_VELOCITYY ), velocity * distanceY / distance );

            getMascot( ).setAnchor( new Point( getMascot( ).getAnchor( ).x + velocityX,
                                               getMascot( ).getAnchor( ).y + velocityY ) );
            getAnimation( ).next( getMascot( ), getTime( ) );
        }

        if( distance <= velocity )
        {
            getMascot( ).setAnchor( new Point( targetX, targetY ) );
        }
    }

    private double getVelocity( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_VELOCITY ), Number.class, DEFAULT_VELOCITY ).doubleValue( );
    }

    /**
     * Returns the current X target. When liveMode is active this reads
     * directly from the environment each call, tracking movement across
     * every tick rather than using the value frozen at init.
     */
    private int getTargetX( ) throws VariableException
    {
        switch( liveMode )
        {
            case CURSOR: return getEnvironment( ).getCursor( ).getX( );
            case IE:     return getEnvironment( ).getActiveIE( ).toRectangle( ).x
                              + getEnvironment( ).getActiveIE( ).toRectangle( ).width / 2;
            default:     return eval( getSchema( ).getString( PARAMETER_TARGETX ), Number.class, DEFAULT_TARGETX ).intValue( );
        }
    }

    /**
     * Returns the current Y target. Same live-sampling logic as getTargetX.
     */
    private int getTargetY( ) throws VariableException
    {
        switch( liveMode )
        {
            case CURSOR: return getEnvironment( ).getCursor( ).getY( );
            case IE:     return getEnvironment( ).getActiveIE( ).toRectangle( ).y
                              + getEnvironment( ).getActiveIE( ).toRectangle( ).height / 2;
            default:     return eval( getSchema( ).getString( PARAMETER_TARGETY ), Number.class, DEFAULT_TARGETY ).intValue( );
        }
    }
}
