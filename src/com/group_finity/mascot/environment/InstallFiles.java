package com.group_finity.mascot.environment;

import java.io.File;

/**
 * Locates helper files that ship next to the application (e.g. {@code TempSensor.exe}).
 *
 * <p>Executables must be launched by absolute path from the install folder rather than by bare
 * name: a bare name is resolved against the current working directory first on Windows, so an
 * elevated {@code Admin.bat} run from a user-writable folder could be tricked into running a
 * planted executable with admin rights.
 */
public final class InstallFiles
{
    private InstallFiles( ) { }

    /**
     * @return the named file next to the running jar/exe, or null if it is not there. Only when
     *         the app is running from loose classes (IDE / dev run, no jar) does it fall back to
     *         the working directory.
     */
    public static File find( final String name )
    {
        boolean packaged = false;
        try
        {
            final File code = new File( InstallFiles.class.getProtectionDomain()
                .getCodeSource( ).getLocation( ).toURI( ) );
            packaged = code.isFile( );          // Shimeji-ee.jar or the Launch4j exe
            final File dir = packaged ? code.getParentFile( ) : null;
            if( dir != null )
            {
                final File f = new File( dir, name );
                if( f.isFile( ) ) return f;
            }
        }
        catch( Exception ignored ) { }

        if( !packaged )
        {
            final File f = new File( System.getProperty( "user.dir" ), name );
            if( f.isFile( ) ) return f.getAbsoluteFile( );
        }
        return null;
    }
}
