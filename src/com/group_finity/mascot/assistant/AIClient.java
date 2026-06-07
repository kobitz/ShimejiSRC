package com.group_finity.mascot.assistant;

/**
 * Common interface for AI backend clients (Ollama, Claude API, etc.).
 * Implementations are expected to be async and queue-based.
 */
public interface AIClient
{
    interface Callback
    {
        void onResponse( String text );
        void onError( String message );
    }

    /** Queue a text-only generation request. */
    void generate( String systemPrompt, String userMessage, Callback callback );

    /** Queue a text-only generation request with a custom token limit. */
    void generate( String systemPrompt, String userMessage, int maxTokens, Callback callback );

    /**
     * Submit a vision request with a base64-encoded screenshot.
     * visionModel is a hint for backends that use a separate vision model (Ollama).
     * Backends that handle vision natively (Claude) may ignore it.
     */
    void generateWithImage( String systemPrompt, String userMessage,
                            String imageBase64, String visionModel, Callback callback );
}
