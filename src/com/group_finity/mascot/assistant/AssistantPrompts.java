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
 * Prompt construction for the assistant: system prompt, user message, personality
 * and speech-rule lookup, weather/audio/situation context, persona pronoun rewrite.
 * Extracted verbatim from {@code Mascot} (step 8 of the split); {@code Mascot} keeps
 * one-line delegates of the same names. Instance members of the mascot are reached
 * as {@code m.x}, statics as {@code Mascot.X}.
 */
public final class AssistantPrompts
{
    private static final Logger log = Logger.getLogger( com.group_finity.mascot.Mascot.class.getName( ) );

    private final AssistantHost m;

    public AssistantPrompts( final AssistantHost m )
    {
        this.m = m;
    }

    /**
     * Wraps the raw user text for the Ollama prompt.
     * When the question is about the active window, appends the window title
     * directly into the user turn so the model can answer concretely.
     */
    public String buildUserMessage( final String userText )
    {
        final String trimmed = userText.trim();
        final String lower   = trimmed.toLowerCase();

        final boolean asksAboutWindow =
            lower.contains( "watching" )   ||
            lower.contains( "playing" )    ||
            lower.contains( "doing" )      ||
            lower.contains( "working on" ) ||
            lower.contains( "looking at" );

        log.info( "[Assistant] buildUserMessage — asksAboutWindow=" + asksAboutWindow
            + " lastActiveWindowTitle=\"" + m.lastActiveWindowTitle() + "\"" );
        if( asksAboutWindow && m.lastActiveWindowTitle() != null && !m.lastActiveWindowTitle().isEmpty() )
        {
            return "“" + trimmed + "”"
                + " [The active window title is: \"" + m.lastActiveWindowTitle() + "\"."
                + " Use this to infer what the user is watching, playing, or doing"
                + " and answer their question naturally. Do not repeat the raw title.]";
        }

        return "“" + trimmed + "”";
    }

    public String buildSystemPrompt( final com.group_finity.mascot.config.Configuration cfg,
                                      final String mascotName,
                                      final String userText )
    {
        final String personalityBase = getPersonality( cfg, mascotName );

        // Use the pre-dialog snapshot — by the time we build the prompt the
        // input dialog has focus and getActiveWindowTitle() would return itself.
        final String activeTitle = m.lastActiveWindowTitle();
        final String windowContext = ( activeTitle != null && !activeTitle.isEmpty( ) )
            ? "\nThe user's foreground window title is: \"" + activeTitle + "\". "
              + "Use this to infer what the user is doing or watching — "
              + "extract the meaningful part (e.g. video title, app name, document name) "
              + "and refer to that naturally. Do not quote or repeat the raw title string."
            : "";

        final com.group_finity.mascot.assistant.MascotMemory memory =
            com.group_finity.mascot.assistant.MascotMemory.forImageSet( m.getImageSet() );

        final String peerCtx = com.group_finity.mascot.assistant.MascotSpeechRegistry
            .buildContext( m.getImageSet() );

        final java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat( "EEEE, MMMM d yyyy 'at' h:mm a" );
        final String timeStr = sdf.format( new java.util.Date() );
        final String cachedPlace =
            com.group_finity.mascot.assistant.WeatherTool.getCachedPlaceName();
        final String cachedWx =
            com.group_finity.mascot.assistant.WeatherTool.getCachedResult();
        final String sitCtx = AssistantShared.situationContext( );
        final String envCtx = "\n[Current time: " + timeStr + ".]"
            + ( cachedPlace != null ? "\n[User location: " + cachedPlace + ".]" : "" )
            + ( cachedWx   != null ? "\n[Current weather: " + cachedWx + ".]" : "" )
            + ( sitCtx.isEmpty( ) ? "" : "\n[" + sitCtx + "]" );

        // Relationship layer: a compact multi-day digest of the user's activity, so the companion
        // can call back to the arc of the week ("you've been deep in that all week"). Empty when
        // the journal is disabled/cold. Direct replies only — the conversational surface where it lands.
        final String recentDays =
            com.group_finity.mascot.assistant.SituationModel.recentDaysDigest( );

        final String permBlock = userText != null
            ? memory.buildPermanentMemoryBlock( userText ) : "";

        // Keyword-gated like permanentMemories: only injects when the user's words
        // match indexed paths, so unrelated chats cost no extra prompt tokens.
        final String driveBlock = userText != null
            ? com.group_finity.mascot.assistant.DriveIndexTool.buildContextBlock( userText ) : "";

        final String speechRule = getSpeechRule( cfg );
        final String result = personalityBase
            + memory.buildMemoryBlock()
            + recentDays
            + permBlock
            + driveBlock
            + ( peerCtx.isEmpty() ? "" : "\n\nOther desktop mascots present:" + peerCtx )
            + envCtx
            + "\n\n---"
            + "\nRULES (override everything else):"
            + ( speechRule.isEmpty() ? "" : "\n- CRITICAL SPEECH CONSTRAINT: " + speechRule )
            + "\n- Reply in ONE sentence. Two at most if truly necessary. Never more."
            + "\n- Answer only what was asked. Do not volunteer extra information."
            + "\n- No greetings, no sign-offs, no filler words."
            + "\n- Speak your response directly — do not wrap it in quotation marks."
            + "\n- When asked about time or weather: you MUST state the actual values from context (e.g. the real temperature, the real time). Express them in your character voice, but the real numbers must appear in your answer. Do not replace them with metaphor or fictional equivalents."
            + "\n- Only when the user explicitly asks you to set a timer, reminder, or alarm in THIS message — never on your own initiative, never because a topic reminded you of one: append [TIMER:VALUE:reminder text]. VALUE = whole minutes for durations, e.g. [TIMER:15:reminder text], OR the exact clock time the user stated for specific times, e.g. [TIMER:5:30pm:reminder text] when the user says 'at 5:30pm'. For clock times: write the time EXACTLY as the user said it — do NOT convert to minutes, do NOT compute anything. Java handles the math. The reminder text must be drawn from what the user actually said, never invented."
            + "\n- You may optionally append [ACTION:BehaviorName] after your spoken text to trigger a matching animation. The tag is silent — not spoken. Omit it when nothing fits naturally."
            + "\n- To save something permanently for future conversations: append [REMEMBER:keyword1,keyword2|the fact to remember]. Use short, specific keywords the user is likely to mention again. The tag is silent."
            + windowContext
            + "\n---"
            + "\nRespond to the user\'s message now.";
        return withSpeechReminder( result, speechRule );
    }

    public String getPersonality( final com.group_finity.mascot.config.Configuration cfg,
                                   final String mascotName )
    {
        if( cfg != null )
        {
            final String p = cfg.getInformation( "Personality" );
            if( p != null && !p.trim( ).isEmpty( ) )
                return p.trim( );
        }
        return "You are " + mascotName + ", a small desktop mascot assistant living on the user's screen. "
             + "You are helpful, cheerful, and a little quirky. "
             + "You are aware that you are a tiny animated character on their desktop.";
    }

    public String getPersonalityQuick( final com.group_finity.mascot.config.Configuration cfg,
                                        final String mascotName )
    {
        if( cfg != null )
        {
            final String brief = cfg.getInformation( "PersonalityBrief" );
            if( brief != null && !brief.trim().isEmpty() )
                return brief.trim();
        }
        return getPersonality( cfg, mascotName );
    }

    /**
     * Returns the personality string for this mascot.
     * Reads <Personality> from the mascot's Information XML block if present;
     * falls back to a generic desktop-assistant prompt.
     */
    /** Returns the SpeechRule line for injection into RULES, or empty string if not set. */
    public static String getSpeechRule( final com.group_finity.mascot.config.Configuration cfg )
    {
        if( cfg == null ) return "";
        final String r = cfg.getInformation( "SpeechRule" );
        return ( r != null && !r.isBlank() ) ? r.trim() : "";
    }

    /** Appends a speech rule reminder after the closing --- for recency reinforcement. */
    public static String withSpeechReminder( final String system, final String speechRule )
    {
        if( speechRule.isEmpty() ) return system;
        return system + "\nFinal reminder: " + speechRule;
    }

    /**
     * The fused situational read as a labeled, background-only clause to append to an
     * unprompted-reaction user prompt (peer/audio/vision/user-speech/spontaneous), or "" when
     * the model is inactive. Leading space + the "[Background — ...]" frame are included so it
     * appends cleanly and the model treats it as ambient context, not the thing to react to
     * (reinforced by QUICK_REACTION_STYLE_RULES' "context lines are background only"). This is
     * the cross-signal fusion: every reaction is informed by the one shared picture of what the
     * user is doing, not just its own triggering signal.
     */
    public static String situationBackground( )
    {
        final String s = AssistantShared.situationContext( );
        return s.isEmpty( ) ? "" : " [Background — " + s + "]";
    }

    /** Returns a weather context string to append to the user message, or "" if not applicable. */
    public String weatherContext( final String userText )
    {
        if( !isWeatherQuery( userText ) ) return "";
        // Weather is the one feature that contacts outside services (open-meteo, plus an IP
        // geolocation service for WeatherLocation=auto), so it has an off switch.
        if( "false".equalsIgnoreCase( Main.getInstance().getProperties()
                .getProperty( "WeatherEnabled", "true" ) ) ) return "";
        final String location = Main.getInstance().getProperties()
            .getProperty( "WeatherLocation", "auto" );
        // Run fetch on a daemon thread with a hard cap so HTTPS hangs never block the Ollama call.
        // If the fetch completes in the background after the timeout, the result is cached
        // and the next call returns instantly from the cache.
        final java.util.concurrent.FutureTask<String> task =
            new java.util.concurrent.FutureTask<>(
                () -> com.group_finity.mascot.assistant.WeatherTool.fetch( location ) );
        final Thread t = new Thread( task, "weather-fetch" );
        t.setDaemon( true );
        t.start();
        try
        {
            final String wx = task.get( 10, java.util.concurrent.TimeUnit.SECONDS );
            return wx != null ? "\n\n[Current weather: " + wx + "]" : "";
        }
        catch( final Exception e )
        {
            log.warning( "[Weather] fetch timed out or failed: " + e.getMessage() );
            return "";
        }
    }

    /**
     * Returns a non-empty "[Audio context: ...]" label if a recent system audio
     * transcript is available, otherwise returns an empty string.
     * Non-blocking — reads the volatile field populated by fireAudioReaction.
     */
    public static String audioSnapshotContext()
    {
        final String t = com.group_finity.mascot.assistant.AudioTranscriptBuffer.lastSysTranscript;
        return ( t != null && !t.isBlank() ) ? "[Audio context: " + t.trim() + "]" : "";
    }

    /**
     * Post-generation rewrite: replaces first-person pronouns with the mascot's name
     * when the speech rule contains a third-person self-reference constraint.
     * Gated by: assistantMode on, non-empty speech rule, mascot name in the rule.
     */
    public String applyPersonaRewrites( final String text )
    {
        if( text == null ) return text;
        final String name = m.getImageSet( );
        // Strip "Name: " self-prefix the model occasionally echoes
        String s = text.startsWith( name + ": " ) ? text.substring( name.length() + 2 ) : text;
        // Purely config-driven: opt in with <ThirdPersonRewrite>true</ThirdPersonRewrite>
        // in the mascot's <Information> block (Paimon carries this in her own XML). No
        // mascot name is hardcoded here — the rule lives with the mascot.
        final com.group_finity.mascot.config.Configuration cfg =
            Main.getInstance( ).getConfiguration( name );
        if( cfg != null )
        {
            final String flag = cfg.getInformation( "ThirdPersonRewrite" );
            if( "true".equalsIgnoreCase( flag ) )
                return rewriteFirstPerson( s, name );
        }
        return s;
    }
}
