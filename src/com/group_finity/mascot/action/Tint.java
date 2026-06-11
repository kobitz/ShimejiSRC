package com.group_finity.mascot.action;

import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.Constant;
import com.group_finity.mascot.script.Variable;
import com.group_finity.mascot.script.VariableMap;

/**
 * Applies a color tint overlay to the mascot.
 *
 * When Target is a script expression, the expression is stored on the mascot and
 * re-evaluated every tick in Mascot.tick() — independently of what behavior is active.
 * This means a single short-lived TintWithRam action registers the expression and then
 * the tint continuously tracks the sensor for the rest of the session.
 *
 * When Duration is specified the tint snaps to cleared on expiry.
 * No Duration = persists indefinitely (or until overwritten by another Tint action).
 *
 * XML usage:
 *
 *   <!-- Dynamic: RAM-driven opacity, registers expression then exits after 1 tick -->
 *   <Action Name="TintWithRam" Type="Embedded"
 *           Class="com.group_finity.mascot.action.Tint"
 *           Color="FF0000"
 *           Target="#{mascot.environment.ramLoad / 100}"
 *           LerpFactor="0.08" />
 *
 *   <!-- Static: flash red for 25 ticks then clear -->
 *   <Action Name="FlashRed" Type="Embedded"
 *           Class="com.group_finity.mascot.action.Tint"
 *           Color="FF0000"
 *           Opacity="0.4"
 *           Duration="25" />
 *
 *   <!-- Dynamic color (rainbow/strobe), opacity fixed -->
 *   <Action Name="RainbowStrobe" Type="Embedded"
 *           Class="com.group_finity.mascot.action.Tint"
 *           Color="${someHexExpr}"
 *           Opacity="0.5"
 *           LerpFactor="1.0" />
 *
 *   <!-- Clear any active tint immediately -->
 *   <Action Name="ClearTint" Type="Embedded"
 *           Class="com.group_finity.mascot.action.Tint"
 *           Color="000000"
 *           Opacity="0"
 *           Duration="1" />
 */
public class Tint extends ActionBase
{
    private static final Logger log = Logger.getLogger( Tint.class.getName( ) );

    public static final String PARAMETER_COLOR          = "Color";
    public static final String PARAMETER_OPACITY        = "Opacity";
    public static final String PARAMETER_TARGET         = "Target";
    public static final String PARAMETER_LERP           = "LerpFactor";
    // Explicit flag: only snap-clear the tint when the action exits if this is "true".
    // Duration alone does NOT clear — it only controls how long the action runs.
    // This lets <ActionReference Name="TintWithRam" Duration="5"/> set up a persistent
    // tint in 5 ticks without wiping it on exit.
    public static final String PARAMETER_CLEAR_ON_EXPIRY = "ClearOnExpiry";

    private static final String DEFAULT_COLOR   = "FFFFFF";
    private static final double DEFAULT_OPACITY = 0.0;
    private static final double DEFAULT_LERP    = 0.1;

    public Tint( final ResourceBundle schema, final List<Animation> animations, final VariableMap params )
    {
        super( schema, animations, params );
    }

    @Override
    public void init( final Mascot mascot ) throws VariableException
    {
        super.init( mascot );

        // Register any script expressions (Target and/or Color) on the mascot so they keep
        // being evaluated every tick even after this action ends.
        Variable targetVar = getVariables( ).getRawMap( ).get( getSchema( ).getString( PARAMETER_TARGET ) );
        Variable colorVar  = getVariables( ).getRawMap( ).get( getSchema( ).getString( PARAMETER_COLOR ) );
        boolean dynamicOpacity = targetVar != null && !( targetVar instanceof Constant );
        boolean dynamicColor   = colorVar  != null && !( colorVar  instanceof Constant );
        if( dynamicOpacity || dynamicColor )
        {
            getMascot( ).setTintExpr(
                dynamicOpacity ? targetVar : null,
                dynamicColor   ? colorVar  : null,
                getVariables( ), getColor( ), getLerpFactor( ) );
        }
    }

    @Override
    protected void tick( ) throws LostGroundException, VariableException
    {
        java.awt.Color color = getColor( );
        float lerp           = getLerpFactor( );

        // If Target is a dynamic expression the mascot owns the lerp — just keep color current.
        // If static (Opacity only), push the target to the mascot and let it lerp.
        Variable targetVar = getVariables( ).getRawMap( ).get( getSchema( ).getString( PARAMETER_TARGET ) );
        boolean dynamicOpacity = targetVar != null && !( targetVar instanceof Constant );
        // When opacity is static (no Target expression), push the static value so Mascot.tick() lerps toward it.
        // When opacity is dynamic, Mascot.tick() owns the lerp — pass current so setTintTarget only refreshes color+lerp.
        float opacityTarget = dynamicOpacity ? getMascot( ).getTintCurrentOpacity( ) : getStaticOpacity( );
        getMascot( ).setTintTarget( color, opacityTarget, lerp );

        final Animation anim = getAnimation( );
        if( anim != null )
        {
            anim.next( getMascot( ), getTime( ) );
        }
    }

    @Override
    public boolean hasNext( ) throws VariableException
    {
        if( !super.hasNext( ) )
        {
            if( isClearOnExpiry( ) )
            {
                getMascot( ).snapClearTint( );
            }
            return false;
        }
        return true;
    }

    // -- helpers --

    private boolean isClearOnExpiry( ) throws VariableException
    {
        return eval( getSchema( ).getString( PARAMETER_CLEAR_ON_EXPIRY ), Boolean.class, false );
    }

    private java.awt.Color getColor( ) throws VariableException
    {
        String hex = eval( getSchema( ).getString( PARAMETER_COLOR ), String.class, DEFAULT_COLOR );
        if( hex == null ) hex = DEFAULT_COLOR;
        if( hex.startsWith( "#" ) ) hex = hex.substring( 1 );
        if( hex.length( ) < 6 ) return java.awt.Color.WHITE;
        try
        {
            int r = Math.max( 0, Math.min( 255, Integer.parseInt( hex.substring( 0, 2 ), 16 ) ) );
            int g = Math.max( 0, Math.min( 255, Integer.parseInt( hex.substring( 2, 4 ), 16 ) ) );
            int b = Math.max( 0, Math.min( 255, Integer.parseInt( hex.substring( 4, 6 ), 16 ) ) );
            return new java.awt.Color( r, g, b );
        }
        catch( NumberFormatException e )
        {
            return java.awt.Color.WHITE;
        }
    }

    private float getStaticOpacity( ) throws VariableException
    {
        double o = eval( getSchema( ).getString( PARAMETER_OPACITY ), Number.class, DEFAULT_OPACITY ).doubleValue( );
        return (float) Math.max( 0.0, Math.min( 1.0, o ) );
    }

    private float getLerpFactor( ) throws VariableException
    {
        return (float) Math.max( 0.001, Math.min( 1.0,
            eval( getSchema( ).getString( PARAMETER_LERP ), Number.class, DEFAULT_LERP ).doubleValue( ) ) );
    }
}
