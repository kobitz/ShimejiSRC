package com.group_finity.mascot.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-image-set behavior counters (distance walked, time sat, jumps, ... plus any custom
 * counter an action XML bumps through the {@code Stat} action). Kept as lifetime totals plus
 * per-day buckets; "today" and "this week" (last 7 days including today) are derived from the
 * buckets, so nothing needs a rollover job. Buckets older than {@link #KEEP_DAYS} are pruned.
 * Not thread-safe by itself — {@link MascotMemory} calls it under its own monitor.
 */
public final class MascotStats
{
    public static final String WALKED  = "walked";   // unscaled pixels
    public static final String SAT     = "sat";      // seconds
    public static final String JUMPED  = "jumped";
    public static final String CARRIED = "carried";
    public static final String FELL    = "fell";

    static final int KEEP_DAYS = 8;
    /** Reference width used to turn pixels into "screen-widths" for the prompt. */
    static final double SCREEN_PX = 1920.0;

    private final Map<String, Double> lifetime = new LinkedHashMap<>();
    private final Map<Long, Map<String, Double>> days = new TreeMap<>();

    /** Letters, digits, space, '_' and '-' only; anything else dropped. Null if nothing is left. */
    public static String sanitize( final String name )
    {
        if( name == null ) return null;
        final String s = name.replaceAll( "[^A-Za-z0-9 _-]", "" ).trim();
        return s.isEmpty() ? null : ( s.length() > 40 ? s.substring( 0, 40 ) : s );
    }

    public void add( final String rawName, final double amount, final long epochDay )
    {
        final String name = sanitize( rawName );
        if( name == null || !( amount > 0 ) || Double.isInfinite( amount ) ) return;
        lifetime.merge( name, amount, Double::sum );
        Map<String, Double> bucket = days.get( epochDay );
        if( bucket == null )
        {
            bucket = new LinkedHashMap<>();
            days.put( epochDay, bucket );
            prune( epochDay );   // only when a new day starts
        }
        bucket.merge( name, amount, Double::sum );
    }

    private void prune( final long today )
    {
        days.keySet().removeIf( d -> d <= today - KEEP_DAYS );
    }

    public double lifetime( final String name ) { return lifetime.getOrDefault( name, 0.0 ); }

    public double day( final String name, final long epochDay )
    {
        final Map<String, Double> m = days.get( epochDay );
        return m == null ? 0.0 : m.getOrDefault( name, 0.0 );
    }

    /** Sum over the 7 days ending at {@code today}. */
    public double week( final String name, final long today )
    {
        double sum = 0;
        for( long d = today - 6; d <= today; d++ ) sum += day( name, d );
        return sum;
    }

    public boolean isEmpty() { return lifetime.isEmpty(); }

    // ── Persistence: flat "L|name" / "D<epochDay>|name" keys ─────────────────

    String toJsonObject()
    {
        final StringBuilder sb = new StringBuilder( "{" );
        boolean first = true;
        for( final Map.Entry<String, Double> e : lifetime.entrySet() )
        {
            sb.append( first ? "\n" : ",\n" ).append( "    \"L|" ).append( e.getKey() ).append( "\": " ).append( num( e.getValue() ) );
            first = false;
        }
        for( final Map.Entry<Long, Map<String, Double>> d : days.entrySet() )
            for( final Map.Entry<String, Double> e : d.getValue().entrySet() )
            {
                sb.append( first ? "\n" : ",\n" ).append( "    \"D" ).append( d.getKey() ).append( '|' )
                  .append( e.getKey() ).append( "\": " ).append( num( e.getValue() ) );
                first = false;
            }
        return sb.append( first ? "}" : "\n  }" ).toString();
    }

    private static String num( final double v )
    {
        return v == Math.rint( v ) && Math.abs( v ) < 1e15 ? Long.toString( (long) v ) : String.format( java.util.Locale.ROOT, "%.2f", v );
    }

    private static final Pattern ENTRY = Pattern.compile( "\"([LD])(-?\\d*)\\|([A-Za-z0-9 _-]+)\"\\s*:\\s*(-?[0-9.]+)" );

    /** Parses the {@code "stats": { ... }} object out of a whole memory.json; absent → empty (back-compat). */
    static MascotStats fromJson( final String json )
    {
        final MascotStats s = new MascotStats();
        final int k = json.indexOf( "\"stats\"" );
        if( k < 0 ) return s;
        final int open = json.indexOf( '{', k );
        final int close = open < 0 ? -1 : json.indexOf( '}', open );
        if( open < 0 || close < 0 ) return s;
        final Matcher m = ENTRY.matcher( json.substring( open, close ) );
        while( m.find() )
        {
            final double v;
            try { v = Double.parseDouble( m.group( 4 ) ); } catch( final NumberFormatException e ) { continue; }
            if( v < 0 ) continue;
            if( m.group( 1 ).equals( "L" ) ) s.lifetime.merge( m.group( 3 ), v, Double::sum );
            else if( !m.group( 2 ).isEmpty() )
                s.days.computeIfAbsent( Long.parseLong( m.group( 2 ) ), d -> new LinkedHashMap<>() ).merge( m.group( 3 ), v, Double::sum );
        }
        return s;
    }

    // ── Prompt text ──────────────────────────────────────────────────────────

    private interface Val { double get( String name ); }

    /**
     * "Your own life (counted, not guessed): today: …; this week: …; all-time: …" or "" when
     * nothing has been counted. Periods that add nothing over a shorter one are dropped.
     */
    public String describe( final long today )
    {
        final List<String> names = new ArrayList<>( lifetime.keySet() );
        final String t = items( names, n -> day( n, today ) );
        final String w = items( names, n -> week( n, today ) );
        final String a = items( names, n -> lifetime( n ) );
        final List<String> parts = new ArrayList<>();
        if( !t.isEmpty() ) parts.add( "today: " + t );
        if( !w.isEmpty() && !w.equals( t ) ) parts.add( "this week: " + w );
        if( !a.isEmpty() && !a.equals( w ) ) parts.add( "all-time: " + a );
        return parts.isEmpty() ? "" : "\nYour own life (counted, not guessed) — " + String.join( "; ", parts );
    }

    private static String items( final List<String> names, final Val v )
    {
        final String[] builtin = { WALKED, SAT, JUMPED, CARRIED, FELL };
        final List<String> order = new ArrayList<>();
        for( final String b : builtin ) order.add( b );
        for( final String n : names ) if( !order.contains( n ) ) order.add( n );
        final List<String> out = new ArrayList<>();
        for( final String n : order )
        {
            final double x = v.get( n );
            final String f = fmt( n, x );
            if( f != null && out.size() < 6 ) out.add( f );
        }
        return String.join( ", ", out );
    }

    static String fmt( final String name, final double x )
    {
        switch( name )
        {
            case WALKED:
            {
                final double w = x / SCREEN_PX;
                if( w < 0.1 ) return null;
                return "walked " + ( w < 10 ? String.format( java.util.Locale.ROOT, "%.1f", w ) : Long.toString( Math.round( w ) ) ) + " screen-widths";
            }
            case SAT:
            {
                final long mins = Math.round( x / 60.0 );
                if( mins < 1 ) return null;
                return mins < 120 ? "sat " + mins + " min" : "sat " + Math.round( mins / 60.0 ) + " h";
            }
            case JUMPED:  return times( "jumped", x );
            case CARRIED: return times( "picked up", x );
            case FELL:    return times( "fell", x );
            default:
            {
                final long n = Math.round( x );
                return n < 1 ? null : name + " " + n;
            }
        }
    }

    private static String times( final String verb, final double x )
    {
        final long n = Math.round( x );
        return n < 1 ? null : verb + " " + n + ( n == 1 ? " time" : " times" );
    }
}
