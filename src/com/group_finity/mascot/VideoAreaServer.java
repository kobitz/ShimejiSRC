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
    private static final String DEFAULT_ALLOWED_HOSTS = "youtube.com,twitch.tv,netflix.com";

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
            server.createContext( "/command",    this::handleCommand );
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
                int x = Integer.parseInt( p.get( "x" ) );
                int y = Integer.parseInt( p.get( "y" ) );
                int w = Integer.parseInt( p.get( "w" ) );
                int h = Integer.parseInt( p.get( "h" ) );
                if( p.containsKey( "s" ) )
                {
                    // One request for the whole video rect (the extension sends this on every scroll
                    // frame): the four edge strips are derived here, ids <site>_top/bottom/left/right.
                    final int st = Math.max( 1, Math.min( 64, Integer.parseInt( p.get( "s" ) ) ) );
                    final String[] sides = { "top", "bottom", "left", "right" };
                    final Rectangle[] strips = {
                        new Rectangle( x, y, w, st ), new Rectangle( x, y + h - st, w, st ),
                        new Rectangle( x, y, st, h ), new Rectangle( x + w - st, y, st, h ) };
                    for( String sd : sides ) if( !idOk( id + "_" + sd, areas ) ) throw new IllegalArgumentException( "bad id" );
                    for( int i = 0; i < 4; i++ ) areas.put( id + "_" + sides[ i ], strips[ i ] );
                    rebuildSnapshot();
                    ex.sendResponseHeaders( 200, 0 );
                    ex.getResponseBody().close();
                    return;
                }
                if( !idOk( id, areas ) ) throw new IllegalArgumentException( "bad id" );
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

    // ---- media commands (agent -> browser extension) -------------------------------------

    /** The only commands the extension is ever told to run. */
    static final Set<String> COMMANDS = Set.of( "pause", "play", "fullscreen", "exit_fullscreen" );
    private static final long COMMAND_TTL_MS = 4000;
    private volatile String pendingCommand;
    private volatile long   pendingAtMs;

    /** True while the extension is reporting at least one video on screen. */
    public boolean hasVideo() { return !areas.isEmpty(); }

    /**
     * Queue one whitelisted command for the extension and wait up to {@code waitMs} for a tab to pick
     * it up (it polls every ~0.5 s). Returns true if a tab took it. Only the agent calls this, from
     * the user's own words.
     */
    public boolean sendCommand( final String cmd, final long waitMs )
    {
        if( cmd == null || !COMMANDS.contains( cmd ) ) return false;
        pendingAtMs = System.currentTimeMillis();
        pendingCommand = cmd;
        final long end = System.currentTimeMillis() + waitMs;
        while( pendingCommand != null && System.currentTimeMillis() < end )
        {
            try { Thread.sleep( 50 ); } catch( InterruptedException e ) { Thread.currentThread().interrupt(); break; }
        }
        if( pendingCommand == null ) return true;
        pendingCommand = null;
        return false;
    }

    /**
     * GET /command?playing=1|0 -- a tab asks for work. pause is only handed to a tab whose video is
     * playing and play to one whose video is paused, so with several tabs open the command lands on
     * the right video. 200 + command name, or 204.
     */
    private void handleCommand( HttpExchange ex ) throws IOException
    {
        if( !checkOrigin( ex ) ) return;
        addCors( ex );
        final String method = ex.getRequestMethod();
        if( "OPTIONS".equals( method ) ) { ex.sendResponseHeaders( 204, -1 ); return; }
        if( !"GET".equals( method ) ) { ex.sendResponseHeaders( 405, -1 ); ex.getResponseBody().close(); return; }
        final String q = ex.getRequestURI().getQuery();
        final boolean playing = "1".equals( parseForm( q != null ? q : "" ).get( "playing" ) );
        final String cmd = pendingCommand;
        final boolean fresh = System.currentTimeMillis() - pendingAtMs < COMMAND_TTL_MS;
        final boolean applies = cmd != null && fresh
            && ( cmd.equals( "pause" ) ? playing : cmd.equals( "play" ) ? !playing : true );
        boolean take = false;
        synchronized( this ) { if( applies && pendingCommand == cmd ) { pendingCommand = null; take = true; } }
        if( take )
        {
            final byte[] b = cmd.getBytes( StandardCharsets.UTF_8 );
            ex.sendResponseHeaders( 200, b.length );
            ex.getResponseBody().write( b );
        }
        else ex.sendResponseHeaders( 204, -1 );
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

    /**
     * The registered video areas, eased toward their latest reported position. The extension reports
     * a scrolling video in steps; handing those steps straight to the mascots made anything standing
     * on a video edge jump. Each rect now glides (exponential ease, SMOOTH_TAU_S) and snaps outright
     * when it is new or has moved far (a different video / layout change). Time-based, so it does not
     * matter how often or from which thread this is called.
     */
    private static final double SMOOTH_TAU_S = 0.05;
    private static final double SNAP_PX      = 600;
    private final Map<String,double[]> smoothCur = new HashMap<>();
    private long smoothLastNs = 0;
    private volatile Map<String,Rectangle> smoothedById = Collections.emptyMap();

    public synchronized List<Rectangle> getSyntheticAreas()
    {
        final long now = System.nanoTime();
        final double dt = smoothLastNs == 0 ? 0 : Math.min( 0.25, ( now - smoothLastNs ) / 1e9 );
        smoothLastNs = now;
        final double a = 1 - Math.exp( -dt / SMOOTH_TAU_S );
        smoothCur.keySet().retainAll( areas.keySet() );
        final List<Rectangle> out = new ArrayList<>( areas.size() );
        final Map<String,Rectangle> byId = new HashMap<>();
        for( Map.Entry<String,Rectangle> e : areas.entrySet() )
        {
            final Rectangle t = e.getValue();
            final double[] tv = { t.x, t.y, t.width, t.height };
            double[] c = smoothCur.get( e.getKey() );
            if( c == null || Math.abs( tv[0] - c[0] ) + Math.abs( tv[1] - c[1] ) > SNAP_PX ) { c = tv.clone(); smoothCur.put( e.getKey(), c ); }
            else for( int i = 0; i < 4; i++ ) c[i] += ( tv[i] - c[i] ) * a;
            final Rectangle r = new Rectangle( (int) Math.round( c[0] ), (int) Math.round( c[1] ), (int) Math.round( c[2] ), (int) Math.round( c[3] ) );
            out.add( r );
            byId.put( e.getKey(), r );
        }
        smoothedById = byId;
        return out;
    }
    public List<Rectangle> getFullscreenAreas()  { return fullscreenSnapshot; }

    /** Returns the site id (e.g. "youtube") for the synthetic area matching rect r, or "". */
    public String getSiteIdForRect( Rectangle r )
    {
        for( Map.Entry<String, Rectangle> e : smoothedById.entrySet() )
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
