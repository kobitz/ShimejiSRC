package com.group_finity.mascot.assistant;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

/**
 * Central AI-provider configuration.
 *
 * <p>Two providers are supported:
 * <ul>
 *   <li>{@code ollama} (default) — the original local Ollama {@code /api/generate} path.</li>
 *   <li>{@code openai} — any OpenAI-compatible {@code /chat/completions} endpoint
 *       (OpenAI, OpenRouter, Groq, Together, LM Studio, llama.cpp server, vLLM, …).</li>
 * </ul>
 *
 * <p>All provider lookups go through this class so a single settings change applies to
 * chat, vision, embeddings and the settings-window model list. Existing keys
 * ({@code OllamaModel}, {@code VisionModel}, {@code OllamaEndpoint}, {@code DriveSemanticModel})
 * keep working unchanged for local users. Remote users get {@code AiBaseUrl},
 * {@code AiApiKey}, {@code AiTextModel}, {@code AiVisionModel}, {@code AiEmbeddingModel}.
 *
 * <p><b>API-key storage:</b> the key is read from, in order:
 * <ol>
 *   <li>{@code SHIMEJI_AI_API_KEY} environment variable</li>
 *   <li>{@code OPENAI_API_KEY} environment variable</li>
 *   <li>{@code conf/api.properties} ({@code apiKey=...}) — written by the Settings UI;
 *       gitignored in the source tree</li>
 *   <li>{@code AiApiKey} in {@code conf/settings.properties} (legacy location, still read)</li>
 * </ol>
 * Environment variables win so a key never has to be written to disk. The Settings UI
 * always persists to {@code conf/api.properties} and removes the legacy {@code AiApiKey}
 * entry from {@code settings.properties}.
 */
public final class AiSettings
{
    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_OPENAI = "openai";

    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_OLLAMA_ENDPOINT = DEFAULT_OLLAMA_BASE_URL + "/api/generate";
    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_OPENAI_MODEL    = "gpt-4o-mini";
    public static final String DEFAULT_OPENAI_EMBEDDING = "text-embedding-3-small";

    /** Fallback text model for the local provider (kept in sync with OllamaClient). */
    public static final String DEFAULT_OLLAMA_MODEL = "gemma4:e2b-it-qat";

    private AiSettings( ) {}

    // ── provider ─────────────────────────────────────────────────────────────

    /** "ollama" or "openai". Unknown values fall back to ollama. */
    public static String provider( )
    {
        final String raw = property( "AiProvider", PROVIDER_OLLAMA ).trim( ).toLowerCase( Locale.ROOT );
        if( raw.equals( PROVIDER_OPENAI ) || raw.equals( "openai-compatible" )
                || raw.equals( "openai_compatible" ) || raw.equals( "api" ) || raw.equals( "remote" ) )
            return PROVIDER_OPENAI;
        return PROVIDER_OLLAMA;
    }

    public static boolean isRemote( )
    {
        return PROVIDER_OPENAI.equals( provider( ) );
    }

    // ── endpoints ────────────────────────────────────────────────────────────

    /** Local Ollama base (no trailing slash, no path), derived from OllamaEndpoint/AiBaseUrl. */
    public static String ollamaBaseUrl( )
    {
        return ollamaBaseFrom( property( "OllamaEndpoint", DEFAULT_OLLAMA_ENDPOINT ) );
    }

    /** Derive a clean Ollama base URL from a bare host or an endpoint. */
    public static String ollamaBaseFrom( final String endpointValue )
    {
        String endpoint = endpointValue == null ? "" : endpointValue.trim( );
        // Accept a bare host, a /api/generate URL, or a base URL.
        int api = endpoint.indexOf( "/api/" );
        if( api >= 0 ) endpoint = endpoint.substring( 0, api );
        while( endpoint.endsWith( "/" ) ) endpoint = endpoint.substring( 0, endpoint.length( ) - 1 );
        if( endpoint.isEmpty( ) ) endpoint = DEFAULT_OLLAMA_BASE_URL;
        return endpoint;
    }

    /** Full URL used for chat/vision requests on the selected provider. */
    public static String chatUrl( )
    {
        if( isRemote( ) )
            return openAiBaseUrl( ) + "/chat/completions";
        return ollamaBaseUrl( ) + "/api/generate";
    }

    /** URL for listing models (Settings window). */
    public static String modelsUrl( )
    {
        if( isRemote( ) )
            return openAiBaseUrl( ) + "/models";
        return ollamaBaseUrl( ) + "/api/tags";
    }

    /** URL for embeddings. */
    public static String embeddingsUrl( )
    {
        if( isRemote( ) )
            return openAiBaseUrl( ) + "/embeddings";
        return ollamaBaseUrl( ) + "/api/embed";
    }

    /**
     * Normalised OpenAI-compatible base URL (includes the version segment).
     * A bare host such as {@code https://api.openai.com} becomes
     * {@code https://api.openai.com/v1}; a pasted full endpoint has its
     * trailing resource path removed so callers can append their own.
     */
    public static String openAiBaseUrl( )
    {
        return normalizeOpenAiBase( firstNonBlank(
            property( "AiBaseUrl", "" ),
            System.getenv( "SHIMEJI_AI_BASE_URL" ),
            System.getenv( "OPENAI_BASE_URL" ),
            DEFAULT_OPENAI_BASE_URL ) );
    }

    /** Normalise a user-entered OpenAI-compatible base URL (public for the Settings UI). */
    public static String normalizeOpenAiBase( final String raw )
    {
        String value = raw == null ? "" : raw.trim( );
        if( value.isEmpty( ) ) value = DEFAULT_OPENAI_BASE_URL;

        // Strip known resource suffixes so both a base URL and a pasted
        // full endpoint work.
        for( final String suffix : new String[]{ "/chat/completions", "/completions", "/models", "/embeddings" } )
            if( value.toLowerCase( Locale.ROOT ).endsWith( suffix ) )
                value = value.substring( 0, value.length( ) - suffix.length( ) );

        while( value.endsWith( "/" ) ) value = value.substring( 0, value.length( ) - 1 );

        // Bare host -> add /v1 (the near-universal OpenAI-compatible layout).
        try
        {
            final URI uri = URI.create( value );
            final String path = uri.getPath( );
            if( path == null || path.isEmpty( ) || path.equals( "/" ) )
                value += "/v1";
        }
        catch( final Exception ignored ) { /* leave as typed; the HTTP layer will report it */ }

        return value;
    }

    // ── models ───────────────────────────────────────────────────────────────

    /** Text/chat model for the selected provider. */
    public static String textModel( )
    {
        if( isRemote( ) )
            return firstNonBlank( property( "AiTextModel", "" ), DEFAULT_OPENAI_MODEL ).trim( );
        return firstNonBlank( property( "OllamaModel", "" ), DEFAULT_OLLAMA_MODEL ).trim( );
    }

    /** Vision model for the selected provider; falls back to the text model. */
    public static String visionModel( )
    {
        if( isRemote( ) )
            return firstNonBlank( property( "AiVisionModel", "" ), textModel( ) ).trim( );
        return firstNonBlank( property( "VisionModel", "" ), DEFAULT_OLLAMA_MODEL ).trim( );
    }

    /** Embedding model for semantic drive search, or "" when disabled. */
    public static String embeddingModel( )
    {
        if( isRemote( ) )
            return firstNonBlank( property( "AiEmbeddingModel", "" ), DEFAULT_OPENAI_EMBEDDING ).trim( );
        return firstNonBlank( property( "DriveSemanticModel", "" ), "nomic-embed-text" ).trim( );
    }

    /**
     * Semantic drive search sends file and folder names to the embeddings endpoint, so in
     * remote mode it stays off unless {@code DriveSemanticRemote=true} is set on purpose.
     */
    public static boolean semanticRemoteAllowed( )
    {
        return "true".equalsIgnoreCase( property( "DriveSemanticRemote", "false" ).trim( ) );
    }

    /**
     * Name of the output-length field for chat requests. api.openai.com's newer models
     * (gpt-5, o-series) reject {@code max_tokens} and want {@code max_completion_tokens};
     * the other OpenAI-compatible servers still expect {@code max_tokens}.
     */
    public static String maxTokensField( )
    {
        try
        {
            final String host = URI.create( openAiBaseUrl( ) ).getHost( );
            if( host != null && host.equalsIgnoreCase( "api.openai.com" ) )
                return "max_completion_tokens";
        }
        catch( final Exception ignored ) { /* fall through */ }
        return "max_tokens";
    }

    /** Identifies the embedding model in use, so cached vectors from another model are never mixed in. */
    public static String embeddingTag( )
    {
        return provider( ) + ":" + embeddingModel( );
    }

    /** True when an API key would be sent over plain http to a host that is not on this machine. */
    public static boolean isInsecureRemote( )
    {
        if( !isRemote( ) ) return false;
        try
        {
            final URI uri = URI.create( openAiBaseUrl( ) );
            final String host = uri.getHost( );
            return "http".equalsIgnoreCase( uri.getScheme( ) ) && host != null
                && !( host.equals( "localhost" ) || host.equals( "127.0.0.1" ) || host.equals( "::1" ) || host.equals( "[::1]" ) );
        }
        catch( final Exception e ) { return false; }
    }

    // ── credentials ──────────────────────────────────────────────────────────

    /** Bearer token for the remote provider, or "" when none is configured. */
    public static String apiKey( )
    {
        String key = firstNonBlank(
            System.getenv( "SHIMEJI_AI_API_KEY" ),
            System.getenv( "OPENAI_API_KEY" ),
            storedApiKey( ) );
        return key == null ? "" : key.trim( );
    }

    /**
     * Key persisted on disk only (conf/api.properties, then legacy AiApiKey in
     * settings.properties). Deliberately excludes environment variables so the
     * Settings UI does not copy an env-supplied secret into a file.
     */
    public static String storedApiKey( )
    {
        return firstNonBlank( apiPropertiesKey( ), property( "AiApiKey", "" ) );
    }

    /**
     * Persist the key to {@code conf/api.properties} (gitignored). Passing an empty
     * value removes the stored key. Any legacy {@code AiApiKey} entry should be
     * removed from settings.properties by the caller.
     */
    public static void storeApiKey( final String key ) throws java.io.IOException
    {
        final Path dir = Paths.get( ".", "conf" );
        if( !java.nio.file.Files.exists( dir ) )
            java.nio.file.Files.createDirectories( dir );
        final Properties p = new Properties( );
        p.setProperty( "apiKey", key == null ? "" : key.trim( ) );
        try( final java.io.OutputStream out = new java.io.FileOutputStream(
                dir.resolve( "api.properties" ).toFile( ) ) )
        {
            p.store( out, "Shimeji AI credentials - do not commit" );
        }
    }

    /** True when the selected remote provider has a bearer token. */
    public static boolean hasApiKey( )
    {
        return !isRemote( ) || !apiKey( ).isEmpty( );
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static String apiPropertiesKey( )
    {
        final Path path = Paths.get( ".", "conf", "api.properties" );
        try( final InputStream in = new FileInputStream( path.toFile( ) ) )
        {
            final Properties p = new Properties( );
            p.load( in );
            return firstNonBlank( p.getProperty( "apiKey" ), p.getProperty( "AiApiKey" ) );
        }
        catch( final Exception ignored )
        {
            return null;
        }
    }

    private static String property( final String key, final String fallback )
    {
        try
        {
            final String v = com.group_finity.mascot.Main.getInstance( ).getProperties( ).getProperty( key );
            return v == null ? fallback : v;
        }
        catch( final Exception | LinkageError ignored )
        {
            // no running app (tests, headless tools): use the default
            return fallback;
        }
    }

    static String firstNonBlank( final String... values )
    {
        if( values != null )
            for( final String v : values )
                if( v != null && !v.trim( ).isEmpty( ) )
                    return v;
        return "";
    }
}
