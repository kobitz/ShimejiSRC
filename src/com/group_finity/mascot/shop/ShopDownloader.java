package com.group_finity.mascot.shop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.function.LongConsumer;

/** HTTPS fetches for the shop: size-capped, redirect-limited, checksum-verified. */
public final class ShopDownloader
{
    static final long MAX_CATALOG_BYTES = 1024 * 1024;
    static final long MAX_PACKAGE_BYTES = 400L * 1024 * 1024;

    private ShopDownloader() { }

    private static HttpURLConnection open( final String url ) throws IOException
    {
        String cur = url;
        for( int hop = 0; hop < 5; hop++ )
        {
            if( !ShopEntry.isWebUrl( cur, true ) ) throw new IOException( "Refusing non-https address." );
            final HttpURLConnection c = (HttpURLConnection) new URL( cur ).openConnection();
            c.setInstanceFollowRedirects( false );
            c.setConnectTimeout( 10_000 );
            c.setReadTimeout( 20_000 );
            c.setRequestProperty( "User-Agent", "ShimejiSRC-Shop" );
            final int code = c.getResponseCode();
            if( code >= 300 && code < 400 )
            {
                final String loc = c.getHeaderField( "Location" );
                c.disconnect();
                if( loc == null ) throw new IOException( "Redirect without a location." );
                cur = new URL( new URL( cur ), loc ).toString();   // resolves relative redirects
                continue;
            }
            if( code != 200 ) { c.disconnect(); throw new IOException( "Server answered HTTP " + code + "." ); }
            return c;
        }
        throw new IOException( "Too many redirects." );
    }

    /** Fetches the catalog JSON text. */
    public static String fetchText( final String url ) throws IOException
    {
        final HttpURLConnection c = open( url );
        try( InputStream in = c.getInputStream() )
        {
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[ 8192 ];
            int n;
            while( ( n = in.read( buf ) ) > 0 )
            {
                bos.write( buf, 0, n );
                if( bos.size() > MAX_CATALOG_BYTES ) throw new IOException( "Catalog is too large." );
            }
            return new String( bos.toByteArray(), StandardCharsets.UTF_8 );
        }
        finally { c.disconnect(); }
    }

    /**
     * Downloads {@code url} to {@code dest} and verifies its SHA-256; deletes the file and throws on mismatch.
     * @param progress receives total bytes read so far
     */
    public static void downloadVerified( final String url, final Path dest, final String sha256Hex, final LongConsumer progress ) throws IOException
    {
        final HttpURLConnection c = open( url );
        try( InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream( dest ) )
        {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            final byte[] buf = new byte[ 64 * 1024 ];
            long total = 0;
            int n;
            while( ( n = in.read( buf ) ) > 0 )
            {
                total += n;
                if( total > MAX_PACKAGE_BYTES ) throw new IOException( "Download is larger than allowed." );
                md.update( buf, 0, n );
                out.write( buf, 0, n );
                if( progress != null ) progress.accept( total );
            }
            final StringBuilder hex = new StringBuilder();
            for( final byte b : md.digest() ) hex.append( String.format( "%02x", b ) );
            if( !hex.toString().equalsIgnoreCase( sha256Hex ) )
                throw new IOException( "Checksum mismatch - the file is not the one the catalog lists. Nothing was installed." );
        }
        catch( final java.security.NoSuchAlgorithmException e )
        {
            throw new IOException( e );
        }
        catch( final IOException e )
        {
            try { Files.deleteIfExists( dest ); } catch( final IOException ignored ) { }
            throw e;
        }
        finally { c.disconnect(); }
    }
}
