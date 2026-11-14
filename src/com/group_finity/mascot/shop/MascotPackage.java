package com.group_finity.mascot.shop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safely installs a downloaded mascot zip into {@code img/}. A mascot zip is untrusted input, so:
 * paths that escape the extraction folder are refused (zip-slip), executables and scripts are
 * refused, entry count and real (not declared) unpacked size are capped, and nothing lands in
 * {@code img/} until the whole archive has extracted cleanly and every target name is free.
 * Existing mascots are never overwritten (that would wipe their memory.json).
 */
public final class MascotPackage
{
    static final int  MAX_ENTRIES     = 5000;
    static final long MAX_TOTAL_BYTES = 600L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 150L * 1024 * 1024;

    /** Things a mascot never needs and a malicious zip would love to plant. */
    private static final Set<String> BANNED_EXT = new HashSet<>( java.util.Arrays.asList(
        "exe", "dll", "bat", "cmd", "com", "scr", "msi", "jar", "ps1", "psm1", "vbs", "vbe", "wsf", "wsh",
        "lnk", "hta", "cpl", "reg", "sh", "pif", "jse", "msc", "appx", "apk" ) );
    private static final Set<String> RESERVED = new HashSet<>( java.util.Arrays.asList(
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "unused" ) );

    private MascotPackage() { }

    /** A safe image-set folder name, or null if nothing usable is left. */
    public static String sanitizeName( final String raw )
    {
        if( raw == null ) return null;
        String s = raw.replaceAll( "[^A-Za-z0-9 _().'-]", "" ).trim();
        while( s.endsWith( "." ) || s.endsWith( " " ) ) s = s.substring( 0, s.length() - 1 );
        if( s.isEmpty() || s.length() > 60 || s.startsWith( "." ) ) return null;
        if( RESERVED.contains( s.toLowerCase( Locale.ROOT ) ) ) return null;
        return s;
    }

    /**
     * Installs every mascot found in {@code zip} under {@code imgDir}.
     *
     * @param fallbackName folder name to use when the zip has {@code conf/} at its top level
     * @return the installed image-set names
     * @throws IOException with a user-readable message when the zip is refused
     */
    public static List<String> installZip( final Path zip, final Path imgDir, final String fallbackName ) throws IOException
    {
        Files.createDirectories( imgDir );
        final Path tmp = Files.createTempDirectory( imgDir, ".shop-" );   // dot-prefixed: the chooser ignores it
        try
        {
            extract( zip, tmp );
            final List<Path> roots = findMascotRoots( tmp );
            if( roots.isEmpty() )
                throw new IOException( "This zip doesn't contain a mascot (no conf/actions.xml found)." );

            final List<String> names = new ArrayList<>();
            for( final Path r : roots )
            {
                final String n = r.equals( tmp ) ? sanitizeName( fallbackName ) : sanitizeName( r.getFileName().toString() );
                if( n == null ) throw new IOException( "The mascot folder has no usable name." );
                if( names.contains( n ) ) throw new IOException( "The zip contains two mascots named " + n + "." );
                if( Files.exists( imgDir.resolve( n ) ) )
                    throw new IOException( n + " is already installed. Remove its folder from img/ first (this keeps your memory.json safe until you decide)." );
                names.add( n );
            }
            for( int k = 0; k < roots.size(); k++ )
                moveInto( roots.get( k ), imgDir.resolve( names.get( k ) ), tmp );
            return names;
        }
        finally
        {
            deleteTree( tmp );
        }
    }

    private static void moveInto( final Path src, final Path dest, final Path tmp ) throws IOException
    {
        if( src.equals( tmp ) )
        {
            Files.createDirectory( dest );
            try( DirectoryStream<Path> ds = Files.newDirectoryStream( tmp ) )
            {
                for( final Path p : ds ) Files.move( p, dest.resolve( p.getFileName() ) );
            }
        }
        else
        {
            Files.move( src, dest );
        }
    }

    private static void extract( final Path zip, final Path into ) throws IOException
    {
        long total = 0;
        int count = 0;
        final Path base = into.toAbsolutePath().normalize();
        try( ZipFile zf = new ZipFile( zip.toFile() ) )
        {
            final Enumeration<? extends ZipEntry> en = zf.entries();
            while( en.hasMoreElements() )
            {
                final ZipEntry e = en.nextElement();
                if( ++count > MAX_ENTRIES ) throw new IOException( "The zip has too many files." );
                final String name = e.getName().replace( '\\', '/' );
                if( name.startsWith( "/" ) || name.contains( ":" ) )
                    throw new IOException( "Refusing unsafe path in zip: " + name );
                for( final String seg : name.split( "/" ) )
                    if( seg.equals( ".." ) ) throw new IOException( "Refusing unsafe path in zip: " + name );
                if( name.startsWith( "__MACOSX/" ) ) continue;

                final Path out = base.resolve( name ).normalize();
                if( !out.startsWith( base ) ) throw new IOException( "Refusing unsafe path in zip: " + name );

                if( e.isDirectory() ) { Files.createDirectories( out ); continue; }

                final int dot = name.lastIndexOf( '.' );
                final String ext = dot < 0 ? "" : name.substring( dot + 1 ).toLowerCase( Locale.ROOT );
                if( BANNED_EXT.contains( ext ) )
                    throw new IOException( "Refusing program file in a mascot zip: " + name );

                Files.createDirectories( out.getParent() );
                try( InputStream in = zf.getInputStream( e ); OutputStream os = Files.newOutputStream( out ) )
                {
                    final byte[] buf = new byte[ 64 * 1024 ];
                    long written = 0;
                    int n;
                    while( ( n = in.read( buf ) ) > 0 )
                    {
                        written += n;
                        total += n;
                        if( written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES )
                            throw new IOException( "The zip unpacks to far more data than a mascot should." );
                        os.write( buf, 0, n );
                    }
                }
            }
        }
        catch( final java.util.zip.ZipException ze )
        {
            throw new IOException( "That file isn't a valid zip: " + ze.getMessage() );
        }
    }

    /** Outermost directories that contain conf/actions.xml (the extraction root counts). */
    static List<Path> findMascotRoots( final Path tmp ) throws IOException
    {
        final List<Path> found = new ArrayList<>();
        try( java.util.stream.Stream<Path> s = Files.walk( tmp ) )
        {
            s.filter( Files::isDirectory )
             .filter( d -> Files.isRegularFile( d.resolve( "conf" ).resolve( "actions.xml" ) ) )
             .forEach( found::add );
        }
        Collections.sort( found );
        final List<Path> outer = new ArrayList<>();
        for( final Path p : found )
        {
            boolean inside = false;
            for( final Path o : outer ) if( p.startsWith( o ) ) { inside = true; break; }
            if( !inside ) outer.add( p );
        }
        return outer;
    }

    static void deleteTree( final Path p )
    {
        try( java.util.stream.Stream<Path> s = Files.walk( p ) )
        {
            s.sorted( Collections.reverseOrder() ).forEach( x -> { try { Files.deleteIfExists( x ); } catch( final IOException ignored ) { } } );
        }
        catch( final IOException ignored ) { }
    }
}
