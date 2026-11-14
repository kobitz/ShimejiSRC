package com.group_finity.mascot.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny strict JSON parser for the shop catalog (no external deps). Returns {@code Map<String,Object>},
 * {@code List<Object>}, {@code String}, {@code Double}, {@code Boolean} or {@code null}.
 * Throws {@link IllegalArgumentException} on malformed input; nesting is capped so a hostile
 * catalog cannot overflow the stack.
 */
final class MiniJson
{
    private static final int MAX_DEPTH = 32;
    private final String s;
    private int i = 0;

    private MiniJson( final String s ) { this.s = s; }

    static Object parse( final String text )
    {
        final MiniJson p = new MiniJson( text );
        p.ws();
        final Object v = p.value( 0 );
        p.ws();
        if( p.i != p.s.length() ) throw p.err( "trailing data" );
        return v;
    }

    private IllegalArgumentException err( final String m ) { return new IllegalArgumentException( m + " at " + i ); }

    private void ws()
    {
        while( i < s.length() && ( s.charAt( i ) == ' ' || s.charAt( i ) == '\n' || s.charAt( i ) == '\r' || s.charAt( i ) == '\t' || s.charAt( i ) == '﻿' ) ) i++;
    }

    private Object value( final int depth )
    {
        if( depth > MAX_DEPTH ) throw err( "too deep" );
        if( i >= s.length() ) throw err( "unexpected end" );
        final char c = s.charAt( i );
        if( c == '{' ) return object( depth );
        if( c == '[' ) return array( depth );
        if( c == '"' ) return string();
        if( s.startsWith( "true", i ) )  { i += 4; return Boolean.TRUE; }
        if( s.startsWith( "false", i ) ) { i += 5; return Boolean.FALSE; }
        if( s.startsWith( "null", i ) )  { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object( final int depth )
    {
        final Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if( i < s.length() && s.charAt( i ) == '}' ) { i++; return m; }
        while( true )
        {
            ws();
            if( i >= s.length() || s.charAt( i ) != '"' ) throw err( "expected key" );
            final String k = string();
            ws();
            if( i >= s.length() || s.charAt( i ) != ':' ) throw err( "expected ':'" );
            i++; ws();
            m.put( k, value( depth + 1 ) );
            ws();
            if( i >= s.length() ) throw err( "unterminated object" );
            if( s.charAt( i ) == ',' ) { i++; continue; }
            if( s.charAt( i ) == '}' ) { i++; return m; }
            throw err( "expected ',' or '}'" );
        }
    }

    private List<Object> array( final int depth )
    {
        final List<Object> l = new ArrayList<>();
        i++; ws();
        if( i < s.length() && s.charAt( i ) == ']' ) { i++; return l; }
        while( true )
        {
            ws();
            l.add( value( depth + 1 ) );
            ws();
            if( i >= s.length() ) throw err( "unterminated array" );
            if( s.charAt( i ) == ',' ) { i++; continue; }
            if( s.charAt( i ) == ']' ) { i++; return l; }
            throw err( "expected ',' or ']'" );
        }
    }

    private String string()
    {
        final StringBuilder sb = new StringBuilder();
        i++;
        while( true )
        {
            if( i >= s.length() ) throw err( "unterminated string" );
            final char c = s.charAt( i++ );
            if( c == '"' ) return sb.toString();
            if( c != '\\' ) { sb.append( c ); continue; }
            if( i >= s.length() ) throw err( "bad escape" );
            final char e = s.charAt( i++ );
            switch( e )
            {
                case '"': sb.append( '"' ); break;
                case '\\': sb.append( '\\' ); break;
                case '/': sb.append( '/' ); break;
                case 'b': sb.append( '\b' ); break;
                case 'f': sb.append( '\f' ); break;
                case 'n': sb.append( '\n' ); break;
                case 'r': sb.append( '\r' ); break;
                case 't': sb.append( '\t' ); break;
                case 'u':
                    if( i + 4 > s.length() ) throw err( "bad \\u escape" );
                    try { sb.append( (char) Integer.parseInt( s.substring( i, i + 4 ), 16 ) ); }
                    catch( final NumberFormatException ex ) { throw err( "bad \\u escape" ); }
                    i += 4;
                    break;
                default: throw err( "bad escape" );
            }
        }
    }

    private Double number()
    {
        final int start = i;
        while( i < s.length() && "+-0123456789.eE".indexOf( s.charAt( i ) ) >= 0 ) i++;
        if( start == i ) throw err( "unexpected character" );
        try { return Double.valueOf( s.substring( start, i ) ); }
        catch( final NumberFormatException e ) { throw err( "bad number" ); }
    }
}
