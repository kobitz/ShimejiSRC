package com.group_finity.mascot.environment;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of "Collision" rectangles. A mascot with Collision enabled publishes
 * the screen-space bounds of its non-transparent pixels each tick; other mascots' environments
 * treat those rectangles like windows (IEs). Rectangles are never mutated after publishing.
 */
public final class CollisionRegistry
{
    private CollisionRegistry( ) { }

    /** Minimum alpha (0-255) for a pixel to count as solid (~10%). */
    public static final int ALPHA_MIN = 26;

    /** Pseudo window-handle values at or below this encode a mascot id (see {@link #idFromHandleValue}). */
    public static final long HANDLE_BASE = -1000L;

    private static final Map<Integer, Rectangle> RECTS = new ConcurrentHashMap<>( );
    private static final Map<BufferedImage, Rectangle> BOUNDS_CACHE =
        Collections.synchronizedMap( new WeakHashMap<BufferedImage, Rectangle>( ) );
    private static final Rectangle NONE = new Rectangle( 0, 0, 0, 0 );

    public static void publish( final int mascotId, final Rectangle r )
    {
        if( r == null || r.width <= 0 || r.height <= 0 ) RECTS.remove( mascotId );
        else RECTS.put( mascotId, new Rectangle( r ) );
    }

    public static void remove( final int mascotId ) { RECTS.remove( mascotId ); }

    /** Live view of id → rectangle. Values are immutable by convention; do not modify. */
    public static Map<Integer, Rectangle> all( ) { return RECTS; }

    public static Rectangle get( final int mascotId ) { return RECTS.get( mascotId ); }

    public static long handleValueFor( final int mascotId ) { return HANDLE_BASE - mascotId; }

    /** @return the mascot id encoded in a pseudo-handle value, or -1 if it isn't one. */
    public static int idFromHandleValue( final long v )
    {
        return v <= HANDLE_BASE ? (int) ( HANDLE_BASE - v ) : -1;
    }

    /** Opaque bounds of {@code img}, cached per image instance. Null if fully transparent. */
    public static Rectangle cachedOpaqueBounds( final BufferedImage img )
    {
        if( img == null ) return null;
        Rectangle r = BOUNDS_CACHE.get( img );
        if( r == null )
        {
            r = opaqueBounds( img, ALPHA_MIN );
            if( r == null ) r = NONE;
            BOUNDS_CACHE.put( img, r );
        }
        return r == NONE ? null : r;
    }

    /**
     * Smallest rectangle (image pixel coordinates) containing every pixel whose alpha is
     * at least {@code alphaMin}; null if there is none.
     */
    public static Rectangle opaqueBounds( final BufferedImage img, final int alphaMin )
    {
        final int w = img.getWidth( ), h = img.getHeight( );
        final int[] row = new int[ w ];
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for( int y = 0; y < h; y++ )
        {
            img.getRGB( 0, y, w, 1, row, 0, w );
            int first = -1, last = -1;
            for( int x = 0; x < w; x++ )
            {
                if( ( row[ x ] >>> 24 ) >= alphaMin )
                {
                    if( first < 0 ) first = x;
                    last = x;
                }
            }
            if( first >= 0 )
            {
                if( y < minY ) minY = y;
                maxY = y;
                if( first < minX ) minX = first;
                if( last > maxX ) maxX = last;
            }
        }
        if( maxX < 0 ) return null;
        return new Rectangle( minX, minY, maxX - minX + 1, maxY - minY + 1 );
    }
}
