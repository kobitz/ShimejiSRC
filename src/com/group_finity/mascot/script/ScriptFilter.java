package com.group_finity.mascot.script;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.openjdk.nashorn.api.scripting.ClassFilter;

/**
 * Decides which Java classes a mascot script may <em>name</em> ({@code Java.type(...)},
 * {@code Packages.x.Y}, {@code new x.Y()}).
 *
 * <p>The default is: none. Mascot XML does not need to name classes -- it works with the objects
 * the engine hands it ({@code mascot}, {@code scaling}, ...), and Nashorn does not consult this
 * filter for method/property access on those objects. This used to expose every
 * {@code com.group_finity.mascot.*} class, which let any script (e.g. from a downloaded mascot
 * pack) reach {@code Main.getInstance().getProperties()} and rewrite settings such as
 * {@code OllamaEndpoint}, or call process-spawning helpers like {@code FanController}.
 *
 * <p>If a trusted script really does need a class, list its exact name in the
 * {@code ScriptAllowedClasses} setting (comma-separated). Denials are logged once per class so the
 * name to add is easy to find. Entries are exact binary class names; a nested class is written
 * {@code Outer$Inner}.
 */
public class ScriptFilter implements ClassFilter
{
    private static final Logger log = Logger.getLogger( ScriptFilter.class.getName( ) );

    /** Denials already reported, so a script evaluated every tick does not flood the log. */
    private static final Set<String> reported = ConcurrentHashMap.newKeySet( );

    private final Set<String> allowed;

    public ScriptFilter( )
    {
        this( Collections.<String>emptySet( ) );
    }

    public ScriptFilter( final Set<String> allowed )
    {
        this.allowed = Set.copyOf( allowed );
    }

    /**
     * Parses the {@code ScriptAllowedClasses} setting. Entries are EXACT fully-qualified class
     * names: no wildcards or package prefixes, so one entry can never re-open a whole package.
     */
    public static Set<String> parseAllowed( final String csv )
    {
        final Set<String> out = new HashSet<String>( );
        if( csv != null )
        {
            for( final String token : csv.split( "," ) )
            {
                final String name = token.trim( );
                if( !name.isEmpty( ) ) out.add( name );
            }
        }
        return out;
    }

    @Override
    public boolean exposeToScripts( final String className )
    {
        if( allowed.contains( className ) ) return true;
        if( looksLikeClassName( className ) && reported.add( className ) )
        {
            log.warning( "[Script] A mascot script tried to use the Java class " + className
                + " -- blocked. If a trusted script needs it, add that exact name to ScriptAllowedClasses." );
        }
        return false;
    }

    /**
     * Nashorn asks about several candidate spellings for one lookup: package prefixes ("com",
     * "com.group_finity"), member paths ("x.Main.getInstance") and '$'/'.' variants of nested
     * names. Only the plain "pkg.Class" spelling is worth a log line; the rest are denied silently
     * (they are all denied anyway) so one blocked attempt produces one warning, not five.
     */
    private static boolean looksLikeClassName( final String name )
    {
        if( name == null || name.indexOf( '$' ) >= 0 ) return false;
        final String last = name.substring( name.lastIndexOf( '.' ) + 1 );
        return !last.isEmpty( ) && Character.isUpperCase( last.charAt( 0 ) );
    }
}
