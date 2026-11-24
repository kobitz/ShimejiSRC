package com.group_finity.mascot.imagesetchooser;

import java.nio.file.Files;
import java.nio.file.Path;

public class HiddenSetTest
{
    static int fails = 0;

    static void check( String what, boolean ok )
    {
        if( !ok )
        {
            fails++;
            System.out.println( "FAIL: " + what );
        }
    }

    public static void main( String[ ] args ) throws Exception
    {
        Path img = Files.createTempDirectory( "hiddenset" );
        Files.createDirectories( img.resolve( "Plain" ).resolve( "conf" ) );
        Files.createDirectories( img.resolve( "Helper" ).resolve( "conf" ) );
        Files.createDirectories( img.resolve( "NoConf" ) );
        Files.createFile( img.resolve( "Helper" ).resolve( "conf" ).resolve( "hidden.txt" ) );
        // a folder with that name is not a marker
        Files.createDirectories( img.resolve( "Tricky" ).resolve( "conf" ).resolve( "hidden.txt" ) );

        check( "plain set is visible", !ImageSetChooser.isHidden( img, "Plain" ) );
        check( "marker hides the set", ImageSetChooser.isHidden( img, "Helper" ) );
        check( "set without a conf folder is visible", !ImageSetChooser.isHidden( img, "NoConf" ) );
        check( "missing set is visible", !ImageSetChooser.isHidden( img, "Nope" ) );
        check( "directory named hidden.txt is ignored", !ImageSetChooser.isHidden( img, "Tricky" ) );
        check( "null and empty names are visible", !ImageSetChooser.isHidden( img, null ) && !ImageSetChooser.isHidden( img, "" ) );

        System.out.println( fails == 0 ? "HiddenSetTest: all passed" : "HiddenSetTest: " + fails + " failed" );
        System.exit( fails == 0 ? 0 : 1 );
    }
}
