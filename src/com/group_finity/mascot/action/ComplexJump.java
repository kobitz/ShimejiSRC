package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import java.awt.Point;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.BehaviorInstantiationException;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * @author Kilkakon
 *
 * LiveTarget parameter: when set to "cursor" or "ie", the jump target is
 * re-sampled from the environment every tick instead of being frozen at
 * action init time. Has no effect when Scan characteristic is active
 * (scan mode already tracks a live mascot target).
 *
 * Valid values for LiveTarget:
 *   "cursor"  - tracks the mouse cursor
 *   "ie"      - tracks the centre of the active window
 *   (absent)  - original behaviour: target locked at init
 */
public class ComplexJump extends ActionBase
{
    private static final Logger log = Logger.getLogger( ComplexJump.class.getName( ) );

    private final Breed.Delegate delegate = new Breed.Delegate( this );

    public static final String PARAMETER_CHARACTERISTICS = "Characteristics";
    private static final String DEFAULT_CHARACTERISTICS  = "";

    public static final String PARAMETER_BEHAVIOUR = "Behaviour";
    private static final String DEFAULT_BEHAVIOUR  = "";

    public static final String PARAMETER_TARGETBEHAVIOUR = "TargetBehaviour";
    private static final String DEFAULT_TARGETBEHAVIOUR  = "";

    public static final String PARAMETER_TARGETLOOK = "TargetLook";
    private static final boolean DEFAULT_TARGETLOOK = false;

    // A Pose Attribute is already named Velocity
    public static final String PARAMETER_VELOCITY = "VelocityParam";
    private static final double DEFAULT_VELOCITY  = 20.0;

    public static final String PARAMETER_TARGETX = "TargetX";
    private static final int   DEFAULT_TARGETX   = 0;

    public static final String PARAMETER_TARGETY = "TargetY";
    private static final int   DEFAULT_TARGETY   = 0;

    /**
     * When set, overrides TargetX/TargetY with a live environment source
     * every tick.  Accepted values (case-insensitive): "cursor", "ie".
     * Ignored when the Scan characteristic is active.
     */
    public static final String PARAMETER_LIVE_TARGET = "LiveTarget";
    private static final String DEFAULT_LIVE_TARGET  = "";

    public static final String VARIABLE_VELOCITYX = "VelocityX";
    public static final String VARIABLE_VELOCITYY = "VelocityY";

    // Resolved once in init() so we don't parse the string every tick
    private enum LiveMode { NONE, CURSOR, IE }
    private LiveMode liveMode = LiveMode.NONE;

    private WeakReference<Mascot> target;

    private boolean breedEnabled = false;
    private boolean scanEnabled  = false;

    public ComplexJump( java.util.ResourceBundle schema, final List<Animation> animations, final VariableMap params )
    {
        super( schema, animations, params );
    }

    @Override
    public void init( final Mascot mascot ) throws VariableException
    {
        super.init( mascot );

        for( String characteristic : getCharacteristics( ).split( "," ) )
        {
            if( characteristic.equals( getSchema( ).getString( "Breed" ) ) )
                breedEnabled = true;
            if( characteristic.equals( getSchema( ).getString( "Scan" ) ) )
                scanEnabled = true;
        }

        if( breedEnabled )
        {
            delegate.validateBornCount( );
            delegate.validateBornInterval( );
        }

        if( scanEnabled )
        {
            // Cannot broadcast while scanning for an affordance
            getMascot( ).getAffordances( ).clear( );

            if( getMascot( ).getManager( ) != null )
                target = getMascot( ).getManager( ).getMascotWithAffordance( getAffordance( ) );
            putVariable( getSchema( ).getString( "TargetX" ), target != null && target.get( ) != null ? target.get( ).getAnchor( ).x : null );
            putVariable( getSchema( ).getString( "TargetY" ), target != null && target.get( ) != null ? target.get( ).getAnchor( ).y : null );
        }
        else
        {
            // Only resolve LiveTarget when not in scan mode (scan tracks its own live mascot)
            String raw = eval( getSchema( ).getString( PARAMETER_LIVE_TARGET ), String.class, DEFAULT_LIVE_TARGET ).trim( ).toLowerCase( );
            switch( raw )
            {
                case "cursor": liveMode = LiveMode.CURSOR; break;
                case "ie":     liveMode = LiveMode.IE;     break;
                default:       liveMode = LiveMode.NONE;   break;
            }
        }
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        if( scanEnabled )
        {
            if( getMascot( ).getManager( ) == null )
                return super.hasNext( );

            return super.hasNext( ) && target != null && target.get( ) != null && target.get( ).getAffordances( ).contains( getAffordance( ) );
        }
        else
        {
            final int targetX = getTargetX( );
            final int targetY = getTargetY( );

            final double distanceX = targetX - getMascot( ).getAnchor( ).x;
            final double distanceY = targetY - getMascot( ).getAnchor( ).y - Math.abs( distanceX ) / 2;

            final double distance = Math.sqrt( distanceX * distanceX + distanceY * distanceY );

            return super.hasNext( ) && ( distance != 0 );
        }
    }

    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        int targetX = 0;
        int targetY = 0;

        if( scanEnabled )
        {
            // Cannot broadcast while scanning for an affordance
            getMascot( ).getAffordances( ).clear( );

            targetX = target.get( ).getAnchor( ).x;
            targetY = target.get( ).getAnchor( ).y;

            putVariable( getSchema( ).getString( "TargetX" ), targetX );
            putVariable( getSchema( ).getString( "TargetY" ), targetY );

            if( getMascot( ).getAnchor( ).x != targetX )
            {
                getMascot( ).setLookRight( getMascot( ).getAnchor( ).x < targetX );
            }
        }
        else
        {
            targetX = getTargetX( );
            targetY = getTargetY( );

            getMascot( ).setLookRight( getMascot( ).getAnchor( ).x < targetX );
        }

        double distanceX = targetX - getMascot( ).getAnchor( ).x;
        double distanceY = targetY - getMascot( ).getAnchor( ).y - Math.abs( distanceX ) / 2;

        double distance = Math.sqrt( distanceX * distanceX + distanceY * distanceY );

        double velocity = getVelocity( );

        if( distance != 0 )
        {
            int velocityX = (int)( velocity * distanceX / distance );
            int velocityY = (int)( velocity * distanceY / distance );

            putVariable( getSchema( ).getString( VARIABLE_VELOCITYX ), velocity * distanceX / distance );
            putVariable( getSchema( ).getString( VARIABLE_VELOCITYY ), velocity * distanceY / distance );

            getMascot( ).setAnchor( new Point( getMascot( ).getAnchor( ).x + velocityX,
                                               getMascot( ).getAnchor( ).y + velocityY ) );
            getAnimation( ).next( getMascot( ), getTime( ) );
        }

        if( distance <= velocity )
        {
            getMascot( ).setAnchor( new Point( targetX, targetY ) );

            if( scanEnabled )
            {
                try
                {
                    getMascot( ).setBehavior( Main.getInstance( ).getConfiguration( getMascot( ).getImageSet( ) ).buildBehavior( getBehavior( ), getMascot( ) ) );
                    target.get( ).setBehavior( Main.getInstance( ).getConfiguration( target.get( ).getImageSet( ) ).buildBehavior( getTargetBehavior( ), target.get( ) ) );
                    if( getTargetLook( ) && target.get( ).isLookRight( ) == getMascot( ).isLookRight( ) )
                    {
                        target.get( ).setLookRight( !getMascot( ).isLookRight( ) );
                    }
                }
                catch( final NullPointerException e )
                {
                    log.log( Level.SEVERE, "Fatal Exception", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
                }
                catch( final BehaviorInstantiationException e )
                {
                    log.log( Level.SEVERE, "Fatal Exception", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
                }
                catch( final CantBeAliveException e )
                {
                    log.log( Level.SEVERE, "Fatal Exception", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
                }
            }
        }

        if( breedEnabled && delegate.isIntervalFrame( ) && delegate.isEnabled( ) )
            delegate.breed( );
    }

    private String getCharacteristics( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_CHARACTERISTICS ), String.class, DEFAULT_CHARACTERISTICS );
    }

    private String getBehavior( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_BEHAVIOUR ), String.class, DEFAULT_BEHAVIOUR );
    }

    private String getTargetBehavior( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_TARGETBEHAVIOUR ), String.class, DEFAULT_TARGETBEHAVIOUR );
    }

    private boolean getTargetLook( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_TARGETLOOK ), Boolean.class, DEFAULT_TARGETLOOK );
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
