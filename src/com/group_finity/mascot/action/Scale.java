package com.group_finity.mascot.action;

import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * Smoothly scales the mascot toward a target scale each tick.
 * Extends ActionBase directly (not Animate) to avoid NPE from missing Animation block.
 *
 * XML usage:
 *   <Action Name="ScaleWithLoad" Type="Embedded"
 *           Class="com.group_finity.mascot.action.Scale"
 *           Target="#{mascot.environment.cpuLoad / 100.0 * 1.9 + 0.1}"
 *           Speed="0.03" />
 */
public class Scale extends ActionBase
{
    private static final Logger log = Logger.getLogger( Scale.class.getName( ) );

    public static final String PARAMETER_TARGET = "Target";
    public static final String PARAMETER_SPEED  = "Speed";

    private static final double DEFAULT_TARGET = 1.0;
    private static final double DEFAULT_SPEED  = 0.03;

    public Scale( final ResourceBundle schema, final List<Animation> animations, final VariableMap params )
    {
        super( schema, animations, params );
    }

    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        double target = Math.max( 0.1, Math.min( 10.0, getTarget( ) ) );
        double speed  = Math.max( 0.001, Math.min( 1.0, getSpeed( ) ) );
        double current = getMascot( ).getCurrentScale( );
        double next = Math.abs( current - target ) > 0.001
            ? current + ( target - current ) * speed
            : target;
        getMascot( ).setCurrentScale( next );
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        return super.hasNext( );
    }

    private double getTarget( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_TARGET ), Number.class, DEFAULT_TARGET ).doubleValue( );
    }

    private double getSpeed( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_SPEED ), Number.class, DEFAULT_SPEED ).doubleValue( );
    }
}
