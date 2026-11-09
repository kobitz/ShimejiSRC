package com.group_finity.mascot.action;

import com.group_finity.mascot.Mascot;
import java.util.List;
import java.util.logging.Logger;

import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * @author Kilkakon
 */
public class BreedFall extends Fall
{
    private static final Logger log = Logger.getLogger( BreedFall.class.getName( ) );
    
    private final Breed.Delegate delegate = new Breed.Delegate( this );
    
    public BreedFall( java.util.ResourceBundle schema, final List<Animation> animations, final VariableMap context )
    {
        super( schema, animations, context );
    }

    @Override
    public void init( final Mascot mascot ) throws VariableException
    {
        super.init( mascot );
        
        delegate.validateBornCount( );
        delegate.validateBornInterval( );
    }

    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        super.tick( );
        
        if( delegate.isIntervalFrame( ) && delegate.isEnabled( ) )
            delegate.breed( );
    }
}
