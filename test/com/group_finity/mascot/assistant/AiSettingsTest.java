package com.group_finity.mascot.assistant;

public class AiSettingsTest
{
    static int fails = 0;

    static void eq( String what, Object want, Object got )
    {
        if( !want.equals( got ) )
        {
            fails++;
            System.out.println( "FAIL " + what + ": want [" + want + "] got [" + got + "]" );
        }
    }

    public static void main( String[ ] args )
    {
        // URL normalisation: bare host, base URL, and a pasted full endpoint all work
        eq( "bare host", "https://api.openai.com/v1", AiSettings.normalizeOpenAiBase( "https://api.openai.com" ) );
        eq( "trailing slash", "https://openrouter.ai/api/v1", AiSettings.normalizeOpenAiBase( "https://openrouter.ai/api/v1/" ) );
        eq( "pasted chat endpoint", "http://localhost:1234/v1", AiSettings.normalizeOpenAiBase( "http://localhost:1234/v1/chat/completions" ) );
        eq( "pasted models endpoint", "https://api.groq.com/openai/v1", AiSettings.normalizeOpenAiBase( "https://api.groq.com/openai/v1/models" ) );
        eq( "blank uses default", "https://api.openai.com/v1", AiSettings.normalizeOpenAiBase( "  " ) );
        eq( "null uses default", "https://api.openai.com/v1", AiSettings.normalizeOpenAiBase( null ) );

        // Ollama base derivation from the old endpoint setting
        eq( "ollama endpoint", "http://localhost:11434", AiSettings.ollamaBaseFrom( "http://localhost:11434/api/generate" ) );
        eq( "ollama bare", "http://box:11434", AiSettings.ollamaBaseFrom( "http://box:11434/" ) );
        eq( "ollama blank", "http://localhost:11434", AiSettings.ollamaBaseFrom( "" ) );

        // Defaults with no Main instance (local mode, nothing remote enabled)
        eq( "default provider", "ollama", AiSettings.provider( ) );
        eq( "default not remote", false, AiSettings.isRemote( ) );
        eq( "no key needed locally", true, AiSettings.hasApiKey( ) );
        eq( "remote drive embeddings are opt-in", false, AiSettings.semanticRemoteAllowed( ) );
        eq( "not insecure when local", false, AiSettings.isInsecureRemote( ) );
        eq( "embedding tag", "ollama:nomic-embed-text", AiSettings.embeddingTag( ) );
        eq( "chat url", "http://localhost:11434/api/generate", AiSettings.chatUrl( ) );

        System.out.println( fails == 0 ? "AiSettingsTest: all passed" : "AiSettingsTest: " + fails + " failed" );
        System.exit( fails == 0 ? 0 : 1 );
    }
}
