package com.group_finity.mascot.assistant;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.logging.Logger;

/**
 * Fetches current weather from Open-Meteo (no API key required).
 * Results are cached for 10 minutes to avoid hammering the API.
 *
 * Location is read from WeatherLocation in settings.properties:
 *   "auto"         -- IP geolocation via ipapi.co
 *   "Kahului, HI"  -- geocoded via Open-Meteo geocoding API
 */
public class WeatherTool
{
    private static final Logger log = Logger.getLogger( WeatherTool.class.getName() );

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int  CONNECT_MS   = 4_000;
    private static final int  READ_MS      = 6_000;

    private static volatile String cachedResult    = null;
    private static volatile long   cacheTime       = 0L;
    private static volatile String cachedLocation  = null;
    private static volatile String cachedPlaceName = null;

    /** Returns the place name from the last successful fetch, or null if never fetched. */
    public static String getCachedPlaceName()
    {
        return cachedPlaceName;
    }

    /** Returns the full weather string from the last successful fetch, or null if never fetched. */
    public static String getCachedResult()
    {
        return cachedResult;
    }

    /** Returns a short weather description, or null if unavailable. */
    public static String fetch( final String location )
    {
        final long now = System.currentTimeMillis();
        if( cachedResult != null
                && now - cacheTime < CACHE_TTL_MS
                && location.equals( cachedLocation ) )
            return cachedResult;

        log.warning( "[WeatherTool] fetching, location=" + location );
        try
        {
            double lat, lon;
            String placeName;

            if( "auto".equalsIgnoreCase( location.trim() ) )
            {
                // ip-api.com is plain HTTP — no TLS, avoids the SSL handshake hang on some JREs
                String geoJson = httpGet( "http://ip-api.com/json/?fields=status,lat,lon,city,regionName" );
                if( geoJson == null || !geoJson.contains( "\"success\"" ) )
                {
                    log.warning( "[WeatherTool] ip-api.com failed, trying ipapi.co" );
                    geoJson = httpGet( "https://ipapi.co/json/" );
                }
                if( geoJson == null )
                {
                    log.warning( "[WeatherTool] both geo sources returned null" );
                    return null;
                }
                // ip-api.com: lat/lon/city/regionName  |  ipapi.co: latitude/longitude/city/region
                lat = parseDouble( geoJson, "lat" );
                if( Double.isNaN( lat ) ) lat = parseDouble( geoJson, "latitude" );
                lon = parseDouble( geoJson, "lon" );
                if( Double.isNaN( lon ) ) lon = parseDouble( geoJson, "longitude" );
                placeName = parseString( geoJson, "city" );
                String region = parseString( geoJson, "regionName" );
                if( region == null ) region = parseString( geoJson, "region" );
                if( placeName != null && region != null )
                    placeName = placeName + ", " + region;
            }
            else
            {
                final String enc    = URLEncoder.encode( location.trim(), "UTF-8" );
                final String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + enc + "&count=1&language=en&format=json";
                final String geoJson = httpGet( geoUrl );
                if( geoJson == null || !geoJson.contains( "\"results\"" ) )
                {
                    log.warning( "[WeatherTool] geocoding null/no-results for: " + location );
                    return null;
                }
                final int ri    = geoJson.indexOf( "\"results\"" );
                final String inner = geoJson.substring( ri );
                lat       = parseDouble( inner, "latitude" );
                lon       = parseDouble( inner, "longitude" );
                placeName = parseString( inner, "name" );
                final String admin = parseString( inner, "admin1" );
                if( placeName != null && admin != null )
                    placeName = placeName + ", " + admin;
            }

            if( Double.isNaN( lat ) || Double.isNaN( lon ) )
            {
                log.warning( "[WeatherTool] NaN lat/lon after geo parse" );
                return null;
            }

            final String wxUrl = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature"
                + ",relative_humidity_2m,weathercode,windspeed_10m"
                + "&temperature_unit=fahrenheit&windspeed_unit=mph&timezone=auto";

            final String wxJson = httpGet( wxUrl );
            if( wxJson == null )
            {
                log.warning( "[WeatherTool] Open-Meteo returned null (non-200 or network fail)" );
                return null;
            }
            // Isolate the "current" block so parseDouble doesn't hit "current_units" keys first.
            // "current_units" has string values like "°F"; "current" has the actual numbers.
            final int curIdx = wxJson.indexOf( "\"current\":" );
            final String curBlock = curIdx >= 0 ? wxJson.substring( curIdx ) : wxJson;

            final double temp     = parseDouble( curBlock, "temperature_2m" );
            final double feels    = parseDouble( curBlock, "apparent_temperature" );
            final double humidity = parseDouble( curBlock, "relative_humidity_2m" );
            final double wind     = parseDouble( curBlock, "windspeed_10m" );
            final int    code     = (int) parseDouble( curBlock, "weathercode" );

            if( Double.isNaN( temp ) )
            {
                log.warning( "[WeatherTool] NaN temp after wx parse" );
                return null;
            }

            final StringBuilder sb = new StringBuilder();
            if( placeName != null ) sb.append( placeName ).append( ": " );
            sb.append( Math.round( temp ) ).append( "\u00b0F" );
            if( !Double.isNaN( feels ) && Math.abs( feels - temp ) >= 3 )
                sb.append( " (feels " ).append( Math.round( feels ) ).append( "\u00b0F)" );
            sb.append( ", " ).append( wmoDescription( code ) );
            if( !Double.isNaN( humidity ) )
                sb.append( ", " ).append( Math.round( humidity ) ).append( "% humidity" );
            if( !Double.isNaN( wind ) && wind >= 1 )
                sb.append( ", " ).append( Math.round( wind ) ).append( " mph wind" );

            final String result = sb.toString();
            cachedResult    = result;
            cacheTime       = System.currentTimeMillis();
            cachedLocation  = location;
            cachedPlaceName = placeName;
            log.warning( "[WeatherTool] " + result );
            return result;
        }
        catch( final Exception e )
        {
            log.warning( "[WeatherTool] fetch failed: " + e.getMessage() );
            return null;
        }
    }

    private static String httpGet( final String urlStr ) throws Exception
    {
        final URL url = new URL( urlStr );
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout( CONNECT_MS );
        conn.setReadTimeout( READ_MS );
        conn.setRequestProperty( "User-Agent", "Shimeji/1.0" );
        conn.connect();
        if( conn.getResponseCode() != 200 ) return null;
        final InputStream in = conn.getInputStream();
        final StringBuilder sb = new StringBuilder();
        final byte[] buf = new byte[ 4096 ];
        int n;
        while( ( n = in.read( buf ) ) > 0 )
            sb.append( new String( buf, 0, n, "UTF-8" ) );
        in.close();
        conn.disconnect();
        return sb.toString();
    }

    private static double parseDouble( final String json, final String key )
    {
        final String search = "\"" + key + "\":";
        final int idx = json.indexOf( search );
        if( idx < 0 ) return Double.NaN;
        int start = idx + search.length();
        while( start < json.length() && json.charAt( start ) == ' ' ) start++;
        int end = start;
        while( end < json.length() )
        {
            final char c = json.charAt( end );
            if( c == ',' || c == '}' || c == '\n' || c == ' ' ) break;
            end++;
        }
        try { return Double.parseDouble( json.substring( start, end ).trim() ); }
        catch( final NumberFormatException e ) { return Double.NaN; }
    }

    private static String parseString( final String json, final String key )
    {
        final String search = "\"" + key + "\":\"";
        final int idx = json.indexOf( search );
        if( idx < 0 ) return null;
        final int start = idx + search.length();
        final int end   = json.indexOf( '"', start );
        return end < 0 ? null : json.substring( start, end );
    }

    private static String wmoDescription( final int code )
    {
        if( code == 0 )           return "clear sky";
        if( code <= 3 )           return "partly cloudy";
        if( code <= 48 )          return "foggy";
        if( code <= 57 )          return "drizzle";
        if( code <= 67 )          return "rain";
        if( code <= 77 )          return "snow";
        if( code <= 82 )          return "showers";
        if( code <= 99 )          return "thunderstorm";
        return "unknown conditions";
    }
}
