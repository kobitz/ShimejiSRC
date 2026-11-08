package com.group_finity.mascot.assistant;

import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.config.Configuration;
import com.group_finity.mascot.exception.CantBeAliveException;

import java.awt.Rectangle;

/**
 * The narrow view of a mascot that assistant classes are allowed to use.
 * {@code Mascot} implements it; tests supply a fake. Grow this one method at a time,
 * only when an assistant class genuinely needs something new from its mascot.
 */
public interface AssistantHost
{
    /** Image-set (character) name of this mascot. */
    String getImageSet( );

    /** Current screen bounds of the mascot window. */
    Rectangle getBounds( );

    /** Behavior configuration of this mascot's image set; may be null. */
    Configuration getConfiguration( );

    /** Switch this mascot to the given behavior. */
    void setBehavior( Behavior behavior ) throws CantBeAliveException;

    /** The mascot's chat bubble, or null before the first exchange. */
    AssistantBubble assistantBubble( );
    void setAssistantBubble( AssistantBubble bubble );

    /** The open typed-input dialog, or null. */
    AssistantInputDialog activeDialog( );
    void setActiveDialog( AssistantInputDialog dialog );

    /** Foreground-window title captured when the user last pressed the mascot ("" if none). */
    String lastActiveWindowTitle( );

    /** All bubbles this mascot owns (chat + timer); thread-safe list. */
    java.util.List<AssistantBubble> activeBubbles( );

    /** Entry point a bubble calls with the user's reply text. */
    void handleReply( String userText, AssistantBubble.Message message );

    /** The countdown-timer bubble, created and registered on first use. */
    AssistantBubble timerBubble( );
}
