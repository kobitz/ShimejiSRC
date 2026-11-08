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
 * Small assistant support helpers: screen capture (base64 PNG), shared Ollama client
 * factory, and the fresh-bubble guard. Extracted verbatim from {@code Mascot} (step 9
 * of the split); {@code Mascot} keeps one-line delegates. The bubble's
 * {@code this::handleReply} is now {@code m::handleReply} (same target).
 */
final class AssistantSupport
{
    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private final Mascot m;

    AssistantSupport( final Mascot m )
    {
        this.m = m;
    }

    /** Capture the primary screen, scale to max 1024px wide, return base64-encoded PNG. */
    static String captureScreenBase64() throws java.awt.AWTException, java.io.IOException
    {
        // Capture + downscale + PNG-encode is a CPU spike that competes with the 40ms
        // tick loop — a screen glance is the heaviest unprompted reaction, and the
        // TickWatch breakdown traced the multi-second whole-program crawls to exactly
        // these vision bursts (cpu pegged, every mascot tick + EnumWindows starved
        // together). Drop this (dedicated, short-lived) thread's priority for the
        // duration so the loop keeps moving, then restore it for the HTTP/inference wait.
        final Thread me = Thread.currentThread();
        final int prevPriority = me.getPriority();
        me.setPriority( Thread.MIN_PRIORITY );
        try
        {
            final java.awt.Rectangle screen = new java.awt.Rectangle(
                java.awt.Toolkit.getDefaultToolkit().getScreenSize() );
            final java.awt.image.BufferedImage full =
                new java.awt.Robot().createScreenCapture( screen );

            // 768-wide cap (was 1024): fewer vision tokens => less model preprocessing
            // and inference (the part that pegs the CPU) plus a smaller/faster encode.
            // Raise back toward 1024 if the vision model's screen-text legibility suffers.
            final int maxW = 768;
            java.awt.image.BufferedImage img = full;
            if( full.getWidth() > maxW )
            {
                final int h = (int)( full.getHeight() * (double) maxW / full.getWidth() );
                img = new java.awt.image.BufferedImage( maxW, h,
                    java.awt.image.BufferedImage.TYPE_INT_RGB );
                final java.awt.Graphics2D g = img.createGraphics();
                g.drawImage( full, 0, 0, maxW, h, null );
                g.dispose();
            }

            final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write( img, "png", baos );
            return java.util.Base64.getEncoder().encodeToString( baos.toByteArray() );
        }
        finally
        {
            me.setPriority( prevPriority );
        }
    }

    /** Create OllamaClient using the model selected in Settings. */
    static OllamaClient createOllamaClient()
    {
        final String model = Main.getInstance().getProperties()
            .getProperty( "OllamaModel", OllamaClient.DEFAULT_MODEL );
        final String endpoint = Main.getInstance().getProperties()
            .getProperty( "OllamaEndpoint", "http://localhost:11434/api/generate" );
        return new OllamaClient( endpoint, model );
    }

    /**
     * Ensure assistantBubble is ready for a new Thinking/response cycle.
     * Must be called on the EDT. Creates a fresh bubble when the current one
     * already has a visible AI response so each exchange gets its own window
     * and its own chronological timestamp for inter-mascot vertical stacking.
     */
    void ensureFreshBubble()
    {
        if( m.assistantBubble == null || m.assistantBubble.hasActiveResponse() )
        {
            m.assistantBubble = new com.group_finity.mascot.assistant.AssistantBubble(
                m::handleReply, m.getImageSet() );
            m.activeBubbles.add( m.assistantBubble );
        }
    }
}
