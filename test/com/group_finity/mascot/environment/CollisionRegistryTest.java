package com.group_finity.mascot.environment;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Plain main-program test. See CLAUDE.md "Tests". */
public class CollisionRegistryTest
{
    static int fails = 0;
    static void check( String n, boolean ok ) { if( !ok ) { fails++; System.out.println( "FAIL: " + n ); } }

    static BufferedImage img( int w, int h, int x0, int y0, int x1, int y1, int alpha )
    {
        BufferedImage b = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        for( int y = y0; y < y1; y++ ) for( int x = x0; x < x1; x++ ) b.setRGB( x, y, ( alpha << 24 ) | 0xFF0000 );
        return b;
    }

    public static void main( String[] a )
    {
        check( "block 64 in 128", new Rectangle( 32, 32, 64, 64 ).equals( CollisionRegistry.opaqueBounds( img( 128, 128, 32, 32, 96, 96, 255 ), 26 ) ) );
        check( "single pixel", new Rectangle( 5, 7, 1, 1 ).equals( CollisionRegistry.opaqueBounds( img( 10, 10, 5, 7, 6, 8, 255 ), 26 ) ) );
        check( "full", new Rectangle( 0, 0, 10, 4 ).equals( CollisionRegistry.opaqueBounds( img( 10, 4, 0, 0, 10, 4, 255 ), 26 ) ) );
        check( "empty -> null", CollisionRegistry.opaqueBounds( img( 10, 10, 0, 0, 0, 0, 0 ), 26 ) == null );
        check( "faint ignored", CollisionRegistry.opaqueBounds( img( 10, 10, 2, 2, 8, 8, 10 ), 26 ) == null );
        check( "threshold inclusive", new Rectangle( 2, 2, 6, 6 ).equals( CollisionRegistry.opaqueBounds( img( 10, 10, 2, 2, 8, 8, 26 ), 26 ) ) );
        // L-shape: bounds span both arms
        BufferedImage l = img( 20, 20, 0, 0, 3, 20, 255 );
        for( int x = 0; x < 20; x++ ) l.setRGB( x, 19, 0xFF00FF00 );
        check( "L shape", new Rectangle( 0, 0, 20, 20 ).equals( CollisionRegistry.opaqueBounds( l, 26 ) ) );
        // cache
        BufferedImage c = img( 16, 16, 4, 4, 12, 12, 255 );
        check( "cached", new Rectangle( 4, 4, 8, 8 ).equals( CollisionRegistry.cachedOpaqueBounds( c ) )
                      && new Rectangle( 4, 4, 8, 8 ).equals( CollisionRegistry.cachedOpaqueBounds( c ) ) );
        check( "cached transparent null", CollisionRegistry.cachedOpaqueBounds( img( 4, 4, 0, 0, 0, 0, 0 ) ) == null );
        // handle round trip
        for( int id : new int[]{ 1, 2, 999, 123456 } )
        {
            long v = CollisionRegistry.handleValueFor( id );
            check( "handle " + id, v <= CollisionRegistry.HANDLE_BASE && CollisionRegistry.idFromHandleValue( v ) == id );
        }
        check( "-1 sentinel not a collision handle", CollisionRegistry.idFromHandleValue( -1L ) == -1 );
        check( "real handle not collision", CollisionRegistry.idFromHandleValue( 0x1A2B3CL ) == -1 );
        // registry
        CollisionRegistry.publish( 7, new Rectangle( 1, 2, 3, 4 ) );
        check( "publish/get", new Rectangle( 1, 2, 3, 4 ).equals( CollisionRegistry.get( 7 ) ) );
        CollisionRegistry.publish( 7, new Rectangle( 0, 0, 0, 0 ) );
        check( "empty publish removes", CollisionRegistry.get( 7 ) == null );
        System.out.println( fails == 0 ? "CollisionRegistryTest: all passed" : "CollisionRegistryTest: " + fails + " FAILED" );
        System.exit( fails == 0 ? 0 : 1 );
    }
}
