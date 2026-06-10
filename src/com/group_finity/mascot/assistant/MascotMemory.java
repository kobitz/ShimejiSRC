package com.group_finity.mascot.assistant;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent memory for a single mascot, stored in img/[ImageSet]/conf/memory.json.
 *
 * Structure:
 *   {
 *     "facts":             ["User likes anime", "User works late"],
 *     "emotionalTone":     "warm",          // tone toward the human user
 *     "peerTones":         { "Hornet": "respectful", "Holo": "playful" },
 *     "interactionCount":  42,
 *     "userExchanges":     [               // human <-> mascot turns only
 *       { "role": "user",   "text": "what do you think?" },
 *       { "role": "mascot", "text": "Hmph. Passable." }
 *     ],
 *     "peerExchanges":     [               // shimeji <-> shimeji turns only
 *       { "speaker": "Hornet", "text": "Do you need help?" },
 *       { "role": "mascot",   "text": "No thank you." }
 *     ]
 *   }
 *
 * userExchanges and peerExchanges are each capped at MAX_EXCHANGES pairs.
 * facts is capped at MAX_FACTS entries.
 * Summarization condenses both when they grow too large.
 */
public class MascotMemory
{
    private static final Logger log = Logger.getLogger( MascotMemory.class.getName() );

    private static final int MAX_EXCHANGES    = 6;   // pairs kept in prompt
    private static final int MAX_FACTS        = 12;  // facts kept before summarization
    private static final int MAX_PERMANENT    = 100; // hard cap on permanent memories
    private static final int SUMMARIZE_EVERY  = 20;  // interactions between summarizations

    // ── Per-instance state ────────────────────────────────────────────────────
    private final Path   filePath;
    private final String imageSet;

    private final List<String>              facts             = new ArrayList<>();
    /** Permanent memories: never overwritten, only injected when a keyword matches. */
    private final List<PermanentMemory>     permanentMemories = new ArrayList<>();
    /** Human ↔ mascot turns. Roles: "user" / "mascot". */
    private final List<Map<String, String>> userExchanges     = new ArrayList<>();
    /** Shimeji ↔ shimeji turns. Roles: "speaker" (peer name) / "mascot". */
    private final List<Map<String, String>> peerExchanges = new ArrayList<>();
    /** Emotional tone toward the human user. */
    private String emotionalTone    = "neutral";
    /** Per-peer emotional tone, keyed by peer mascot name. */
    private final Map<String, String>       peerTones     = new LinkedHashMap<>();
    private int    interactionCount = 0;
    private int    sinceLastSummary = 0;

    /** Dirty flag + timer for debounced saves — collapses burst writes into one. */
    private boolean            dirty      = false;
    private java.util.Timer    flushTimer = null;
    private static final long  FLUSH_DELAY_MS = 500;

    // ── Static cache — one instance per imageSet ──────────────────────────────
    private static final Map<String, MascotMemory> CACHE = new HashMap<>();

    public static MascotMemory forImageSet( final String imageSet )
    {
        return CACHE.computeIfAbsent( imageSet, MascotMemory::new );
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    private MascotMemory( final String imageSet )
    {
        this.imageSet = imageSet;
        this.filePath = Paths.get( "img", imageSet, "conf", "memory.json" );
        load();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build the memory block to inject into the system prompt.
     * @param peerName if non-null, includes peer exchanges for that peer and
     *                 uses the per-peer tone instead of the user tone.
     */
    public synchronized String buildMemoryBlock( final String peerName )
    {
        final boolean hasFacts     = !facts.isEmpty();
        final boolean hasUserEx    = !userExchanges.isEmpty();
        final boolean hasPeerEx    = peerName != null && !peerExchanges.isEmpty();
        if( !hasFacts && !hasUserEx && !hasPeerEx ) return "";

        final StringBuilder sb = new StringBuilder();
        sb.append( "\n\n--- MEMORY ---" );

        if( hasFacts )
        {
            sb.append( "\nWhat you know about this user:" );
            for( final String fact : facts )
                sb.append( "\n- " ).append( fact );
        }

        if( hasUserEx )
        {
            sb.append( "\nRecent exchanges with the human user:" );
            for( final Map<String, String> ex : userExchanges )
                sb.append( "\n" ).append( ex.get("role") ).append( ": " ).append( ex.get("text") );
        }

        if( hasPeerEx )
        {
            sb.append( "\nRecent exchanges with " ).append( peerName ).append( ":" );
            for( final Map<String, String> ex : peerExchanges )
            {
                final String role = ex.containsKey("speaker") ? ex.get("speaker") : ex.get("role");
                sb.append( "\n" ).append( role ).append( ": " ).append( ex.get("text") );
            }
        }

        if( peerName != null )
        {
            final String tone = peerTones.getOrDefault( peerName, emotionalTone );
            sb.append( "\nYour tone toward " ).append( peerName ).append( ": " ).append( tone );
        }
        else
        {
            sb.append( "\nYour tone toward the user: " ).append( emotionalTone );
        }

        sb.append( "\n--- END MEMORY ---" );
        return sb.toString();
    }

    /** Convenience overload for user-context prompts (no peer name). */
    public synchronized String buildMemoryBlock()
    {
        return buildMemoryBlock( null );
    }

    /**
     * Lightweight memory block for peer reactions and audio reactions.
     * Includes facts and tone only — omits exchange histories to keep
     * the system prompt short and reduce Ollama prefill cost.
     */
    public synchronized String buildLightMemoryBlock( final String peerName )
    {
        // Peer replies exclude [Observed] media facts: they bleed stale topics into
        // unrelated conversations (a transcript mentioning a game resurfaces minutes
        // later in a reply to a different mascot about something else).
        final List<String> relevant = new ArrayList<>();
        for( final String fact : facts )
            if( peerName == null || !fact.startsWith( "[Observed]" ) )
                relevant.add( fact );
        if( relevant.isEmpty() && peerName == null ) return "";
        final StringBuilder sb = new StringBuilder();
        sb.append( "\n\n--- MEMORY ---" );
        if( !relevant.isEmpty() )
        {
            sb.append( "\nWhat you know about this user:" );
            for( final String fact : relevant )
                sb.append( "\n- " ).append( fact );
        }
        if( peerName != null )
        {
            final String tone = peerTones.getOrDefault( peerName, emotionalTone );
            sb.append( "\nYour tone toward " ).append( peerName ).append( ": " ).append( tone );
        }
        else
        {
            sb.append( "\nYour tone toward the user: " ).append( emotionalTone );
        }
        sb.append( "\n--- END MEMORY ---" );
        return sb.toString();
    }

    /** Record a human ↔ mascot exchange. */
    public synchronized void recordUserExchange( final String userText, final String mascotText )
    {
        final Map<String, String> userEx = new LinkedHashMap<>();
        userEx.put( "role", "user" );
        userEx.put( "text", userText );
        userExchanges.add( userEx );

        final Map<String, String> mascotEx = new LinkedHashMap<>();
        mascotEx.put( "role", "mascot" );
        mascotEx.put( "text", mascotText );
        userExchanges.add( mascotEx );

        while( userExchanges.size() > MAX_EXCHANGES * 2 )
            userExchanges.remove( 0 );

        interactionCount++;
        sinceLastSummary++;
        scheduleSave();
    }

    /**
     * Back-compat alias — routes to recordUserExchange.
     * @deprecated Prefer recordUserExchange or recordPeerExchange.
     */
    @Deprecated
    public synchronized void recordExchange( final String userText, final String mascotText )
    {
        recordUserExchange( userText, mascotText );
    }

    /**
     * Record a shimeji ↔ shimeji exchange.
     * @param peerName  the name of the other mascot (e.g. "Hornet", "Holo")
     * @param peerText  what the peer said
     * @param mascotText  what this mascot replied
     */
    public synchronized void recordPeerExchange( final String peerName,
                                                  final String peerText,
                                                  final String mascotText )
    {
        final Map<String, String> peerEx = new LinkedHashMap<>();
        peerEx.put( "speaker", peerName );
        peerEx.put( "text", peerText );
        peerExchanges.add( peerEx );

        final Map<String, String> mascotEx = new LinkedHashMap<>();
        mascotEx.put( "role", "mascot" );
        mascotEx.put( "text", mascotText );
        peerExchanges.add( mascotEx );

        while( peerExchanges.size() > MAX_EXCHANGES * 2 )
            peerExchanges.remove( 0 );

        interactionCount++;
        sinceLastSummary++;
        scheduleSave();
    }

    /** Add a fact the mascot has learned. Deduplicates loosely. */
    public synchronized void addFact( final String fact )
    {
        if( fact == null || fact.isBlank() ) return;
        final String trimmed = fact.trim();
        // Simple dedup — don't add if very similar fact already exists
        for( final String existing : facts )
            if( existing.equalsIgnoreCase( trimmed ) ) return;
        facts.add( trimmed );
        while( facts.size() > MAX_FACTS ) facts.remove( 0 );
        scheduleSave();
    }

    // ── Permanent memory ─────────────────────────────────────────────────────

    /** A memory that is never overwritten; only injected when a keyword is mentioned. */
    public static final class PermanentMemory
    {
        public final List<String> keywords;
        public final String       content;
        PermanentMemory( final List<String> keywords, final String content )
        {
            this.keywords = Collections.unmodifiableList( new ArrayList<>( keywords ) );
            this.content  = content;
        }
    }

    /**
     * Save a permanent memory. The LLM triggers this via [REMEMBER:kw1,kw2|content].
     * Permanent memories are never removed by summarization; they accumulate up to MAX_PERMANENT.
     */
    public synchronized void addPermanentMemory( final List<String> keywords, final String content )
    {
        if( content == null || content.isBlank() || keywords == null || keywords.isEmpty() ) return;
        // Avoid exact-content duplicates
        for( final PermanentMemory pm : permanentMemories )
            if( pm.content.equalsIgnoreCase( content.trim() ) ) return;
        permanentMemories.add( new PermanentMemory( keywords, content.trim() ) );
        if( permanentMemories.size() > MAX_PERMANENT )
            permanentMemories.remove( 0 );
        scheduleSave();
    }

    /**
     * Returns a prompt block of permanent memories whose keywords appear in text,
     * or an empty string if none match. Case-insensitive whole-word matching.
     */
    public synchronized String buildPermanentMemoryBlock( final String text )
    {
        if( text == null || text.isEmpty() || permanentMemories.isEmpty() ) return "";
        final String lower = text.toLowerCase( java.util.Locale.ROOT );
        final List<String> hits = new ArrayList<>();
        for( final PermanentMemory pm : permanentMemories )
        {
            for( final String kw : pm.keywords )
            {
                // Word-boundary match so "bike" doesn't match "biker"
                if( lower.matches( ".*\\b" + java.util.regex.Pattern.quote( kw.toLowerCase( java.util.Locale.ROOT ) ) + "\\b.*" ) )
                {
                    hits.add( pm.content );
                    break;
                }
            }
        }
        if( hits.isEmpty() ) return "";
        final StringBuilder sb = new StringBuilder( "\n\n--- RECALLED MEMORIES ---" );
        for( final String h : hits )
            sb.append( "\n- " ).append( h );
        sb.append( "\n--- END RECALLED MEMORIES ---" );
        return sb.toString();
    }

    /** Returns an unmodifiable snapshot of all permanent memories (for display/debug). */
    public synchronized List<PermanentMemory> getPermanentMemories()
    {
        return Collections.unmodifiableList( new ArrayList<>( permanentMemories ) );
    }

    /** Update the emotional tone toward the human user. */
    public synchronized void setTone( final String tone )
    {
        if( tone != null && !tone.isBlank() )
            emotionalTone = tone.trim().toLowerCase();
        scheduleSave();
    }

    /**
     * Update the emotional tone toward a specific peer mascot.
     * @param peerName  e.g. "Hornet", "Holo"
     * @param tone      e.g. "warm", "playful", "wary", "respectful"
     */
    public synchronized void setPeerTone( final String peerName, final String tone )
    {
        if( peerName != null && !peerName.isBlank() && tone != null && !tone.isBlank() )
            peerTones.put( peerName.trim(), tone.trim().toLowerCase() );
        scheduleSave();
    }

    /** Returns the emotional tone toward a specific peer, or the user tone if none set. */
    public synchronized String getPeerTone( final String peerName )
    {
        return peerTones.getOrDefault( peerName, emotionalTone );
    }

    /**
     * Returns the set of peer names that have either a recorded tone or exchange history.
     * Used by summarization to know which peers to include.
     */
    public synchronized java.util.Set<String> getKnownPeers()
    {
        final java.util.LinkedHashSet<String> peers = new java.util.LinkedHashSet<>( peerTones.keySet() );
        // Also include peers that appear in peerExchanges even if no tone is set yet
        for( final Map<String, String> ex : peerExchanges )
            if( ex.containsKey( "speaker" ) ) peers.add( ex.get( "speaker" ) );
        return peers;
    }

    /**
     * Builds a summary-ready block of all peer relationships for use in summarization prompts.
     * Includes each peer's tone and their recent exchange snippet.
     */
    public synchronized String buildAllPeerMemoryBlocks()
    {
        final java.util.Set<String> peers = getKnownPeers();
        if( peers.isEmpty() ) return "";
        final StringBuilder sb = new StringBuilder();
        sb.append( "\nPeer relationships:" );
        for( final String peer : peers )
        {
            final String tone = peerTones.getOrDefault( peer, "neutral" );
            sb.append( "\n  " ).append( peer ).append( " (tone: " ).append( tone ).append( ")" );
            // Include most recent exchange snippet for this peer
            for( int i = peerExchanges.size() - 1; i >= 0; i-- )
            {
                final Map<String, String> ex = peerExchanges.get( i );
                if( peer.equals( ex.get( "speaker" ) ) )
                {
                    // Find the mascot reply that follows
                    if( i + 1 < peerExchanges.size() )
                    {
                        final Map<String, String> reply = peerExchanges.get( i + 1 );
                        sb.append( ": \"" ).append( ex.get("text") )
                          .append( "\" -> \"" ).append( reply.get("text") ).append( "\"" );
                    }
                    break;
                }
            }
        }
        return sb.toString();
    }

    public synchronized int  getInteractionCount() { return interactionCount; }
    public synchronized boolean needsSummarization() { return sinceLastSummary >= SUMMARIZE_EVERY; }
    public synchronized void    resetSummarizationCounter() { sinceLastSummary = 0; scheduleSave(); }

    public synchronized String getImageSet() { return imageSet; }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load()
    {
        if( !Files.exists( filePath ) ) return;
        try
        {
            final String json = new String( Files.readAllBytes( filePath ), StandardCharsets.UTF_8 );
            parseJson( json );
            log.info( "[Memory] Loaded for " + imageSet + " — " + facts.size() + " facts, "
                + ( userExchanges.size() + peerExchanges.size() ) / 2 + " exchanges" );
        }
        catch( final Exception e )
        {
            log.log( Level.WARNING, "[Memory] Failed to load for " + imageSet, e );
        }
    }

    /** Mark dirty and schedule a flush after FLUSH_DELAY_MS if not already pending. */
    private synchronized void scheduleSave()
    {
        dirty = true;
        if( flushTimer != null ) return;
        flushTimer = new java.util.Timer( "memory-flush", true ); // daemon
        flushTimer.schedule( new java.util.TimerTask()
        {
            public void run() { flushDirty(); }
        }, FLUSH_DELAY_MS );
    }

    private synchronized void flushDirty()
    {
        flushTimer = null;
        if( !dirty ) return;
        dirty = false;
        save();
    }

    public synchronized void save()
    {
        try
        {
            Files.createDirectories( filePath.getParent() );
            final String json = toJson();
            Files.write( filePath, json.getBytes( StandardCharsets.UTF_8 ) );
        }
        catch( final Exception e )
        {
            log.log( Level.WARNING, "[Memory] Failed to save for " + imageSet, e );
        }
    }

    // ── Minimal JSON serialization (no external deps) ─────────────────────────

    private String toJson()
    {
        final StringBuilder sb = new StringBuilder( "{\n" );

        sb.append( "  \"interactionCount\": " ).append( interactionCount ).append( ",\n" );
        sb.append( "  \"sinceLastSummary\": " ).append( sinceLastSummary ).append( ",\n" );
        sb.append( "  \"emotionalTone\": " ).append( jsonStr( emotionalTone ) ).append( ",\n" );

        // peerTones
        sb.append( "  \"peerTones\": {" );
        boolean firstPt = true;
        for( final Map.Entry<String, String> pt : peerTones.entrySet() )
        {
            if( !firstPt ) sb.append( "," );
            firstPt = false;
            sb.append( "\n    " ).append( jsonStr( pt.getKey() ) )
              .append( ": " ).append( jsonStr( pt.getValue() ) );
        }
        sb.append( peerTones.isEmpty() ? "" : "\n" ).append( "  },\n" );

        sb.append( "  \"facts\": [" );
        for( int i = 0; i < facts.size(); i++ )
        {
            if( i > 0 ) sb.append( "," );
            sb.append( "\n    " ).append( jsonStr( facts.get(i) ) );
        }
        sb.append( facts.isEmpty() ? "" : "\n" ).append( "  ],\n" );

        // userExchanges
        sb.append( "  \"userExchanges\": [" );
        for( int i = 0; i < userExchanges.size(); i++ )
        {
            if( i > 0 ) sb.append( "," );
            final Map<String, String> ex = userExchanges.get(i);
            sb.append( "\n    {\"role\":" ).append( jsonStr( ex.get("role") ) )
              .append( ",\"text\":" ).append( jsonStr( ex.get("text") ) ).append( "}" );
        }
        sb.append( userExchanges.isEmpty() ? "" : "\n" ).append( "  ],\n" );

        // peerExchanges
        sb.append( "  \"peerExchanges\": [" );
        for( int i = 0; i < peerExchanges.size(); i++ )
        {
            if( i > 0 ) sb.append( "," );
            final Map<String, String> ex = peerExchanges.get(i);
            if( ex.containsKey("speaker") )
                sb.append( "\n    {\"speaker\":" ).append( jsonStr( ex.get("speaker") ) )
                  .append( ",\"text\":" ).append( jsonStr( ex.get("text") ) ).append( "}" );
            else
                sb.append( "\n    {\"role\":" ).append( jsonStr( ex.get("role") ) )
                  .append( ",\"text\":" ).append( jsonStr( ex.get("text") ) ).append( "}" );
        }
        sb.append( peerExchanges.isEmpty() ? "" : "\n" ).append( "  ],\n" );

        // permanentMemories
        sb.append( "  \"permanentMemories\": [" );
        for( int i = 0; i < permanentMemories.size(); i++ )
        {
            if( i > 0 ) sb.append( "," );
            final PermanentMemory pm = permanentMemories.get(i);
            sb.append( "\n    {\"keywords\":[" );
            for( int k = 0; k < pm.keywords.size(); k++ )
            {
                if( k > 0 ) sb.append( "," );
                sb.append( jsonStr( pm.keywords.get(k) ) );
            }
            sb.append( "],\"content\":" ).append( jsonStr( pm.content ) ).append( "}" );
        }
        sb.append( permanentMemories.isEmpty() ? "" : "\n" ).append( "  ]\n}" );

        return sb.toString();
    }

    private void parseJson( final String json )
    {
        try
        {
            interactionCount = parseInt( json, "interactionCount", 0 );
            sinceLastSummary = parseInt( json, "sinceLastSummary", 0 );
            emotionalTone    = parseStr( json, "emotionalTone", "neutral" );

            peerTones.clear();
            parsePeerTones( json );

            facts.clear();
            facts.addAll( parseStrArray( json, "facts" ) );

            userExchanges.clear();
            parseExchangeList( json, "userExchanges", userExchanges );

            peerExchanges.clear();
            parseExchangeList( json, "peerExchanges", peerExchanges );

            // Back-compat: if old "recentExchanges" key exists and userExchanges is empty, migrate it
            if( userExchanges.isEmpty() )
                parseExchangeList( json, "recentExchanges", userExchanges );

            permanentMemories.clear();
            parsePermanentMemories( json );
        }
        catch( final Exception e )
        {
            log.log( Level.WARNING, "[Memory] Parse error for " + imageSet, e );
        }
    }

    private static int parseInt( final String json, final String key, final int def )
    {
        final String pat = "\"" + key + "\"";
        final int idx = json.indexOf( pat );
        if( idx < 0 ) return def;
        final int colon = json.indexOf( ':', idx );
        if( colon < 0 ) return def;
        final int start = colon + 1;
        final int end   = json.indexOf( ',', start );
        final String val = json.substring( start, end < 0 ? json.indexOf( '}', start ) : end ).trim();
        try { return Integer.parseInt( val ); } catch( NumberFormatException e ) { return def; }
    }

    private static String parseStr( final String json, final String key, final String def )
    {
        final String pat = "\"" + key + "\"";
        final int idx = json.indexOf( pat );
        if( idx < 0 ) return def;
        final int colon = json.indexOf( ':', idx );
        final int qOpen = json.indexOf( '"', colon + 1 );
        if( qOpen < 0 ) return def;
        int qClose = -1;
        for( int i = qOpen + 1; i < json.length(); i++ )
        {
            final char c = json.charAt( i );
            if( c == '\\' ) { i++; continue; }
            if( c == '"' )  { qClose = i; break; }
        }
        if( qClose < 0 ) return def;
        return jsonUnescape( json.substring( qOpen + 1, qClose ) );
    }

    private static List<String> parseStrArray( final String json, final String key )
    {
        final List<String> result = new ArrayList<>();
        final int arrStart = json.indexOf( "\"" + key + "\"" );
        if( arrStart < 0 ) return result;
        final int bracketOpen = json.indexOf( '[', arrStart );
        if( bracketOpen < 0 ) return result;
        // Find the closing ] while skipping ] and \" inside quoted strings
        int bracketClose = -1;
        boolean inStr = false;
        for( int i = bracketOpen + 1; i < json.length(); i++ )
        {
            final char c = json.charAt( i );
            if( inStr )
            {
                if( c == '\\' ) { i++; continue; }
                if( c == '"'  ) inStr = false;
            }
            else
            {
                if( c == '"' ) inStr = true;
                else if( c == ']' ) { bracketClose = i; break; }
            }
        }
        if( bracketClose < 0 ) return result;
        final String arr = json.substring( bracketOpen + 1, bracketClose );
        int pos = 0;
        while( pos < arr.length() )
        {
            final int q1 = arr.indexOf( '"', pos );
            if( q1 < 0 ) break;
            int q2 = -1;
            for( int i = q1 + 1; i < arr.length(); i++ )
            {
                final char c = arr.charAt( i );
                if( c == '\\' ) { i++; continue; }
                if( c == '"' )  { q2 = i; break; }
            }
            if( q2 < 0 ) break;
            result.add( jsonUnescape( arr.substring( q1 + 1, q2 ) ) );
            pos = q2 + 1;
        }
        return result;
    }

    /**
     * Parse a JSON array of exchange objects into target list.
     * Handles both {"role":...,"text":...} and {"speaker":...,"text":...} shapes.
     */
    private void parseExchangeList( final String json, final String key,
                                     final List<Map<String, String>> target )
    {
        final int arrStart = json.indexOf( "\"" + key + "\"" );
        if( arrStart < 0 ) return;
        final int bracketOpen = json.indexOf( '[', arrStart );
        if( bracketOpen < 0 ) return;
        // Find matching closing bracket
        int bracketClose = -1;
        int depth = 0;
        boolean inStr = false;
        for( int i = bracketOpen; i < json.length(); i++ )
        {
            final char c = json.charAt( i );
            if( inStr ) { if( c == '\\' ) i++; else if( c == '"' ) inStr = false; continue; }
            if( c == '"' ) { inStr = true; continue; }
            if( c == '[' ) depth++;
            else if( c == ']' ) { if( --depth == 0 ) { bracketClose = i; break; } }
        }
        if( bracketClose < 0 ) return;
        final String arr = json.substring( bracketOpen + 1, bracketClose );
        int pos = 0;
        while( pos < arr.length() )
        {
            final int objOpen = arr.indexOf( '{', pos );
            if( objOpen < 0 ) break;
            int objClose = -1;
            boolean inObjStr = false;
            for( int i = objOpen + 1; i < arr.length(); i++ )
            {
                final char c = arr.charAt( i );
                if( inObjStr ) { if( c == '\\' ) i++; else if( c == '"' ) inObjStr = false; continue; }
                if( c == '"' ) { inObjStr = true; continue; }
                if( c == '}' ) { objClose = i; break; }
            }
            if( objClose < 0 ) break;
            final String obj = "{" + arr.substring( objOpen + 1, objClose ) + "}";
            final String text    = parseStr( obj, "text",    "" );
            final String role    = parseStr( obj, "role",    "" );
            final String speaker = parseStr( obj, "speaker", "" );
            if( !text.isEmpty() )
            {
                final Map<String, String> ex = new LinkedHashMap<>();
                if( !speaker.isEmpty() )
                    ex.put( "speaker", speaker );
                else
                    ex.put( "role", role.isEmpty() ? "mascot" : role );
                ex.put( "text", text );
                target.add( ex );
            }
            pos = objClose + 1;
        }
    }

    private void parsePermanentMemories( final String json )
    {
        final int arrStart = json.indexOf( "\"permanentMemories\"" );
        if( arrStart < 0 ) return;
        final int bracketOpen = json.indexOf( '[', arrStart );
        if( bracketOpen < 0 ) return;
        int bracketClose = -1;
        int depth = 0;
        boolean inStr = false;
        for( int i = bracketOpen; i < json.length(); i++ )
        {
            final char c = json.charAt( i );
            if( inStr ) { if( c == '\\' ) i++; else if( c == '"' ) inStr = false; continue; }
            if( c == '"' ) { inStr = true; continue; }
            if( c == '[' ) depth++;
            else if( c == ']' ) { if( --depth == 0 ) { bracketClose = i; break; } }
        }
        if( bracketClose < 0 ) return;
        final String arr = json.substring( bracketOpen + 1, bracketClose );
        int pos = 0;
        while( pos < arr.length() )
        {
            final int objOpen = arr.indexOf( '{', pos );
            if( objOpen < 0 ) break;
            // Find matching close brace, respecting nested brackets
            int objClose = -1;
            int d2 = 0;
            boolean inS2 = false;
            for( int i = objOpen; i < arr.length(); i++ )
            {
                final char c = arr.charAt( i );
                if( inS2 ) { if( c == '\\' ) i++; else if( c == '"' ) inS2 = false; continue; }
                if( c == '"' ) { inS2 = true; continue; }
                if( c == '{' ) d2++;
                else if( c == '}' ) { if( --d2 == 0 ) { objClose = i; break; } }
            }
            if( objClose < 0 ) break;
            final String obj = arr.substring( objOpen, objClose + 1 );
            final List<String> kws = parseStrArray( obj, "keywords" );
            final String content   = parseStr( obj, "content", "" );
            if( !kws.isEmpty() && !content.isEmpty() )
                permanentMemories.add( new PermanentMemory( kws, content ) );
            pos = objClose + 1;
        }
    }

    /** Parse the peerTones object from the JSON root. */
    private void parsePeerTones( final String json )
    {
        final int keyIdx = json.indexOf( "\"peerTones\"" );
        if( keyIdx < 0 ) return;
        final int braceOpen = json.indexOf( '{', keyIdx );
        if( braceOpen < 0 ) return;
        int braceClose = -1;
        int depth = 0;
        boolean inStr = false;
        for( int i = braceOpen; i < json.length(); i++ )
        {
            final char c = json.charAt( i );
            if( inStr ) { if( c == '\\' ) i++; else if( c == '"' ) inStr = false; continue; }
            if( c == '"' ) { inStr = true; continue; }
            if( c == '{' ) depth++;
            else if( c == '}' ) { if( --depth == 0 ) { braceClose = i; break; } }
        }
        if( braceClose < 0 ) return;
        // Parse key-value string pairs inside the object
        final String obj = json.substring( braceOpen + 1, braceClose );
        int pos = 0;
        while( pos < obj.length() )
        {
            final int q1 = obj.indexOf( '"', pos );
            if( q1 < 0 ) break;
            int q2 = -1;
            for( int i = q1 + 1; i < obj.length(); i++ )
            { final char c = obj.charAt(i); if( c == '\\' ) i++; else if( c == '"' ) { q2 = i; break; } }
            if( q2 < 0 ) break;
            final String peerKey = jsonUnescape( obj.substring( q1 + 1, q2 ) );
            final int colon = obj.indexOf( ':', q2 );
            if( colon < 0 ) break;
            final int q3 = obj.indexOf( '"', colon );
            if( q3 < 0 ) break;
            int q4 = -1;
            for( int i = q3 + 1; i < obj.length(); i++ )
            { final char c = obj.charAt(i); if( c == '\\' ) i++; else if( c == '"' ) { q4 = i; break; } }
            if( q4 < 0 ) break;
            final String peerVal = jsonUnescape( obj.substring( q3 + 1, q4 ) );
            if( !peerKey.isEmpty() && !peerVal.isEmpty() )
                peerTones.put( peerKey, peerVal );
            pos = q4 + 1;
        }
    }

    private static String jsonUnescape( final String raw )
    {
        final StringBuilder sb = new StringBuilder( raw.length() );
        for( int i = 0; i < raw.length(); i++ )
        {
            final char c = raw.charAt( i );
            if( c == '\\' && i + 1 < raw.length() )
            {
                final char next = raw.charAt( i + 1 );
                switch( next )
                {
                    case '"':  sb.append( '"' );  i++; break;
                    case '\\': sb.append( '\\' ); i++; break;
                    case 'n':  sb.append( '\n' ); i++; break;
                    case 'r':  sb.append( '\r' ); i++; break;
                    case 't':  sb.append( '\t' ); i++; break;
                    default:   sb.append( c );         break;
                }
            }
            else
            {
                sb.append( c );
            }
        }
        return sb.toString();
    }

    private static String jsonStr( final String s )
    {
        if( s == null ) return "\"\"";
        return "\"" + s.replace( "\\", "\\\\" )
                       .replace( "\"", "\\\"" )
                       .replace( "\n", "\\n" )
                       .replace( "\r", "" ) + "\"";
    }
}
