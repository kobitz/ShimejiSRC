package com.group_finity.mascot.environment;

import java.io.File;

/**
 * Finds an executable on {@code PATH} and returns an <b>absolute</b> path, so it can be launched
 * without letting the OS search the working directory.
 *
 * <p>Launching a bare name ({@code new ProcessBuilder("python", ...)}) makes Windows look in the
 * current directory before {@code PATH}; under an elevated {@code Admin.bat} started from a
 * user-writable folder, a planted {@code python.exe} there would run with admin rights (same
 * class of problem {@link InstallFiles} closes for {@code TempSensor.exe}). This resolver skips
 * relative {@code PATH} entries and any entry that is the working directory.
 */
public final class ExecutableLocator
{
    private ExecutableLocator( ) { }

    /**
     * @param names   candidate file names in priority order (e.g. {@code "python.exe", "python"})
     * @param pathEnv the {@code PATH} string ({@code File.pathSeparator}-delimited)
     * @param cwd     the working directory to refuse (may be null)
     * @return absolute path of the first match, or null
     */
    public static File findOnPath( final String[] names, final String pathEnv, final File cwd )
    {
        if( pathEnv == null || pathEnv.isEmpty( ) ) return null;
        final File cwdCanon = canon( cwd );
        for( String dir : pathEnv.split( java.util.regex.Pattern.quote( File.pathSeparator ) ) )
        {
            dir = dir.trim( );
            if( dir.length( ) > 1 && dir.startsWith( "\"" ) && dir.endsWith( "\"" ) )
                dir = dir.substring( 1, dir.length( ) - 1 );
            if( dir.isEmpty( ) ) continue;                 // empty entry == "current directory"
            final File d = new File( dir );
            if( !d.isAbsolute( ) ) continue;               // relative entry resolves against cwd
            if( cwdCanon != null && cwdCanon.equals( canon( d ) ) ) continue;
            for( final String n : names )
            {
                final File f = new File( d, n );
                if( f.isFile( ) ) return f.getAbsoluteFile( );
            }
        }
        return null;
    }

    private static File canon( final File f )
    {
        if( f == null ) return null;
        try { return f.getCanonicalFile( ); }
        catch( final java.io.IOException e ) { return f.getAbsoluteFile( ); }
    }
}
