package com.group_finity.mascot;

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
 * The conversational reactions of a {@link Mascot}: replying when its name is spoken, answering a
 * screen query with a vision model, and reacting to another mascot's line. Extracted from
 * {@code Mascot}; the method bodies are unchanged apart from reaching the mascot through
 * {@code m.} (instance members) or {@code Mascot.} (statics). {@code Mascot} keeps one-line
 * delegates of the same names, so its call sites and the {@code this::handleNameTrigger}
 * registrations do not change.
 */
final class AssistantReplies
{
    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private final Mascot m;

    AssistantReplies( final Mascot m )
    {
        this.m = m;
    }

    /**
     * React to something another mascot just said.
     * Called on the peer-reaction scheduler thread; dispatches Ollama async.
     * chainDepth controls how far along the exchange chain we are.
     */
    void firePeerReaction( final String speakerName,
                                   final String speakerImageSet,
                                   final String speakerText,
                                   final int chainDepth )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : m.getImageSet();
        final String personality = m.getPersonality( cfg, mascotName );

        final com.group_finity.mascot.assistant.MascotMemory memory =
            com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() );
        final String peerTone = memory.getPeerTone( speakerName );

        // Peer reactions use a lightweight memory block (facts + tone only, no full
        // exchange history) to keep the system prompt short and reduce prefill cost.
        // No peerCtx here: quoted peer lines act as style examples the 4B model
        // imitates, dragging every character into the same register. The speaker's
        // line (in the user prompt) is the only context a reply needs.
        final String peerSpeechRule = Mascot.getSpeechRule( cfg );
        final String system = Mascot.withSpeechReminder( personality
            + memory.buildLightMemoryBlock( speakerName )
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( peerSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + peerSpeechRule )
            + "\n- Reply in ONE sentence. 15 words maximum."
            + Mascot.QUICK_REACTION_STYLE_RULES
            + "\n- You are reacting to something another mascot just said to you."
            + "\n- Reply in YOUR OWN voice. Do not borrow " + speakerName + "'s vocabulary, phrasing, or sentence shape — if they speak formally, you still answer the way YOU speak."
            + "\n- Address " + speakerName + " only as \"" + speakerName + "\" — no other names or nicknames for them."
            + "\n- Your emotional tone toward " + speakerName + " is: " + peerTone + "."
            + "\n- Stay fully in character. No greetings, no filler."
            + "\n- Speak your response directly — do not wrap it in quotation marks."
            + "\n- Output ONLY your own words. Never prefix with another character's name or a dialogue tag (e.g. never write \"2B: ...\" or \"Holo says: ...\")."
            + "\n- End with a complete sentence. Never trail off with an ellipsis or open fragment."
            + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
            + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
            + "\n---", peerSpeechRule );

        final String prompt = speakerName + " just said: \""
            + speakerText + "\". Reply to them in one sentence, in a tone that is " + peerTone + "."
            + Mascot.situationBackground( );

        if( Mascot.ollamaClient == null ) Mascot.ollamaClient = Mascot.createOllamaClient();
        final OllamaClient client = Mascot.ollamaClient;

        javax.swing.SwingUtilities.invokeLater( () ->
        {
            m.ensureFreshBubble();
            final java.awt.Rectangle b = m.getBounds();
            if( b != null ) m.assistantBubble.showThinking( b );
        });

        final String ctx = "[Reacting to " + speakerName + "] " + speakerText;

        client.generateDeferrable( system, prompt, new OllamaClient.Callback()
        {
            @Override public void onResponse( final String raw )
            {
                m.fireActionFromResponse( raw );
                final String text = trimToFirstSentence( m.applyPersonaRewrites( polishUtterance( stripActionTag( raw ) ) ) );
                if( isTrivialAcknowledgement( text ) || isIncompleteUtterance( text ) )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                    });
                    return;
                }
                com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(to: " + speakerName + ")", text );
                com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                    .recordPeerExchange( speakerName, speakerText, text );
                m.maybeSummarizeMemory();
                com.group_finity.mascot.assistant.MascotSpeechRegistry
                    .record( m.getImageSet(), mascotName, text, chainDepth );
                javax.swing.SwingUtilities.invokeLater( () ->
                {
                    final java.awt.Rectangle b = m.getBounds();
                    if( b != null && m.assistantBubble != null )
                        m.assistantBubble.showResponse( text, ctx, b );
                });
            }
            @Override public void onError( final String error )
            {
                javax.swing.SwingUtilities.invokeLater( () ->
                {
                    if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                });
            }
        });
    }

    /**
     * Called when the mascot's name is detected in the mic stream.
     * Receives the VCL transcript so the mic does not need to be re-transcribed.
     * Transcribes system audio separately for context, then calls Ollama.
     */
    void handleNameTrigger( final String vclTranscript )
    {
        log.info( "[Voice] Name trigger fired for " + m.getImageSet() );

        // Ensure the shared audio buffer is running
        synchronized( Mascot.AUDIO_LOCK )
        {
            if( Mascot.audioBuffer == null )
            {
                Mascot.audioBuffer = new com.group_finity.mascot.assistant.AudioTranscriptBuffer();
                if( !Mascot.audioBuffer.start() )
                {
                    log.warning( "[Voice] Failed to start audio buffer for name trigger." );
                    Mascot.audioBuffer = null;
                }
            }
        }

        final com.group_finity.mascot.assistant.AudioTranscriptBuffer buf = Mascot.audioBuffer;
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : m.getImageSet();
        final String personality = m.getPersonality( cfg, mascotName );
        final String ntSpeechRule = Mascot.getSpeechRule( cfg );

        final java.text.SimpleDateFormat ntSdf =
            new java.text.SimpleDateFormat( "EEEE, MMMM d yyyy 'at' h:mm a" );
        final String ntTimeStr = ntSdf.format( new java.util.Date() );
        final String ntPlace =
            com.group_finity.mascot.assistant.WeatherTool.getCachedPlaceName();
        final String ntCachedWx =
            com.group_finity.mascot.assistant.WeatherTool.getCachedResult();
        final String ntEnvCtx = "\n[Current time: " + ntTimeStr + ".]"
            + ( ntPlace   != null ? "\n[User location: " + ntPlace + ".]"       : "" )
            + ( ntCachedWx != null ? "\n[Current weather: " + ntCachedWx + ".]" : "" );

        final String system = Mascot.withSpeechReminder( personality
            + ntEnvCtx
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( ntSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + ntSpeechRule )
            + "\n- Reply in ONE sentence. Two at most if truly necessary."
            + "\n- The user just called your name — respond to them directly."
            + "\n- Be brief and in-character. No greetings, no filler."
            + "\n- Speak your response directly — do not wrap it in quotation marks."
            + "\n- Only when the user explicitly asks you to set a timer, reminder, or alarm in THIS message — never on your own initiative: append [TIMER:VALUE:reminder text]. VALUE = whole minutes for durations, e.g. [TIMER:15:reminder text], or the exact clock time the user said, e.g. [TIMER:5:30pm:reminder text] for 'at 5:30pm'. For clock times: copy it exactly, do NOT convert to minutes. The reminder text must be drawn from what the user actually said, never invented."
            + "\n- When asked about time or weather: you MUST state the actual values from context (e.g. the real temperature, the real time). Express them in your character voice, but the real numbers must appear in your answer. Do not replace them with metaphor or fictional equivalents."
            + "\n---", ntSpeechRule );

        if( Mascot.ollamaClient == null ) Mascot.ollamaClient = Mascot.createOllamaClient();
        final OllamaClient client = Mascot.ollamaClient;

        new Thread( () ->
        {
            // ── Step 1: Dispatch behavior immediately from the mic transcript ──────
            // vclTranscript is already transcribed by VCL — no Whisper call needed.
            // Don't wait for system audio; keyword commands must feel instant.
            final com.group_finity.mascot.config.Configuration trigCfg =
                Main.getInstance().getConfiguration( m.getImageSet() );
            final boolean[] behaviorDispatched = { false };
            if( trigCfg != null )
            {
                final String behaviorMatch = m.findActionByKeyword( vclTranscript, trigCfg );
                if( behaviorMatch != null )
                {
                    behaviorDispatched[0] = true;
                    javax.swing.SwingUtilities.invokeLater(
                        () -> m.tryRunBehavior( behaviorMatch, trigCfg ) );
                }
            }

            // ── Step 2: System audio transcription (skip if behavior matched) ──────
            // buf.transcribe() blocks on WhisperProcess lock — expensive.
            // If the user issued a pure action command, skip it to avoid stalling
            // the Whisper server while the mascot is already executing the behavior.
            final String sysTranscript = ( buf != null && !behaviorDispatched[0] )
                ? buf.transcribe() : null;
            final boolean hasSys = sysTranscript != null && !sysTranscript.isBlank();

            // Strip system audio words from the mic transcript so video/speaker
            // bleed doesn't pollute what the user actually said.
            final String cleanUser = hasSys
                ? com.group_finity.mascot.assistant.AudioTranscriptBuffer
                    .stripOverlap( sysTranscript, vclTranscript )
                : vclTranscript;
            final boolean hasUser = cleanUser != null && !cleanUser.isBlank();

            // ── Step 3: Build Ollama prompt ───────────────────────────────────────
            final String userPrompt;
            final String memoryContext;

            if( hasSys && hasUser )
            {
                userPrompt   = "You were just called by name."
                    + " Here is what was happening in the last few seconds: \""
                    + sysTranscript + "\"."
                    + " The user said: \"" + cleanUser + "\"."
                    + " Respond to the user directly.";
                memoryContext = sysTranscript + " | User: " + cleanUser;
            }
            else if( hasSys )
            {
                userPrompt   = "You were just called by name."
                    + " Here is what was happening: \"" + sysTranscript + "\"."
                    + " Respond to the user directly.";
                memoryContext = sysTranscript;
            }
            else if( hasUser )
            {
                userPrompt   = "You were just called by name."
                    + " The user said: \"" + cleanUser + "\"."
                    + " Respond to them.";
                memoryContext = cleanUser;
            }
            else
            {
                userPrompt   = "The user just called your name with no other audio context."
                    + " Acknowledge them briefly, in character.";
                memoryContext = "[name trigger, no system audio]";
            }

            // ── Step 4: Guard against concurrent Ollama inference ─────────────────
            // ollamaClient is shared. If another request (fireAudioReaction, etc.)
            // is already running, skip the Ollama call — the behavior already fired.
            // Request is queued via OllamaClient — no manual claiming needed.

            javax.swing.SwingUtilities.invokeLater( () ->
            {
                m.ensureFreshBubble();
                final java.awt.Rectangle b = m.getBounds();
                if( b != null ) m.assistantBubble.showThinking( b );
            });

            if( hasUser && isScreenQuery( cleanUser ) )
            {
                com.group_finity.mascot.assistant.ChatLog.append( "User(voice)", cleanUser );
                handleScreenQuery( cleanUser, system, client );
                return;
            }

            com.group_finity.mascot.assistant.ChatLog.append( "User(voice)",
                hasUser ? cleanUser : "[name trigger]" );
            final String wxCtx = m.weatherContext( cleanUser != null ? cleanUser : "" );
            client.generate( system, userPrompt + wxCtx, new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    m.fireActionFromResponse( raw );
                    final String text = m.applyPersonaRewrites( polishUtterance( stripActionTag( raw ) ) );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName, text );
                    if( !isEphemeralQuery( memoryContext ) )
                        com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                            .recordUserExchange( "[Called by name] " + memoryContext, text );
                    m.maybeSummarizeMemory();
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = m.getBounds();
                        if( b != null && m.assistantBubble != null )
                            m.assistantBubble.showResponse( text, memoryContext, b );
                    });
                }
                @Override public void onError( final String error )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                    });
                }
            });
        }, "name-trigger" ).start();
    }

    /**
     * Handles a screen-content query: captures a screenshot, sends it to the
     * vision model (llava by default), and shows the response in the bubble.
     * Must be called after thinking is already showing.
     */
    void handleScreenQuery( final String userText,
                                    final String system,
                                    final OllamaClient client )
    {
        final String visionModel = Main.getInstance().getProperties()
            .getProperty( "VisionModel", Mascot.DEFAULT_VISION_MODEL );
        new Thread( () ->
        {
            try
            {
                final String base64 = Mascot.captureScreenBase64();
                final String audioCtxVision = Mascot.audioSnapshotContext();
                final String enrichedUserText = userText
                    + ( audioCtxVision.isEmpty() ? "" : "\n" + audioCtxVision );
                client.generateWithImage( system, enrichedUserText, base64, visionModel,
                    new OllamaClient.Callback()
                    {
                        @Override
                        public void onResponse( final String raw )
                        {
                            final String display = m.applyPersonaRewrites( polishUtterance( stripActionTag( sanitizeResponse( raw ) ) ) );
                            com.group_finity.mascot.assistant.ChatLog.append( m.getImageSet() + "(screen glance)", display );
                            SwingUtilities.invokeLater( () ->
                            {
                                if( m.assistantBubble != null )
                                    m.assistantBubble.showResponse( display, m.getBounds() );
                            });
                        }

                        @Override
                        public void onError( final String message )
                        {
                            SwingUtilities.invokeLater( () ->
                            {
                                if( m.assistantBubble != null )
                                    m.assistantBubble.showError( message, m.getBounds() );
                            });
                        }
                    });
            }
            catch( final Exception e )
            {
                log.warning( "[Vision] Screen capture failed: " + e.getMessage() );
                SwingUtilities.invokeLater( () ->
                {
                    if( m.assistantBubble != null )
                        m.assistantBubble.showError( "Screen capture failed.", m.getBounds() );
                });
            }
        }, "screen-capture-thread" ).start();
    }
}
