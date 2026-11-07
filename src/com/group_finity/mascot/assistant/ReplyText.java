package com.group_finity.mascot.assistant;

/**
 * Pure text helpers for assistant replies: output cleanup, tag and timer parsing, persona
 * pronoun rewriting, and the small query classifiers that decide what a user message is about.
 *
 * <p>Everything here is a static function of its arguments (plus compiled-once regex tables), so
 * it can be tested without a mascot, a window or Ollama. Extracted from {@code Mascot} with no
 * change in behavior.
 */
public final class ReplyText
{
    private ReplyText( ) { }

    /**
     * Returns true if two audio transcripts share > 70% word overlap.
     * Used to skip reacting when the rolling buffer yields essentially the same content
     * across consecutive fires (same background audio, or repeated Whisper hallucination).
     */
    public static boolean isAudioTranscriptDuplicate( final String a, final String b )
    {
        if( a == null || b == null || b.isEmpty() ) return false;
        final String[] wa = a.toLowerCase().split( "\\W+" );
        final String[] wb = b.toLowerCase().split( "\\W+" );
        if( wa.length < 3 || wb.length < 3 ) return false;
        final java.util.Set<String> sa = new java.util.HashSet<>( java.util.Arrays.asList( wa ) );
        int overlap = 0;
        for( final String w : wb ) if( sa.contains( w ) ) overlap++;
        return (float) overlap / Math.min( wa.length, wb.length ) > 0.70f;
    }

    public static String stripActionTag( final String text )
    {
        if( text == null ) return text;
        // Case-insensitive: catches [ACTION:...], [Action:...], [blink], [Gasping: Startled], etc.
        return text.replaceAll( "(?i)\\[[a-zA-Z][^\\]]*\\]", "" ).trim();
    }

    /**
     * First-person rewrite table: {regex, suffix appended to the mascot name}.
     * Order matters — contractions before bare-I, catch-all I last, pronouns after.
     * Patterns are name-independent and compiled once (see REWRITE_PATTERNS);
     * the old replaceAll chain recompiled ~50 regexes on every utterance.
     */
    private static final String[][] FIRST_PERSON_REWRITES = {
        { "\\bI'm not\\b", " is not" },
        { "\\bI'm\\b",     " is" },
        { "\\bI've\\b",    " has" },
        { "\\bI'll\\b",    " will" },
        { "\\bI'd\\b",     " would" },
        // be / have
        { "\\bI am\\b",   " is" },
        { "\\bI was\\b",  " was" },
        { "\\bI have\\b", " has" },
        { "\\bI had\\b",  " had" },
        // do
        { "\\bI do\\b",  " does" },
        { "\\bI did\\b", " did" },
        // modals (person-invariant)
        { "\\bI can\\b",    " can" },
        { "\\bI will\\b",   " will" },
        { "\\bI would\\b",  " would" },
        { "\\bI could\\b",  " could" },
        { "\\bI should\\b", " should" },
        { "\\bI might\\b",  " might" },
        { "\\bI must\\b",   " must" },
        // common present-tense verbs
        { "\\bI think\\b",      " thinks" },
        { "\\bI know\\b",       " knows" },
        { "\\bI want\\b",       " wants" },
        { "\\bI need\\b",       " needs" },
        { "\\bI like\\b",       " likes" },
        { "\\bI love\\b",       " loves" },
        { "\\bI hate\\b",       " hates" },
        { "\\bI feel\\b",       " feels" },
        { "\\bI hope\\b",       " hopes" },
        { "\\bI see\\b",        " sees" },
        { "\\bI say\\b",        " says" },
        { "\\bI mean\\b",       " means" },
        { "\\bI get\\b",        " gets" },
        { "\\bI wonder\\b",     " wonders" },
        { "\\bI understand\\b", " understands" },
        { "\\bI remember\\b",   " remembers" },
        { "\\bI believe\\b",    " believes" },
        { "\\bI find\\b",       " finds" },
        { "\\bI guess\\b",      " guesses" },
        { "\\bI suggest\\b",    " suggests" },
        { "\\bI prefer\\b",     " prefers" },
        { "\\bI wish\\b",       " wishes" },
        { "\\bI acknowledge\\b", " acknowledges" },
        { "\\bI agree\\b",      " agrees" },
        { "\\bI notice\\b",     " notices" },
        { "\\bI suppose\\b",    " supposes" },
        { "\\bI doubt\\b",      " doubts" },
        { "\\bI admit\\b",      " admits" },
        { "\\bI expect\\b",     " expects" },
        { "\\bI detect\\b",     " detects" },
        // catch-all I -> name (may leave unconjugated verbs; acceptable)
        { "\\bI\\b", "" },
        // object / possessive pronouns
        { "\\b[Mm]yself\\b", "" },
        { "\\b[Mm]ine\\b",   "'s" },
        { "\\b[Mm]y\\b",     "'s" },
        { "\\b[Mm]e\\b",     "" },
    };

    /** Compiled once from FIRST_PERSON_REWRITES, same order. */
    private static final java.util.regex.Pattern[] REWRITE_PATTERNS;

    static
    {
        REWRITE_PATTERNS = new java.util.regex.Pattern[ FIRST_PERSON_REWRITES.length ];
        for( int i = 0; i < FIRST_PERSON_REWRITES.length; i++ )
            REWRITE_PATTERNS[i] = java.util.regex.Pattern.compile( FIRST_PERSON_REWRITES[i][0] );
    }

    /** Name-contraction patterns ("Paimon'm" etc.), cached per mascot name. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern[]>
        NAME_CONTRACTION_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] NAME_CONTRACTION_SUFFIXES = { " is", " will", " has", " would" };

    public static String rewriteFirstPerson( String s, final String name )
    {
        // Contractions onto the name directly: "Paimon'm", "Paimon'll", "Paimon've", "Paimon'd"
        final java.util.regex.Pattern[] namePats = NAME_CONTRACTION_CACHE.computeIfAbsent( name, n ->
        {
            final String q = java.util.regex.Pattern.quote( n );
            return new java.util.regex.Pattern[] {
                java.util.regex.Pattern.compile( "\\b" + q + "'m\\b" ),
                java.util.regex.Pattern.compile( "\\b" + q + "'ll\\b" ),
                java.util.regex.Pattern.compile( "\\b" + q + "'ve\\b" ),
                java.util.regex.Pattern.compile( "\\b" + q + "'d\\b" ),
            };
        });
        for( int i = 0; i < namePats.length; i++ )
            s = namePats[i].matcher( s ).replaceAll(
                java.util.regex.Matcher.quoteReplacement( name + NAME_CONTRACTION_SUFFIXES[i] ) );

        for( int i = 0; i < REWRITE_PATTERNS.length; i++ )
            s = REWRITE_PATTERNS[i].matcher( s ).replaceAll(
                java.util.regex.Matcher.quoteReplacement( name + FIRST_PERSON_REWRITES[i][1] ) );
        return s;
    }

    // Matches [TIMER:5:reminder] (minutes) or [TIMER:4:40pm:reminder] (wall-clock)
    private static final java.util.regex.Pattern TIMER_TAG =
        java.util.regex.Pattern.compile(
            "\\[TIMER:(\\d+(?::\\d+(?:am|pm)?)?):([^\\]]+)\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE );

    private static final java.util.regex.Pattern WALL_CLOCK =
        java.util.regex.Pattern.compile(
            "(\\d{1,2}):(\\d{2})(am|pm)?",
            java.util.regex.Pattern.CASE_INSENSITIVE );

    public static String[] extractTimerTag( final String raw )
    {
        if( raw == null ) return null;
        final java.util.regex.Matcher m = TIMER_TAG.matcher( raw );
        return m.find() ? new String[]{ m.group(1), m.group(2).trim() } : null;
    }

    public static long parseTimerDurationMs( final String value )
    {
        final String v = value.trim();
        // Pure integer → whole minutes (cap at 1440 = 24 h; larger values are LLM math errors)
        try
        {
            final long mins = Long.parseLong( v );
            if( mins < 1 || mins > 1440 ) return -1L;
            return mins * 60_000L;
        }
        catch( final NumberFormatException ignored ) {}
        // Wall-clock: H:MMam / H:MMpm / HH:MM
        final java.util.regex.Matcher m = WALL_CLOCK.matcher( v );
        if( m.matches() )
        {
            int hour = Integer.parseInt( m.group(1) );
            final int minute = Integer.parseInt( m.group(2) );
            final String ampm = m.group(3);
            if( "pm".equalsIgnoreCase( ampm ) && hour != 12 ) hour += 12;
            else if( "am".equalsIgnoreCase( ampm ) && hour == 12 ) hour = 0;
            final java.util.Calendar now = java.util.Calendar.getInstance();
            final java.util.Calendar target = (java.util.Calendar) now.clone();
            target.set( java.util.Calendar.HOUR_OF_DAY, hour );
            target.set( java.util.Calendar.MINUTE, minute );
            target.set( java.util.Calendar.SECOND, 0 );
            target.set( java.util.Calendar.MILLISECOND, 0 );
            long delta = target.getTimeInMillis() - now.getTimeInMillis();
            if( delta <= 0 ) delta += 24L * 60 * 60 * 1000; // roll to next day if past
            return delta;
        }
        return -1L;
    }

    /**
     * Strip injected content from an LLM response before displaying or speaking it.
     * Cuts off at the first line that looks like injected instructions.
     */
    public static String sanitizeResponse( final String raw )
    {
        if( raw == null ) return "(no response)";
        // Split into sentences/lines and stop at the first injection marker
        final String[] lines = raw.split( "(?<=[.!?])\\s+|\\n" );
        final StringBuilder clean = new StringBuilder();
        for( final String line : lines )
        {
            final String lower = line.toLowerCase();
            if( lower.contains( "##" )
                || lower.matches( ".*\\bu003[ce]\\b.*" ) )
                break; // stop here — everything after is injected
            if( clean.length() > 0 ) clean.append( " " );
            clean.append( line.trim() );
        }
        final String result = clean.toString().trim();
        return result.isEmpty() ? raw.split( "[.!?]" )[0].trim() : result;
    }

    /** Levenshtein edit distance - used for fuzzy keyword matching of misheard words. */
    public static int editDistance( final String a, final String b )
    {
        final int la = a.length( ), lb = b.length( );
        final int[] prev = new int[ lb + 1 ], curr = new int[ lb + 1 ];
        for( int j = 0; j <= lb; j++ ) prev[j] = j;
        for( int i = 1; i <= la; i++ )
        {
            curr[0] = i;
            for( int j = 1; j <= lb; j++ )
            {
                if( a.charAt( i-1 ) == b.charAt( j-1 ) ) curr[j] = prev[j-1];
                else curr[j] = 1 + Math.min( prev[j-1], Math.min( prev[j], curr[j-1] ) );
            }
            System.arraycopy( curr, 0, prev, 0, lb + 1 );
        }
        return prev[lb];
    }

    public static String trimToFirstSentence( final String text )
    {
        if( text == null || text.length() < 10 ) return text;
        final java.util.regex.Matcher m =
            java.util.regex.Pattern.compile( "[.!?](?:\\s|$)" ).matcher( text );
        while( m.find() )
        {
            if( m.start() >= 5 )
                return text.substring( 0, m.end() ).trim();
        }
        return text;
    }

    /** True if the text is a bare non-answer (Noted. / Acknowledged. / etc.) that adds nothing. */
    public static boolean isTrivialAcknowledgement( final String text )
    {
        if( text == null ) return false;
        final String norm = text.trim().replaceAll( "[.!?,\"']", "" ).toLowerCase();
        return norm.equals( "noted" )
            || norm.equals( "acknowledged" )
            || norm.equals( "understood" )
            || norm.equals( "affirmative" )
            || norm.equals( "confirmed" )
            || norm.equals( "copy that" )
            || norm.equals( "roger" )
            || norm.equals( "indeed" );
    }

    /**
     * Deterministic cleanup of model output: strips a leading "Name:" dialogue tag,
     * wrapping quotation marks (straight or curly), and whitespace before punctuation.
     * Prompt rules ask for all three but the 4B model still violates them regularly.
     */
    public static String polishUtterance( String s )
    {
        if( s == null ) return s;
        s = s.trim();
        // Leading fiction-style dialogue tag, e.g. "2B: \"Your efforts...\"" or "Holo says: ..."
        s = s.replaceFirst( "^[A-Z0-9][\\w'-]{0,15}(?: [A-Z][\\w'-]{0,15})?(?: says)?:\\s+", "" );
        if( s.length() > 2 )
        {
            final char first = s.charAt( 0 );
            final char last  = s.charAt( s.length() - 1 );
            final boolean openQ  = first == '"' || first == '“';
            final boolean closeQ = last  == '"' || last  == '”';
            if( openQ && closeQ )
                s = s.substring( 1, s.length() - 1 ).trim();
            else if( openQ && s.indexOf( '"', 1 ) < 0 && s.indexOf( '”', 1 ) < 0 )
                s = s.substring( 1 ).trim(); // unbalanced opening quote
        }
        // "fascinating indeed !" -> "fascinating indeed!"
        s = s.replaceAll( "\\s+([.,!?;:])", "$1" );
        return s;
    }

    /**
     * True if the utterance trails off - ends in an ellipsis or a dangling copula
     * ("Your confidence seems...", "Your analysis of system resources is."). Used to
     * silently drop unprompted reactions the model failed to finish.
     */
    public static boolean isIncompleteUtterance( final String text )
    {
        if( text == null || text.trim().isEmpty() ) return true;
        final String t = text.trim();
        if( t.endsWith( "..." ) || t.endsWith( "…" ) ) return true;
        final String noPunct = t.replaceAll( "[.!?,…\"”\\s]+$", "" ).toLowerCase();
        return noPunct.endsWith( " is" )      || noPunct.endsWith( " are" )
            || noPunct.endsWith( " was" )     || noPunct.endsWith( " were" )
            || noPunct.endsWith( " seems" )   || noPunct.endsWith( " appears" )
            || noPunct.endsWith( " and" )     || noPunct.endsWith( " but" )
            || noPunct.endsWith( " the" )     || noPunct.endsWith( " a" );
    }

    public static boolean isValidTone( final String tone )
    {
        if( tone == null || tone.isBlank() ) return false;
        final String t = tone.trim().toLowerCase();
        // A colon or comma never belongs in a real tone phrase ("wary respect") — its presence
        // means a "Name:tone" pair or a multi-peer list leaked in. Reject outright as a backstop
        // to the comma-splitting in parsePeerToneLine.
        if( t.indexOf( ':' ) >= 0 || t.indexOf( ',' ) >= 0 ) return false;
        // Must be at least 2 words OR a single word that's clearly an adjective (>=5 chars)
        final String[] words = t.split( "\\s+" );
        if( words.length == 1 && words[0].length() < 5 ) return false;
        // Cap at 3 words — real tone phrases are 1-3 ("wary respect", "grudging respect");
        // 4+ is almost always an in-character sentence fragment the model emitted as a
        // "tone" (2B's "assessment lacks objective weight" / "predictable data points noted").
        if( words.length > 3 ) return false;
        // Reject if the entire value is a pronoun or article
        final java.util.Set<String> banned = new java.util.HashSet<>( java.util.Arrays.asList(
            "i","me","my","mine","myself","you","your","yours","yourself",
            "he","him","his","himself","she","her","hers","herself",
            "it","its","itself","we","us","our","ours","ourselves",
            "they","them","their","theirs","themselves",
            "this","that","these","those","a","an","the","is","are","was","were"
        ) );
        if( words.length == 1 && banned.contains( words[0] ) ) return false;
        // Must contain at least one actual letter sequence (not just punctuation/numbers)
        return t.matches( ".*[a-z]{2,}.*" );
    }

    public static boolean isTimeQuery( final String text )
    {
        final String t = text.toLowerCase();
        return ( t.contains( "time" ) && ( t.contains( "what" ) || t.contains( "current" ) || t.contains( "what's" ) ) )
            || t.equals( "time?" ) || t.equals( "time" );
    }

    /** Returns true for queries whose answers are ephemeral — should not be recorded in memory. */
    public static boolean isEphemeralQuery( final String text )
    {
        if( isWeatherQuery( text ) || isTimeQuery( text ) || isScreenQuery( text ) ) return true;
        // Factual drive lookups (last download / how much space / what's in a folder)
        // change as files change — don't store them as durable memory facts.
        if( com.group_finity.mascot.assistant.DriveIndexTool.isFactualDriveQuery( text ) ) return true;
        final String t = text.toLowerCase();
        return t.contains( "timer" ) || t.contains( "reminder" ) || t.contains( "alarm" )
            || t.contains( "remind me" ) || t.contains( "set a " );
    }

    public static boolean isScreenQuery( final String text )
    {
        final String t = text.toLowerCase();
        return t.contains( "my screen" )
            || t.contains( "looking at" )
            || t.contains( "what do you see" )
            || t.contains( "what's on" )
            || t.contains( "what is on" )
            || t.contains( "can you see" )
            || t.contains( "see my" )
            || t.contains( "on screen" );
    }

    public static boolean isWeatherQuery( final String text )
    {
        final String t = text.toLowerCase();
        return t.contains( "weather" )
            || t.contains( "temperature" )
            || t.contains( "forecast" )
            || t.contains( "raining" )
            || t.contains( "snowing" )
            || t.contains( "sunny" )
            || t.contains( "humid" )
            || t.contains( "degrees" )
            || ( t.contains( "outside" ) && ( t.contains( "like" )
                || t.contains( "cold" ) || t.contains( "hot" ) || t.contains( "warm" ) ) );
    }
}
