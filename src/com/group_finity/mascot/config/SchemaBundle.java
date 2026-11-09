package com.group_finity.mascot.config;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Wraps the XML schema bundle so tags added by newer versions still resolve when the installed
 * {@code conf/schema*.properties} is older than the jar (schema files are read from the install
 * folder, not the jar). Each key listed in {@link #IDENTITY_DEFAULTS} falls back to its own name,
 * which is exactly what the shipped schema maps it to. Every other missing key still throws.
 */
final class SchemaBundle extends ResourceBundle
{
    /** Keys added after 1.0.22 whose schema value is the same as the key. */
    static final Set<String> IDENTITY_DEFAULTS = Collections.unmodifiableSet(
        new HashSet<String>( java.util.Arrays.asList( "Look", "Arc", "SplashImages" ) ) );

    private final ResourceBundle delegate;

    SchemaBundle( final ResourceBundle delegate )
    {
        this.delegate = delegate;
    }

    @Override
    protected Object handleGetObject( final String key )
    {
        try
        {
            return delegate.getObject( key );
        }
        catch( final MissingResourceException e )
        {
            return IDENTITY_DEFAULTS.contains( key ) ? key : null;
        }
    }

    @Override
    public Enumeration<String> getKeys( )
    {
        final Set<String> keys = new HashSet<String>( IDENTITY_DEFAULTS );
        keys.addAll( delegate.keySet( ) );
        return Collections.enumeration( keys );
    }
}
