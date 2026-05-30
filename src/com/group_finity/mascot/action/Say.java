package com.group_finity.mascot.action;

import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

import java.util.logging.Logger;

/**
 * Instant action that shows a text bubble on the mascot.
 *
 * XML usage:
 *   &lt;Action Class="Say" Text="Found one." /&gt;
 *
 * The Text attribute supports script expressions, e.g.:
 *   &lt;Action Class="Say" Text="${'Hmm...'}" /&gt;
 *
 * Because Say extends InstantAction it fires once and immediately
 * hands control back to the behavior — no animation required.
 */
public class Say extends InstantAction
{
    private static final Logger log = Logger.getLogger( Say.class.getName( ) );

    public static final String PARAMETER_TEXT = "Text";
    private static final String DEFAULT_TEXT  = "";

    public Say( final java.util.ResourceBundle schema, final VariableMap params )
    {
        super( schema, params );
    }

    @Override
    protected void apply( ) throws VariableException
    {
        final String text = eval(
            getSchema( ).getString( PARAMETER_TEXT ), String.class, DEFAULT_TEXT );

        if( text == null || text.trim( ).isEmpty( ) )
        {
            log.warning( "Say action fired with empty Text on " + getMascot( ) );
            return;
        }

        getMascot( ).say( text.trim( ) );
    }
}
