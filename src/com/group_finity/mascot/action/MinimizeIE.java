package com.group_finity.mascot.action;

import java.util.List;
import java.util.logging.Logger;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * @author Kilkakon
 */
public class MinimizeIE extends Animate
{
    private static final Logger log = Logger.getLogger( MinimizeIE.class.getName( ) );
    
    public MinimizeIE( java.util.ResourceBundle schema, final List<Animation> animations, final VariableMap context )
    {
        super( schema, animations, context );
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        if( !Main.getInstance( ).getMascotBooleanSetting( "Throwing", getMascot( ) ) )
            return false;

        final boolean ieVisible = getEnvironment( ).getActiveIE( ).isVisible( );

        return super.hasNext( ) && ieVisible;
    }
    
    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        super.tick( );

        if( ( getTime( ) == getAnimation( ).getDuration( ) - 1 || getAnimation( ).getDuration( ) == 1 ) )
        {
            getEnvironment( ).minimizeActiveIE( );
        }
    }
}
