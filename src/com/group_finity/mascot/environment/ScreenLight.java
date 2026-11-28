package com.group_finity.mascot.environment;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

/**
 * Movie Mode light source: the average colour of each monitor, sampled a few times a second on
 * one daemon thread. Local only (a screen grab shrunk to a coarse pixel grid; nothing is stored
 * or sent). The thread sleeps while nobody has asked for a colour in the last few seconds, so it
 * costs nothing when Movie Mode is off. Protected (DRM) video usually captures as black.
 */
public final class ScreenLight
{
    private static final Logger log = Logger.getLogger( ScreenLight.class.getName( ) );

    private static final long   PERIOD_MS = 350;
    private static final long   IDLE_MS   = 5000;   // stop sampling when nobody asked for this long
    private static final int    GRID_X = 32, GRID_Y = 18;

    private static final class Sample
    {
        final Rectangle bounds; final int rgb;
        Sample( Rectangle b, int rgb ) { this.bounds = b; this.rgb = rgb; }
    }

    private static volatile Sample[] samples = new Sample[ 0 ];
    private static volatile long lastAskMs = 0;
    private static Thread thread;

    private ScreenLight( ) { }

    /** Average RGB (0xRRGGBB) of the monitor containing the point, or -1 when unknown. */
    public static int rgbAt( final int x, final int y )
    {
        lastAskMs = System.currentTimeMillis( );
        ensureStarted( );
        for( final Sample s : samples )
            if( s.bounds.contains( x, y ) ) return s.rgb;
        return -1;
    }

    private static synchronized void ensureStarted( )
    {
        if( thread != null && thread.isAlive( ) ) return;
        thread = new Thread( ScreenLight::loop, "ScreenLight" );
        thread.setDaemon( true );
        thread.setPriority( Thread.MIN_PRIORITY );
        thread.start( );
    }

    private static void loop( )
    {
        try
        {
            while( System.currentTimeMillis( ) - lastAskMs < IDLE_MS || samples.length == 0 )
            {
                try { samples = scan( ); }
                catch( Throwable t ) { log.fine( "[ScreenLight] scan failed: " + t ); samples = new Sample[ 0 ]; }
                Thread.sleep( PERIOD_MS );
            }
        }
        catch( InterruptedException e ) { Thread.currentThread( ).interrupt( ); }
        finally { samples = new Sample[ 0 ]; }
    }

    private static Sample[] scan( ) throws Exception
    {
        if( GraphicsEnvironment.isHeadless( ) ) return new Sample[ 0 ];
        final GraphicsDevice[] devs = GraphicsEnvironment.getLocalGraphicsEnvironment( ).getScreenDevices( );
        final Sample[] out = new Sample[ devs.length ];
        int n = 0;
        for( final GraphicsDevice d : devs )
        {
            final Rectangle b = d.getDefaultConfiguration( ).getBounds( );
            final BufferedImage img = new Robot( d ).createScreenCapture( b );
            long r = 0, g = 0, bl = 0; int c = 0;
            final int w = img.getWidth( ), h = img.getHeight( );
            for( int gy = 0; gy < GRID_Y; gy++ )
                for( int gx = 0; gx < GRID_X; gx++ )
                {
                    final int p = img.getRGB( ( gx * 2 + 1 ) * w / ( GRID_X * 2 ), ( gy * 2 + 1 ) * h / ( GRID_Y * 2 ) );
                    r += ( p >> 16 ) & 255; g += ( p >> 8 ) & 255; bl += p & 255; c++;
                }
            img.flush( );
            out[ n++ ] = new Sample( b, ( (int) ( r / c ) << 16 ) | ( (int) ( g / c ) << 8 ) | (int) ( bl / c ) );
        }
        return n == out.length ? out : java.util.Arrays.copyOf( out, n );
    }
}
