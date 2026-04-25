package com.group_finity.mascot;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class VideoAreaServer
{
    private static final Logger log = Logger.getLogger( VideoAreaServer.class.getName() );
    private static final int PORT = 41221;

    private static VideoAreaServer instance;
    public  static VideoAreaServer getInstance() { return instance; }

    private HttpServer server;
    private final ConcurrentHashMap<String, Rectangle> areas = new ConcurrentHashMap<>();
    private volatile List<Rectangle> snapshot = Collections.emptyList();

    public static void start()
    {
        if( instance != null ) return;
        instance = new VideoAreaServer();
        instance.startServer();
    }

    private void startServer()
    {
        try
        {
            server = HttpServer.create( new InetSocketAddress( "127.0.0.1", PORT ), 0 );
            server.createContext( "/video", this::handle );
            server.setExecutor( Executors.newSingleThreadExecutor( r -> {
                Thread t = new Thread( r, "VideoAreaServer" );
                t.setDaemon( true );
                return t;
            }));
            server.start();
            log.info( "VideoAreaServer listening on http://127.0.0.1:" + PORT + "/video" );
            System.out.println( "[Shimeji] VideoAreaServer started on port " + PORT );
        }
        catch( Exception e )
        {
            System.err.println( "[Shimeji] VideoAreaServer failed to start: " + e.getMessage() );
            log.warning( "VideoAreaServer failed to start: " + e.getMessage() );
        }
    }

    private void handle( HttpExchange ex ) throws IOException
    {
        ex.getResponseHeaders().add( "Access-Control-Allow-Origin",  "*" );
        ex.getResponseHeaders().add( "Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS" );
        ex.getResponseHeaders().add( "Access-Control-Allow-Headers", "Content-Type" );

        String method = ex.getRequestMethod();

        if( "OPTIONS".equals( method ) )
        {
            ex.sendResponseHeaders( 204, -1 ); return;
        }

        if( "GET".equals( method ) )
        {
            // Debug: return current registered areas as plain text
            StringBuilder sb = new StringBuilder( "Registered video areas:\n" );
            for( Map.Entry<String,Rectangle> e : areas.entrySet() )
                sb.append( e.getKey() ).append( ": " ).append( e.getValue() ).append( "\n" );
            byte[] bytes = sb.toString().getBytes();
            ex.sendResponseHeaders( 200, bytes.length );
            ex.getResponseBody().write( bytes );
        }
        else if( "POST".equals( method ) )
        {
            String body = new String( ex.getRequestBody().readAllBytes() );
            Map<String,String> p = parseForm( body );
            try
            {
                String id = p.getOrDefault( "id", "video" );
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

    private void rebuildSnapshot() { snapshot = List.copyOf( areas.values() ); }

    public List<Rectangle> getSyntheticAreas() { return snapshot; }

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
