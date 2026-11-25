package com.group_finity.mascot.assistant;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal client for the configured embedding endpoint, used by {@link DriveIndexTool}
 * for semantic drive search. Kept separate from {@link OllamaClient} because embeddings
 * use a different endpoint, a different (tiny) model, and a batched request/response
 * shape.
 *
 * <p>Two transports are supported (selected by {@link AiSettings}):
 * <ul>
 *   <li>Ollama {@code /api/embed} — CPU-only by design ({@code num_gpu:0}) so the embed
 *       model never competes for chat-model VRAM.</li>
 *   <li>OpenAI-compatible {@code /embeddings} — bearer key, {@code data[].embedding}.</li>
 * </ul>
 *
 * No JSON library: requests are built by hand and arrays parsed with a small numeric
 * scanner (same house style as OllamaClient).
 */
public final class OllamaEmbeddings
{
    private static final Logger log = Logger.getLogger( OllamaEmbeddings.class.getName() );

    /** Embedding calls are short; a generous-but-bounded timeout covers a cold load. */
    private static final int    TIMEOUT_MS = 20_000;
    /** Keep the tiny model warm briefly so a burst of batches (or a query right after
     *  indexing) doesn't pay a reload each time. Short -- it's only ~280 MB. */
    private static final String KEEP_ALIVE = "30s";

    private OllamaEmbeddings() {}

    /**
     * Embed a batch of inputs. Returns a float[inputs.size()][dims] array, or null on
     * ANY failure (model not pulled, endpoint down, parse error, count mismatch). Callers
     * treat null as "semantic unavailable" and fall back to keyword retrieval, so this
     * never throws.
     */
    public static float[][] embed( final String model, final List<String> inputs )
    {
        if( inputs == null || inputs.isEmpty() ) return new float[0][];
        final boolean remote = AiSettings.isRemote( );
        final String body = remote ? buildOpenAiBody( model, inputs ) : buildBody( model, inputs );
        try
        {
            final URL url = new URL( AiSettings.embeddingsUrl( ) );
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod( "POST" );
            conn.setRequestProperty( "Content-Type", "application/json; charset=utf-8" );
            if( remote )
            {
                final String key = AiSettings.apiKey( );
                if( !key.isEmpty( ) )
                    conn.setRequestProperty( "Authorization", "Bearer " + key );
            }
            conn.setConnectTimeout( TIMEOUT_MS );
            conn.setReadTimeout( TIMEOUT_MS );
            conn.setDoOutput( true );
            try ( OutputStream os = conn.getOutputStream() )
            {
                os.write( body.getBytes( StandardCharsets.UTF_8 ) );
            }

            final int status = conn.getResponseCode();
            if( status != 200 )
            {
                log.log( Level.WARNING, "[DriveSemantic] embed HTTP {0} (model={1})",
                         new Object[]{ status, model } );
                return null;
            }

            final StringBuilder sb = new StringBuilder();
            try ( BufferedReader br = new BufferedReader(
                    new InputStreamReader( conn.getInputStream(), StandardCharsets.UTF_8 ) ) )
            {
                String line;
                while( ( line = br.readLine() ) != null ) sb.append( line );
            }
            return remote ? parseOpenAiEmbeddings( sb.toString(), inputs.size( ) )
                          : parseEmbeddings( sb.toString(), inputs.size( ) );
        }
        catch( final Exception e )
        {
            log.log( Level.WARNING, "[DriveSemantic] embed failed: {0}", e.getMessage() );
            return null;
        }
    }

    private static String buildOpenAiBody( final String model, final List<String> inputs )
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( "{\"model\":" ).append( jsonStr( model ) ).append( ",\"input\":[" );
        for( int i = 0; i < inputs.size(); i++ )
        {
            if( i > 0 ) sb.append( ',' );
            sb.append( jsonStr( inputs.get( i ) ) );
        }
        sb.append( "]}" );
        return sb.toString();
    }

    private static String buildBody( final String model, final List<String> inputs )
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( "{\"model\":" ).append( jsonStr( model ) ).append( ",\"input\":[" );
        for( int i = 0; i < inputs.size(); i++ )
        {
            if( i > 0 ) sb.append( ',' );
            sb.append( jsonStr( inputs.get( i ) ) );
        }
        sb.append( "],\"keep_alive\":\"" ).append( KEEP_ALIVE ).append( "\"," );
        // num_gpu:0 -> CPU only. Never touch the chat model's VRAM.
        sb.append( "\"options\":{\"num_gpu\":0}}" );
        return sb.toString();
    }

    /**
     * Parse an OpenAI-compatible {"data":[{"embedding":[..]},…]} reply.
     * Returns null unless the row count matches the request.
     */
    private static float[][] parseOpenAiEmbeddings( final String json, final int expected )
    {
        final int key = json.indexOf( "\"data\"" );
        if( key < 0 ) return null;
        final List<float[]> rows = new ArrayList<>();
        int i = key + 6;
        while( ( i = json.indexOf( "\"embedding\"", i ) ) >= 0 )
        {
            final int open = json.indexOf( '[', i );
            if( open < 0 ) break;
            final int close = json.indexOf( ']', open );
            if( close < 0 ) break;
            rows.add( parseFloatArray( json.substring( open + 1, close ) ) );
            i = close + 1;
        }
        if( rows.size() != expected ) return null;
        return rows.toArray( new float[0][] );
    }

    private static float[] parseFloatArray( final String csv )
    {
        final String[] parts = csv.split( "," );
        final float[] vec = new float[ parts.length ];
        int n = 0;
        for( final String p : parts )
        {
            final String t = p.trim();
            if( t.isEmpty() ) continue;
            try { vec[ n++ ] = Float.parseFloat( t ); }
            catch( final NumberFormatException ignored ) {}
        }
        return n == vec.length ? vec : java.util.Arrays.copyOf( vec, n );
    }

    /**
     * Parse {"embeddings":[[..],[..]]} into a float[][]. Returns null if the row
     * count doesn't match the input count -- a short or garbled reply must not
     * silently misalign vectors with the files they were meant to describe.
     */
    private static float[][] parseEmbeddings( final String json, final int expected )
    {
        final int key = json.indexOf( "\"embeddings\"" );
        if( key < 0 ) return null;
        int i = json.indexOf( '[', key );
        if( i < 0 ) return null;
        final List<float[]> rows = new ArrayList<>();
        i++; // step past the outer '['
        while( i < json.length() )
        {
            // advance to the next inner array, or the end of the outer array
            while( i < json.length() && json.charAt( i ) != '[' && json.charAt( i ) != ']' ) i++;
            if( i >= json.length() || json.charAt( i ) == ']' ) break;
            final int end = json.indexOf( ']', i );
            if( end < 0 ) break;
            final String[] parts = json.substring( i + 1, end ).split( "," );
            final float[] vec = new float[ parts.length ];
            int n = 0;
            for( final String p : parts )
            {
                final String t = p.trim();
                if( t.isEmpty() ) continue;
                try { vec[ n++ ] = Float.parseFloat( t ); }
                catch( final NumberFormatException ignored ) {}
            }
            rows.add( n == vec.length ? vec : java.util.Arrays.copyOf( vec, n ) );
            i = end + 1;
        }
        if( rows.size() != expected ) return null;
        return rows.toArray( new float[0][] );
    }

    /** Cosine similarity of two equal-length vectors; -1 on any mismatch. */
    public static double cosine( final float[] a, final float[] b )
    {
        if( a == null || b == null || a.length != b.length ) return -1;
        double dot = 0, na = 0, nb = 0;
        for( int i = 0; i < a.length; i++ )
        {
            dot += (double) a[ i ] * b[ i ];
            na  += (double) a[ i ] * a[ i ];
            nb  += (double) b[ i ] * b[ i ];
        }
        if( na == 0 || nb == 0 ) return -1;
        return dot / ( Math.sqrt( na ) * Math.sqrt( nb ) );
    }

    private static String jsonStr( final String s )
    {
        final StringBuilder sb = new StringBuilder( s.length() + 8 );
        sb.append( '"' );
        for( int i = 0; i < s.length(); i++ )
        {
            final char c = s.charAt( i );
            switch( c )
            {
                case '"':  sb.append( "\\\"" ); break;
                case '\\': sb.append( "\\\\" ); break;
                case '\n': sb.append( "\\n" );  break;
                case '\r': sb.append( "\\r" );  break;
                case '\t': sb.append( "\\t" );  break;
                default:   sb.append( c );      break;
            }
        }
        sb.append( '"' );
        return sb.toString();
    }
}
