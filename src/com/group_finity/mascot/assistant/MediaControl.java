package com.group_finity.mascot.assistant;

/**
 * Pause / play / fullscreen for the video the user is watching in the browser, through the
 * Shimeji Video Tracker extension. Only a fixed set of command names can be sent.
 */
public interface MediaControl
{
    /** True if the extension is reporting a video on screen. */
    boolean hasVideo();

    /** Send pause, play, fullscreen or exit_fullscreen; true if a tab carried it out. */
    boolean send( String command );

    static MediaControl get()
    {
        return new MediaControl()
        {
            public boolean hasVideo() { final com.group_finity.mascot.VideoAreaServer s = com.group_finity.mascot.VideoAreaServer.getInstance(); return s != null && s.hasVideo(); }
            public boolean send( final String c ) { final com.group_finity.mascot.VideoAreaServer s = com.group_finity.mascot.VideoAreaServer.getInstance(); return s != null && s.sendCommand( c, 2000 ); }
        };
    }
}
