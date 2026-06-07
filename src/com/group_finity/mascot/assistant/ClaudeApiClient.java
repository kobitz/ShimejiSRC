package com.group_finity.mascot.assistant;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async client for the Anthropic Messages API.
 * Uses a bounded queue (depth 5) and a single worker thread, mirroring OllamaClient.
 */
public class ClaudeApiClient implements AIClient
{
    private static final Logger log = Logger.getLogger( ClaudeApiClient.class.getName() );

    public static final String DEFAULT_MODEL   = "claude-haiku-4-5-20251001";
    public static final String MESSAGES_URL    = "https://api.anthropic.com/v1/messages";
    public static final String API_VERSION     = "2023-06-01";

    private static final int  TIMEOUT_MS          = 30_000;
    private static final int  MAX_TOKENS          = 150;
    private static final long DISPATCH_INTERVAL_MS = 1_000L;
    private static final int  MAX_QUEUE_DEPTH     = 5;

    private static final class Request
    {
        final String   system;
        final String   user;
        final String   imageBase64; // null for text-only
        final int      maxTokens;
        final Callback callback;

        Request( String system, String user, int maxTokens, Callback callback )
        {
            this( system, user, null, maxTokens, callback );
        }

        Request( String system, String user, String imageBase64, int maxTokens, Callback callback )
        {
            this.system      = system;
            this.user        = user;
            this.imageBase64 = imageBase64;
            this.maxTokens   = maxTokens;
            this.callback    = callback;
        }
    }

    private final String apiKey;
    private final String model;

    private final BlockingQueue<Request> requestQueue =
        new ArrayBlockingQueue<>( MAX_QUEUE_DEPTH );

    public ClaudeApiClient( final String apiKey )
    {
        this( apiKey, DEFAULT_MODEL );
    }

    public ClaudeApiClient( final String apiKey, final String model )
    {
        this.apiKey = apiKey;
        this.model  = model;

        final Thread worker = new Thread( this::runQueueWorker, "claude-queue-worker" );
        worker.setDaemon( true );
        worker.start();
    }

    @Override
    public void generate( final String systemPrompt, final String userMessage,
                          final Callback callback )
    {
        generate( systemPrompt, userMessage, MAX_TOKENS, callback );
    }

    @Override
    public void generate( final String systemPrompt, final String userMessage,
                          final int maxTokens, final Callback callback )
    {
        enqueue( new Request( systemPrompt, userMessage, maxTokens, callback ) );
    }

    @Override
    public void generateWithImage( final String systemPrompt, final String userMessage,
                                   final String imageBase64, final String visionModel,
                                   final Callback callback )
    {
        // Claude handles vision natively; visionModel hint is ignored.
        final Thread t = new Thread( () ->
        {
            try
            {
                final String response = sendRequest(
                    systemPrompt, userMessage, imageBase64, MAX_TOKENS * 3 );
                callback.onResponse( response );
            }
            catch( IOException e )
            {
                callback.onError( buildFriendlyError( e ) );
            }
        }, "claude-vision-worker" );
        t.setDaemon( true );
        t.start();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void enqueue( final Request req )
    {
        if( !requestQueue.offer( req ) )
        {
            final Request dropped = requestQueue.poll();
            if( dropped != null )
                log.warning( "[ClaudeQueue] Queue full — dropped oldest pending request." );
            requestQueue.offer( req );
        }
    }

    private void runQueueWorker()
    {
        long lastDispatchMs = 0L;
        while( !Thread.currentThread().isInterrupted() )
        {
            try
            {
                final Request req = requestQueue.take();
                final long wait = DISPATCH_INTERVAL_MS
                    - ( System.currentTimeMillis() - lastDispatchMs );
                if( wait > 0 )
                    Thread.sleep( wait );

                lastDispatchMs = System.currentTimeMillis();
                log.log( Level.FINE, "[ClaudeQueue] Dispatching request, queue depth={0}",
                         requestQueue.size() );

                try
                {
                    final String response = sendRequest(
                        req.system, req.user, req.imageBase64, req.maxTokens );
                    req.callback.onResponse( response );
                }
                catch( IOException e )
                {
                    log.log( Level.WARNING, "Claude API request failed: {0}", e.getMessage() );
                    req.callback.onError( buildFriendlyError( e ) );
                }
            }
            catch( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private String sendRequest( final String system, final String user,
                                final String imageBase64, final int maxTokens )
        throws IOException
    {
        final String body = buildJson( system, user, imageBase64, maxTokens );
        final byte[] bodyBytes = body.getBytes( StandardCharsets.UTF_8 );

        final URL url = new URL( MESSAGES_URL );
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod( "POST" );
        conn.setRequestProperty( "Content-Type", "application/json; charset=utf-8" );
        conn.setRequestProperty( "x-api-key", apiKey );
        conn.setRequestProperty( "anthropic-version", API_VERSION );
        conn.setConnectTimeout( TIMEOUT_MS );
        conn.setReadTimeout( TIMEOUT_MS );
        conn.setDoOutput( true );

        try ( OutputStream os = conn.getOutputStream() )
        {
            os.write( bodyBytes );
        }

        final int status = conn.getResponseCode();
        if( status != 200 )
        {
            final StringBuilder errBody = new StringBuilder();
            try ( BufferedReader br = new BufferedReader(
                    new InputStreamReader( conn.getErrorStream(), StandardCharsets.UTF_8 ) ) )
            {
                String line;
                while( ( line = br.readLine() ) != null )
                    errBody.append( line );
            }
            throw new IOException( "HTTP " + status + " from Claude API: " + errBody );
        }

        final StringBuilder sb = new StringBuilder();
        try ( BufferedReader br = new BufferedReader(
                new InputStreamReader( conn.getInputStream(), StandardCharsets.UTF_8 ) ) )
        {
            String line;
            while( ( line = br.readLine() ) != null )
                sb.append( line );
        }

        return parseResponse( sb.toString() );
    }

    /**
     * Builds the Anthropic Messages API JSON body.
     * System prompt goes in the top-level "system" field.
     * User content is either a plain string or a multimodal array with an image block.
     */
    private String buildJson( final String system, final String user,
                              final String imageBase64, final int maxTokens )
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( "{\"model\":" ).append( jsonStr( model ) ).append( ',' );
        sb.append( "\"max_tokens\":" ).append( maxTokens ).append( ',' );
        sb.append( "\"system\":" ).append( jsonStr( system ) ).append( ',' );
        sb.append( "\"messages\":[{\"role\":\"user\",\"content\":" );

        if( imageBase64 != null )
        {
            sb.append( "[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":" );
            sb.append( jsonStr( imageBase64 ) );
            sb.append( "}}," );
            sb.append( "{\"type\":\"text\",\"text\":" ).append( jsonStr( user ) ).append( "}]" );
        }
        else
        {
            sb.append( jsonStr( user ) );
        }

        sb.append( "}]}" );
        return sb.toString();
    }

    /**
     * Parses the text from the first content block in the Anthropic Messages API response.
     * Expected shape: {"content":[{"type":"text","text":"..."}],...}
     */
    private String parseResponse( final String json )
    {
        // Find "content":[ then the first "text":" value after it
        final int contentIdx = json.indexOf( "\"content\":" );
        if( contentIdx < 0 )
            return "(no content field in Claude response)";

        final int textKey = json.indexOf( "\"text\":", contentIdx );
        if( textKey < 0 )
            return "(no text block in Claude response)";

        int i = textKey + 7; // skip past "text":
        while( i < json.length() && json.charAt( i ) == ' ' ) i++;
        if( i >= json.length() || json.charAt( i ) != '"' )
            return "(unexpected Claude response format)";

        final StringBuilder result = new StringBuilder();
        i++; // skip opening "
        while( i < json.length() )
        {
            final char c = json.charAt( i );
            if( c == '\\' && i + 1 < json.length() )
            {
                final char next = json.charAt( i + 1 );
                switch( next )
                {
                    case '"':  result.append( '"' );  break;
                    case '\\': result.append( '\\' ); break;
                    case 'n':  result.append( '\n' ); break;
                    case 't':  result.append( '\t' ); break;
                    default:   result.append( next ); break;
                }
                i += 2;
            }
            else if( c == '"' )
            {
                break;
            }
            else
            {
                result.append( c );
                i++;
            }
        }
        return result.toString().trim();
    }

    private static String jsonStr( final String s )
    {
        final StringBuilder sb = new StringBuilder( s.length() + 10 );
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

    private static String buildFriendlyError( final IOException e )
    {
        final String msg = e.getMessage();
        if( msg != null && msg.contains( "401" ) )
            return "Claude API key is invalid or missing.";
        if( msg != null && msg.contains( "429" ) )
            return "Claude API rate limit hit — try again shortly.";
        if( msg != null && msg.contains( "UnknownHost" ) )
            return "Can't reach api.anthropic.com — check your internet connection.";
        return "Claude API error: " + ( msg != null ? msg : e.getClass().getSimpleName() );
    }
}
