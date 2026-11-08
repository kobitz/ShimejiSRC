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
 * The four unprompted reaction types of a {@link Mascot}: spontaneous window comments, system
 * audio, screen glances and overheard user speech. Extracted from {@code Mascot}; the method
 * bodies are unchanged apart from reaching the mascot through {@code m.} (instance members) or
 * {@code Mascot.} (statics), so {@code Mascot} keeps one-line delegates and its call sites do not
 * change.
 */
final class AssistantReactions
{
    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private final Mascot m;

    AssistantReactions( final Mascot m )
    {
        this.m = m;
    }

    void fireSpontaneousComment( final String windowTitle )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( m.getImageSet( ) );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : m.getImageSet();
        final String personality = m.getPersonality( cfg, mascotName );

        final String spontSpeechRule = Mascot.getSpeechRule( cfg );

        // No peerCtx: this comment is addressed to the user; quoted peer lines
        // leak in as non-sequitur mentions ("...while 2B mentions Unreal Engine").
        final String system = Mascot.withSpeechReminder( m.getPersonalityQuick( cfg, mascotName )
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( spontSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + spontSpeechRule )
            + "\n- Reply in ONE sentence. 15 words maximum."
            + Mascot.QUICK_REACTION_STYLE_RULES
            + "\n- This is an unprompted observation. Be brief and natural."
            + "\n- No greetings, no filler."
            + "\n- Do not reuse or echo words directly from the window title."
            + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
            + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
            + "\n---", spontSpeechRule );

        final String audioCtxSpont = Mascot.audioSnapshotContext();
        final String prompt =
            "The user's active window is: \"" + windowTitle + "\"."
            + ( audioCtxSpont.isEmpty() ? "" : " " + audioCtxSpont + "." )
            + Mascot.situationBackground( )
            + " Make one short in-character observation, addressing the user directly as \"you\" — not in third person."
            + " Do not repeat the raw title string.";

        // All Swing calls must be on the EDT. fireSpontaneousComment runs on the
        // manager thread, so dispatch bubble creation and showThinking via invokeLater.
        if( Mascot.ollamaClient == null ) Mascot.ollamaClient = Mascot.createOllamaClient();
        final OllamaClient client = Mascot.ollamaClient;

        javax.swing.SwingUtilities.invokeLater( ( ) ->
        {
            m.ensureFreshBubble();
            final java.awt.Rectangle b = m.getBounds( );
            if( b != null ) m.assistantBubble.showThinking( b );
        } );

        client.generateDeferrable( system, prompt, new OllamaClient.Callback( )
        {
            @Override public void onResponse( final String raw )
            {
                m.fireActionFromResponse( raw );
                final String text = trimToFirstSentence( m.applyPersonaRewrites( polishUtterance( stripActionTag( raw ) ) ) );
                if( isIncompleteUtterance( text ) )
                {
                    javax.swing.SwingUtilities.invokeLater( ( ) ->
                    {
                        if( m.assistantBubble != null ) m.assistantBubble.dismiss( );
                    } );
                    return;
                }
                com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(to: " + windowTitle + ")", text );
                com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                    .addFact( "[Observed] Window: " + windowTitle );
                com.group_finity.mascot.assistant.MascotSpeechRegistry
                    .record( m.getImageSet(), mascotName, text, 0 );
                final String wCtx = "[Active window] " + windowTitle;
                javax.swing.SwingUtilities.invokeLater( ( ) ->
                {
                    final java.awt.Rectangle b = m.getBounds( );
                    if( b != null && m.assistantBubble != null )
                        m.assistantBubble.showResponse( text, wCtx, b );
                } );
            }
            @Override public void onError( final String error )
            {
                javax.swing.SwingUtilities.invokeLater( ( ) ->
                {
                    if( m.assistantBubble != null ) m.assistantBubble.dismiss( );
                } );
            }
        } );
    }

    void fireVisionReaction()
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : m.getImageSet();
        final String personality = m.getPersonalityQuick( cfg, mascotName );
        final String visionSpeechRule = Mascot.getSpeechRule( cfg );

        // No peerCtx: screen-glance comments address the user; peer quotes bleed in.
        final String system = Mascot.withSpeechReminder( personality
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( visionSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + visionSpeechRule )
            + "\n- Reply in ONE sentence. 15 words maximum."
            + Mascot.QUICK_REACTION_STYLE_RULES
            + "\n- This is an unprompted glance at the user's screen. Be brief, natural, in-character."
            + "\n- No greetings, no filler."
            + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
            + "\n---", visionSpeechRule );

        if( Mascot.ollamaClient == null ) Mascot.ollamaClient = Mascot.createOllamaClient();
        final OllamaClient client = Mascot.ollamaClient;
        final String vModel = Main.getInstance().getProperties()
            .getProperty( "VisionModel", Mascot.DEFAULT_VISION_MODEL );

        new Thread( () ->
        {
            try
            {
                final String base64 = Mascot.captureScreenBase64();
                final String audioCtxVision = Mascot.audioSnapshotContext();
                final String prompt =
                    "You just glanced at the user's screen."
                    + ( audioCtxVision.isEmpty() ? "" : " " + audioCtxVision + "." )
                    + " Make one brief, in-character observation about what you see."
                    + " Address the user directly as \"you\". One sentence only."
                    + Mascot.situationBackground( );

                javax.swing.SwingUtilities.invokeLater( () ->
                {
                    m.ensureFreshBubble();
                    final java.awt.Rectangle b = m.getBounds();
                    if( b != null ) m.assistantBubble.showThinking( b );
                });

                client.generateWithImage( system, prompt, base64, vModel, true,
                    new OllamaClient.Callback()
                    {
                        @Override public void onResponse( final String raw )
                        {
                            m.fireActionFromResponse( raw );
                            final String text = trimToFirstSentence( m.applyPersonaRewrites( polishUtterance( stripActionTag( raw ) ) ) );
                            if( isIncompleteUtterance( text ) )
                            {
                                javax.swing.SwingUtilities.invokeLater( () ->
                                {
                                    if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                                });
                                return;
                            }
                            com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(screen glance)", text );
                            // No fact stored: "Screen glance" alone carries no information, and
                            // storing the reaction text self-reinforces phrasing templates.
                            com.group_finity.mascot.assistant.MascotSpeechRegistry
                                .record( m.getImageSet(), mascotName, text, 0 );
                            javax.swing.SwingUtilities.invokeLater( () ->
                            {
                                final java.awt.Rectangle b = m.getBounds();
                                if( b != null && m.assistantBubble != null )
                                    m.assistantBubble.showResponse( text, "[Screen glance]", b );
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
            catch( final Exception e )
            {
                log.warning( "[Vision] Spontaneous capture failed: " + e.getMessage() );
            }
        }, "vision-reaction" ).start();
    }

    /**
     * Ensures the shared AudioTranscriptBuffer is started, then
     * transcribes the last ~15s of audio and fires a personality reaction.
     * Called from tick() — runs Whisper + Ollama on a background thread.
     */
    void fireAudioReaction()
    {
        synchronized( Mascot.AUDIO_LOCK )
        {
            if( Mascot.audioBuffer == null )
            {
                Mascot.audioBuffer = new com.group_finity.mascot.assistant.AudioTranscriptBuffer();
                if( !Mascot.audioBuffer.start() )
                {
                    log.warning( "[Audio] Failed to start capture — audio reactions disabled." );
                    Mascot.audioBuffer = null;
                    return;
                }
            }
        }

        final com.group_finity.mascot.assistant.AudioTranscriptBuffer buf = Mascot.audioBuffer;
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : m.getImageSet();
        final String personality = m.getPersonalityQuick( cfg, mascotName );
        final String audioSpeechRule = Mascot.getSpeechRule( cfg );

        if( Mascot.ollamaClient == null ) Mascot.ollamaClient = Mascot.createOllamaClient();
        final OllamaClient client = Mascot.ollamaClient;
        final String vModel = Main.getInstance().getProperties()
            .getProperty( "VisionModel", Mascot.DEFAULT_VISION_MODEL );

        // Snapshot window title now — focus may shift during Whisper's run
        final String windowTitleSnapshot = m.lastActiveWindowTitle;

        // Whisper runs first on background thread — only show bubble if transcript is good
        new Thread( () ->
        {
            final com.group_finity.mascot.assistant.AudioTranscriptBuffer.TranscriptResult result =
                buf.transcribeWithSource();
            if( result == null || result.transcript == null || result.transcript.length() < 8 )
            {
                log.info( "[Audio] Nothing useful heard, skipping." );
                return;
            }
            final String transcript = result.transcript;
            final String source     = result.source; // may be null
            log.info( "[Audio] Transcript: " + transcript
                + ( source != null ? " (source: " + source + ")" : "" ) );

            if( isAudioTranscriptDuplicate( transcript, Mascot.lastAudioTranscriptText ) )
            {
                log.info( "[Audio] Near-duplicate transcript — skipping reaction." );
                return;
            }
            Mascot.lastAudioTranscriptText = transcript;

            // Attempt screen capture — enriches the reaction with visual context
            String capturedBase64 = null;
            try { capturedBase64 = Mascot.captureScreenBase64(); }
            catch( final Exception e )
            { log.fine( "[Audio] Screen capture failed, falling back to audio-only: " + e.getMessage() ); }
            final boolean hasScreen = capturedBase64 != null;

            final String screenRule = hasScreen
                ? "\n- You overheard this audio; a screen snapshot is attached ONLY so you understand what the audio belongs to."
                  + "\n- React to the single most interesting detail. Do NOT describe the screen and the audio together in one sentence."
                : "\n- This is an unprompted reaction to overheard audio only. You cannot see the screen."
                  + "\n- React only to what was said.";

            // No peerCtx: quoted peer lines leak into user-directed reactions as
            // non-sequitur mentions and style contamination.
            final String system = Mascot.withSpeechReminder( personality
                + "\n\n---"
                + "\nRULES (override everything else):"
                + ( audioSpeechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + audioSpeechRule )
                + "\n- Reply in ONE sentence. 15 words maximum."
                + Mascot.QUICK_REACTION_STYLE_RULES
                + screenRule
                + "\n- Be brief, natural, in-character. No greetings, no filler."
                + "\n- Avoid defaulting to the phrase \"preoccupied with\" — use it sparingly, not as a go-to."
                + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
                + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
                + "\n---", audioSpeechRule );

            // Good transcript — now show thinking and send to Ollama
            javax.swing.SwingUtilities.invokeLater( () ->
            {
                m.ensureFreshBubble();
                final java.awt.Rectangle b = m.getBounds();
                if( b != null ) m.assistantBubble.showThinking( b );
            });

            final boolean isBrowser =
                com.group_finity.mascot.assistant.AudioSessionUtil.BROWSER.equals( source );

            // For browsers, include the page/tab title so Ollama knows what's playing
            final String tabContext;
            if( isBrowser && windowTitleSnapshot != null && !windowTitleSnapshot.isEmpty() )
                tabContext = " The browser tab title is: \"" + windowTitleSnapshot + "\".";
            else
                tabContext = "";

            final String userSpeech = result.userSpeech; // may be null

            final String sourceHint = source != null ? " from " + source : "";
            final String mediaHint  = isBrowser
                ? " This is likely a video, stream, or music track playing in a browser."
                  + " The transcript may be speech, dialogue, commentary, or song lyrics."
                  + tabContext
                : "";
            final String userHint = userSpeech != null
                ? " The user also said: \"" + userSpeech + "\"."
                : "";
            final String reactionScope = userSpeech != null
                ? " React naturally to both what was playing and what the user said."
                : " Refer to whoever is speaking or what is playing as \"they\" or \"them\" — not \"you\".";
            final String prompt =
                "You just overheard this audio" + sourceHint + ": \"" + transcript + "\"."
                + userHint
                + mediaHint
                + " Make one short, in-character reaction."
                + reactionScope
                + " Do not repeat what was said verbatim."
                + Mascot.situationBackground( );

            final String audioContext = source != null
                ? "[Overheard audio from " + source + "] " + transcript
                : "[Overheard audio] " + transcript;

            // Guard: skip if a name-trigger or other Ollama request is already in flight.
            // Request is queued via OllamaClient — no manual claiming needed.

            // Summarization needs ~200-300 tokens: 5 facts + TONE + PEER_TONE lines.
            // The default 80-token cap cuts off the response before TONE: is reached.
            final OllamaClient.Callback audioCb = new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    m.fireActionFromResponse( raw );
                    final String text = trimToFirstSentence( m.applyPersonaRewrites( polishUtterance( stripActionTag( raw ) ) ) );
                    if( isIncompleteUtterance( text ) )
                    {
                        javax.swing.SwingUtilities.invokeLater( () ->
                        {
                            if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                        });
                        return;
                    }
                    final String audioSource = source != null ? source : "audio";
                    com.group_finity.mascot.assistant.ChatLog.append( audioSource,
                        "\"" + transcript.substring( 0, Math.min( 300, transcript.length() ) ) + "\"" );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName + "(to: " + audioSource + ")", text );
                    final String sourcePrefix = source != null ? " from " + source : "";
                    final String userNote = userSpeech != null
                        ? " [User: " + userSpeech.substring( 0, Math.min( 120, userSpeech.length() ) ) + "]"
                        : "";
                    // Store only the observation — feeding the mascot's own reaction back
                    // into memory entrenches whatever phrasing template it used.
                    com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                        .addFact( "[Observed] Heard" + sourcePrefix + ": "
                            + transcript.substring( 0, Math.min( 150, transcript.length() ) )
                            + userNote );
                    com.group_finity.mascot.assistant.MascotSpeechRegistry
                        .record( m.getImageSet(), mascotName, text, 0 );
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = m.getBounds();
                        if( b != null && m.assistantBubble != null )
                            m.assistantBubble.showResponse( text, audioContext, b );
                    });
                }
                @Override public void onError( final String error )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                    });
                }
            };

            if( hasScreen )
                client.generateWithImage( system, prompt, capturedBase64, vModel, true, audioCb );
            else
                client.generateDeferrable( system, prompt, audioCb );
        }, "audio-reaction" ).start();
    }

    /**
     * Reacts to the user being overheard talking to something or someone else —
     * a voice call, a stream chat, swearing at a game — without calling any
     * mascot's name. Fired by VoiceCommandListener with the echo-stripped mic
     * transcript. Transcribes the system-audio buffer first so the reaction
     * understands what the user was responding to (the other side of the call,
     * the video they're reacting to), then reacts to the exchange as a whole.
     * Called on the voice-poll thread; cheap gates run here, heavy work
     * (Whisper + Ollama) on a dedicated daemon thread.
     */
    void fireUserSpeechReaction( final String micText )
    {
        // Substance gate: short fragments are mostly Whisper noise or half-words.
        if( micText == null || micText.trim().split( "\\s+" ).length < 4 ) return;

        final long nowU = System.currentTimeMillis();
        final long lastU = globalUserSpeechLastFiredMs.get();
        if( nowU - lastU < GLOBAL_USER_SPEECH_COOLDOWN_MS
                || !globalUserSpeechLastFiredMs.compareAndSet( lastU, nowU ) )
            return;

        synchronized( Mascot.AUDIO_LOCK )
        {
            if( Mascot.audioBuffer == null )
            {
                Mascot.audioBuffer = new com.group_finity.mascot.assistant.AudioTranscriptBuffer();
                if( !Mascot.audioBuffer.start() )
                {
                    Mascot.audioBuffer = null; // react to the mic transcript alone
                }
            }
        }

        final com.group_finity.mascot.assistant.AudioTranscriptBuffer buf = Mascot.audioBuffer;
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : m.getImageSet();
        final String personality = m.getPersonalityQuick( cfg, mascotName );
        final String speechRule  = Mascot.getSpeechRule( cfg );

        if( Mascot.ollamaClient == null ) Mascot.ollamaClient = Mascot.createOllamaClient();
        final OllamaClient client = Mascot.ollamaClient;

        new Thread( () ->
        {
            // System audio = what the user is responding to. blocks on Whisper.
            final String sysTranscript = buf != null ? buf.transcribe() : null;
            final boolean hasSys = sysTranscript != null && !sysTranscript.isBlank();

            // Re-strip against the FRESH system transcript. The strip the voice
            // listener did used lastSysTranscript, which can be minutes old —
            // speaker content newer than that had no reference and slipped
            // through as "user speech". This transcript covers the same window
            // the mic just heard.
            final String userSaid = hasSys
                ? com.group_finity.mascot.assistant.AudioTranscriptBuffer
                    .stripOverlap( sysTranscript, micText )
                : micText;
            if( userSaid == null || userSaid.isBlank() )
            {
                // Echo after all — return the cooldown so a genuine utterance
                // shortly after can still fire.
                globalUserSpeechLastFiredMs.compareAndSet( nowU, lastU );
                log.fine( "[Voice] Overheard speech was speaker echo — skipping reaction." );
                return;
            }

            final String system = Mascot.withSpeechReminder( personality
                + "\n\n---"
                + "\nRULES (override everything else):"
                + ( speechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + speechRule )
                + "\n- Reply in ONE sentence. 15 words maximum."
                + Mascot.QUICK_REACTION_STYLE_RULES
                + "\n- You overheard the user speaking — they were NOT talking to you. Do not answer as if addressed."
                + "\n- Address the user as \"you\". Refer to anyone else in the conversation as \"they\"."
                + "\n- Be brief, natural, in-character. No greetings, no filler."
                + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent. Omit it when nothing fits."
                + "\n- Do not generate any other bracket tags such as [OBSERVATION:...] or [NOTE:...]. Only [ACTION:BehaviorName] is valid."
                + "\n---", speechRule );

            final String sysCtx = hasSys
                ? " At the same time this audio was playing (likely the other side of their conversation,"
                  + " or whatever they are reacting to): \"" + sysTranscript + "\"."
                  + " Use it ONLY to understand what the user is responding to."
                : "";
            final String prompt =
                "You overheard the user say: \"" + userSaid + "\"." + sysCtx
                + " Make one short, in-character remark about the user's side of the exchange."
                + " Do not repeat what was said verbatim."
                + Mascot.situationBackground( );

            javax.swing.SwingUtilities.invokeLater( () ->
            {
                m.ensureFreshBubble();
                final java.awt.Rectangle b = m.getBounds();
                if( b != null ) m.assistantBubble.showThinking( b );
            });

            final String overheardContext = "[Overheard user] " + userSaid;

            client.generateDeferrable( system, prompt, new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    m.fireActionFromResponse( raw );
                    final String text = trimToFirstSentence( m.applyPersonaRewrites(
                        polishUtterance( stripActionTag( raw ) ) ) );
                    if( isIncompleteUtterance( text ) )
                    {
                        javax.swing.SwingUtilities.invokeLater( () ->
                        {
                            if( m.assistantBubble != null ) m.assistantBubble.dismiss();
                        });
                        return;
                    }
                    com.group_finity.mascot.assistant.ChatLog.append( "User(overheard)",
                        "\"" + userSaid.substring( 0, Math.min( 300, userSaid.length() ) ) + "\"" );
                    com.group_finity.mascot.assistant.ChatLog.append(
                        mascotName + "(to: overheard user)", text );
                    // Store only the observation — never the mascot's own reaction text.
                    com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                        .addFact( "[Observed] User said (not to me): "
                            + userSaid.substring( 0, Math.min( 150, userSaid.length() ) ) );
                    com.group_finity.mascot.assistant.MascotSpeechRegistry
                        .record( m.getImageSet(), mascotName, text, 0 );
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = m.getBounds();
                        if( b != null && m.assistantBubble != null )
                            m.assistantBubble.showResponse( text, overheardContext, b );
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
        }, "user-speech-reaction" ).start();
    }
}
