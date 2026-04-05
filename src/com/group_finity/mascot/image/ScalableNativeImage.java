package com.group_finity.mascot.image;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.group_finity.mascot.Main;

/**
 * Produces scaled MascotImages without blocking the manager thread.
 * One instance per mascot (recreated when source image changes).
 * Uses a background thread for GDI-heavy NativeImage construction.
 * Respects the Filter setting from settings.properties (bicubic or nearest).
 */
public class ScalableNativeImage
{
    private static final double ROUND = 10.0;

    private static final ExecutorService WORKER =
        Executors.newSingleThreadExecutor( r -> {
            Thread t = new Thread( r, "ScaleWorker" );
            t.setDaemon( true );
            return t;
        } );

    private final MascotImage  source;
    private final AtomicReference<CachedEntry> ready    = new AtomicReference<>( null );
    private volatile boolean                   building = false;
    private volatile double                    buildingScale = Double.NaN;

    public ScalableNativeImage( MascotImage source )
    {
        this.source = source;
    }

    public MascotImage getSource( ) { return source; }

    public MascotImage get( double scale )
    {
        double rounded = Math.round( scale * ROUND ) / ROUND;

        CachedEntry entry = ready.get( );

        // Only submit if: not already building this exact scale, and cache doesn't have it
        boolean cacheHit = entry != null && entry.scale == rounded;
        boolean alreadyBuilding = building && buildingScale == rounded;

        if( !cacheHit && !alreadyBuilding )
        {
            building = true;
            buildingScale = rounded;
            final double   finalScale  = rounded;
            final BufferedImage src    = source.getBufferedImage( );
            final Point         center = source.getCenter( );

            // Read filter setting on the submitting thread (Main is thread-safe for getProperties)
            final String filterProp = Main.getInstance( ).getProperties( ).getProperty( "Filter", "false" );
            final Object interpolation = filterProp.equalsIgnoreCase( "bicubic" )
                ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;

            if( src != null )
            {
                WORKER.submit( () -> {
                    try
                    {
                        int w = Math.max( 1, (int) Math.round( src.getWidth()  * finalScale ) );
                        int h = Math.max( 1, (int) Math.round( src.getHeight() * finalScale ) );

                        BufferedImage scaled = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB_PRE );
                        Graphics2D g = scaled.createGraphics( );
                        g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, interpolation );
                        g.drawImage( src, 0, 0, w, h, null );
                        g.dispose( );

                        Point newCenter = new Point(
                            (int) Math.round( center.x * finalScale ),
                            (int) Math.round( center.y * finalScale ) );

                        MascotImage result = new MascotImage( scaled, newCenter );
                        ready.set( new CachedEntry( finalScale, result ) );
                    }
                    catch( Exception e ) { /* leave old cache entry */ }
                    finally             { building = false; }
                } );
            }
        }

        return entry != null ? entry.image : null;
    }

    private static class CachedEntry
    {
        final double      scale;
        final MascotImage image;
        CachedEntry( double s, MascotImage i ) { scale = s; image = i; }
    }
}
