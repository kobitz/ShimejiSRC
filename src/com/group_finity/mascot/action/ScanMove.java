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
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public class ScanMove extends BorderedAction
{
    private static final Logger log = Logger.getLogger( ScanMove.class.getName( ) );
    
    public static final String PARAMETER_BEHAVIOUR = "Behaviour";

    private static final String DEFAULT_BEHAVIOUR = "";
    
    public static final String PARAMETER_TARGETBEHAVIOUR = "TargetBehaviour";

    private static final String DEFAULT_TARGETBEHAVIOUR = "";
    
    public static final String PARAMETER_TARGETLOOK = "TargetLook";

    private static final boolean DEFAULT_TARGETLOOK = false;

    public static final String PARAMETER_PROXIMITY = "Proximity";

    private static final int DEFAULT_PROXIMITY = 0;

    public static final String PARAMETER_TARGETID = "TargetId";

    private static final int DEFAULT_TARGETID = -1;
    
    private WeakReference<Mascot> target;
    
    private boolean turning = false;
    
    private Boolean hasTurning = null;
    
    public ScanMove( java.util.ResourceBundle schema, final List<Animation> animations, final VariableMap params )
    {
	super( schema, animations, params );
    }

    @Override
    public void init( final Mascot mascot ) throws VariableException
    {
        super.init( mascot );
        
        // cannot broadcast while scanning for an affordance
        getMascot( ).getAffordances( ).clear( );
        
        if( getMascot( ).getManager( ) != null )
            target = acquireTarget( );
        putVariable( getSchema( ).getString( "TargetX" ), target != null && target.get( ) != null ? target.get( ).getAnchor( ).x : null );
        putVariable( getSchema( ).getString( "TargetY" ), target != null && target.get( ) != null ? target.get( ).getAnchor( ).y : null );
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        if( getMascot( ).getManager( ) == null )
            return super.hasNext( );

        if( target == null || target.get( ) == null )
            return false;
        
        return super.hasNext( ) && ( turning || target.get( ).getAffordances( ).contains( getAffordance( ) ) );
    }

    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        super.tick( );
        
        // cannot broadcast while scanning for an affordance
        getMascot( ).getAffordances( ).clear( );

        if( ( getBorder( ) != null ) && !getBorder( ).isOn( getMascot( ).getAnchor( ) ) )
        {
            log.log( Level.INFO, "Lost Ground ({0},{1})", new Object[ ] { getMascot( ), this } );
            throw new LostGroundException( );
        }

        // Refresh target if it lost its affordance or disappeared; prefer stored ID
        if( getMascot( ).getManager( ) != null &&
            ( target == null || target.get( ) == null || !target.get( ).getAffordances( ).contains( getAffordance( ) ) ) )
            target = acquireTarget( );

        if( target == null || target.get( ) == null )
            return;

        int targetX = target.get( ).getAnchor( ).x;
        int targetY = target.get( ).getAnchor( ).y;
        
        putVariable( getSchema( ).getString( "TargetX" ), targetX );
        putVariable( getSchema( ).getString( "TargetY" ), targetY );

        if( getMascot( ).getAnchor( ).x != targetX )
        {
            // activate turn animation if we change directions
            turning = hasTurningAnimation( ) && ( turning || getMascot( ).getAnchor( ).x < targetX != getMascot( ).isLookRight( ) );
            getMascot( ).setLookRight( getMascot( ).getAnchor( ).x < targetX );
        }
        boolean down = getMascot( ).getAnchor( ).y < targetY;
        
        // check if turning animation has finished
        if( turning )
        {
            Animation turnAnim = getAnimation( );
            if( turnAnim != null && getTime( ) >= turnAnim.getDuration( ) )
            {
                setTime( getTime( ) - turnAnim.getDuration( ) );
                turning = false;
            }
        }

        Animation anim = getAnimation( );
        if( anim == null )
            return;

        anim.next( getMascot( ), getTime( ) );

        if( getProximity( ) == 0 )
        {
            if( ( getMascot( ).isLookRight( ) && ( getMascot( ).getAnchor( ).x >= targetX ) ) ||
                ( !getMascot( ).isLookRight( ) && ( getMascot( ).getAnchor( ).x <= targetX ) ) )
            {
                getMascot( ).setAnchor( new Point( targetX, getMascot( ).getAnchor( ).y ) );
            }
            if( ( down && ( getMascot( ).getAnchor( ).y >= targetY ) ) ||
                ( !down && ( getMascot( ).getAnchor( ).y <= targetY ) ) )
            {
                getMascot( ).setAnchor( new Point( getMascot( ).getAnchor( ).x, targetY ) );
            }
        }
        
        if( !turning && Math.abs( getMascot( ).getAnchor( ).x - targetX ) <= getProximity( ) )
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
            return;
        }
    }
    
    @Override
    public Animation getAnimation( ) throws VariableException
    {
        List<Animation> animations = super.getAnimations( );
        for( int index = 0; index < animations.size( ); index++ )
        {
            if( animations.get( index ).isEffective( getVariables( ) ) && 
                turning == animations.get( index ).isTurn( ) )
            {
                return animations.get( index );
            }
        }

        return null;
    }
    
    protected boolean hasTurningAnimation( )
    {
        if( hasTurning == null )
        {
            hasTurning = false;
            List<Animation> animations = super.getAnimations( );
            for( int index = 0; index < animations.size( ); index++ )
            {
                if( animations.get( index ).isTurn( ) )
                {
                    hasTurning = true;
                    index = animations.size( );
                }
            }
        }
        return hasTurning;
    }
    
    protected boolean isTurning( )
    {
        return turning;
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

    private int getProximity( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_PROXIMITY ), Number.class, DEFAULT_PROXIMITY ).intValue( );
    }

    /**
     * Returns the nearest mascot with the required affordance.
     * Using nearest ensures ScanMove always chases the closest valid target
     * regardless of what any behavior condition may have previously stored.
     */
    private WeakReference<Mascot> acquireTarget( ) throws VariableException
    {
        return getMascot( ).getManager( ).getMascotNearestWithAffordance( getAffordance( ), getMascot( ).getAnchor( ) );
    }

    private int getTargetId( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_TARGETID ), Number.class, DEFAULT_TARGETID ).intValue( );
    }
}
