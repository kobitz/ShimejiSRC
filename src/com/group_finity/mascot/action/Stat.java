package com.group_finity.mascot.action;

import com.group_finity.mascot.assistant.MascotStats;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * Bumps a custom behavior counter in the mascot's assistant memory (lifetime + daily + weekly),
 * e.g. {@code <Action Type="Embedded" Class="...Stat" Counter="Mosscreeps hunted" />}. The
 * counter shows up in the mascot's "Your own life" prompt line. {@code Amount} defaults to 1.
 * No-op for image sets that don't keep memory yet.
 */
public class Stat extends InstantAction
{
    public static final String PARAMETER_COUNTER = "Counter";
    public static final String PARAMETER_AMOUNT  = "Amount";

    public Stat( final java.util.ResourceBundle schema, final VariableMap params )
    {
        super( schema, params );
    }

    @Override
    protected void apply( ) throws VariableException
    {
        final String counter = eval( getSchema( ).getString( PARAMETER_COUNTER ), String.class, "" );
        final Number amount  = eval( getSchema( ).getString( PARAMETER_AMOUNT ), Number.class, 1 );
        if( MascotStats.sanitize( counter ) == null ) return;
        getMascot( ).recordStat( counter, amount == null ? 1 : amount.doubleValue( ) );
    }
}
