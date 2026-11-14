package com.group_finity.mascot.shop;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One mascot listing in the shop catalog.
 * <ul>
 *   <li>{@code install} - free and redistributable: the shop downloads {@code downloadUrl}
 *       (https only), verifies {@code sha256}, and installs it with one click.</li>
 *   <li>{@code link} - the creator hosts it (Cachomon, DeviantArt, Patreon, Ko-fi ...): the shop
 *       only opens {@code pageUrl}/{@code buyUrl} in the browser; the user downloads the zip there
 *       and brings it back with "Import .zip".</li>
 * </ul>
 */
public final class ShopEntry
{
    public enum Mode { INSTALL, LINK }

    private static final Pattern SHA256 = Pattern.compile( "[0-9a-fA-F]{64}" );

    public final String id, name, creator, description, source, price;
    public final String pageUrl, buyUrl, downloadUrl, sha256;
    public final List<String> tags;
    public final Mode mode;

    private ShopEntry( final String id, final String name, final String creator, final String description,
                       final String source, final String price, final String pageUrl, final String buyUrl,
                       final String downloadUrl, final String sha256, final List<String> tags, final Mode mode )
    {
        this.id = id; this.name = name; this.creator = creator; this.description = description;
        this.source = source; this.price = price; this.pageUrl = pageUrl; this.buyUrl = buyUrl;
        this.downloadUrl = downloadUrl; this.sha256 = sha256; this.tags = tags; this.mode = mode;
    }

    public boolean isFree()
    {
        final String p = price.trim().toLowerCase( Locale.ROOT );
        return p.isEmpty() || p.equals( "free" ) || p.equals( "0" ) || p.equals( "$0" );
    }

    /** The page to open for "Get it from the creator" (buy link first, then the listing page). */
    public String openUrl() { return !buyUrl.isEmpty() ? buyUrl : pageUrl; }

    /** http(s) only: no file:, javascript:, or hostless URLs ever reach the browser or the downloader. */
    static boolean isWebUrl( final String u, final boolean httpsOnly )
    {
        if( u == null || u.isEmpty() || u.length() > 1000 ) return false;
        try
        {
            final URI uri = new URI( u );
            final String sc = uri.getScheme();
            if( sc == null || uri.getHost() == null ) return false;
            return sc.equalsIgnoreCase( "https" ) || ( !httpsOnly && sc.equalsIgnoreCase( "http" ) );
        }
        catch( final Exception e ) { return false; }
    }

    private static String str( final Map<?, ?> m, final String k, final int max )
    {
        final Object o = m.get( k );
        if( !( o instanceof String ) ) return "";
        final String s = ( (String) o ).trim();
        return s.length() > max ? s.substring( 0, max ) : s;
    }

    /** Builds an entry from a catalog object, or returns null if it is unusable. Never throws. */
    static ShopEntry from( final Object o )
    {
        if( !( o instanceof Map ) ) return null;
        final Map<?, ?> m = (Map<?, ?>) o;
        final String name = str( m, "name", 80 );
        final String id = MascotPackage.sanitizeName( str( m, "id", 80 ).isEmpty() ? name : str( m, "id", 80 ) );
        if( name.isEmpty() || id == null ) return null;

        String page = str( m, "pageUrl", 1000 );
        String buy = str( m, "buyUrl", 1000 );
        String dl = str( m, "downloadUrl", 1000 );
        final String sha = str( m, "sha256", 64 );
        if( !isWebUrl( page, false ) ) page = "";
        if( !isWebUrl( buy, false ) ) buy = "";

        Mode mode = "install".equalsIgnoreCase( str( m, "mode", 10 ) ) ? Mode.INSTALL : Mode.LINK;
        // A one-click install needs an https download AND a checksum; anything less becomes a plain link.
        if( mode == Mode.INSTALL && ( !isWebUrl( dl, true ) || !SHA256.matcher( sha ).matches() ) ) mode = Mode.LINK;
        if( mode == Mode.LINK && page.isEmpty() && buy.isEmpty() ) return null;
        if( mode == Mode.LINK ) dl = "";

        final List<String> tags = new ArrayList<>();
        if( m.get( "tags" ) instanceof List )
            for( final Object t : (List<?>) m.get( "tags" ) )
                if( t instanceof String && tags.size() < 8 && !( (String) t ).trim().isEmpty() )
                    tags.add( ( (String) t ).trim() );

        return new ShopEntry( id, name, str( m, "creator", 80 ), str( m, "description", 600 ), str( m, "source", 40 ),
                              str( m, "price", 20 ), page, buy, dl, sha.toLowerCase( Locale.ROOT ), tags, mode );
    }
}
