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
    private enum LiveMode { NONE, CURSOR, IE, HUNT }
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
            case "hunt":   liveMode = LiveMode.HUNT;   break;
            default:       liveMode = LiveMode.NONE;   break;
        }
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        final int targetX = getTargetX( );
        final int targetY = getTargetY( );

        getMascot( ).setUserData( "jumpCurrentTargetX", targetX );
        getMascot( ).setUserData( "jumpCurrentTargetY", targetY );

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

        // Cache current targets so XML/scripts can read them back (e.g. for mid-air steering)
        getMascot( ).setUserData( "jumpCurrentTargetX", targetX );
        getMascot( ).setUserData( "jumpCurrentTargetY", targetY );

        getMascot( ).setLookRight( getMascot( ).getAnchor( ).x < targetX );

        final double distanceX = targetX - getMascot( ).getAnchor( ).x;
        final double distanceY = targetY - getMascot( ).getAnchor( ).y - Math.abs( distanceX ) / 2;

        final double distance = Math.sqrt( distanceX * distanceX + distanceY * distanceY );

        final double velocity = getVelocity( ) * getMascot( ).getCurrentScale( );

        if( distance != 0 )
        {
            final int velocityX = (int)( velocity * distanceX / distance );
            final int velocityY = (int)( velocity * distanceY / distance );

            putVariable( getSchema( ).getString( VARIABLE_VELOCITYX ), velocity * distanceX / distance );
            putVariable( getSchema( ).getString( VARIABLE_VELOCITYY ), velocity * distanceY / distance );

            // Store jump exit velocity so Fall can carry momentum from the jump
            getMascot( ).setUserData( "jumpExitVelocityX", velocityX );

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
            case HUNT:
            {
                com.group_finity.mascot.Mascot target = findHuntTarget( );
                if( target != null )
                {
                    // Track 90% of the way to the target X, same as the static jump calculation
                    return getMascot( ).getAnchor( ).x
                        + (int)Math.round( ( target.getAnchor( ).x - getMascot( ).getAnchor( ).x ) * 0.90 );
                }
                // No target found — fall back to static value
            }
            default:
            {
                Number val = eval( getSchema( ).getString( PARAMETER_TARGETX ), Number.class, DEFAULT_TARGETX );
                int baseX = val != null ? val.intValue( ) : DEFAULT_TARGETX;
                int steer = getMascot( ).consumeJumpTargetXOffset( );
                if( steer != 0 )
                {
                    double currentDX = baseX - getMascot( ).getAnchor( ).x;
                    if( ( steer < 0 && currentDX > 0 ) || ( steer > 0 && currentDX < 0 ) )
                        // Reverse input — redirect target to current X (straight up from here)
                        baseX = getMascot( ).getAnchor( ).x;
                    else
                        baseX += (int)( steer * getMascot( ).getCurrentScale( ) );
                }
                return baseX;
            }
        }
    }

    /**
     * Returns the current Y target. Same live-sampling logic as getTargetX.
     */
    private int getTargetY( ) throws VariableException
    {
        if( overrideTargetY != Integer.MIN_VALUE ) return overrideTargetY;
        switch( liveMode )
        {
            case CURSOR: return getEnvironment( ).getCursor( ).getY( );
            case IE:     return getEnvironment( ).getActiveIE( ).toRectangle( ).y
                              + getEnvironment( ).getActiveIE( ).toRectangle( ).height / 2;
            case HUNT:
            {
                com.group_finity.mascot.Mascot target = findHuntTarget( );
                if( target != null )
                {
                    return target.getAnchor( ).y;
                }
                // No target found — fall back to static value
            }
            default:
                Number val = eval( getSchema( ).getString( PARAMETER_TARGETY ), Number.class, DEFAULT_TARGETY );
                return val != null ? val.intValue( ) : DEFAULT_TARGETY;
        }
    }

    /** Sentinel value meaning "no override set". */
    private int overrideTargetY = Integer.MIN_VALUE;

    /**
     * Override TargetY at runtime (used by hold-cancel for short hops).
     * Once set, getTargetY() returns this value on every tick, cutting the
     * jump arc short. Set to Integer.MIN_VALUE to clear the override.
     */
    public void setTargetY( final int y )
    {
        this.overrideTargetY = y;
    }

    /**
     * Finds the nearest mascot with the "Hunt" affordance.
     * Returns null if none exists or the manager is unavailable.
     */
    private com.group_finity.mascot.Mascot findHuntTarget( )
    {
        com.group_finity.mascot.Manager manager = getMascot( ).getManager( );
        if( manager == null ) return null;

        com.group_finity.mascot.Mascot best = null;
        double bestDist = Double.MAX_VALUE;

        for( com.group_finity.mascot.Mascot other : manager.getMascotList( ) )
        {
            if( other == getMascot( ) ) continue;
            if( !other.getAffordances( ).contains( "Hunt" ) ) continue;

            double dx = other.getAnchor( ).x - getMascot( ).getAnchor( ).x;
            double dy = other.getAnchor( ).y - getMascot( ).getAnchor( ).y;
            double dist = Math.sqrt( dx * dx + dy * dy );

            if( dist < bestDist )
            {
                bestDist = dist;
                best = other;
            }
        }

        return best;
    }
}
