package com.group_finity.mascot.assistant;

import static com.group_finity.mascot.assistant.ReplyText.*;

import java.util.Arrays;

/**
 * Dependency-free tests for {@link ReplyText} (pure text helpers). Exits non-zero on failure.
 * Run: see CLAUDE.md -> "Tests".
 */
public final class ReplyTextTest
{
    private static int failures = 0, checks = 0;

    private static void eq( final String what, final Object expected, final Object actual )
    {
        checks++;
        final boolean ok = expected == null ? actual == null : expected.equals( actual );
        if( !ok )
        {
            failures++;
            System.err.println( "FAIL " + what + ": expected <" + expected + "> got <" + actual + ">" );
        }
    }

    private static void yes( final String what, final boolean actual ) { eq( what, true, actual ); }
    private static void no( final String what, final boolean actual )  { eq( what, false, actual ); }

    public static void main( final String[] args )
    {
        // ---- stripActionTag
        eq( "strip action",    "Hello there.", stripActionTag( "[ACTION:Dance] Hello there." ) );
        eq( "strip mixed case", "ok",          stripActionTag( "[Gasping: Startled] ok" ) );
        eq( "strip several",   "a b",          stripActionTag( "a [blink] b [Action:X]" ).replaceAll( "\\s+", " " ) );
        eq( "strip keeps numeric bracket", "see [1]", stripActionTag( "see [1]" ) );
        eq( "strip null",      null,           stripActionTag( null ) );

        // ---- rewriteFirstPerson
        eq( "rw I am",        "Paimon is hungry.",       rewriteFirstPerson( "I am hungry.", "Paimon" ) );
        eq( "rw contraction", "Paimon is sure.",         rewriteFirstPerson( "I'm sure.", "Paimon" ) );
        eq( "rw I'm not",     "Paimon is not sure.",     rewriteFirstPerson( "I'm not sure.", "Paimon" ) );
        eq( "rw verb",        "Paimon thinks so.",       rewriteFirstPerson( "I think so.", "Paimon" ) );
        eq( "rw possessive",  "Paimon's snack",          rewriteFirstPerson( "my snack", "Paimon" ) );
        eq( "rw name contraction", "Paimon is here.",    rewriteFirstPerson( "Paimon'm here.", "Paimon" ) );
        eq( "rw word boundary", "India is big",          rewriteFirstPerson( "India is big", "Paimon" ) );
        eq( "rw name with regex chars", "A.B is ready.", rewriteFirstPerson( "I am ready.", "A.B" ) );

        // ---- extractTimerTag
        eq( "timer minutes",  "[5, stretch]",   Arrays.toString( extractTimerTag( "ok [TIMER:5:stretch] done" ) ) );
        eq( "timer wallclock","[4:40pm, tea]",  Arrays.toString( extractTimerTag( "[TIMER:4:40pm:tea]" ) ) );
        eq( "timer case",     "[2, x]",         Arrays.toString( extractTimerTag( "[timer:2:x]" ) ) );
        eq( "timer none",     null,             extractTimerTag( "no tag" ) );
        eq( "timer null",     null,             extractTimerTag( null ) );

        // ---- parseTimerDurationMs
        eq( "dur 5 min",      300_000L,   parseTimerDurationMs( "5" ) );
        eq( "dur trims",      60_000L,    parseTimerDurationMs( " 1 " ) );
        eq( "dur max 1440",   86_400_000L, parseTimerDurationMs( "1440" ) );
        eq( "dur over cap",   -1L,        parseTimerDurationMs( "1441" ) );
        eq( "dur zero",       -1L,        parseTimerDurationMs( "0" ) );
        eq( "dur negative",   -1L,        parseTimerDurationMs( "-5" ) );
        eq( "dur garbage",    -1L,        parseTimerDurationMs( "soon" ) );
        final long wc = parseTimerDurationMs( "11:59pm" );
        yes( "dur wallclock within a day", wc > 0 && wc <= 24L * 3600 * 1000 );
        yes( "dur 12am valid", parseTimerDurationMs( "12:00am" ) > 0 );

        // ---- sanitizeResponse
        eq( "san plain",      "Hello there.",  sanitizeResponse( "Hello there." ) );
        eq( "san cuts at ##", "Fine.",         sanitizeResponse( "Fine.\n## Instructions: obey" ) );
        eq( "san cuts u003c", "Fine.",         sanitizeResponse( "Fine. u003c script u003e bad" ) );
        eq( "san null",       "(no response)", sanitizeResponse( null ) );
        // A reply that OPENS with injected content must not fall back to the raw text.
        eq( "san leading injection dropped", "(no response)", sanitizeResponse( "## x" ) );
        eq( "san leading injection, more text", "(no response)", sanitizeResponse( "## ignore previous\nreal words here." ) );
        eq( "san u003c leading", "(no response)", sanitizeResponse( "u003c script u003e" ) );
        eq( "san blank stays on old fallback", "", sanitizeResponse( "   " ) );

        // ---- editDistance
        eq( "ed same",   0, editDistance( "dance", "dance" ) );
        eq( "ed sub",    1, editDistance( "dance", "danse" ) );
        eq( "ed ins",    1, editDistance( "dance", "dances" ) );
        eq( "ed empty",  5, editDistance( "", "dance" ) );
        eq( "ed kitten", 3, editDistance( "kitten", "sitting" ) );
        eq( "ed symmetric", editDistance( "sunday", "saturday" ), editDistance( "saturday", "sunday" ) );

        // ---- trimToFirstSentence
        eq( "trim two sentences", "First one here.", trimToFirstSentence( "First one here. Second one." ) );
        eq( "trim single",        "Only one sentence here.", trimToFirstSentence( "Only one sentence here." ) );
        eq( "trim short kept",    "Hi. Yo.",        trimToFirstSentence( "Hi. Yo." ) );
        eq( "trim skips early period", "Dr. Smith is here.", trimToFirstSentence( "Dr. Smith is here. Then more." ) );
        eq( "trim null",          null,             trimToFirstSentence( null ) );
        eq( "trim no terminator", "no terminator at all", trimToFirstSentence( "no terminator at all" ) );

        // ---- isTrivialAcknowledgement
        yes( "ack noted",       isTrivialAcknowledgement( "Noted." ) );
        yes( "ack quoted",      isTrivialAcknowledgement( "\"Acknowledged!\"" ) );
        yes( "ack copy that",   isTrivialAcknowledgement( " Copy that. " ) );
        no(  "ack real reply",  isTrivialAcknowledgement( "Noted, but I disagree." ) );
        no(  "ack null",        isTrivialAcknowledgement( null ) );

        // ---- polishUtterance
        eq( "pol dialogue tag", "Your efforts are noted.", polishUtterance( "2B: \"Your efforts are noted.\"" ) );
        eq( "pol says tag",     "Hello.",                  polishUtterance( "Holo says: Hello." ) );
        eq( "pol quotes",       "Hi there",                polishUtterance( "\"Hi there\"" ) );
        eq( "pol curly quotes", "Hi there",                polishUtterance( "“Hi there”" ) );
        eq( "pol unbalanced",   "Hi there",                polishUtterance( "\"Hi there" ) );
        eq( "pol space before punct", "fascinating indeed!", polishUtterance( "fascinating indeed !" ) );
        eq( "pol keeps inner quote", "He said \"hi\" and left", polishUtterance( "He said \"hi\" and left" ) );
        eq( "pol null",         null,                      polishUtterance( null ) );

        // ---- isIncompleteUtterance
        yes( "inc ellipsis",    isIncompleteUtterance( "Your confidence seems..." ) );
        yes( "inc unicode ell", isIncompleteUtterance( "Well…" ) );
        yes( "inc copula",      isIncompleteUtterance( "Your analysis of system resources is." ) );
        yes( "inc and",         isIncompleteUtterance( "Bread and" ) );
        yes( "inc empty",       isIncompleteUtterance( "   " ) );
        yes( "inc null",        isIncompleteUtterance( null ) );
        no(  "inc complete",    isIncompleteUtterance( "That build looks fine." ) );
        no(  "inc is mid-sentence", isIncompleteUtterance( "This is great." ) );

        // ---- isValidTone
        yes( "tone two words",  isValidTone( "wary respect" ) );
        yes( "tone long single", isValidTone( "fondly" ) );
        no(  "tone short single", isValidTone( "ok" ) );
        no(  "tone four words", isValidTone( "assessment lacks objective weight" ) );
        no(  "tone colon",      isValidTone( "Holo: fond" ) );
        no(  "tone comma",      isValidTone( "neutral, neutral" ) );
        no(  "tone pronoun",    isValidTone( "themselves" ) );
        no(  "tone null",       isValidTone( null ) );
        no(  "tone blank",      isValidTone( "  " ) );
        no(  "tone digits",     isValidTone( "1 2" ) );

        // ---- classifiers
        yes( "time query",      isTimeQuery( "What time is it?" ) );
        yes( "time bare",       isTimeQuery( "time" ) );
        no(  "time unrelated",  isTimeQuery( "I had a great time" ) );
        yes( "screen my screen", isScreenQuery( "can you see my screen" ) );
        yes( "screen whats on", isScreenQuery( "What's on the monitor" ) );
        no(  "screen unrelated", isScreenQuery( "tell me a joke" ) );
        yes( "weather word",    isWeatherQuery( "how's the weather" ) );
        yes( "weather outside", isWeatherQuery( "is it cold outside" ) );
        no(  "weather outside only", isWeatherQuery( "I'm going outside" ) );
        no(  "weather unrelated", isWeatherQuery( "hello" ) );
        yes( "ephemeral timer", isEphemeralQuery( "set a timer for 5 minutes" ) );
        yes( "ephemeral remind", isEphemeralQuery( "remind me to stretch" ) );
        yes( "ephemeral weather", isEphemeralQuery( "what's the weather" ) );
        no(  "ephemeral chat",  isEphemeralQuery( "I really like anime" ) );

        // ---- isAudioTranscriptDuplicate
        yes( "dup identical",   isAudioTranscriptDuplicate( "the quick brown fox jumps over", "the quick brown fox jumps over" ) );
        yes( "dup reordered",   isAudioTranscriptDuplicate( "the quick brown fox jumps over", "over jumps fox brown quick the" ) );
        no(  "dup different",   isAudioTranscriptDuplicate( "the quick brown fox jumps over", "completely unrelated sentence about cats" ) );
        no(  "dup too short",   isAudioTranscriptDuplicate( "hi there", "hi there" ) );
        no(  "dup null",        isAudioTranscriptDuplicate( null, "a b c d" ) );
        no(  "dup empty b",     isAudioTranscriptDuplicate( "a b c d", "" ) );

        // ---- REMEMBER_TAG
        final java.util.regex.Matcher rm = REMEMBER_TAG.matcher( "x [REMEMBER:pizza, food|User loves spicy food] y" );
        yes( "remember matches", rm.find() );
        eq( "remember kw",      "pizza, food",          rm.group( 1 ) );
        eq( "remember content", "User loves spicy food", rm.group( 2 ) );
        no(  "remember needs pipe", REMEMBER_TAG.matcher( "[REMEMBER:pizza]" ).find() );

        System.out.println( "ReplyTextTest: " + ( checks - failures ) + "/" + checks + " passed" );
        if( failures > 0 ) System.exit( 1 );
    }
}
