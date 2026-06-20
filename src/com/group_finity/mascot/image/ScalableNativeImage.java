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
    private final String       imageSet;
    private final AtomicReference<CachedEntry> ready    = new AtomicReference<>( null );
    private volatile boolean                   building = false;
    private volatile double                    buildingScale = Double.NaN;

    // Full-resolution source PNG (premultiplied + mirrored to match this frame), loaded once on
    // the worker thread the first time a scaled frame is built. Lets dynamic resizing rasterize
    // straight from the source instead of upscaling the already-(globalScaling)-shrunk bitmap.
    private volatile java.awt.image.BufferedImage nativeSource = null;
    private volatile boolean                      nativeSourceLoaded = false;

    public ScalableNativeImage( MascotImage source )
    {
        this( source, null );
    }

    public ScalableNativeImage( MascotImage source, String imageSet )
    {
        this.source   = source;
        this.imageSet = imageSet;
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
            final String filterProp = imageSet != null
                ? Main.getInstance( ).getProperties( ).getProperty( "Filter.imageset." + imageSet,
                    Main.getInstance( ).getProperties( ).getProperty( "Filter", "false" ) )
                : Main.getInstance( ).getProperties( ).getProperty( "Filter", "false" );
            final Object interpolation = filterProp.equalsIgnoreCase( "bicubic" )
                ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;

            if( src != null )
            {
                WORKER.submit( () -> {
                    try
                    {
                        // Target dimensions are still derived from the globalScaling-baked source so
                        // the final size (and thus the renderCX/renderCY anchor math) is byte-for-byte
                        // what the old resample produced. Only the PIXELS are sourced differently:
                        // draw from the full-resolution PNG when available, so e.g. global 0.5 + dynamic
                        // 1.4 rasterizes 1.0 -> 0.7 in one step instead of 1.0 -> 0.5 -> 0.7 (no double
                        // resample, no compounded blur). Falls back to the baked bitmap if the source
                        // PNG can't be loaded (programmatic images, read failure).
                        int w = Math.max( 1, (int) Math.round( src.getWidth()  * finalScale ) );
                        int h = Math.max( 1, (int) Math.round( src.getHeight() * finalScale ) );

                        BufferedImage drawSrc = loadNativeSource( );
                        if( drawSrc == null )
                            drawSrc = src;

                        BufferedImage scaled = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB_PRE );
                        Graphics2D g = scaled.createGraphics( );
                        g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, interpolation );
                        g.drawImage( drawSrc, 0, 0, w, h, null );
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

    /**
     * Load the full-resolution source PNG for this frame once, premultiplied by the current
     * Opacity and mirrored to match a right-facing (flipped) frame, so it composites identically
     * to the globalScaling-baked bitmap — only at higher resolution. Runs on the worker thread.
     * Returns null (and is not retried) when there is no on-disk source or the read fails, in
     * which case the caller falls back to resampling the baked bitmap.
     */
    private BufferedImage loadNativeSource( )
    {
        if( nativeSourceLoaded )
            return nativeSource;

        try
        {
            final java.nio.file.Path path = source.getSourcePath( );
            if( path != null )
            {
                final double opacity = Double.parseDouble(
                    Main.getInstance( ).getProperties( ).getProperty( "Opacity", "1.0" ) );
                BufferedImage raw = javax.imageio.ImageIO.read( path.toFile( ) );
                if( raw != null )
                {
                    BufferedImage pm = ImagePairLoader.premultiply( raw, opacity );
                    nativeSource = source.isSourceFlipped( ) ? ImagePairLoader.flip( pm ) : pm;
                }
            }
        }
        catch( Exception e )
        {
            nativeSource = null;
        }
        nativeSourceLoaded = true;
        return nativeSource;
    }

    private static class CachedEntry
    {
        final double      scale;
        final MascotImage image;
        CachedEntry( double s, MascotImage i ) { scale = s; image = i; }
    }
}
