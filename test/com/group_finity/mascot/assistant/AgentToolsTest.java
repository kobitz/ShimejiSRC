package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class AgentToolsTest
{
    static int fails = 0;
    static void eq( final String n, final Object want, final Object got )
    {
        if( !java.util.Objects.equals( want, got ) ) { fails++; System.out.println( "FAIL " + n + ": want " + want + " got " + got ); }
    }

    static class FakeWin implements WindowControl
    {
        final List<Info> wins = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        /** Current geometry per handle (all test windows share handle 1 unless set). */
        Rectangle rect = new Rectangle( 100, 100, 400, 300 );
        boolean gone = false, maximized = false, minimized = false;
        List<Rectangle> mons = List.of();
        public List<Rectangle> monitors() { return mons; }
        public boolean place( final Info w, final Rectangle r ) { rect = new Rectangle( r ); applied.add( "PLACE" ); return true; }
        public List<Info> list() { return wins; }
        public boolean apply( final Info w, final Op op )
        {
            applied.add( op + ":" + w.title );
            if( op == Op.RESTORE ) { maximized = false; minimized = false; }
            if( op == Op.MAXIMIZE ) maximized = true;
            return true;
        }
        public Info current( final long handle ) { return gone ? null : new Info( handle, "t", new Rectangle( rect ), minimized, maximized ); }
        public boolean moveTo( final Info w, final int x, final int y ) { if( gone ) return false; rect.setLocation( x, y ); applied.add( "MOVE" ); return true; }
    }

    static WindowControl.Info w( final String t ) { return new WindowControl.Info( 1, t, new Rectangle( 0, 0, 100, 100 ), false ); }

    public static void main( final String[] a ) throws Exception
    {
        // parsing
        final List<String[]> tags = AgentTools.parse( "Sure. [DO:window|left|Google Chrome] [do:UNDO] [ACTION:Dance] [DO:organize|Downloads]" );
        eq( "tag count", 3, tags.size() );
        eq( "tool lower", "window", tags.get( 0 )[ 0 ] );
        eq( "arg", "Google Chrome", tags.get( 0 )[ 2 ] );
        eq( "undo no args", 1, tags.get( 1 ).length );
        eq( "cap per reply", 3, AgentTools.parse( "[DO:undo][DO:undo][DO:undo][DO:undo][DO:undo]" ).size() );
        eq( "null", 0, AgentTools.parse( null ).size() );

        // matching: never guess between equals
        final List<WindowControl.Info> all = List.of( w( "Google Chrome" ), w( "Discord" ), w( "Notes - Chrome Dev" ), w( "Untitled - Notepad" ) );
        eq( "two contains is ambiguous", null, AgentTools.matchWindow( all, "chrome" ).match );
        eq( "starts-with", "Google Chrome", AgentTools.matchWindow( all, "google" ).match.title );
        final List<WindowControl.Info> sw = List.of( w( "Chrome" ), w( "Docs - Chrome" ) );
        eq( "starts-with beats contains", "Chrome", AgentTools.matchWindow( sw, "chrome" ).match.title );
        eq( "exact", "Discord", AgentTools.matchWindow( all, "discord" ).match.title );
        final List<WindowControl.Info> two = List.of( w( "Docs - Chrome" ), w( "Mail - Chrome" ) );
        eq( "tie is ambiguous", null, AgentTools.matchWindow( two, "chrome" ).match );
        eq( "tie lists both", 2, AgentTools.matchWindow( two, "chrome" ).ambiguous.size() );
        eq( "none", 0, AgentTools.matchWindow( all, "zzz" ).ambiguous.size() );
        eq( "empty query", null, AgentTools.matchWindow( all, "" ).match );

        // ops + layout
        eq( "op focus", WindowControl.Op.FOCUS, AgentTools.opOf( "Focus" ) );
        eq( "op bad", null, AgentTools.opOf( "close" ) );
        final Rectangle wa = new Rectangle( 0, 0, 1000, 800 );
        eq( "left", new Rectangle( 0, 0, 500, 800 ), AgentLayout.target( wa, WindowControl.Op.LEFT ) );
        eq( "right", new Rectangle( 500, 0, 500, 800 ), AgentLayout.target( wa, WindowControl.Op.RIGHT ) );
        eq( "center", new Rectangle( 150, 120, 700, 560 ), AgentLayout.target( wa, WindowControl.Op.CENTER ) );

        // policy
        final Path root = Files.createTempDirectory( "agent" );
        final Path home = root.resolve( "home" );
        final Path dl = home.resolve( "Downloads" );
        Files.createDirectories( dl );
        final Properties p = new Properties();
        eq( "off by default", false, AgentPolicy.enabled( p ) );
        p.setProperty( "AgentEnabled", "true" );
        eq( "on", true, AgentPolicy.enabled( p ) );
        eq( "default folder", List.of( dl ), AgentPolicy.allowedFolders( p, home ) );
        p.setProperty( "AgentFolders", home.toString() + ";" + root.getRoot() );
        eq( "home and drive root refused", 0, AgentPolicy.allowedFolders( p, home ).size() );
        p.setProperty( "AgentFolders", "Downloads" );
        eq( "resolve by name", dl, AgentPolicy.resolveFolder( "downloads", AgentPolicy.allowedFolders( p, home ) ) );
        eq( "other folder refused", null, AgentPolicy.resolveFolder( "Documents", AgentPolicy.allowedFolders( p, home ) ) );

        // organizer
        final long old = System.currentTimeMillis() - 3_600_000L;
        for( final String n : new String[] { "a.png", "b.PDF", "c.zip", "weird.xyz", "dl.crdownload", ".hidden.png", "fresh.png" } )
        {
            final Path f = dl.resolve( n );
            Files.write( f, new byte[] { 1 } );
            Files.setLastModifiedTime( f, java.nio.file.attribute.FileTime.fromMillis( n.equals( "fresh.png" ) ? System.currentTimeMillis() : old ) );
        }
        Files.createDirectories( dl.resolve( "Images" ) );
        Files.write( dl.resolve( "Images" ).resolve( "taken.png" ), new byte[] { 1 } );
        Files.write( dl.resolve( "taken.png" ), new byte[] { 2 } );
        Files.setLastModifiedTime( dl.resolve( "taken.png" ), java.nio.file.attribute.FileTime.fromMillis( old ) );
        final List<FileOrganizer.Move> plan = FileOrganizer.plan( dl, System.currentTimeMillis() );
        eq( "plan size (png, pdf, zip only)", 3, plan.size() );
        eq( "summary", true, FileOrganizer.summary( plan ).startsWith( "3 files" ) );
        eq( "category", "Documents", FileOrganizer.categoryOf( "x.PDF" ) );
        eq( "partial download ignored", null, FileOrganizer.categoryOf( "x.crdownload" ) );

        // tool flow with confirmation
        final FakeWin fw = new FakeWin();
        fw.wins.add( w( "Google Chrome" ) );
        final List<String> said = new ArrayList<>();
        final boolean[] yes = { false };
        final Path journal = root.resolve( "journal.tsv" );
        final AgentTools t = new AgentTools( p, fw, ( ti, m ) -> yes[ 0 ], said::add, journal, home );

        t.handleNow( "ok [DO:window|left|chrome]" );
        eq( "window applied", List.of( "LEFT:Google Chrome" ), fw.applied );
        t.handleNow( "[DO:organize|Downloads]" );
        eq( "declined: nothing moved", true, Files.exists( dl.resolve( "a.png" ) ) );
        yes[ 0 ] = true;
        t.handleNow( "[DO:organize|Downloads]" );
        eq( "moved png", true, Files.exists( dl.resolve( "Images" ).resolve( "a.png" ) ) );
        eq( "moved pdf", true, Files.exists( dl.resolve( "Documents" ).resolve( "b.PDF" ) ) );
        eq( "clash left alone", true, Files.exists( dl.resolve( "taken.png" ) ) );
        eq( "unknown stays", true, Files.exists( dl.resolve( "weird.xyz" ) ) );
        eq( "fresh stays", true, Files.exists( dl.resolve( "fresh.png" ) ) );
        t.handleNow( "[DO:undo]" );
        eq( "undo restores", true, Files.exists( dl.resolve( "a.png" ) ) && Files.exists( dl.resolve( "b.PDF" ) ) );
        eq( "undo removes empty category dir", false, Files.exists( dl.resolve( "Documents" ) ) );
        eq( "pre-existing Images dir kept", true, Files.exists( dl.resolve( "Images" ).resolve( "taken.png" ) ) );
        eq( "second undo", -1, FileOrganizer.undoLast( journal ) );

        // refusals
        said.clear();
        t.handleNow( "[DO:organize|Documents]" );
        eq( "disallowed folder says so", true, said.size() == 1 && said.get( 0 ).contains( "Downloads" ) );
        t.handleNow( "[DO:organize|../..]" );
        eq( "traversal refused", true, said.size() == 2 );

        // kill switch + disabled
        fw.applied.clear();
        AgentPolicy.halt();
        t.handleNow( "[DO:window|left|chrome]" );
        eq( "halted does nothing", 0, fw.applied.size() );
        eq( "halted prompt denies abilities", true, t.promptBlock().contains( "cannot move" ) );
        AgentPolicy.resume();
        eq( "prompt lists windows", true, t.promptBlock().contains( "Google Chrome" ) );
        final Properties off = new Properties();
        eq( "disabled prompt denies abilities", true, new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).promptBlock().contains( "cannot move" ) );
        said.clear();
        new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).handleUser( "minimize my browser", "[DO:window|minimize|chrome]" );
        eq( "disabled tells user how to enable", true, said.size() == 1 && said.get( 0 ).contains( "AgentEnabled=true" ) );
        said.clear();
        new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).handleUser( "what is the weather", "" );
        eq( "disabled + chit-chat is silent", 0, said.size() );
        for( final String w : new String[] { "minimize this window", "maximize this window", "restore this window", "minimize it", "hide that window", "minimize the current window" } )
        {
            final List<String[]> r = AgentIntent.parse( w );
            eq( "deictic window command: " + w, true, r.size() == 1 && r.get( 0 )[ 0 ].equals( "window" ) && r.get( 0 )[ 2 ].isEmpty() );
        }
        said.clear();
        eq( "command words are claimed (no chat reply)", true, new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).handleCommand( "minimize my browser" ) );
        eq( "chit-chat is not claimed", false, new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).handleCommand( "what is the weather" ) );

        // intent from the user's own words
        eq( "minimize browser", "window|minimize|browser", String.join( "|", AgentIntent.parse( "Can you minimize my browser?" ).get( 0 ) ) );
        eq( "snap left", "window|left|notepad", String.join( "|", AgentIntent.parse( "snap notepad to the left" ).get( 0 ) ) );
        eq( "put on right half", "window|right|discord", String.join( "|", AgentIntent.parse( "please put the discord window on the right half of the screen" ).get( 0 ) ) );
        eq( "center", "window|center|chrome", String.join( "|", AgentIntent.parse( "center chrome" ).get( 0 ) ) );
        eq( "switch to", "window|focus|spotify", String.join( "|", AgentIntent.parse( "switch to spotify" ).get( 0 ) ) );
        eq( "clean up downloads", "organize|downloads", String.join( "|", AgentIntent.parse( "clean up my downloads" ).get( 0 ) ) );
        eq( "tidy folder", "organize|downloads", String.join( "|", AgentIntent.parse( "tidy the downloads folder" ).get( 0 ) ) );
        eq( "undo", "undo", String.join( "|", AgentIntent.parse( "undo that" ).get( 0 ) ) );
        eq( "chit-chat is not a command", 0, AgentIntent.parse( "how are you today" ).size() );
        eq( "question is not a command", 0, AgentIntent.parse( "what does minimize mean" ).size() );
        eq( "sentence is not a command", 0, AgentIntent.parse( "I like to show my friends the cat" ).size() );

        eq( "show me is chit-chat", 0, AgentIntent.parse( "show me the time" ).size() );
        eq( "focus on is chit-chat", 0, AgentIntent.parse( "focus on my work" ).size() );

        // carry / drop are no longer voice tools: windows only move as visible errands
        eq( "take is chit-chat", 0, AgentIntent.parse( "take a look at this" ).size() );
        eq( "hold is chit-chat", 0, AgentIntent.parse( "hold on a second" ).size() );

        eq( "carry is not an instant tool", 0, AgentIntent.parse( "carry my browser around" ).size() );
        eq( "drop it is chit-chat", 0, AgentIntent.parse( "ok drop it" ).size() );

        // generic folder word tidies the single allowed folder
        said.clear();
        yes[ 0 ] = false;
        t.handleNow( "[DO:organize|drive]" );
        eq( "generic word reaches the Downloads preview", true, said.size() == 1 && said.get( 0 ).contains( "left it alone" ) );

        // aliases
        final List<WindowControl.Info> br = List.of( w( "Inbox - Mozilla Firefox" ), w( "Discord" ) );
        eq( "browser alias", "Inbox - Mozilla Firefox", AgentTools.matchWindow( br, "browser" ).match.title );
        eq( "librewolf is a browser", "Home - LibreWolf", AgentTools.matchWindow( List.of( w( "Home - LibreWolf" ), w( "Discord" ) ), "browser" ).match.title );
        eq( "two browsers ambiguous", null, AgentTools.matchWindow( List.of( w( "A - Google Chrome" ), w( "B - Mozilla Firefox" ) ), "browser" ).match );

        // ---- monitors: geometry ----
        final List<Rectangle> mons2 = List.of( new Rectangle( 0, 0, 1920, 1040 ), new Rectangle( 1920, 0, 2560, 1400 ) );
        final Rectangle onA = new Rectangle( 100, 100, 800, 600 );
        eq( "other from first", 1, AgentLayout.pickMonitor( mons2, onA, "other" ) );
        eq( "other wraps", 0, AgentLayout.pickMonitor( mons2, new Rectangle( 2000, 100, 800, 600 ), "other" ) );
        eq( "right of first", 1, AgentLayout.pickMonitor( mons2, onA, "right" ) );
        eq( "left of first none", -1, AgentLayout.pickMonitor( mons2, onA, "left" ) );
        eq( "third none", -1, AgentLayout.pickMonitor( mons2, onA, "third" ) );
        eq( "primary", 0, AgentLayout.pickMonitor( mons2, new Rectangle( 2000, 100, 800, 600 ), "primary" ) );
        eq( "one monitor has no other", -1, AgentLayout.pickMonitor( List.of( mons2.get( 0 ) ), onA, "other" ) );
        final Rectangle moved = AgentLayout.toMonitor( onA, mons2.get( 0 ), mons2.get( 1 ) );
        eq( "moved inside target", true, mons2.get( 1 ).contains( moved ) );
        eq( "size kept", 800, moved.width );
        final Rectangle big = AgentLayout.toMonitor( new Rectangle( 0, 0, 1920, 1040 ), mons2.get( 0 ), new Rectangle( 0, 0, 1280, 720 ) );
        eq( "shrunk to fit", new Rectangle( 0, 0, 1280, 720 ), big );
        eq( "monitorIndex by overlap", 1, AgentLayout.monitorIndex( mons2, new Rectangle( 1800, 100, 400, 300 ) ) );
        eq( "monitorIndex straddle picks more area", 1, AgentLayout.monitorIndex( mons2, new Rectangle( 1800, 100, 400, 300 ) ) );

        // ---- monitors: intents ----
        eq( "intent other monitor", "monitor|other|", String.join( "|", AgentIntent.parse( "move this to my other monitor" ).get( 0 ) ) );
        eq( "intent named", "monitor|second|firefox", String.join( "|", AgentIntent.parse( "Hey, send my firefox window to the second screen" ).get( 0 ) ) );
        eq( "intent monitor 2", "monitor|2|chrome", String.join( "|", AgentIntent.parse( "move chrome to monitor 2" ).get( 0 ) ) );
        eq( "snap still snaps", "window", AgentIntent.parse( "move chrome to the left" ).get( 0 )[ 0 ] );

        // ---- monitors: tool flow ----
        final FakeWin mw = new FakeWin();
        mw.wins.add( w( "Google Chrome" ) );
        mw.mons = mons2;
        mw.rect = new Rectangle( 100, 100, 800, 600 );
        final List<String> msaid = new ArrayList<>();
        final AgentTools mt = new AgentTools( p, mw, ( x, y ) -> true, msaid::add, journal, home );
        mt.handleNow( "[DO:monitor|other|chrome]" );
        eq( "window now on monitor 2", true, mons2.get( 1 ).contains( mw.rect ) );
        eq( "monitor move reported", "There you go.", msaid.get( 0 ) );
        mt.handleNow( "[DO:monitor|right|chrome]" );
        eq( "no screen to the right", true, msaid.get( 1 ).contains( "no screen" ) );
        mw.mons = List.of( mons2.get( 0 ) );
        mt.handleNow( "[DO:monitor|other|chrome]" );
        eq( "single screen message", "I only see one screen.", msaid.get( 2 ) );
        mw.mons = mons2;
        mw.maximized = true;
        mw.rect = new Rectangle( 0, 0, 1920, 1040 );
        mt.handleNow( "[DO:monitor|other|chrome]" );
        eq( "maximized window restored then re-maximized", true, mw.applied.contains( "RESTORE:t" ) && mw.applied.contains( "MAXIMIZE:t" ) );

        // ---- file mover ----
        final Path mh = Files.createTempDirectory( "mover" );
        Files.createDirectories( mh.resolve( "Downloads" ) );
        Files.createDirectories( mh.resolve( "Documents" ).resolve( "Taxes" ) );
        Files.write( mh.resolve( "Downloads" ).resolve( "Report Final.pdf" ), new byte[] { 1 } );
        Files.write( mh.resolve( "Downloads" ).resolve( "notes.txt" ), new byte[] { 1 } );
        Files.write( mh.resolve( "Downloads" ).resolve( "notes2.txt" ), new byte[] { 1 } );
        Files.write( mh.resolve( "Documents" ).resolve( "dup.txt" ), new byte[] { 1 } );
        Files.write( mh.resolve( "Downloads" ).resolve( "dup.txt" ), new byte[] { 1 } );
        final List<Path> mroots = FileMover.roots( p, mh );
        eq( "roots include Documents", true, mroots.contains( mh.resolve( "Documents" ) ) );
        eq( "found by base name", "Report Final.pdf", FileMover.plan( "report final", "documents", mroots ).move.from.getFileName().toString() );
        eq( "dest subfolder", mh.resolve( "Documents" ).resolve( "Taxes" ).resolve( "Report Final.pdf" ), FileMover.plan( "report final.pdf", "the taxes folder", mroots ).move.to );
        eq( "ambiguous file", true, FileMover.plan( "note", "documents", mroots ).error.startsWith( "Which one" ) );
        eq( "exact beats prefix", "notes.txt", FileMover.plan( "notes.txt", "documents", mroots ).move.from.getFileName().toString() );
        eq( "no overwrite", true, FileMover.plan( "dup.txt", "documents", mroots ).error != null );
        eq( "unknown folder", true, FileMover.plan( "notes.txt", "nowhere", mroots ).error.contains( "can't find a folder" ) );
        eq( "this file asks", true, FileMover.plan( "this", "documents", mroots ).error.startsWith( "Which file" ) );
        eq( "path traversal refused", true, FileMover.plan( "..\\x.txt", "documents", mroots ).error != null );
        eq( "intent move file", "move|report.pdf|documents", String.join( "|", AgentIntent.parse( "move report.pdf to my documents folder" ).get( 0 ) ) );
        eq( "intent says file", "move|budget|desktop", String.join( "|", AgentIntent.parse( "can you move the budget file to desktop" ).get( 0 ) ) );
        eq( "window move not a file move", "window", AgentIntent.parse( "move chrome to the right" ).get( 0 )[ 0 ] );
        eq( "no ext no file word: ignored", 0, AgentIntent.parse( "move chrome to documents" ).size() );

        final Properties mp = new Properties();
        mp.setProperty( "AgentEnabled", "true" );
        final List<String> fsaid = new ArrayList<>();
        final boolean[] fyes = { false };
        final String[] asked = { null };
        final AgentTools ft = new AgentTools( mp, null, ( ti, m ) -> { asked[ 0 ] = ti + "|" + m; return fyes[ 0 ]; }, fsaid::add, mh.resolve( "j.tsv" ), mh );
        ft.handleNow( "[DO:move|report final.pdf|documents]" );
        eq( "declined move leaves file", true, Files.exists( mh.resolve( "Downloads" ).resolve( "Report Final.pdf" ) ) );
        eq( "confirm names file", true, asked[ 0 ].contains( "Report Final.pdf" ) );
        fyes[ 0 ] = true;
        ft.handleNow( "[DO:move|report final.pdf|documents]" );
        eq( "confirmed move happened", true, Files.exists( mh.resolve( "Documents" ).resolve( "Report Final.pdf" ) ) );
        ft.handleNow( "[DO:undo]" );
        eq( "undo moves file back", true, Files.exists( mh.resolve( "Downloads" ).resolve( "Report Final.pdf" ) ) );

        // ---- visible move (errand) ----
        class FakeStage implements AgentErrand.Stage
        {
            final List<String> calls = new ArrayList<>();
            boolean sourceOk = true;
            Path heldSeen;
            Path fromDir;
            public void openFolders( final Path f, final Path t ) { calls.add( "open" ); fromDir = f; }
            public boolean walkToSource() { calls.add( "toSource" ); return sourceOk; }
            public void grab( final Path h ) { calls.add( "grab" ); heldSeen = h; }
            public boolean walkToDest() { calls.add( "toDest" ); return true; }
            public void drop() { calls.add( "drop" ); }
            public void finish() { calls.add( "finish" ); }
        }
        Files.write( mh.resolve( "Downloads" ).resolve( "visible.txt" ), new byte[] { 7 } );
        final FakeStage fs = new FakeStage();
        final AgentTools vt = new AgentTools( mp, null, ( ti, m ) -> true, fsaid::add, mh.resolve( "j2.tsv" ), mh ).withStage( () -> fs );
        vt.handleNow( "[DO:move|visible.txt|documents]" );
        eq( "errand order", List.of( "open", "toSource", "grab", "toDest", "drop", "finish" ), fs.calls );
        eq( "file was out of its folder while held", true, fs.heldSeen.toString().contains( AgentErrand.HOLD_DIR ) );
        eq( "visible move arrived", true, Files.exists( mh.resolve( "Documents" ).resolve( "visible.txt" ) ) );
        eq( "gone from source", false, Files.exists( mh.resolve( "Downloads" ).resolve( "visible.txt" ) ) );
        eq( "hold folder cleaned up", false, Files.exists( mh.resolve( "Downloads" ).resolve( AgentErrand.HOLD_DIR ) ) );
        eq( "holding record cleared", false, Files.exists( mh.resolve( "agent-holding.tsv" ) ) );
        vt.handleNow( "[DO:undo]" );
        eq( "undo after visible move", true, Files.exists( mh.resolve( "Downloads" ).resolve( "visible.txt" ) ) );

        // mascot can't reach the folder: falls back to an instant move, nothing lost
        final FakeStage fs2 = new FakeStage();
        fs2.sourceOk = false;
        final AgentTools vt2 = new AgentTools( mp, null, ( ti, m ) -> true, fsaid::add, mh.resolve( "j3.tsv" ), mh ).withStage( () -> fs2 );
        vt2.handleNow( "[DO:move|visible.txt|documents]" );
        eq( "fallback moved instantly", true, Files.exists( mh.resolve( "Documents" ).resolve( "visible.txt" ) ) );
        eq( "fallback still finishes stage", true, fs2.calls.contains( "finish" ) );
        Files.move( mh.resolve( "Documents" ).resolve( "visible.txt" ), mh.resolve( "Downloads" ).resolve( "visible.txt" ) );

        // destination appears while the file is in the mascot's hands: put back, never overwritten
        final FakeStage fs3 = new FakeStage()
        {
            @Override public void drop() { calls.add( "drop" ); try { Files.write( mh.resolve( "Documents" ).resolve( "visible.txt" ), new byte[] { 9 } ); } catch( final Exception e ) { } }
        };
        final AgentTools vt3 = new AgentTools( mp, null, ( ti, m ) -> true, fsaid::add, mh.resolve( "j4.tsv" ), mh ).withStage( () -> fs3 );
        vt3.handleNow( "[DO:move|visible.txt|documents]" );
        eq( "clash: original restored", true, Files.exists( mh.resolve( "Downloads" ).resolve( "visible.txt" ) ) );
        eq( "clash: existing file untouched", 9, (int) Files.readAllBytes( mh.resolve( "Documents" ).resolve( "visible.txt" ) )[ 0 ] );
        Files.delete( mh.resolve( "Documents" ).resolve( "visible.txt" ) );

        // crash recovery
        final Path hd = mh.resolve( "Downloads" ).resolve( AgentErrand.HOLD_DIR );
        Files.createDirectories( hd );
        Files.write( hd.resolve( "lost.txt" ), new byte[] { 1 } );
        Files.write( mh.resolve( "agent-holding.tsv" ), ( hd.resolve( "lost.txt" ) + "\t" + mh.resolve( "Downloads" ).resolve( "lost.txt" ) + "\n" ).getBytes( "UTF-8" ) );
        eq( "recover count", 1, AgentErrand.recover( mh.resolve( "agent-holding.tsv" ) ) );
        eq( "recovered file back", true, Files.exists( mh.resolve( "Downloads" ).resolve( "lost.txt" ) ) );
        // a tampered record pointing outside a hold folder is ignored
        Files.write( mh.resolve( "Downloads" ).resolve( "innocent.txt" ), new byte[] { 1 } );
        Files.write( mh.resolve( "agent-holding.tsv" ), ( mh.resolve( "Downloads" ).resolve( "innocent.txt" ) + "\t" + mh.resolve( "Documents" ).resolve( "x.txt" ) + "\n" ).getBytes( "UTF-8" ) );
        eq( "tampered record ignored", 0, AgentErrand.recover( mh.resolve( "agent-holding.tsv" ) ) );
        eq( "innocent file untouched", true, Files.exists( mh.resolve( "Downloads" ).resolve( "innocent.txt" ) ) );

        // finding the Explorer window for a folder
        final List<WindowControl.Info> ew = List.of( w( "Downloads - Mozilla Firefox" ), w( "Documents - File Explorer" ), w( "Documents" ) );
        eq( "explorer window found", "Documents - File Explorer", AgentErrandStage.findFolderWindow( ew, Paths.get( "C:/Users/ko/Documents" ), new java.util.HashSet<>(), false ).title );
        eq( "browser tab is not a folder", null, AgentErrandStage.findFolderWindow( List.of( w( "Downloads - Mozilla Firefox" ) ), Paths.get( "C:/Users/ko/Downloads" ), new java.util.HashSet<>(), false ) );

        // ---- confirm bubble fade ----
        eq( "solid early", 1f, AgentConfirmBubble.opacityFor( 20000 ) );
        eq( "half at 2s", 0.5f, AgentConfirmBubble.opacityFor( 2000 ) );
        eq( "never fully invisible", 0.15f, AgentConfirmBubble.opacityFor( 10 ) );
        eq( "headless asks deny", false, AgentConfirmBubble.ask( "t", "m", () -> new Rectangle( 0, 0, 10, 10 ), 100 ) );


        // ---- scheduling ----
        final java.time.ZoneId zid = java.time.ZoneId.systemDefault();
        final long now0 = java.time.ZonedDateTime.of( 2026, 11, 16, 18, 0, 0, 0, zid ).toInstant().toEpochMilli();
        List<String[]> si = AgentIntent.parse( "play a sound in 20 minutes", now0, zid );
        eq( "sound tool", "schedule", si.get( 0 )[ 0 ] );
        eq( "sound at", Long.toString( now0 + 20 * 60_000L ), si.get( 0 )[ 1 ] );
        eq( "sound kind", "sound", si.get( 0 )[ 2 ] );
        si = AgentIntent.parse( "can you ask claude to continue at 8:40pm", now0, zid );
        eq( "type kind", "type", si.get( 0 )[ 2 ] );
        eq( "type target", "claude", si.get( 0 )[ 3 ] );
        eq( "type text", "continue", si.get( 0 )[ 4 ] );
        eq( "type time", java.time.ZonedDateTime.of( 2026, 11, 16, 20, 40, 0, 0, zid ).toInstant().toEpochMilli() + "", si.get( 0 )[ 1 ] );
        si = AgentIntent.parse( "remind me to stretch in half an hour", now0, zid );
        eq( "remind", "remind", si.get( 0 )[ 2 ] );
        eq( "remind text", "stretch", si.get( 0 )[ 3 ] );
        si = AgentIntent.parse( "open youtube.com tomorrow at 9am", now0, zid );
        eq( "open kind", "open", si.get( 0 )[ 2 ] );
        eq( "open arg", "youtube.com", si.get( 0 )[ 3 ] );
        si = AgentIntent.parse( "add a meeting with Sam on Friday at 3pm to my calendar", now0, zid );
        eq( "calendar tool", "calendar", si.get( 0 )[ 0 ] );
        eq( "agenda", "agenda", AgentIntent.parse( "what's scheduled", now0, zid ).get( 0 )[ 0 ] );
        eq( "cancel all", "all", AgentIntent.parse( "cancel all my reminders", now0, zid ).get( 0 )[ 1 ] );
        eq( "cancel last", "last", AgentIntent.parse( "cancel that", now0, zid ).get( 0 )[ 1 ] );
        eq( "plain chat unaffected", 0, AgentIntent.parse( "I'll see you at 5 haha", now0, zid ).size() );

        final Path sh = Files.createTempDirectory( "sched" );
        final AgentScheduler sch = new AgentScheduler( sh.resolve( "s.tsv" ) );
        final AgentScheduler.Task t1 = sch.add( 1000, "sound", "ding", "" );
        sch.add( 2000, "remind", "tea", "" );
        sch.add( 9_000_000, "remind", "later", "" );
        eq( "persisted", 3, new AgentScheduler( sh.resolve( "s.tsv" ) ).list().size() );
        final List<String> fired = new ArrayList<>();
        eq( "fireDue count", 2, sch.fireDue( 3000, ( x, late ) -> fired.add( x.a + ( late ? "!" : "" ) ) ) );
        eq( "fired order", List.of( "ding", "tea" ), fired );
        eq( "not refired", 0, sch.fireDue( 3000, ( x, late ) -> fired.add( "again" ) ) );
        eq( "late flag", true, sch.fireDue( 9_000_000 + AgentScheduler.LATE_MS + 1, ( x, late ) -> fired.add( late ? "LATE" : "ok" ) ) == 1 && fired.contains( "LATE" ) );
        sch.add( 5, "remind", "buy milk", "" ); sch.add( 6, "remind", "call mom", "" );
        eq( "cancel by text", 1, sch.cancel( "milk" ) );
        eq( "cancel all", 1, sch.cancel( "all" ) );

        // tool flow with a fake clock + captured reporter
        final Properties sp = new Properties();
        sp.setProperty( "AgentEnabled", "true" );
        final List<String> ssaid = new ArrayList<>();
        final List<String> cds = new ArrayList<>();
        final AgentScheduler sch2 = new AgentScheduler( sh.resolve( "s2.tsv" ) );
        final FakeWin tw = new FakeWin() { public String foregroundTitle() { return "Claude"; } };
        tw.wins.add( w( "Claude" ) );
        final AgentTools st = new AgentTools( sp, tw, ( x, y ) -> true, ssaid::add, sh.resolve( "j.tsv" ), sh )
            .withScheduler( sch2, sh.resolve( "cal" ), ( end, label ) -> cds.add( label ) ).withClock( () -> now0 );
        st.run( new String[] { "schedule", Long.toString( now0 + 60_000 ), "sound", "tea is ready", "" } );
        eq( "task added", 1, sch2.list().size() );
        eq( "countdown shown", List.of( "tea is ready" ), cds );
        eq( "confirmation says when", true, ssaid.get( 0 ).contains( "tea is ready" ) );
        st.run( new String[] { "schedule", Long.toString( now0 - 5 ), "sound", "", "" } );
        eq( "past refused", true, ssaid.get( 1 ).contains( "passed" ) );
        st.run( new String[] { "schedule", Long.toString( now0 + 60_000 ), "type", "claude", "continue" } );
        eq( "typing off by default", 1, sch2.list().size() );
        eq( "typing off says how", true, ssaid.get( 2 ).contains( "AgentTypeEnabled" ) );
        st.run( new String[] { "agenda" } );
        eq( "agenda lists", true, ssaid.get( 3 ).startsWith( "Scheduled:" ) );
        st.run( new String[] { "unschedule", "all" } );
        eq( "unscheduled", 0, sch2.list().size() );
        // the model cannot schedule or type
        st.handleNow( "[DO:schedule|" + ( now0 + 1000 ) + "|type|claude|rm everything]" );
        eq( "model tag ignored", 0, sch2.list().size() );
        // firing: late type is skipped, typing-off type is skipped, nothing typed
        final int before = ssaid.size();
        st.fire( new AgentScheduler.Task( 99, now0, "type", "claude", "continue", now0 ), false );
        eq( "type off at fire", true, ssaid.get( before ).contains( "typing is off" ) );
        st.fire( new AgentScheduler.Task( 100, now0, "open", "file", "C:/nowhere/x.txt|x.txt", now0 ), true );
        eq( "late open skipped", true, ssaid.get( ssaid.size() - 1 ).contains( "missed" ) );
        // timer tag dropped when we schedule
        eq( "timer stripped", "ok ", st.withoutTimerIfScheduled( "remind me to stretch in 10 minutes", "ok [TIMER:10:stretch]" ) );
        eq( "timer kept otherwise", "ok [TIMER:10:x]", st.withoutTimerIfScheduled( "hello", "ok [TIMER:10:x]" ) );

        // open resolution
        final Path od = Files.createTempDirectory( "open" );
        Files.write( od.resolve( "report.pdf" ), new byte[] { 1 } );
        Files.write( od.resolve( "tool.exe" ), new byte[] { 1 } );
        final Path menu = Files.createDirectories( od.resolve( "menu" ) );
        Files.write( menu.resolve( "Spotify.lnk" ), new byte[] { 1 } );
        eq( "url", "url", AgentOpen.resolve( "youtube.com", List.of( od ), List.of( menu ) ).target.kind );
        eq( "file by name", "file", AgentOpen.resolve( "report.pdf", List.of( od ), List.of( menu ) ).target.kind );
        eq( "exe refused", null, AgentOpen.resolve( "tool.exe", List.of( od ), List.of( menu ) ).target );
        eq( "path refused", null, AgentOpen.resolve( "C:\\Windows\\system32\\cmd.exe", List.of( od ), List.of( menu ) ).target );
        eq( "app by start menu", "app", AgentOpen.resolve( "spotify", List.of( od ), List.of( menu ) ).target.kind );
        eq( "javascript url refused", null, AgentOpen.resolve( "javascript:alert(1)", List.of( od ), List.of( menu ) ).target );
        eq( "stillSafe file", true, AgentOpen.stillSafe( "file", od.resolve( "report.pdf" ).toString(), List.of( od ), List.of( menu ) ) );
        eq( "stillSafe rejects exe", false, AgentOpen.stillSafe( "file", od.resolve( "tool.exe" ).toString(), List.of( od ), List.of( menu ) ) );
        eq( "stillSafe rejects outside", false, AgentOpen.stillSafe( "file", "C:/Windows/notepad.exe", List.of( od ), List.of( menu ) ) );

        // calendar + sound + typer
        final String ics = AgentCalendar.ics( "Lunch; with, Sam", now0, 3600_000L, 10, now0 );
        eq( "ics begin", true, ics.startsWith( "BEGIN:VCALENDAR" ) );
        eq( "ics escaped", true, ics.contains( "SUMMARY:Lunch\\; with\\, Sam" ) );
        eq( "ics alarm", true, ics.contains( "BEGIN:VALARM" ) );
        eq( "chime has samples", true, AgentSound.chimeSamples( 3 ).length > 40000 );
        eq( "typer one line", "a b", AgentTyper.sanitize( "a\nb" ) );
        eq( "typer cap", AgentTyper.MAX_CHARS, AgentTyper.sanitize( "x".repeat( 900 ) ).length() );
        eq( "typer headless false", false, AgentTyper.typeLine( "hi" ) );


        // ---- system controls ----
        eq( "vol down", "volume_down", AgentIntent.parse( "can you turn down the volume" ).get( 0 )[ 1 ] );
        eq( "vol down amount", "5", AgentIntent.parse( "can you turn down the volume" ).get( 0 )[ 2 ] );
        eq( "vol down bit", "3", AgentIntent.parse( "turn the volume down a bit" ).get( 0 )[ 2 ] );
        eq( "quieter", "volume_down", AgentIntent.parse( "make it quieter" ).get( 0 )[ 1 ] );
        eq( "vol up", "volume_up", AgentIntent.parse( "turn up the volume" ).get( 0 )[ 1 ] );
        eq( "louder", "volume_up", AgentIntent.parse( "louder please" ).get( 0 )[ 1 ] );
        eq( "crank", "15", AgentIntent.parse( "crank the volume up" ).get( 0 )[ 2 ] );
        eq( "set vol", "30", AgentIntent.parse( "set the volume to 30%" ).get( 0 )[ 2 ] );
        eq( "set vol cap", "100", AgentIntent.parse( "volume to 400" ).get( 0 )[ 2 ] );
        eq( "mute", "mute", AgentIntent.parse( "mute" ).get( 0 )[ 1 ] );
        eq( "mute sound", "mute", AgentIntent.parse( "turn off the sound" ).get( 0 )[ 1 ] );
        eq( "pause music", "playpause", AgentIntent.parse( "pause the music" ).get( 0 )[ 1 ] );
        eq( "skip song", "next", AgentIntent.parse( "skip this song" ).get( 0 )[ 1 ] );
        eq( "previous", "previous", AgentIntent.parse( "go back to the previous song" ).get( 0 )[ 1 ] );
        for( final String bad : new String[] { "shut down my computer", "restart the pc", "close chrome", "delete all my files", "uninstall steam", "put the computer to sleep", "turn off my laptop", "log off", "kill the process" } )
            eq( "refused: " + bad, "refuse", AgentIntent.parse( bad ).isEmpty() ? "NONE" : AgentIntent.parse( bad ).get( 0 )[ 0 ] );
        for( final String ok : new String[] { "close enough", "I'm going to quit smoking", "kill time with me", "what's the volume of a sphere", "the music is great", "how are you" } )
            eq( "not an action: " + ok, 0, AgentIntent.parse( ok ).size() );
        final List<String> keys = new ArrayList<>();
        final List<String> ysaid = new ArrayList<>();
        final AgentTools yt = new AgentTools( sp, tw, ( x, y ) -> true, ysaid::add, sh.resolve( "j.tsv" ), sh )
            .withSystem( k -> keys.add( k.name() ) ).withKeyPause( 0 );
        yt.run( new String[] { "sys", "volume_down", "5" } );
        eq( "5 presses", 5, keys.size() );
        eq( "down key", "VOLUME_DOWN", keys.get( 0 ) );
        keys.clear();
        yt.run( new String[] { "sys", "volume_set", "30" } );
        eq( "set = 50 down + 15 up", 65, keys.size() );
        eq( "set ends up", "VOLUME_UP", keys.get( 64 ) );
        keys.clear();
        yt.run( new String[] { "sys", "volume_up", "9999" } );
        eq( "press count capped", 25, keys.size() );
        yt.run( new String[] { "refuse" } );
        eq( "exact refusal", "This action is not within my parameters.", ysaid.get( ysaid.size() - 1 ) );
        keys.clear();
        yt.handleNow( "[DO:sys|shutdown]" );
        eq( "unknown sys action refused", "This action is not within my parameters.", ysaid.get( ysaid.size() - 1 ) );
        eq( "no key for unknown", 0, keys.size() );

        System.out.println( fails == 0 ? "AgentToolsTest: all passed" : "AgentToolsTest: " + fails + " FAILED" );
        if( fails > 0 ) System.exit( 1 );
    }
}
