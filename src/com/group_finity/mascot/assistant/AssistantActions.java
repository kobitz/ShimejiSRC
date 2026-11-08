package com.group_finity.mascot.assistant;

import static com.group_finity.mascot.assistant.ReplyText.extractTimerTag;
import static com.group_finity.mascot.assistant.ReplyText.parseTimerDurationMs;
import static com.group_finity.mascot.assistant.ReplyText.editDistance;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import javax.swing.SwingUtilities;
import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.exception.CantBeAliveException;

/**
 * Turning an LLM reply into mascot behavior: the {@code [ACTION:...]}/{@code [TIMER:...]}/
 * {@code [REMEMBER:...]} tag handling, keyword-to-behavior lookup, and the timer bubble. Extracted
 * from {@code Mascot}; method bodies are unchanged apart from reaching the mascot through
 * {@code host.} (instance members) or {@code Mascot.} (statics); the timer bubble's
 * {@code this::handleReply} callback is now {@code host::handleReply} (same target).
 */
public final class AssistantActions
{
    private static final Logger log = Logger.getLogger( com.group_finity.mascot.Mascot.class.getName( ) );

    private final AssistantHost host;

    public AssistantActions( final AssistantHost host )
    {
        this.host = host;
    }

    public void fireActionFromResponse( final String raw )
    {
        final String behaviorName = extractActionTag( raw );
        if( behaviorName != null )
        {
            final com.group_finity.mascot.config.Configuration cfg =
                host.getConfiguration( );
            if( cfg != null )
                javax.swing.SwingUtilities.invokeLater( () -> tryRunBehavior( behaviorName, cfg ) );
        }

        final String[] timerParts = extractTimerTag( raw );
        if( timerParts != null )
            setMascotTimer( timerParts[0], timerParts[1] );

        // Bare [word] tags — attempt behavior lookup as shorthand for [ACTION:Word].
        // Tries the word as-is, then with the first letter capitalised. Silent on miss.
        if( raw != null )
        {
            final com.group_finity.mascot.config.Configuration bareCfg =
                host.getConfiguration( );
            if( bareCfg != null )
            {
                final java.util.regex.Matcher bm =
                    java.util.regex.Pattern.compile( "\\[([a-zA-Z_]+)\\]" ).matcher( raw );
                while( bm.find() )
                {
                    final String word = bm.group( 1 );
                    final String cap  = Character.toUpperCase( word.charAt( 0 ) )
                                      + word.substring( 1 ).toLowerCase( java.util.Locale.ROOT );
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( tryRunBehavior( word, bareCfg ) != null
                                && tryRunBehavior( cap, bareCfg ) != null )
                            log.warning( "[Action] No behavior found for bare tag: [" + word + "]" );
                    } );
                }
            }
        }

        // Parse any [REMEMBER:kw1,kw2|content] tags and persist them permanently.
        if( raw != null )
        {
            final java.util.regex.Matcher rm = ReplyText.REMEMBER_TAG.matcher( raw );
            while( rm.find() )
            {
                final String[] kwParts = rm.group(1).split( "[,\\s]+" );
                final java.util.List<String> kws = new java.util.ArrayList<>();
                for( final String kw : kwParts )
                    if( !kw.isBlank() ) kws.add( kw.trim().toLowerCase( java.util.Locale.ROOT ) );
                final String content = rm.group(2).trim();
                if( !kws.isEmpty() && !content.isEmpty() )
                {
                    com.group_finity.mascot.assistant.MascotMemory
                        .forImageSet( host.getImageSet() )
                        .addPermanentMemory( kws, content );
                    log.info( "[Memory] Permanent memory saved: " + kws + " -> " + content );
                }
            }
        }
    }

    /**
     * Keyword-based behavior search: returns the first behavior name whose
     * words overlap with the user's input words (case-insensitive).
     * Ignores hidden behaviors and sub-behaviors (those containing "/").
     */
    public String findActionByKeyword( final String userText,
                                        final com.group_finity.mascot.config.Configuration cfg )
    {
        final String[] inputWords = userText.toLowerCase( ).split( "[^a-z0-9]+" );
        String bestMatch = null;
        int    bestScore = 0;

        for( final String name : cfg.getBehaviorNames( ) )
        {
            if( name.contains( "/" ) ) continue;
            if( cfg.isBehaviorHidden( name ) ) continue;

            final String[] nameWords = name.toLowerCase( ).split( "[^a-z0-9]+" );
            int score = 0;
            for( final String iw : inputWords )
            {
                if( iw.isEmpty( ) ) continue;
                for( final String nw : nameWords )
                {
                    if( nw.equals( iw ) )         score += 4; // exact
                    else if( nw.contains( iw ) )  score += 2; // partial
                    else if( iw.contains( nw ) )  score += 2; // partial reverse
                    else if( iw.length( ) >= 4 && nw.length( ) >= 4
                             && editDistance( iw, nw ) <= 2 ) score += 3; // fuzzy
                }
            }
            if( score > bestScore )
            {
                bestScore = score;
                bestMatch = name;
            }
        }
        return bestScore > 0 ? bestMatch : null;
    }

    /**
     * Attempt to set this mascot's behavior by name, same as the right-click menu does -
     * conditions are intentionally bypassed for direct/manual invocation.
     * Returns null on success, or a user-facing message on failure.
     */
    public String tryRunBehavior( final String name,
                                   final com.group_finity.mascot.config.Configuration cfg )
    {
        log.info( "tryRunBehavior: " + name );
        try
        {
            final com.group_finity.mascot.behavior.Behavior b = cfg.buildBehavior( name );
            if( b == null ) return "I don't know how to do that.";
            host.setBehavior( b );
            return null;
        }
        catch( com.group_finity.mascot.exception.BehaviorInstantiationException
             | com.group_finity.mascot.exception.CantBeAliveException e )
        {
            log.log( Level.WARNING, "Assistant could not trigger behavior: " + name, e );
            return "Something went wrong trying to do that.";
        }
    }

    public void setMascotTimer( final String timeValue, final String reminder )
    {
        final long durationMs = parseTimerDurationMs( timeValue );
        if( durationMs <= 0 ) return;
        final long endMs = System.currentTimeMillis() + durationMs;
        javax.swing.SwingUtilities.invokeLater( () ->
        {
            host.timerBubble().showCountdown( endMs, reminder, host.getBounds() );
        });
    }

    /**
     * Extract [ACTION:BehaviorName] tag from an LLM response.
     * Returns the behavior name string, or null if no tag is present.
     * Validates that the behavior actually exists in the mascot's config.
     */
    public String extractActionTag( final String response )
    {
        final java.util.regex.Matcher m =
            java.util.regex.Pattern.compile( "\\[ACTION:([^\\]]+)\\]" ).matcher( response );
        if( !m.find( ) ) return null;
        final String name = m.group( 1 ).trim( );
        final com.group_finity.mascot.config.Configuration cfg =
            host.getConfiguration( );
        if( cfg == null ) return null;
        // Exact match first
        if( cfg.hasBehavior( name ) ) return name;
        // Case-insensitive fallback
        for( final String bname : cfg.getBehaviorNames( ) )
            if( bname.equalsIgnoreCase( name ) ) return bname;
        return null;
    }
}
