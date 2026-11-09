package com.group_finity.mascot.config;

import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/** Plain main-program test (needs conf/ on the classpath for the schema bundle). See CLAUDE.md "Tests". */
public class ConfigurationSplashTest
{
    static int fails = 0;
    static void check( String n, boolean ok ) { if( !ok ) { fails++; System.out.println( "FAIL: " + n ); } }

    static Configuration load( String informationXml ) throws Exception
    {
        String xml = "<Mascot xmlns=\"http://www.group-finity.com/Mascot\">" + informationXml + "</Mascot>";
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance( );
        f.setNamespaceAware( true );
        org.w3c.dom.Document d = f.newDocumentBuilder( ).parse( new ByteArrayInputStream( xml.getBytes( "UTF-8" ) ) );
        Configuration c = new Configuration( );
        c.load( new Entry( d.getDocumentElement( ) ), "Test" );
        return c;
    }

    public static void main( String[] a ) throws Exception
    {
        Configuration multi = load( "<Information><Name>N</Name><SplashImages><SplashImage>a.png</SplashImage><SplashImage> b.png </SplashImage><SplashImage></SplashImage></SplashImages></Information>" );
        check( "multi has SplashImages", multi.containsInformationKey( "SplashImages" ) );
        check( "multi has no single SplashImage", !multi.containsInformationKey( "SplashImage" ) );
        check( "pages trimmed, blanks dropped", "a.png\nb.png".equals( multi.getInformation( "SplashImages" ) ) );

        Configuration single = load( "<Information><SplashImage>one.png</SplashImage></Information>" );
        check( "single unchanged", single.containsInformationKey( "SplashImage" ) && !single.containsInformationKey( "SplashImages" ) );

        Configuration empty = load( "<Information><SplashImages></SplashImages></Information>" );
        check( "empty pages -> no key", !empty.containsInformationKey( "SplashImages" ) );

        // Tags added after 1.0.22 must resolve even if the installed schema file is older than the jar
        check( "schema Arc", "Arc".equals( single.getSchema( ).getString( "Arc" ) ) );
        check( "schema Look", "Look".equals( single.getSchema( ).getString( "Look" ) ) );
        boolean threw = false;
        try { single.getSchema( ).getString( "NoSuchKeyAnywhere" ); } catch( java.util.MissingResourceException e ) { threw = true; }
        check( "unknown keys still throw", threw );

        System.out.println( fails == 0 ? "ConfigurationSplashTest: all passed" : "ConfigurationSplashTest: " + fails + " FAILED" );
        System.exit( fails == 0 ? 0 : 1 );
    }
}
