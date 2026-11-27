package com.group_finity.mascot.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the user's OWN words for an agent command, so common requests work even when a small local
 * model never emits a {@code [DO:...]} tag. Returns the same {tool, arg...} arrays as
 * {@link AgentTools#parse}. Only ever fed the user's message, never screen text or other audio.
 */
public final class AgentIntent
{
    private AgentIntent() { }

    private static final String DIR = "(left|right|top|bottom)";
    private static final Pattern SNAP_A = Pattern.compile( "^(?:snap|dock|put|move|send|tile)\\s+(.+?)\\s+(?:window\\s+)?(?:to|on|onto|at)\\s+(?:the\\s+)?" + DIR + "(?:\\s+(?:half|side|part))?(?:\\s+of\\s+(?:the|my)\\s+screen)?$" );
    private static final Pattern SNAP_B = Pattern.compile( "^(?:snap|dock|put|move|send|tile)\\s+(?:the\\s+|my\\s+)?" + DIR + "\\s+(?:window\\s+)?(?:to|on)\\s+(.+)$" ); // rare; kept simple
    private static final String MON = "(other|another|second|2nd|next|third|3rd|primary|main|first|1st|secondary|left|right)";
    private static final Pattern MONITOR_A = Pattern.compile(
        "^(?:move|send|put|throw|shift|push|bring|take)\\s+(.+?)\\s+(?:over\\s+)?(?:to|onto|on)\\s+(?:the\\s+|my\\s+)*" + MON + "\\s+(?:one\\s+)?(?:monitor|screen|display)s?$" );
    private static final Pattern MONITOR_B = Pattern.compile(
        "^(?:move|send|put|throw|shift|push|bring|take)\\s+(.+?)\\s+(?:over\\s+)?(?:to|onto|on)\\s+(?:the\\s+|my\\s+)*(?:monitor|screen|display)\\s+(\\d)$" );
    private static final Pattern MOVE_FILE = Pattern.compile(
        "^(?:move|put|send|relocate|transfer)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+(?:to|into|in)\\s+(?:the\\s+|my\\s+)?(.+?)$" );
    private static final Pattern HAS_EXT = Pattern.compile( "\\.[a-z0-9]{1,5}$" );
    private static final Pattern CENTER = Pattern.compile( "^(?:center|centre)\\s+(.+)$" );
    private static final Pattern SIMPLE = Pattern.compile(
        "^(minimi[sz]e|hide|maximi[sz]e|fullscreen|restore|unminimi[sz]e|focus|switch\\s+to|bring\\s+up)\\s+(.+)$" );
    private static final Pattern ORGANIZE = Pattern.compile(
        "^(?:clean|tidy|organi[sz]e|sort)(?:\\s+up)?\\s+(?:out\\s+)?(?:the\\s+|my\\s+)?([a-z0-9 _-]+?)(?:\\s+folder)?$" );
    private static final Pattern UNDO = Pattern.compile( "^undo(?:\\s+(?:that|it|the\\s+(?:tidy|cleanup|clean up|sort|organi[sz]ing)))?$" );

    /** "for a minute", "for a while", "for 5 minutes", "for a couple of minutes"... */
    private static final Pattern DURATION = Pattern.compile(
        "\\s+for\\s+(?:(?:a|an|one)\\s+(minute|min|while|bit|little while|little|moment|second|sec)|(?:a\\s+)?(?:few|couple(?:\\s+of)?)\\s+minutes?|some\\s+time|(\\d+)\\s*(seconds?|secs?|minutes?|mins?))\\b" );

    /** Seconds named by the duration phrase in {@code t}, or -1 when there is none. */
    static long durationSeconds( final String t )
    {
        final Matcher d = DURATION.matcher( t );
        if( !d.find() ) return -1;
        if( d.group( 2 ) != null ) return Long.parseLong( d.group( 2 ) ) * ( d.group( 3 ).startsWith( "m" ) ? 60 : 1 );
        final String w = d.group( 1 );
        if( w == null ) return 180;                     // a few / a couple of minutes / some time
        switch( w )
        {
            case "minute": case "min": return 60;
            case "second": case "sec": return 10;
            case "bit": case "little": case "moment": return 60;
            default: return 180;                         // a while, a little while
        }
    }

    private static final Pattern FILLER = Pattern.compile( "^(?:(?:hey|ok|okay|please|can you|could you|would you|will you|go ahead and|just)[ ,]+)+", Pattern.CASE_INSENSITIVE );

    private static final Pattern S_SOUND = Pattern.compile( "^(?:play|make|ring|sound)\\s+(?:me\\s+)?(?:a\\s+|an\\s+|the\\s+)?(?:sound|chime|alarm|beep|ding|noise|bell|tone)(?:\\s+(?:for|to|saying)\\s+(.+))?$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_ALARM = Pattern.compile( "^(?:set|start|make|create)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?(?:alarm|timer)(?:\\s+(?:for|to|called|named)\\s+(.+))?$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_WAKE = Pattern.compile( "^wake me(?:\\s+up)?$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_REMIND = Pattern.compile( "^remind me\\s+(?:to\\s+|about\\s+|that\\s+|of\\s+)?(.+)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_TYPE_A = Pattern.compile( "^(?:ask|tell|have|get)\\s+(.+?)\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_TYPE_B = Pattern.compile( "^(?:type|send|say)\\s+(.+?)\\s+(?:to|in|into)\\s+(?:the\\s+|my\\s+)?(.+)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_OPEN = Pattern.compile( "^(?:open|launch|start|run|pull up|bring up|go to|visit|load)\\s+(.+)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_CAL1 = Pattern.compile( "^(?:add|put|schedule|create|set up|make|book)\\s+(?:an?\\s+)?(?:(?:calendar\\s+)?(?:event|appointment|meeting|entry)|calendar\\s+entry)\\s*(?:called|named|titled|for|about|to|:)?\\s*(.*)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_CAL2 = Pattern.compile( "^(?:add|put)\\s+(.+?)\\s+(?:on|to|in)\\s+(?:my\\s+)?calendar$", Pattern.CASE_INSENSITIVE );
    private static final Pattern S_CAL3 = Pattern.compile( "^schedule\\s+(?:a\\s+|an\\s+)?(.+)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern AGENDA = Pattern.compile(
        "^(?:what(?:'s| is| do you have| have you got| do i have)?\\s+)?(?:scheduled|(?:my\\s+)?(?:schedule|reminders|timers|alarms|agenda))(?:\\s+for me)?$"
        + "|^(?:show|list|what are)\\s+(?:me\\s+)?(?:my\\s+|the\\s+)?(?:schedule|reminders|timers|alarms|scheduled\\s+\\w+)$", Pattern.CASE_INSENSITIVE );
    private static final Pattern CANCEL = Pattern.compile(
        "^(?:cancel|clear|delete|remove|forget|stop)\\s+(all\\s+)?(?:my\\s+|the\\s+|that\\s+|those\\s+)?(all\\s+)?(reminders?|timers?|alarms?|schedule|scheduled\\s*\\w*|that|it|everything|last one)(?:\\s+(?:for|about|to)\\s+(.+))?$", Pattern.CASE_INSENSITIVE );

    /** Schedule / calendar / agenda / cancel commands; null when the text is none of those. */
    static List<String[]> parseSchedule( final String text, final long now, final java.time.ZoneId zone )
    {
        final String o = FILLER.matcher( text.trim().replaceAll( "[.!?,]+$", "" ) ).replaceAll( "" ).replaceAll( "(?i)\\s+please$", "" ).replaceAll( "\\s+", " " ).trim();
        Matcher m;
        if( AGENDA.matcher( o ).matches() ) return java.util.Collections.singletonList( new String[] { "agenda" } );
        if( ( m = CANCEL.matcher( o ) ).matches() )
        {
            final String what = m.group( 3 ).toLowerCase( Locale.ROOT ).trim(), about = m.group( 4 );
            final String arg = about != null ? about.trim()
                : m.group( 1 ) != null || m.group( 2 ) != null || what.equals( "everything" ) || what.endsWith( "s" ) || what.startsWith( "schedule" ) ? "all"
                : "last";
            return java.util.Collections.singletonList( new String[] { "unschedule", arg } );
        }
        final TimeParse.Result tr = TimeParse.parse( o, now, zone );
        if( tr == null ) return null;
        final String r = tr.rest.replaceAll( "[,;:]+$", "" ).trim();
        final String at = Long.toString( tr.atMs );
        if( ( m = S_SOUND.matcher( r ) ).matches() || ( m = S_ALARM.matcher( r ) ).matches() )
            return one( "schedule", at, "sound", m.group( 1 ) == null ? "" : m.group( 1 ).trim(), "" );
        if( S_WAKE.matcher( r ).matches() ) return one( "schedule", at, "sound", "Wake up!", "" );
        if( ( m = S_REMIND.matcher( r ) ).matches() ) return one( "schedule", at, "remind", m.group( 1 ).trim(), "" );
        if( ( m = S_CAL2.matcher( r ) ).matches() ) return one( "calendar", at, m.group( 1 ).trim() );
        if( ( m = S_CAL1.matcher( r ) ).matches() ) return one( "calendar", at, m.group( 1 ).trim() );
        if( ( m = S_TYPE_A.matcher( r ) ).matches() ) return one( "schedule", at, "type", clean( m.group( 1 ) ), m.group( 2 ).trim() );
        if( ( m = S_TYPE_B.matcher( r ) ).matches() ) return one( "schedule", at, "type", clean( m.group( 2 ) ), m.group( 1 ).trim().replaceAll( "^[\"']|[\"']$", "" ) );
        if( ( m = S_OPEN.matcher( r ) ).matches() ) return one( "schedule", at, "open", m.group( 1 ).trim(), "" );
        if( ( m = S_CAL3.matcher( r ) ).matches() ) return one( "calendar", at, m.group( 1 ).trim() );
        return null;
    }


    // system controls: volume and media keys only; anything destructive is refused in the mascot's own words
    private static final String VOL = "(?:the\\s+|my\\s+)?(?:volume|sound|audio|music|speakers?|playback)";
    private static final Pattern VOL_DOWN = Pattern.compile( "^(?:(?:turn|bring|put|lower|drop|reduce|decrease|take)\\s+(?:down\\s+)?" + VOL + "(?:\\s+(?:down|lower|a (?:bit|little|lot|notch)|a bit lower))?|(?:turn|bring)\\s+(?:it|that)\\s+(?:down|lower)|make\\s+(?:it|that|things?)\\s+(?:quieter|softer|lower)|quieter|too loud|it'?s too loud|(?:turn|bring) down|volume down)(.*)$" );
    private static final Pattern VOL_UP = Pattern.compile( "^(?:(?:turn|bring|put|raise|pump|crank|increase|bump|take)\\s+(?:up\\s+)?" + VOL + "(?:\\s+(?:up|higher|a (?:bit|little|lot|notch)|a bit louder))?|(?:turn|bring|put|crank|bump)\\s+(?:it|that)\\s+(?:up|higher)|make\\s+(?:it|that|things?)\\s+(?:louder|higher)|louder|too quiet|it'?s too quiet|(?:turn|bring) up|volume up)(.*)$" );
    private static final Pattern VOL_SET = Pattern.compile( "^(?:set|put|turn|change|make)\\s+" + VOL + "\\s+(?:to|at)\\s+(\\d{1,3})\\s*(?:%|percent)?$|^" + VOL + "\\s+(?:to|at)\\s+(\\d{1,3})\\s*(?:%|percent)?$" );
    private static final Pattern MUTE = Pattern.compile( "^(?:mute|unmute|toggle mute|silence)(?:\\s+" + VOL + "|\\s+(?:it|this|everything|my computer|the computer|my pc))?$|^(?:turn off|shut off)\\s+" + VOL + "$|^(?:turn|bring)\\s+" + VOL + "\\s+off$" );
    private static final Pattern MEDIA = Pattern.compile( "^(?:(pause|stop|resume|unpause|play|continue)(?:\\s+(?:the|my|this|that))?\\s+(?:music|song|songs|video|media|track|playback|audio|podcast|youtube|spotify)|(?:skip|next)(?:\\s+(?:to|the))?(?:\\s+(?:this|that|the next|next))?\\s*(?:song|track|video)?|(?:go\\s+)?(?:back|previous|last)(?:\\s+(?:to the|the))?(?:\\s+(?:previous|last))?\\s*(?:song|track)|(pause|resume)(?:\\s+it)?|(play|pause)\\s+(?:something)?)$" );
    private static final String THING = ".*\\b(?:files?|folders?|computer|pc|laptop|system|drive|disk|process(?:es)?|programs?|apps?|applications?|windows?|tabs?|browser|chrome|firefox|edge|discord|steam|everything|downloads?|documents?|recycle|bin|registry|password|firewall|antivirus|defender)\\b.*";
    private static final Pattern REFUSE = Pattern.compile(
        "^(?:shut\\s*down|power\\s*off|power down|reboot|hibernate|log\\s*off|sign\\s*out|log me out)\\b.*$"
        + "|^(?:restart|sleep|turn off|switch off|put)\\b" + THING + "$"
        + "|^(?:format|wipe|erase|uninstall|install|kill|terminate|force quit|force close|close|quit|exit|delete|remove|trash|destroy|empty|change|reset|disable)\\b" + THING + "$" );


    /** Volume / media keys, or a refusal for destructive system requests; null when the text is neither. */
    static List<String[]> parseSystem( final String t )
    {
        Matcher m;
        if( ( m = VOL_SET.matcher( t ) ).matches() )
        {
            final String n = m.group( 1 ) != null ? m.group( 1 ) : m.group( 2 );
            return one( "sys", "volume_set", Integer.toString( Math.min( 100, Integer.parseInt( n ) ) ) );
        }
        if( MUTE.matcher( t ).matches() ) return one( "sys", "mute" );
        boolean down = ( m = VOL_DOWN.matcher( t ) ).matches();
        boolean up = !down && ( m = VOL_UP.matcher( t ) ).matches();
        if( down || up )
        {
            final String tail = m.group( m.groupCount() ).toLowerCase( Locale.ROOT );
            final String amount = tail.contains( "lot" ) || tail.contains( "way" ) || t.contains( "crank" ) ? "15"
                : tail.contains( "bit" ) || tail.contains( "little" ) || tail.contains( "notch" ) || t.contains( "a bit" ) || t.contains( "a little" ) || t.contains( "notch" ) ? "3" : "5";
            return one( "sys", down ? "volume_down" : "volume_up", amount );
        }
        if( ( m = MEDIA.matcher( t ) ).matches() )
        {
            if( t.contains( "skip" ) || t.startsWith( "next" ) ) return one( "sys", "next" );
            if( t.contains( "back" ) || t.contains( "previous" ) || t.contains( "last" ) ) return one( "sys", "previous" );
            return one( "sys", "playpause" );
        }
        if( REFUSE.matcher( t ).matches() && !t.matches( "^(?:put|turn off|switch off)\\b.*(?:window|sound|volume|music|audio).*" ) && !t.startsWith( "put " ) ) return one( "refuse" );
        if( t.matches( "^put\\s+(?:the\\s+|my\\s+)?(?:computer|pc|laptop|machine)\\s+(?:to sleep|in sleep mode|into sleep).*$" ) ) return one( "refuse" );
        return null;
    }

    private static List<String[]> one( final String... tag ) { return java.util.Collections.singletonList( tag ); }

    /** Tags implied by what the user said, or an empty list. */
    public static List<String[]> parse( final String userText )
    {
        return parse( userText, System.currentTimeMillis(), java.time.ZoneId.systemDefault() );
    }

    public static List<String[]> parse( final String userText, final long nowMs, final java.time.ZoneId zone )
    {
        final List<String[]> out = new ArrayList<>();
        if( userText == null ) return out;
        final List<String[]> sched = parseSchedule( userText, nowMs, zone );
        if( sched != null ) return sched;
        String t = userText.toLowerCase( Locale.ROOT ).trim();
        t = t.replaceAll( "[.!?,]+$", "" ).replaceAll( "^(?:(?:hey|ok|okay|please|can you|could you|would you|will you|go ahead and|just)[ ,]+)+", "" )
             .replaceAll( "\\s+please$", "" ).replaceAll( "\\s+", " " ).trim();
        Matcher m;
        {
            final List<String[]> sys = parseSystem( t );
            if( sys != null ) return sys;
        }
        if( ( m = UNDO.matcher( t ) ).matches() ) { out.add( new String[] { "undo" } ); return out; }
        if( ( m = ORGANIZE.matcher( t ) ).matches() ) { out.add( new String[] { "organize", m.group( 1 ).trim() } ); return out; }
        if( ( m = MONITOR_A.matcher( t ) ).matches() ) { out.add( new String[] { "monitor", m.group( 2 ), clean( m.group( 1 ) ) } ); return out; }
        if( ( m = MONITOR_B.matcher( t ) ).matches() ) { out.add( new String[] { "monitor", m.group( 2 ), clean( m.group( 1 ) ) } ); return out; }
        if( ( m = MOVE_FILE.matcher( t ) ).matches() && isFileMove( m.group( 1 ), m.group( 2 ) ) )
        {
            out.add( new String[] { "move", m.group( 1 ).replaceAll( "^file\\s+", "" ).replaceAll( "\\s+file$", "" ).trim(), m.group( 2 ).replaceAll( "\\s+folder$", "" ).trim() } );
            return out;
        }
        if( ( m = SNAP_A.matcher( t ) ).matches() ) { out.add( new String[] { "window", m.group( 2 ), clean( m.group( 1 ) ) } ); return out; }
        if( ( m = SNAP_B.matcher( t ) ).matches() ) { out.add( new String[] { "window", m.group( 1 ), clean( m.group( 2 ) ) } ); return out; }
        if( ( m = CENTER.matcher( t ) ).matches() ) { out.add( new String[] { "window", "center", clean( m.group( 1 ) ) } ); return out; }
        if( ( m = SIMPLE.matcher( t ) ).matches() )
        {
            final String verb = m.group( 1 );
            final String op = verb.startsWith( "minimi" ) || verb.equals( "hide" ) ? "minimize"
                : verb.startsWith( "maximi" ) || verb.equals( "fullscreen" ) ? "maximize"
                : verb.equals( "restore" ) || verb.startsWith( "unminimi" ) ? "restore" : "focus";
            final String target = clean( m.group( 2 ) );
            if( !m.group( 2 ).trim().isEmpty() && !target.startsWith( "on " ) && !target.startsWith( "me " ) ) out.add( new String[] { "window", op, target } );
        }
        return out;
    }

    /** A file move names a file (has an extension, or says "file") and a destination that isn't a snap/monitor word. */
    private static boolean isFileMove( final String src, final String dest )
    {
        if( dest.matches( "(?:the\\s+)?(?:left|right|top|bottom|center|centre)(?:\\s+.*)?" ) ) return false;
        if( dest.matches( ".*\\b(?:monitor|screen|display)\\b.*" ) ) return false;
        return HAS_EXT.matcher( src.trim() ).find() || src.matches( "(?:file\\s+.+|.+\\s+file|this|it|that)" ) && src.contains( "file" );
    }

    /** Drop filler so "my browser window" becomes "browser". */
    static String clean( final String s )
    {
        // "this window" / "it" / "the current one" all mean the window the user was on: an empty name
        if( s.trim().matches( "(?:the\\s+|my\\s+)?(?:this|that|current|active|front|foreground|it)(?:\\s+(?:window|one|app|application|program|thing))?" ) ) return "";
        return s.replaceAll( "\\b(?:the|my|this|that|window|app|application|program)\\b", " " ).replaceAll( "\\s+", " " ).trim();
    }
}
