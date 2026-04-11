package com.group_finity.mascot.image;

import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public class ImagePairs
{
    private static ConcurrentHashMap<String,ImagePair> imagepairs = new ConcurrentHashMap<String,ImagePair>( ); 

    public static void load( final String filename, final ImagePair imagepair )
    {
        // putIfAbsent is atomic on ConcurrentHashMap — no need for a separate containsKey check
        imagepairs.putIfAbsent( filename, imagepair );
    }

    public static ImagePair getImagePair( String filename )
    {
        // Single lookup instead of containsKey + get
        return imagepairs.get( filename );
    }	

    public static boolean contains( String filename )
    {
        return imagepairs.containsKey( filename );
    }

    public static void clear( )
    {
        imagepairs.clear( );
    }

    public static void removeAll( String searchTerm )
    {
        if( imagepairs.isEmpty( ) )
            return;

        for( Enumeration<String> key = imagepairs.keys( ); key.hasMoreElements( ); )
        {
            String filename = key.nextElement( );
            if( searchTerm.equals( Paths.get( filename ).getName( 2 ).toString( ) ) )
                imagepairs.remove( filename );
        }
    }

    public static MascotImage getImage( String filename, boolean isLookRight )
    {
        // Single lookup instead of containsKey + get
        ImagePair ip = imagepairs.get( filename );
        if( ip == null )
            return null;
        return ip.getImage( isLookRight );
    }
}