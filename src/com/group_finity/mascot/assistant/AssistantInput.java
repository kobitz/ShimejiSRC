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
 * Direct, user-initiated conversation for a {@link Mascot}: the typed-input dialog, the reply to a
 * bubble click or typed line, and the text-input handler. Extracted from {@code Mascot}; method
 * bodies are unchanged apart from reaching the mascot through {@code m.} (instance members) or
 * {@code Mascot.} (statics). The {@code this::handleReply} callback now binds to this class, which
 * is equivalent because {@code Mascot.handleReply} only delegated here.
 */
public final class AssistantInput
{
    private static final Logger log = Logger.getLogger( com.group_finity.mascot.Mascot.class.getName( ) );

    private final AssistantHost m;

    public AssistantInput( final AssistantHost m )
    {
        this.m = m;
    }

    /**
     * Handle a voice utterance addressed to this mascot.
     * Mirrors the text-input pipeline but speaks the response aloud via TTS.
     * Called on a background thread by AssistantCoordinator.
     */
    public void handleTextInput( final String userText,
                                   final String mascotName,
                                   final com.group_finity.mascot.config.Configuration cfg )
    {
        // Bubble state is managed by AssistantCoordinator callbacks:
        //   wake word → onListening() → "Listening..."
        //   command captured → onThinking() → "Thinking..."
        // By the time we get here, "Thinking..." should already be showing.
        // If somehow it isn't (e.g. wake word + command in one utterance), set it now.
        SwingUtilities.invokeLater( ( ) ->
        {
            if( m.assistantBubble() == null || !m.assistantBubble().isThinking() )
            {
                new AssistantSupport( m ).ensureFreshBubble();
                m.assistantBubble().showThinking( m.getBounds( ) );
            }
        });

        final String keywordMatch = cfg != null ? new AssistantActions( m ).findActionByKeyword( userText, cfg ) : null;
        log.info( "Voice keyword match for \"" + userText + "\": " + keywordMatch );
        final String system       = new AssistantPrompts( m ).buildSystemPrompt( cfg, mascotName, userText );

        // ── Fire action immediately — don't wait for Ollama ──────────────────
        // This means voice commands interrupt whatever the mascot is doing right now,
        // same as manually selecting a behavior from the right-click menu.
        if( keywordMatch != null && cfg != null )
        {
            SwingUtilities.invokeLater( ( ) ->
            {
                new AssistantActions( m ).tryRunBehavior( keywordMatch, cfg );
            });
        }

        if( AssistantShared.ollamaClient != null )
        {
            if( isScreenQuery( userText ) )
            {
                com.group_finity.mascot.assistant.ChatLog.append( "User(voice)", userText );
                new AssistantReplies( m ).handleScreenQuery( userText, system, AssistantShared.ollamaClient );
                return;
            }
            com.group_finity.mascot.assistant.ChatLog.append( "User(voice)", userText );
            final String safeUserText = new AssistantPrompts( m ).buildUserMessage( userText ) + new AssistantPrompts( m ).weatherContext( userText );
            AssistantShared.ollamaClient.generate( system, safeUserText, new OllamaClient.Callback( )
            {
                @Override
                public void onResponse( final String raw )
                {
                    new AssistantActions( m ).fireActionFromResponse( raw );
                    final String display = new AssistantPrompts( m ).applyPersonaRewrites( polishUtterance( stripActionTag( sanitizeResponse( raw ) ) ) );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName, display );
                    if( !isEphemeralQuery( userText ) )
                        com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                            .recordUserExchange( userText, display );
                    new AssistantMemory( m ).maybeSummarizeMemory();
                    // Record without triggering peer reactions — don't interrupt user conversations
                    com.group_finity.mascot.assistant.MascotSpeechRegistry
                        .record( m.getImageSet(), mascotName, display, 1, false );

                    SwingUtilities.invokeLater( ( ) ->
                    {
                        if( m.assistantBubble() != null )
                            m.assistantBubble().showResponse( display, m.getBounds( ) );
                    });

                }

                @Override
                public void onError( final String message )
                {
                    SwingUtilities.invokeLater( ( ) ->
                    {
                        if( m.assistantBubble() != null )
                            m.assistantBubble().showError( message, m.getBounds( ) );
                    });
                }
            });
        }
        else
        {
            // No LLM — keyword action only
            SwingUtilities.invokeLater( ( ) ->
            {
                if( m.assistantBubble() != null )
                {
                    if( keywordMatch != null && cfg != null )
                    {
                        final String fail = new AssistantActions( m ).tryRunBehavior( keywordMatch, cfg );
                        m.assistantBubble().showResponse(
                            fail == null ? "On it!" : fail, m.getBounds( ) );
                    }
                    else
                    {
                        m.assistantBubble().showError( "Ollama isn't running.", m.getBounds( ) );
                    }
                }
            });
        }
    }

    public void openAssistantInput( )
    {
        // lastActiveWindowTitle was already captured at mouse-press time,
        // before the mascot window took foreground focus.

        // Close any existing dialog for this mascot before opening a new one
        if( m.activeDialog() != null )
        {
            m.activeDialog().close( );
            m.setActiveDialog( null );
        }

        // Lazily create the shared Ollama client — must go through createOllamaClient()
        // so the configured OllamaModel/OllamaEndpoint settings are honored (the bare
        // constructor would silently fall back to the hardcoded default model).
        if( AssistantShared.ollamaClient == null )
            AssistantShared.ollamaClient = AssistantSupport.createOllamaClient( );

        // Ensure a bubble exists so the dialog has something to attach to
        if( m.assistantBubble() == null )
        {
            m.setAssistantBubble( new AssistantBubble( this::handleReply, m.getImageSet() ) );
            m.activeBubbles().add( m.assistantBubble() );
        }

        final Rectangle bounds = m.getBounds( );

        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( m.getImageSet( ) );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" )
            : m.getImageSet( );

        m.setActiveDialog( new AssistantInputDialog(
            bounds,
            mascotName,
            userText ->
            {
                m.setActiveDialog( null );

                // ── Step 1: keyword action fires immediately ───────────────────
                final String keywordMatch = cfg != null ? new AssistantActions( m ).findActionByKeyword( userText, cfg ) : null;
                if( keywordMatch != null && cfg != null )
                    new AssistantActions( m ).tryRunBehavior( keywordMatch, cfg ); // on EDT — dialog callback is on EDT

                if( AssistantShared.ollamaClient == null ) AssistantShared.ollamaClient = AssistantSupport.createOllamaClient();
                final OllamaClient client = AssistantShared.ollamaClient;

                SwingUtilities.invokeLater( ( ) ->
                {
                    new AssistantSupport( m ).ensureFreshBubble();
                    m.assistantBubble().showThinking( m.getBounds( ) );
                } );

                final String system = new AssistantPrompts( m ).buildSystemPrompt( cfg, mascotName, userText );
                if( isScreenQuery( userText ) )
                {
                    com.group_finity.mascot.assistant.ChatLog.append( "User", userText );
                    new AssistantReplies( m ).handleScreenQuery( userText, system, client );
                    return;
                }
                com.group_finity.mascot.assistant.ChatLog.append( "User", userText );
                final String safeUserText = new AssistantPrompts( m ).buildUserMessage( userText );
                new Thread( () ->
                {
                    client.generate( system, safeUserText + new AssistantPrompts( m ).weatherContext( userText ), new OllamaClient.Callback( )
                    {
                        @Override
                        public void onResponse( final String raw )
                        {
                            new AssistantActions( m ).fireActionFromResponse( raw );
                            final String display = new AssistantPrompts( m ).applyPersonaRewrites( polishUtterance( stripActionTag( sanitizeResponse( raw ) ) ) );
                            com.group_finity.mascot.assistant.ChatLog.append( mascotName, display );
                            if( !isEphemeralQuery( userText ) )
                                com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                                    .recordUserExchange( userText, display );
                            new AssistantMemory( m ).maybeSummarizeMemory();
                            SwingUtilities.invokeLater( ( ) ->
                                m.assistantBubble().showResponse( display, m.getBounds( ) ) );
                        }

                        @Override
                        public void onError( final String message )
                        {
                            SwingUtilities.invokeLater( ( ) ->
                                m.assistantBubble().showError( message, m.getBounds( ) ) );
                        }
                    });
                }, "text-input-thread" ).start();
            }
        ) );
        m.activeDialog().show( );
    }

    /**
     * Fires an unprompted one-liner reacting to the user's current active window.
     * Called from tick() on a random cadence. Ollama runs on a background thread.
     */
    /**
     * Called when the user clicks a bubble message and submits a reply.
     * context is the original trigger (transcript/window title), may be null.
     * Called from VoiceCommandListener poll thread or EDT — safe either way.
     */
    public void handleReply( final String userText,
                              final com.group_finity.mascot.assistant.AssistantBubble.Message message )
    {
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance().getConfiguration( m.getImageSet() );
        final String mascotName = ( cfg != null && cfg.getInformation( "Name" ) != null )
            ? cfg.getInformation( "Name" ) : "mascot";

        final String system = new AssistantPrompts( m ).buildSystemPrompt( cfg, mascotName, userText );

        // Build prompt from full conversation thread
        final StringBuilder userMsg = new StringBuilder();
        if( message != null && message.context != null && !message.context.isEmpty() )
            userMsg.append( "[Original context: " ).append( message.context ).append( "]\n" );
        if( message != null && message.thread.size() > 1 )
        {
            userMsg.append( "Conversation so far:\n" );
            for( final String[] turn : message.thread )
                userMsg.append( turn[0] ).append( ": " ).append( turn[1] ).append( "\n" );
        }
        userMsg.append( "User reply: " ).append( userText );

        com.group_finity.mascot.assistant.ChatLog.append( "User", userText );

        if( AssistantShared.ollamaClient == null ) AssistantShared.ollamaClient = AssistantSupport.createOllamaClient();
        final OllamaClient client = AssistantShared.ollamaClient;

        javax.swing.SwingUtilities.invokeLater( () ->
        {
            new AssistantSupport( m ).ensureFreshBubble();
            final java.awt.Rectangle b = m.getBounds();
            if( b != null ) m.assistantBubble().showThinking( b );
        });

        new Thread( () ->
        {
            client.generate( system, userMsg.toString(), new OllamaClient.Callback()
            {
                @Override public void onResponse( final String raw )
                {
                    new AssistantActions( m ).fireActionFromResponse( raw );
                    final String text = new AssistantPrompts( m ).applyPersonaRewrites( polishUtterance( stripActionTag( sanitizeResponse( raw ) ) ) );
                    com.group_finity.mascot.assistant.ChatLog.append( mascotName, text );
                    // Append mascot reply to thread for next chained reply
                    if( m.assistantBubble() != null && message != null )
                        m.assistantBubble().appendReplyToThread( message, text );
                    if( !isEphemeralQuery( userText ) )
                        com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() )
                            .recordUserExchange( userText, text );
                    new AssistantMemory( m ).maybeSummarizeMemory();
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        final java.awt.Rectangle b = m.getBounds();
                        if( b != null && m.assistantBubble() != null )
                            m.assistantBubble().showResponse( text, b );
                    });
                }
                @Override public void onError( final String error )
                {
                    javax.swing.SwingUtilities.invokeLater( () ->
                    {
                        if( m.assistantBubble() != null ) m.assistantBubble().dismiss();
                    });
                }
            });
        }, "reply-thread" ).start();
    }
}
