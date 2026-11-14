package com.group_finity.mascot.shop;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free tests for the shop: catalog parsing and the safe zip installer. Exits non-zero on failure. */
public final class ShopTest
{
    private static int failures = 0;

    private static void eq( final String what, final Object expected, final Object actual )
    {
        if( !java.util.Objects.equals( expected, actual ) )
        {
            failures++;
            System.err.println( "FAIL " + what + ": expected <" + expected + "> got <" + actual + ">" );
        }
    }

    private static Path zip( final Path dir, final String file, final String... pairs ) throws Exception
    {
        final Path z = dir.resolve( file );
        try( ZipOutputStream o = new ZipOutputStream( Files.newOutputStream( z ) ) )
        {
            for( int i = 0; i < pairs.length; i += 2 )
            {
                o.putNextEntry( new ZipEntry( pairs[ i ] ) );
                o.write( pairs[ i + 1 ].getBytes( "UTF-8" ) );
                o.closeEntry();
            }
        }
        return z;
    }

    private static String refusal( final Path z, final Path img )
    {
        try { MascotPackage.installZip( z, img, "Fallback" ); return null; }
        catch( final Exception e ) { return e.getMessage(); }
    }

    public static void main( final String[] args ) throws Exception
    {
        final String sha = "a".repeat( 64 );
        final ShopCatalog c = ShopCatalog.parse( "{\"mascots\":[" +
            "{\"id\":\"Kopa\",\"name\":\"Kopa\",\"creator\":\"Cachomon\",\"price\":\"Free\",\"mode\":\"link\",\"pageUrl\":\"https://cachomon.com/x\"}," +
            "{\"name\":\"One Click\",\"mode\":\"install\",\"downloadUrl\":\"https://example.com/a.zip\",\"sha256\":\"" + sha + "\"}," +
            "{\"name\":\"No Sum\",\"mode\":\"install\",\"downloadUrl\":\"https://example.com/a.zip\",\"pageUrl\":\"https://example.com/p\"}," +
            "{\"name\":\"Http Dl\",\"mode\":\"install\",\"downloadUrl\":\"http://example.com/a.zip\",\"sha256\":\"" + sha + "\",\"pageUrl\":\"https://example.com/p\"}," +
            "{\"name\":\"Evil\",\"mode\":\"link\",\"pageUrl\":\"javascript:alert(1)\"}," +
            "{\"name\":\"Paid\",\"price\":\"$5\",\"buyUrl\":\"https://patreon.com/x\"}," +
            "{\"name\":\"kopa\",\"pageUrl\":\"https://example.com\"}," +
            "42, {\"mode\":\"link\"}]}" );
        final List<ShopEntry> e = c.entries();
        eq( "kept entries", 5, e.size() );                      // Kopa, One Click, No Sum, Http Dl, Paid (Evil/dup/junk dropped)
        eq( "kopa mode", ShopEntry.Mode.LINK, e.get( 0 ).mode );
        eq( "one-click mode", ShopEntry.Mode.INSTALL, e.get( 1 ).mode );
        eq( "no checksum degrades to link", ShopEntry.Mode.LINK, e.get( 2 ).mode );
        eq( "degraded keeps no download", "", e.get( 2 ).downloadUrl );
        eq( "paid", false, e.get( 4 ).isFree() );
        eq( "paid opens buy url", "https://patreon.com/x", e.get( 4 ).openUrl() );
        eq( "free", true, e.get( 0 ).isFree() );

        try { ShopCatalog.parse( "{\"nope\":1}" ); eq( "bad catalog throws", true, false ); } catch( final IllegalArgumentException ex ) { }
        try { ShopCatalog.parse( "{\"mascots\":[}" ); eq( "bad json throws", true, false ); } catch( final IllegalArgumentException ex ) { }
        try { ShopCatalog.parse( "[".repeat( 100 ) ); eq( "deep json throws", true, false ); } catch( final IllegalArgumentException ex ) { }

        eq( "sanitize", "Hornet", MascotPackage.sanitizeName( "Hornet" ) );
        eq( "traversal name refused", null, MascotPackage.sanitizeName( "../x" ) );
        eq( "http download degrades to link", ShopEntry.Mode.LINK, e.get( 3 ).mode );
        eq( "reserved", null, MascotPackage.sanitizeName( "CON" ) );
        eq( "unused", null, MascotPackage.sanitizeName( "unused" ) );
        eq( "dot", null, MascotPackage.sanitizeName( ".hidden" ) );

        final Path root = Files.createTempDirectory( "shoptest" );
        final Path img = root.resolve( "img" );
        final String A = "<Mascot/>";

        // folder at top level
        List<String> r = MascotPackage.installZip( zip( root, "a.zip", "Foo/conf/actions.xml", A, "Foo/conf/behaviors.xml", A, "Foo/shime1.png", "x" ), img, "ignored" );
        eq( "installed folder name", List.of( "Foo" ), r );
        eq( "files landed", true, Files.exists( img.resolve( "Foo/conf/actions.xml" ) ) && Files.exists( img.resolve( "Foo/shime1.png" ) ) );
        eq( "refuses overwrite", true, refusal( zip( root, "a2.zip", "Foo/conf/actions.xml", A ), img ).contains( "already installed" ) );

        // conf at root uses the fallback name; img/<name> layout; two mascots
        eq( "root layout", List.of( "Rooty" ), MascotPackage.installZip( zip( root, "b.zip", "conf/actions.xml", A, "s.png", "x" ), img, "Rooty" ) );
        eq( "img layout multi", List.of( "P1", "P2" ), MascotPackage.installZip( zip( root, "c.zip", "img/P1/conf/actions.xml", A, "img/P2/conf/actions.xml", A ), img, "z" ) );

        // refusals
        eq( "zip slip", true, refusal( zip( root, "d.zip", "../evil.txt", "x", "Q/conf/actions.xml", A ), img ).contains( "unsafe" ) );
        eq( "absolute path", true, refusal( zip( root, "d2.zip", "/evil.txt", "x" ), img ).contains( "unsafe" ) );
        eq( "drive path", true, refusal( zip( root, "d3.zip", "C:/evil.txt", "x" ), img ).contains( "unsafe" ) );
        eq( "exe refused", true, refusal( zip( root, "e.zip", "Bad/conf/actions.xml", A, "Bad/run.EXE", "x" ), img ).contains( "program file" ) );
        eq( "not a mascot", true, refusal( zip( root, "f.zip", "readme.txt", "hi" ), img ).contains( "doesn't contain a mascot" ) );
        eq( "not a zip", true, refusal( Files.write( root.resolve( "g.zip" ), "nope".getBytes() ), img ).contains( "valid zip" ) );
        eq( "nothing from refused zips", false, Files.exists( img.resolve( "Bad" ) ) || Files.exists( img.resolve( "Q" ) ) || Files.exists( root.resolve( "evil.txt" ) ) );

        // no leftovers (temp dirs) in img
        try( java.util.stream.Stream<Path> s = Files.list( img ) )
        {
            eq( "no temp leftovers", 0L, s.filter( p -> p.getFileName().toString().startsWith( ".shop-" ) ).count() );
        }
        MascotPackage.deleteTree( root );

        if( failures > 0 ) { System.err.println( failures + " failure(s)" ); System.exit( 1 ); }
        System.out.println( "ShopTest OK" );
    }
}
