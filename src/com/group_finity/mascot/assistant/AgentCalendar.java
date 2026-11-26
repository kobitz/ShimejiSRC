package com.group_finity.mascot.assistant;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Calendar events as a standard .ics file (opens in Outlook, Windows Calendar, Apple/Google import) or a Google Calendar link. */
public final class AgentCalendar
{
    private AgentCalendar() { }

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern( "yyyyMMdd'T'HHmmss'Z'" ).withZone( ZoneOffset.UTC );

    static String esc( final String s )
    {
        return s.replace( "\\", "\\\\" ).replace( ";", "\\;" ).replace( ",", "\\," ).replace( "\r", "" ).replace( "\n", "\\n" );
    }

    /** The calendar text. Times are UTC so every app places them correctly whatever the zone. */
    public static String ics( final String title, final long startMs, final long durMs, final int alarmMinutes, final long nowMs )
    {
        final String t = title.length() > 120 ? title.substring( 0, 120 ) : title;
        final StringBuilder sb = new StringBuilder();
        sb.append( "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//koaibo//Agent//EN\r\nCALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\n" )
          .append( "BEGIN:VEVENT\r\nUID:" ).append( UUID.randomUUID() ).append( "@koaibo\r\n" )
          .append( "DTSTAMP:" ).append( UTC.format( Instant.ofEpochMilli( nowMs ) ) ).append( "\r\n" )
          .append( "DTSTART:" ).append( UTC.format( Instant.ofEpochMilli( startMs ) ) ).append( "\r\n" )
          .append( "DTEND:" ).append( UTC.format( Instant.ofEpochMilli( startMs + durMs ) ) ).append( "\r\n" )
          .append( "SUMMARY:" ).append( esc( t ) ).append( "\r\n" );
        if( alarmMinutes > 0 )
            sb.append( "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:" ).append( esc( t ) ).append( "\r\nTRIGGER:-PT" ).append( alarmMinutes ).append( "M\r\nEND:VALARM\r\n" );
        sb.append( "END:VEVENT\r\nEND:VCALENDAR\r\n" );
        return sb.toString();
    }

    /** Write the .ics into {@code dir} and return its path. */
    public static Path write( final Path dir, final String title, final long startMs, final long durMs, final int alarmMinutes ) throws IOException
    {
        Files.createDirectories( dir );
        final String slug = title.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", "-" ).replaceAll( "^-|-$", "" );
        final Path p = dir.resolve( UTC.format( Instant.ofEpochMilli( startMs ) ).substring( 0, 13 ) + "-" + ( slug.isEmpty() ? "event" : slug.substring( 0, Math.min( 40, slug.length() ) ) ) + ".ics" );
        Files.write( p, ics( title, startMs, durMs, alarmMinutes, System.currentTimeMillis() ).getBytes( StandardCharsets.UTF_8 ) );
        return p;
    }

    /** Google Calendar's "add event" page, prefilled; the user just presses Save. */
    public static String googleUrl( final String title, final long startMs, final long durMs )
    {
        try
        {
            return "https://calendar.google.com/calendar/render?action=TEMPLATE&text=" + URLEncoder.encode( title, "UTF-8" )
                + "&dates=" + UTC.format( Instant.ofEpochMilli( startMs ) ) + "/" + UTC.format( Instant.ofEpochMilli( startMs + durMs ) );
        }
        catch( final java.io.UnsupportedEncodingException e ) { throw new IllegalStateException( e ); }
    }
}
