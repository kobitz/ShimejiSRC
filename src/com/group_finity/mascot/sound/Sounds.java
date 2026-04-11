package com.group_finity.mascot.sound;

import com.group_finity.mascot.Main;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.Clip;

/**
 * This static class contains all the sounds loaded by Shimeji-ee.
 * 
 * Visit kilkakon.com/shimeji for updates
 * @author Kilkakon
 */
public class Sounds
{
    private final static ConcurrentHashMap<String,Clip> SOUNDS = new ConcurrentHashMap<String,Clip>( );

    public static void load( final String filename, final Clip clip )
    {
        // putIfAbsent is atomic on ConcurrentHashMap — no separate containsKey needed
        SOUNDS.putIfAbsent( filename, clip );
    }

    public static boolean contains( String filename )
    {
        return SOUNDS.containsKey( filename );
    }

    public static Clip getSound( String filename )
    {
        // Single lookup instead of containsKey + get
        return SOUNDS.get( filename );
    }

    public static ArrayList<Clip> getSoundsIgnoringVolume( String filename )
    {
        ArrayList<Clip> sounds = new ArrayList( 5 );
        Enumeration<String> keys = SOUNDS.keys( );
        while( keys.hasMoreElements( ) )
        {
            String soundName = keys.nextElement( );
            if( soundName.startsWith( filename ) )
            {
                sounds.add( SOUNDS.get( soundName ) );
            }
        }
        return sounds;
    }

    /**
     * Removes and properly closes all clips whose key starts with the given prefix.
     * Clips hold a native audio line -- close() must be called to release it,
     * otherwise the system audio resource leaks when an image set is unloaded.
     */
    public static void removeAll( String searchTerm )
    {
        Enumeration<String> keys = SOUNDS.keys( );
        while( keys.hasMoreElements( ) )
        {
            String key = keys.nextElement( );
            if( key.startsWith( searchTerm ) )
            {
                Clip clip = SOUNDS.remove( key );
                if( clip != null )
                {
                    clip.stop( );
                    clip.close( );
                }
            }
        }
    }

    /**
     * Stops and closes all clips, releasing native audio lines, then clears the map.
     */
    public static void clear( )
    {
        for( Clip clip : SOUNDS.values( ) )
        {
            clip.stop( );
            clip.close( );
        }
        SOUNDS.clear( );
    }

    public static boolean isMuted( )
    {
        return ! Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "Sounds", "true" ) );
    }
    
    public static void setMuted( boolean mutedFlag )
    {
        if( mutedFlag )
        {
            // mute everything
            Enumeration<String> keys = SOUNDS.keys( );
            while( keys.hasMoreElements( ) )
            {
                SOUNDS.get( keys.nextElement( ) ).stop( );
            }
        }
    }
}
