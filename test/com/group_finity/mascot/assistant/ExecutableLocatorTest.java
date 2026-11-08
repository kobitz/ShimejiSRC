package com.group_finity.mascot.assistant;

import com.group_finity.mascot.environment.ExecutableLocator;

import java.io.File;
import java.nio.file.Files;

/** Tests for {@link ExecutableLocator}: PATH search must never resolve via the working directory. */
public final class ExecutableLocatorTest
{
    private static int failures = 0;

    private static void check( final String what, final boolean ok )
    {
        if( !ok ) { failures++; System.err.println( "FAIL " + what ); }
    }

    private static File touch( final File dir, final String name ) throws Exception
    {
        dir.mkdirs( );
        final File f = new File( dir, name );
        Files.write( f.toPath( ), new byte[]{ 0 } );
        return f;
    }

    public static void main( final String[] args ) throws Exception
    {
        final File root = Files.createTempDirectory( "exeloc" ).toFile( );
        final File good = new File( root, "good" ), cwd = new File( root, "cwd" ), other = new File( root, "other" );
        final String[] names = { "python.exe", "python" };
        final String sep = File.pathSeparator;

        final File planted = touch( cwd, "python.exe" );
        final File real = touch( good, "python.exe" );
        other.mkdirs( );

        check( "finds absolute PATH entry", real.equals( ExecutableLocator.findOnPath( names, good + sep + other, cwd ) ) );
        check( "returns absolute path", ExecutableLocator.findOnPath( names, good.getPath( ), cwd ).isAbsolute( ) );
        check( "skips the working directory", ExecutableLocator.findOnPath( names, cwd + sep + other, cwd ) == null );
        check( "cwd listed first still loses to later real one", real.equals( ExecutableLocator.findOnPath( names, cwd + sep + good, cwd ) ) );
        check( "skips relative entries", ExecutableLocator.findOnPath( names, "cwd", root ) == null );
        check( "skips empty entries", ExecutableLocator.findOnPath( names, sep + sep, cwd ) == null );
        check( "dot entry is relative", ExecutableLocator.findOnPath( names, ".", cwd ) == null );
        check( "quoted entry ok", real.equals( ExecutableLocator.findOnPath( names, "\"" + good + "\"", null ) ) );
        check( "second name tried", touch( other, "python" ).equals( ExecutableLocator.findOnPath( names, other.getPath( ), cwd ) ) );
        check( "null PATH", ExecutableLocator.findOnPath( names, null, cwd ) == null );
        check( "empty PATH", ExecutableLocator.findOnPath( names, "", cwd ) == null );
        check( "dir named like exe is not a match", ExecutableLocator.findOnPath( new String[]{ "sub" }, root.getPath( ), cwd ) == null && new File( root, "sub" ).mkdir( ) );

        // cleanup
        for( final File f : new File[]{ planted, real, new File( other, "python" ), new File( root, "sub" ) } ) f.delete( );
        cwd.delete( ); good.delete( ); other.delete( ); root.delete( );

        if( failures > 0 ) { System.err.println( failures + " failure(s)" ); System.exit( 1 ); }
        System.out.println( "ExecutableLocatorTest: all passed" );
    }
}
