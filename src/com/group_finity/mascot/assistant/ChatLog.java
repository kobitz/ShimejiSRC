package com.group_finity.mascot.assistant;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Appends every mascot/user exchange to chat.log in the working directory.
 * Format per line: yyyy-MM-dd:HH:mm:ss:Source:Message
 * Source examples: User, User(voice), Hornet, Hornet(window: Notepad),
 *                  Hornet(heard from: Spotify), Hornet(screen glance),
 *                  Hornet(peer: Holo), Hornet(say)
 */
public final class ChatLog
{
    private static final File LOG_FILE = new File( "chat.log" );
    private static final SimpleDateFormat FMT = new SimpleDateFormat( "yyyy-MM-dd:HH:mm:ss" );

    private ChatLog() {}

    public static void append( final String source, final String message )
    {
        final String clean = message == null ? ""
            : message.replace( "\r", "" ).replace( "\n", " " ).trim();
        final String line = FMT.format( new Date() ) + ":" + source + ":" + clean;
        synchronized( ChatLog.class )
        {
            try( BufferedWriter w = new BufferedWriter( new FileWriter( LOG_FILE, true ) ) )
            {
                w.write( line );
                w.newLine();
            }
            catch( final IOException e )
            {
                // Silent — never break normal operation for a log write failure
            }
        }
    }
}
