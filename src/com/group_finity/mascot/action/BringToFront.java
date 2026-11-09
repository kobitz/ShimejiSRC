package com.group_finity.mascot.action;

import java.util.logging.Logger;

import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * By Kilkakon
 * kilkakon.com
 */
public class BringToFront extends InstantAction
{
    private static final Logger log = Logger.getLogger( BringToFront.class.getName( ) );

    public BringToFront( java.util.ResourceBundle schema, final VariableMap params )
    {
        super( schema, params );
    }

    @Override
    protected void apply( ) throws VariableException
    {
        getMascot( ).bringToFront( );
    }
}