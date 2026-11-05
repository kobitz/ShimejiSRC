package com.group_finity.mascot;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class VideoAreaServer
{
    private static final Logger log = Logger.getLogger( VideoAreaServer.class.getName() );
    private static final int PORT = 41221;

    /** Largest request body accepted (the real payloads are ~40 bytes). */
    private static final int MAX_BODY_BYTES = 2048;
    /** Caps on registered ids so a misbehaving client cannot grow the maps without bound. */
    private static final int MAX_IDS    = 64;
    private static final int MAX_ID_LEN = 64;
    /**
     * Sites whose pages may talk to this server (the shimeji-video-tracker content script runs
     * inside these pages, so requests carry the page's Origin). Browser-extension origins are
     * always allowed. Override with the comma-separated {@code VideoAreaAllowedHosts} setting
     * (host suffixes, e.g. "youtube.com,twitch.tv,netflix.com"); read once at startup.
     */
    private static final String DEFAULT_ALLOWED_HOSTS = "youtube.com,twitch.tv";

    private static VideoAreaServer instance;
    public  static VideoAreaServer getInstance() { return instance; }

    private HttpServer server;
    private final ConcurrentHashMap<String, Rectangle> areas = new ConcurrentHashMap<>();
    private volatile List<Rectangle> snapshot = Collections.emptyList();

    /** Browser tabs currently in fullscreen: tabId -> screen Rectangle of the browser window. */
    private final ConcurrentHashMap<String, Rectangle> fullscreenAreas = new ConcurrentHashMap<>();
    private volatile List<Rectangle> fullscreenSnapshot = Collections.emptyList();

    public static void start()
    {
        start( null );
    }

    /**
     * @param allowedHostsCsv value of the {@code VideoAreaAllowedHosts} setting (comma-separated
     *                        host suffixes), or null/blank for the defaults. Read once here so the
     *                        request threads never depend on {@code Main}; changing it needs a restart.
     */
    public static void start( String allowedHostsCsv )
    {
        if( instance != null ) return;
        instance = new VideoAreaServer();
        if( allowedHostsCsv != null && !allowedHostsCsv.trim().isEmpty() )
            instance.allowedHosts = parseHosts( allowedHostsCsv );
        instance.startServer();
    }

    /** Host suffixes (lower-case) whose pages may call this server; see DEFAULT_ALLOWED_HOSTS. */
    private volatile String[] allowedHosts = parseHosts( DEFAULT_ALLOWED_HOSTS );

    private void startServer()
    {
        try
        {
            server = HttpServer.create( new InetSocketAddress( "127.0.0.1", PORT ), 0 );
            server.createContext( "/video",      this::handleVideo );
            server.createContext( "/fullscreen", this::handleFullscreen );
            server.setExecutor( Executors.newSingleThreadExecutor( r -> {
                Thread t = new Thread( r, "VideoAreaServer" );
                t.setDaemon( true );
                return t;
            }));
            server.start();
            log.info( "VideoAreaServer listening on http://127.0.0.1:" + PORT );
            System.out.println( "[Shimeji] VideoAreaServer started on port " + PORT );
        }
        catch( Exception e )
        {
            System.err.println( "[Shimeji] VideoAreaServer failed to start: " + e.getMessage() );
            log.warning( "VideoAreaServer failed to start: " + e.getMessage() );
        }
    }

    private void handleVideo( HttpExchange ex ) throws IOException
    {
        if( !checkOrigin( ex ) ) return;
        addCors( ex );
        String method = ex.getRequestMethod();

        if( "OPTIONS".equals( method ) )
        {
            ex.sendResponseHeaders( 204, -1 ); return;
        }

        if( "GET".equals( method ) )
        {
            StringBuilder sb = new StringBuilder( "Registered video areas:\n" );
            for( Map.Entry<String,Rectangle> e : areas.entrySet() )
                sb.append( e.getKey() ).append( ": " ).append( e.getValue() ).append( "\n" );
            byte[] bytes = sb.toString().getBytes();
            ex.sendResponseHeaders( 200, bytes.length );
            ex.getResponseBody().write( bytes );
        }
        else if( "POST".equals( method ) )
        {
            String body = readBody( ex );
            if( body == null ) { ex.sendResponseHeaders( 413, -1 ); ex.getResponseBody().close(); return; }
            Map<String,String> p = parseForm( body );
            try
            {
                String id = p.getOrDefault( "id", "video" );
                if( !idOk( id, areas ) ) throw new IllegalArgumentException( "bad id" );
                int x = Integer.parseInt( p.get( "x" ) );
                int y = Integer.parseInt( p.get( "y" ) );
                int w = Integer.parseInt( p.get( "w" ) );
                int h = Integer.parseInt( p.get( "h" ) );
                areas.put( id, new Rectangle( x, y, w, h ) );
                rebuildSnapshot();
                System.out.println( "[Shimeji] Video area registered: " + id + " " + x + "," + y + " " + w + "x" + h );
                ex.sendResponseHeaders( 200, 0 );
            }
            catch( Exception e )
            {
                System.err.println( "[Shimeji] Bad POST body: " + body );
                ex.sendResponseHeaders( 400, 0 );
            }
        }
        else if( "DELETE".equals( method ) )
        {
            String query = ex.getRequestURI().getQuery();
            Map<String,String> p = parseForm( query != null ? query : "" );
            String id = p.getOrDefault( "id", "video" );
            areas.remove( id );
            rebuildSnapshot();
            System.out.println( "[Shimeji] Video area removed: " + id );
            ex.sendResponseHeaders( 200, 0 );
        }
        else
        {
            ex.sendResponseHeaders( 405, 0 );
        }

        ex.getResponseBody().close();
    }

    /**
     * /fullscreen endpoint — called by the browser extension when a tab enters
     * or exits browser fullscreen (F11 / video fullscreen button).
     *
     * POST  id=<tabId>&x=<screenX>&y=<screenY>&w=<width>&h=<height>
     *   Registers the browser window's screen rect as a fullscreen area.
     *   WindowsEnvironment.beginTick() will find which monitor this covers
     *   and treat that monitor as having a fullscreen app.
     *
     * DELETE ?id=<tabId>
     *   Removes the fullscreen registration for that tab.
     */
    private void handleFullscreen( HttpExchange ex ) throws IOException
    {
        if( !checkOrigin( ex ) ) return;
        addCors( ex );
        String method = ex.getRequestMethod();

        if( "OPTIONS".equals( method ) )
        {
            ex.sendResponseHeaders( 204, -1 ); return;
        }

        if( "POST".equals( method ) )
        {
            String body = readBody( ex );
            if( body == null ) { ex.sendResponseHeaders( 413, -1 ); ex.getResponseBody().close(); return; }
            Map<String,String> p = parseForm( body );
            try
            {
                String id = p.getOrDefault( "id", "browser" );
                if( !idOk( id, fullscreenAreas ) ) throw new IllegalArgumentException( "bad id" );
                int x = Integer.parseInt( p.get( "x" ) );
                int y = Integer.parseInt( p.get( "y" ) );
                int w = Integer.parseInt( p.get( "w" ) );
                int h = Integer.parseInt( p.get( "h" ) );
                fullscreenAreas.put( id, new Rectangle( x, y, w, h ) );
                rebuildFullscreenSnapshot();
                System.out.println( "[Shimeji] Browser fullscreen registered: " + id + " " + x + "," + y + " " + w + "x" + h );
                ex.sendResponseHeaders( 200, 0 );
            }
            catch( Exception e )
            {
                System.err.println( "[Shimeji] Bad fullscreen POST body: " + body );
                ex.sendResponseHeaders( 400, 0 );
            }
        }
        else if( "DELETE".equals( method ) )
        {
            String query = ex.getRequestURI().getQuery();
            Map<String,String> p = parseForm( query != null ? query : "" );
            String id = p.getOrDefault( "id", "browser" );
            fullscreenAreas.remove( id );
            rebuildFullscreenSnapshot();
            System.out.println( "[Shimeji] Browser fullscreen cleared: " + id );
            ex.sendResponseHeaders( 200, 0 );
        }
        else
        {
            ex.sendResponseHeaders( 405, 0 );
        }

        ex.getResponseBody().close();
    }

    private void addCors( HttpExchange ex )
    {
        // Only reached after checkOrigin() passed, so echoing the Origin back is safe.
        final String origin = ex.getRequestHeaders().getFirst( "Origin" );
        if( origin != null )
        {
            ex.getResponseHeaders().add( "Access-Control-Allow-Origin", origin );
            ex.getResponseHeaders().add( "Vary", "Origin" );
        }
        ex.getResponseHeaders().add( "Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS" );
        ex.getResponseHeaders().add( "Access-Control-Allow-Headers", "Content-Type" );
    }

    /**
     * Rejects (403) requests from web pages that are not on the allowlist, so an arbitrary site
     * open in the browser cannot register fake video/fullscreen areas. Requests with no Origin
     * header (curl, local tools) are allowed: any local process can already do worse, and the
     * socket is bound to loopback only. Returns true if the handler should continue.
     */
    private boolean checkOrigin( HttpExchange ex ) throws IOException
    {
        if( originAllowed( ex.getRequestHeaders().getFirst( "Origin" ) ) ) return true;
        System.err.println( "[Shimeji] VideoAreaServer rejected origin: " + ex.getRequestHeaders().getFirst( "Origin" ) );
        ex.sendResponseHeaders( 403, -1 );
        ex.getResponseBody().close();
        return false;
    }

    boolean originAllowed( String origin )
    {
        if( origin == null || origin.isEmpty() ) return true;
        final URI u;
        try { u = URI.create( origin ); }
        catch( IllegalArgumentException e ) { return false; }   // includes the literal "null" origin
        final String scheme = u.getScheme();
        final String host   = u.getHost();
        if( scheme == null ) return false;
        if( scheme.equals( "moz-extension" ) || scheme.equals( "chrome-extension" )
                || scheme.equals( "safari-web-extension" ) ) return true;
        if( host == null || !( scheme.equals( "http" ) || scheme.equals( "https" ) ) ) return false;
        final String h = host.toLowerCase( Locale.ROOT );
        for( String suffix : allowedHosts )
            if( h.equals( suffix ) || h.endsWith( "." + suffix ) ) return true;
        return false;
    }

    private static String[] parseHosts( String csv )
    {
        final List<String> out = new ArrayList<>();
        for( String t : csv.split( "," ) )
        {
            final String v = t.trim().toLowerCase( Locale.ROOT );
            if( !v.isEmpty() ) out.add( v );
        }
        return out.toArray( new String[0] );
    }

    /** Reads the request body, or returns null if it exceeds MAX_BODY_BYTES. */
    private static String readBody( HttpExchange ex ) throws IOException
    {
        final byte[] b = ex.getRequestBody().readNBytes( MAX_BODY_BYTES + 1 );
        if( b.length > MAX_BODY_BYTES ) return null;
        return new String( b, StandardCharsets.UTF_8 );
    }

    private static boolean idOk( String id, Map<String,?> existing )
    {
        if( id == null || id.isEmpty() || id.length() > MAX_ID_LEN ) return false;
        return existing.containsKey( id ) || existing.size() < MAX_IDS;
    }

    private void rebuildSnapshot()         { snapshot           = List.copyOf( areas.values() );           }
    private void rebuildFullscreenSnapshot(){ fullscreenSnapshot = List.copyOf( fullscreenAreas.values() ); }

    public List<Rectangle> getSyntheticAreas()   { return snapshot;           }
    public List<Rectangle> getFullscreenAreas()  { return fullscreenSnapshot; }

    /** Returns the site id (e.g. "youtube") for the synthetic area matching rect r, or "". */
    public String getSiteIdForRect( Rectangle r )
    {
        for( Map.Entry<String, Rectangle> e : areas.entrySet() )
            if( r.equals( e.getValue() ) )
                return e.getKey().replaceFirst( "_(top|bottom|left|right)$", "" );
        return "";
    }

    private static Map<String,String> parseForm( String s )
    {
        Map<String,String> m = new LinkedHashMap<>();
        for( String pair : s.split( "&" ) )
        {
            String[] kv = pair.split( "=", 2 );
            if( kv.length == 2 ) m.put( kv[0].trim(), kv[1].trim() );
        }
        return m;
    }
}
