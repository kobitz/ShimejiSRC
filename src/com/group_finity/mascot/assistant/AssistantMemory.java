package com.group_finity.mascot.assistant;

import com.group_finity.mascot.Main;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_SPONTANEOUS_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_AUDIO_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_VISION_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.GLOBAL_USER_SPEECH_COOLDOWN_MS;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalSpontaneousLastFiredMs;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalAudioLastFiredMs;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalVisionLastFiredMs;
import static com.group_finity.mascot.assistant.ReactionCooldowns.globalUserSpeechLastFiredMs;
import static com.group_finity.mascot.assistant.ReplyText.isAudioTranscriptDuplicate;
import static com.group_finity.mascot.assistant.ReplyText.stripActionTag;
import static com.group_finity.mascot.assistant.ReplyText.rewriteFirstPerson;
import static com.group_finity.mascot.assistant.ReplyText.extractTimerTag;
import static com.group_finity.mascot.assistant.ReplyText.parseTimerDurationMs;
import static com.group_finity.mascot.assistant.ReplyText.sanitizeResponse;
import static com.group_finity.mascot.assistant.ReplyText.editDistance;
import static com.group_finity.mascot.assistant.ReplyText.trimToFirstSentence;
import static com.group_finity.mascot.assistant.ReplyText.isTrivialAcknowledgement;
import static com.group_finity.mascot.assistant.ReplyText.polishUtterance;
import static com.group_finity.mascot.assistant.ReplyText.isIncompleteUtterance;
import static com.group_finity.mascot.assistant.ReplyText.isEphemeralQuery;
import static com.group_finity.mascot.assistant.ReplyText.isScreenQuery;
import static com.group_finity.mascot.assistant.ReplyText.isWeatherQuery;
import static com.group_finity.mascot.assistant.ReplyText.isValidTone;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.ResourceBundle;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.sound.sampled.Clip;
import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.environment.MascotEnvironment;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.hotspot.Hotspot;
import com.group_finity.mascot.image.ImagePairs;
import com.group_finity.mascot.image.MascotImage;
import com.group_finity.mascot.image.NativeImage;
import com.group_finity.mascot.image.TranslucentWindow;
import com.group_finity.mascot.menu.JLongMenu;
import com.group_finity.mascot.assistant.AssistantBubble;
import com.group_finity.mascot.assistant.AssistantInputDialog;
import com.group_finity.mascot.assistant.OllamaClient;
import com.group_finity.mascot.script.VariableMap;
import com.group_finity.mascot.sound.Sounds;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

/**
 * Conversation-memory summarizing for a {@link Mascot}: the periodic LLM pass that condenses recent
 * exchanges into facts, an emotional tone and per-peer tones, plus the parser for its peer-tone
 * lines. Extracted from {@code Mascot}; method bodies are unchanged apart from reaching the mascot
 * through {@code m.} (instance members) or {@code Mascot.} (statics).
 */
public final class AssistantMemory
{
    private static final Logger log = Logger.getLogger( com.group_finity.mascot.Mascot.class.getName( ) );

    private final AssistantHost m;

    public AssistantMemory( final AssistantHost m )
    {
        this.m = m;
    }

    public void maybeSummarizeMemory()
    {
        final com.group_finity.mascot.assistant.MascotMemory memory =
            com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() );
        if( !memory.needsSummarization() ) return;
        // Do NOT reset here — reset only on successful Ollama response.
        // Resetting before the call means a failed/errored summarization silently
        // resets the counter and summarization won't retry for another 20 interactions.

        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : "mascot";

        final String currentFacts = memory.buildMemoryBlock();
        final String personality  = ( cfg != null && cfg.getInformation( "Personality" ) != null )
            ? cfg.getInformation( "Personality" ) : "";
        final String personalitySnip = personality.isEmpty() ? ""
            : personality.substring( 0, Math.min( 300, personality.length() ) );

        if( AssistantShared.ollamaClient == null ) AssistantShared.ollamaClient = AssistantSupport.createOllamaClient();
        final OllamaClient client = AssistantShared.ollamaClient;

        new Thread( () ->
        {
            final String peerContext = memory.buildAllPeerMemoryBlocks();
            final boolean hasPeers    = !peerContext.isEmpty();

            final String system = "You are a memory distillation assistant. "
                + "Given a memory block and conversation history, extract EXACTLY 5 concise "
                + "factual observations about the user. No more than 5. One per line, no bullet points, "
                + "no numbering. Keep each fact under 12 words. "
                + "Facts must come from the user's OWN words and actions only. Never attribute "
                + "opinions or statements from podcasts, videos, or other media transcripts to the "
                + "user — something merely playing on their screen or speakers is NOT a fact about them. "
                + "Also output a 2-4 word adjective phrase describing the emotional tone of the relationship "
                + "toward the human user. The tone must be an adjective phrase, for example: "
                + "\"cautiously warm\", \"coldly respectful\", \"openly hostile\", \"warmly amused\", "
                + "\"guarded but curious\", \"deeply loyal\", \"wary and distant\". "
                + "Never output a pronoun, verb, or sentence as a tone. "
                + ( hasPeers
                    ? "For each peer mascot listed, also output a 2-4 word adjective tone phrase "
                      + "describing how this mascot feels toward that peer. "
                      + "Examples: \"grudging respect\", \"dismissive rivalry\", \"wary tolerance\", \"playful contempt\". "
                      + "Never output a pronoun, verb, article, or single word as a tone. "
                    : "" )
                + "Respond ONLY in this exact format with no extra commentary:\n"
                + "FACTS:\n<one fact per line>\nTONE:<adjective phrase>"
                + ( hasPeers
                    ? "\nPEER_TONE:<name>:<adjective phrase>"
                      + "\nExample: PEER_TONE:Hornet:grudging respect"
                      + "\nOne PEER_TONE line per peer. The name and tone must be on the SAME line as PEER_TONE:."
                    : "" );

            final java.util.List<com.group_finity.mascot.assistant.MascotMemory.PermanentMemory>
                permMems = memory.getPermanentMemories();
            final StringBuilder permBlock = new StringBuilder();
            if( !permMems.isEmpty() )
            {
                permBlock.append( "Permanent memories (things this mascot chose to remember forever):\n" );
                for( final com.group_finity.mascot.assistant.MascotMemory.PermanentMemory pm : permMems )
                    permBlock.append( "- " ).append( pm.content ).append( "\n" );
            }

            final String prompt = "Mascot name: " + mascotName + "\n"
                + ( personalitySnip.isEmpty() ? ""
                    : "Mascot personality: " + personalitySnip + "\n" )
                + "Current memory:\n" + currentFacts + "\n"
                + ( permBlock.length() > 0 ? permBlock.toString() : "" )
                + ( hasPeers ? peerContext + "\n" : "" )
                + "Distill into key facts and tone.";

            // Summary needs 300 tokens: 5 facts + TONE + PEER_TONE lines.
            // Default 80-token cap cuts off before TONE: is reached.
            client.generate( system, prompt, 300, true, new OllamaClient.Callback()
            {
                @Override public void onResponse( final String text )
                {
                    memory.resetSummarizationCounter();
                    log.fine( "[Memory] Raw summary response for " + m.getImageSet()
                        + ":\n" + text );

                    // ── Parse line by line — avoids indexOf("TONE:") matching "PEER_TONE:" ──
                    boolean inFacts = false;
                    boolean foundTone = false;
                    boolean inPeerToneBlock = false;
                    for( final String rawLine : text.split( "\n" ) )
                    {
                        final String line = rawLine.trim();
                        if( line.isEmpty() ) continue;

                        // All prefix checks are case-insensitive — the model emits
                        // "Tone:" / "Facts:" in mixed case often enough that the old
                        // case-sensitive checks let "Tone: X" fall through into the
                        // facts branch and get stored as a fact.
                        if( line.regionMatches( true, 0, "FACTS:", 0, 6 ) )
                        {
                            inFacts = true;
                            // Handle facts on the same line as "FACTS:" keyword
                            final String inline = line.substring( 6 ).trim().replaceFirst( "^[-*]\\s*", "" );
                            if( !inline.isEmpty() ) memory.addFact( inline );
                            continue;
                        }
                        // TONE: must be an exact prefix, not inside PEER_TONE:
                        if( !line.regionMatches( true, 0, "PEER_TONE:", 0, 10 )
                                && line.regionMatches( true, 0, "TONE:", 0, 5 ) )
                        {
                            inFacts = false;
                            foundTone = true;
                            final String tone = line.substring( 5 ).trim().toLowerCase();
                            if( isValidTone( tone ) )
                                memory.setTone( tone );
                            else
                                log.warning( "[Memory] Rejected invalid user tone: \"" + tone + "\"" );
                            continue;
                        }
                        if( line.regionMatches( true, 0, "PEER_TONE:", 0, 10 ) )
                        {
                            inFacts = false;
                            final String payload = line.substring( 10 ).trim();
                            if( payload.isEmpty() )
                            {
                                // Model put "PEER_TONE:" on its own line — following lines are
                                // "Name: tone" entries until we hit a blank or unrecognised line.
                                inPeerToneBlock = true;
                            }
                            else
                            {
                                // Inline form: "PEER_TONE:Name:tone"
                                parsePeerToneLine( payload, memory );
                            }
                            continue;
                        }
                        // Inside a PEER_TONE block — lines are "Name: tone" or "Name - tone"
                        if( inPeerToneBlock )
                        {
                            if( line.isEmpty() ) { inPeerToneBlock = false; continue; }
                            // Accept "Name: tone", "Name - tone", or "Name tone" (space-separated)
                            final int sep = line.indexOf( ':' ) >= 0 ? line.indexOf( ':' )
                                          : line.indexOf( '-' ) >= 0 ? line.indexOf( '-' ) : -1;
                            if( sep > 0 )
                                parsePeerToneLine( line.substring(0, sep).trim()
                                    + ":" + line.substring( sep + 1 ).trim(), memory );
                            else
                            {
                                // No separator — likely end of block (e.g. "Holo" alone means truncated)
                                log.warning( "[Memory] Peer tone block ended unexpectedly at: \"" + line + "\"" );
                                inPeerToneBlock = false;
                            }
                            continue;
                        }
                        if( inFacts )
                        {
                            final String fact = line.replaceFirst( "^[-*\\d.]+\\s*", "" ).trim();
                            // Safety net: never store tone lines as facts even if the
                            // model wraps them in bullets or other prefixes.
                            if( fact.matches( "(?i)^(peer_)?tone\\s*:.*" ) ) continue;
                            if( !fact.isEmpty() ) memory.addFact( fact );
                        }
                    }
                    if( !foundTone )
                        log.warning( "[Memory] Summary response had no TONE: line — raw: " + text );
                    log.fine( "[Memory] Summarized for " + m.getImageSet() );
                }
                @Override public void onError( final String error )
                {
                    log.warning( "[Memory] Summarization failed: " + error );
                }
            });
        }, "memory-summarize" ).start();
    }

    /**
     * Show a text bubble with arbitrary text — callable from action classes (e.g. Say).
     * Safe to call from any thread; dispatches to EDT internally.
     */
    /** Called from SettingsWindow.Done — refreshes bubble display settings. */
    /**
     * If enough exchanges have accumulated, ask Ollama to distill them into
     * a few key facts and update the memory. Runs on a background thread.
     */
    /**
     * Validates a tone string from summarization output.
     * Rejects single-word pronouns, articles, verbs, and other non-adjective garbage
     * that LLMs sometimes emit when they don't follow the format instruction.
     * A valid tone is 1-3 words, contains no banned pronouns as the entire value,
     * and contains at least one letter beyond a single word.
     */
    /** Parse a "Name:tone" or "Name: tone" string and apply it to memory if valid.
     *  The model often crams every peer onto ONE line — "PEER_TONE:2B:neutral, Paimon:neutral,
     *  Hornet:neutral" — so split on commas and process each "Name:tone" segment independently.
     *  Without this the first peer absorbed the rest of the line as its tone ("neutral,
     *  paimon:neutral, hornet:neutral"), which slipped past isValidTone's 3-word cap because the
     *  comma-joined value happens to be exactly 3 whitespace tokens (observed June 2026, Frieren). */
    public void parsePeerToneLine( final String payload,
                                     final com.group_finity.mascot.assistant.MascotMemory memory )
    {
        if( payload.indexOf( ',' ) >= 0 )
        {
            for( final String seg : payload.split( "," ) )
            {
                final String s = seg.trim();
                if( !s.isEmpty() ) parsePeerToneLine( s, memory );
            }
            return;
        }
        final int colon = payload.indexOf( ':' );
        if( colon <= 0 ) return;
        final String peerName = payload.substring( 0, colon ).trim();
        final String peerTone = payload.substring( colon + 1 ).trim().toLowerCase();
        if( peerName.isEmpty() ) return;
        if( peerName.equalsIgnoreCase( m.getImageSet() ) ) return;
        if( !AssistantShared.isKnownImageSet( peerName ) )
        {
            // The summarizer occasionally hallucinates a peer name ("Holantype") or
            // names a mascot that isn't an actual loaded image set. Only real image
            // sets can be conversational peers, so reject anything else outright —
            // this is what keeps garbage keys out of peerTones.
            log.warning( "[Memory] Rejected unknown peer name: \"" + peerName + "\"" );
            return;
        }
        if( isValidTone( peerTone ) )
        {
            memory.setPeerTone( peerName, peerTone );
            log.fine( "[Memory] Peer tone set: " + peerName + " = " + peerTone );
        }
        else
        {
            log.warning( "[Memory] Rejected invalid peer tone for "
                + peerName + ": \"" + peerTone + "\"" );
        }
    }
}
