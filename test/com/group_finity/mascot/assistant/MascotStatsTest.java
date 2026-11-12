package com.group_finity.mascot.assistant;

/** Dependency-free tests for {@link MascotStats}. Exits non-zero on failure. */
public final class MascotStatsTest
{
    private static int failures = 0;

    private static void eq( final String what, final Object expected, final Object actual )
    {
        if( !java.util.Objects.equals( expected, actual ) )
        {
            failures++;
            System.err.println( "FAIL " + what + ": expected <" + expected + "> got <" + actual + ">" );
        }
    }

    public static void main( final String[] args )
    {
        final long D = 20000;
        final MascotStats s = new MascotStats();
        eq( "empty describe", "", s.describe( D ) );

        s.add( MascotStats.JUMPED, 3, D );
        s.add( MascotStats.JUMPED, 2, D - 3 );     // within the week
        s.add( MascotStats.JUMPED, 10, D - 7 );    // outside the week, still lifetime
        eq( "lifetime", 15.0, s.lifetime( "jumped" ) );
        eq( "today", 3.0, s.day( "jumped", D ) );
        eq( "week", 5.0, s.week( "jumped", D ) );

        s.add( "Mosscreeps hunted", 1, D );
        s.add( "bad<>name", 1, D );
        s.add( "x", -4, D );
        s.add( "x", Double.NaN, D );
        eq( "sanitized", 1.0, s.lifetime( "badname" ) );
        eq( "negative/NaN ignored", 0.0, s.lifetime( "x" ) );

        s.add( MascotStats.WALKED, 1920 * 2.5, D );
        s.add( MascotStats.SAT, 600, D );
        final String line = s.describe( D );
        eq( "today part", true, line.contains( "today: walked 2.5 screen-widths, sat 10 min, jumped 3 times" ) );
        eq( "custom", true, line.contains( "Mosscreeps hunted 1" ) );
        eq( "week differs", true, line.contains( "this week: " ) );
        eq( "alltime", true, line.contains( "all-time: " ) && line.contains( "jumped 15 times" ) );

        // round trip, with surrounding unrelated JSON
        final String json = "{\"interactionCount\": 1,\n  \"stats\": " + s.toJsonObject() + ",\n  \"facts\": []\n}";
        final MascotStats r = MascotStats.fromJson( json );
        eq( "rt lifetime", s.lifetime( "jumped" ), r.lifetime( "jumped" ) );
        eq( "rt day", s.day( "jumped", D - 3 ), r.day( "jumped", D - 3 ) );
        eq( "rt describe", line, r.describe( D ) );

        // back-compat: no stats key
        eq( "no stats", true, MascotStats.fromJson( "{\"facts\": []}" ).isEmpty() );

        // pruning when a new day starts
        s.add( MascotStats.FELL, 1, D + 10 );
        eq( "pruned old day", 0.0, s.day( "jumped", D ) );
        eq( "lifetime kept", 15.0, s.lifetime( "jumped" ) );

        eq( "singular", "fell 1 time", MascotStats.fmt( "fell", 1 ) );
        eq( "below threshold", null, MascotStats.fmt( "walked", 10 ) );

        if( failures > 0 ) { System.err.println( failures + " failure(s)" ); System.exit( 1 ); }
        System.out.println( "MascotStatsTest OK" );
    }
}
