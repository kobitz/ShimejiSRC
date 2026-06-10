package com.group_finity.mascot.assistant;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Local drive indexer with keyword retrieval for the assistant layer.
 *
 * A daemon thread walks all readable drive roots to a limited depth and writes a
 * compact index to driveindex.txt next to the JAR (one entry per line:
 * size|mtime|path, size -1 for directories). The index is rebuilt when missing
 * or older than 24 hours and is held in memory for queries.
 *
 * buildContextBlock(userText) tokenizes the user's message and returns a small
 * prompt block listing the best-matching paths, modeled on the permanentMemories
 * keyword gating in MascotMemory. Returns "" when nothing matches, so callers
 * can append it unconditionally.
 *
 * Settings (settings.properties): DriveIndexEnabled (default true),
 * DriveIndexDepth (default 4 directory levels per root).
 *
 * Everything stays on the local machine - the index is only ever injected into
 * prompts sent to the local Ollama endpoint.
 */
public class DriveIndexTool
{
    private static final Logger log = Logger.getLogger( DriveIndexTool.class.getName() );

    private static final File INDEX_FILE = new File( "driveindex.txt" );

    private static final long RESCAN_MS          = 24L * 60 * 60 * 1000; // rebuild daily
    private static final long RECHECK_MS         = 60L * 60 * 1000;      // staleness poll
    private static final long ROOT_BUDGET_MS     = 180_000L;             // max walk time per root
    private static final int  MAX_ENTRIES        = 60_000;               // global entry cap
    private static final int  MAX_FILES_PER_DIR  = 300;                  // listing cap per dir
    private static final int  DEFAULT_DEPTH      = 4;
    private static final int  MAX_RESULTS        = 5;                    // paths injected per query

    // Directory names never worth indexing (system internals, package caches).
    private static final Set<String> SKIP_DIRS = new HashSet<>( Arrays.asList(
        "windows", "program files", "program files (x86)", "programdata",
        "$recycle.bin", "system volume information", "appdata", "perflogs",
        "recovery", "msocache", "windows.old", "node_modules", "__pycache__",
        "venv", "intel", "nvidia", "onedrivetemp" ) );

    // Query tokens too generic to identify a file. File-shaped words ("file",
    // "folder") are included: alone they cannot single out a path anyway.
    private static final Set<String> STOPWORDS = new HashSet<>( Arrays.asList(
        "the", "and", "you", "your", "where", "what", "when", "which", "who",
        "how", "why", "did", "does", "have", "has", "had", "can", "could",
        "would", "should", "will", "was", "were", "are", "that", "this",
        "these", "those", "with", "for", "from", "about", "tell", "show",
        "find", "look", "put", "get", "got", "know", "remember", "saved",
        "file", "files", "folder", "folders", "directory", "drive", "drives",
        "disk", "computer", "anything", "something", "stuff", "there" ) );

    private static final class Entry
    {
        final String path;       // original-case absolute path
        final String pathLower;
        final long   size;       // -1 for directories
        final long   mtime;

        Entry( final String path, final long size, final long mtime )
        {
            this.path      = path;
            this.pathLower = path.toLowerCase( Locale.ROOT );
            this.size      = size;
            this.mtime     = mtime;
        }
    }

    // Swapped wholesale by the indexer thread; readers grab the reference once.
    private static volatile List<Entry> entries = new ArrayList<>();

    private static volatile boolean started = false;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Idempotent. Loads the existing index and keeps it fresh on a daemon thread. */
    public static synchronized void ensureStarted()
    {
        if( started ) return;
        if( !"true".equalsIgnoreCase( getProperty( "DriveIndexEnabled", "true" ) ) )
        {
            log.info( "[DriveIndex] Disabled via DriveIndexEnabled." );
            return;
        }
        started = true;

        final Thread t = new Thread( () ->
        {
            // Serve the previous index immediately while any rescan runs
            if( INDEX_FILE.exists() ) loadIndex();
            while( true )
            {
                try
                {
                    if( !INDEX_FILE.exists()
                            || System.currentTimeMillis() - INDEX_FILE.lastModified() > RESCAN_MS )
                    {
                        scan();
                        saveIndex();
                    }
                    Thread.sleep( RECHECK_MS );
                }
                catch( final InterruptedException ie ) { return; }
                catch( final Exception e )
                {
                    log.warning( "[DriveIndex] Index cycle failed: " + e.getMessage() );
                    try { Thread.sleep( RECHECK_MS ); }
                    catch( final InterruptedException ie ) { return; }
                }
            }
        }, "drive-indexer" );
        t.setDaemon( true );
        t.start();
    }

    /**
     * Returns a prompt block listing indexed paths matching the user's words,
     * or "" when nothing matches well enough. Safe to call before the first
     * scan completes (returns "").
     */
    public static String buildContextBlock( final String userText )
    {
        final List<Entry> snapshot = entries;
        if( userText == null || userText.isEmpty() || snapshot.isEmpty() ) return "";

        final List<String> tokens = tokenize( userText );
        if( tokens.isEmpty() ) return "";

        // Score: filename hit is worth a path hit x3 - the filename is what
        // users remember; bare path-component hits alone are too noisy.
        Entry[] best   = new Entry[ MAX_RESULTS ];
        int[]   scores = new int[ MAX_RESULTS ];
        for( final Entry e : snapshot )
        {
            final int sep  = e.pathLower.lastIndexOf( '\\' );
            final String nameLower = sep >= 0 ? e.pathLower.substring( sep + 1 ) : e.pathLower;
            int score = 0;
            for( final String tok : tokens )
            {
                if( nameLower.contains( tok ) )        score += 3;
                else if( e.pathLower.contains( tok ) ) score += 1;
            }
            if( score < 3 ) continue; // require at least one filename hit
            for( int i = 0; i < MAX_RESULTS; i++ )
            {
                if( score > scores[ i ]
                        || ( score == scores[ i ] && best[ i ] != null
                             && e.mtime > best[ i ].mtime ) )
                {
                    System.arraycopy( scores, i, scores, i + 1, MAX_RESULTS - i - 1 );
                    System.arraycopy( best,   i, best,   i + 1, MAX_RESULTS - i - 1 );
                    scores[ i ] = score;
                    best[ i ]   = e;
                    break;
                }
            }
        }
        if( best[ 0 ] == null ) return "";

        final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat( "yyyy-MM-dd" );
        final StringBuilder sb = new StringBuilder( "\n\n--- FILES ON THE USER'S DRIVES (matched their words) ---" );
        for( int i = 0; i < MAX_RESULTS && best[ i ] != null; i++ )
        {
            final Entry e = best[ i ];
            sb.append( "\n- " ).append( e.path );
            if( e.size < 0 ) sb.append( " (folder)" );
            else sb.append( " (" ).append( humanSize( e.size ) )
                   .append( ", modified " ).append( sdf.format( new java.util.Date( e.mtime ) ) )
                   .append( ")" );
        }
        sb.append( "\n--- END FILES ---" )
          .append( "\nUse these only if the user is asking about their files; state the full path when you do." );
        return sb.toString();
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private static void scan()
    {
        final int depth = parseInt( getProperty( "DriveIndexDepth", String.valueOf( DEFAULT_DEPTH ) ),
                                    DEFAULT_DEPTH );
        final List<Entry> fresh = new ArrayList<>();
        final long startMs = System.currentTimeMillis();

        for( final File root : File.listRoots() )
        {
            // Skip empty card readers / disconnected drives without touching them hard
            if( root.getTotalSpace() <= 0 ) continue;
            final long budgetEnd = System.currentTimeMillis() + ROOT_BUDGET_MS;
            log.info( "[DriveIndex] Scanning " + root + " to depth " + depth );
            walk( root, 0, depth, budgetEnd, fresh );
            if( fresh.size() >= MAX_ENTRIES ) break;
        }

        entries = fresh;
        log.info( "[DriveIndex] Scan complete: " + fresh.size() + " entries in "
            + ( ( System.currentTimeMillis() - startMs ) / 1000 ) + "s" );
    }

    private static void walk( final File dir, final int level, final int maxDepth,
                              final long budgetEnd, final List<Entry> out )
    {
        if( out.size() >= MAX_ENTRIES ) return;
        if( System.currentTimeMillis() > budgetEnd ) return;

        final File[] children = dir.listFiles();
        if( children == null ) return; // permission denied or IO error

        int filesRecorded = 0;
        for( final File f : children )
        {
            if( out.size() >= MAX_ENTRIES ) return;
            final String name = f.getName();
            if( name.isEmpty() || name.startsWith( "$" ) || name.startsWith( "." ) ) continue;

            if( f.isDirectory() )
            {
                if( SKIP_DIRS.contains( name.toLowerCase( Locale.ROOT ) ) ) continue;
                if( isReparsePoint( f ) ) continue; // junctions/symlinks: avoid cycles
                out.add( new Entry( f.getAbsolutePath(), -1, f.lastModified() ) );
                if( level + 1 < maxDepth )
                    walk( f, level + 1, maxDepth, budgetEnd, out );
            }
            else if( filesRecorded < MAX_FILES_PER_DIR )
            {
                out.add( new Entry( f.getAbsolutePath(), f.length(), f.lastModified() ) );
                filesRecorded++;
            }
        }
    }

    /** True for junctions and symlinks (canonical path differs from absolute). */
    private static boolean isReparsePoint( final File dir )
    {
        try
        {
            return !dir.getCanonicalPath().equalsIgnoreCase( dir.getAbsolutePath() );
        }
        catch( final Exception e ) { return true; } // unreadable: treat as skip
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private static void saveIndex()
    {
        final List<Entry> snapshot = entries;
        try( final BufferedWriter w = new BufferedWriter( new OutputStreamWriter(
                new FileOutputStream( INDEX_FILE ), "UTF-8" ) ) )
        {
            for( final Entry e : snapshot )
            {
                // '|' is illegal in Windows filenames, so it is a safe separator
                w.write( e.size + "|" + e.mtime + "|" + e.path );
                w.newLine();
            }
            log.info( "[DriveIndex] Saved " + snapshot.size() + " entries to " + INDEX_FILE );
        }
        catch( final Exception e )
        {
            log.warning( "[DriveIndex] Save failed: " + e.getMessage() );
        }
    }

    private static void loadIndex()
    {
        final List<Entry> loaded = new ArrayList<>();
        try( final BufferedReader r = new BufferedReader( new InputStreamReader(
                new FileInputStream( INDEX_FILE ), "UTF-8" ) ) )
        {
            String line;
            while( ( line = r.readLine() ) != null && loaded.size() < MAX_ENTRIES )
            {
                final int p1 = line.indexOf( '|' );
                final int p2 = line.indexOf( '|', p1 + 1 );
                if( p1 < 0 || p2 < 0 ) continue;
                try
                {
                    loaded.add( new Entry( line.substring( p2 + 1 ),
                        Long.parseLong( line.substring( 0, p1 ) ),
                        Long.parseLong( line.substring( p1 + 1, p2 ) ) ) );
                }
                catch( final NumberFormatException ignored ) {}
            }
            entries = loaded;
            log.info( "[DriveIndex] Loaded " + loaded.size() + " entries from " + INDEX_FILE );
        }
        catch( final Exception e )
        {
            log.warning( "[DriveIndex] Load failed: " + e.getMessage() );
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<String> tokenize( final String text )
    {
        final List<String> tokens = new ArrayList<>();
        for( final String raw : text.toLowerCase( Locale.ROOT ).split( "[^a-z0-9]+" ) )
        {
            if( raw.length() < 3 ) continue;
            if( STOPWORDS.contains( raw ) ) continue;
            if( !tokens.contains( raw ) ) tokens.add( raw );
        }
        return tokens;
    }

    private static String humanSize( final long bytes )
    {
        if( bytes >= 1L << 30 ) return String.format( Locale.ROOT, "%.1f GB", bytes / (double)( 1L << 30 ) );
        if( bytes >= 1L << 20 ) return String.format( Locale.ROOT, "%.1f MB", bytes / (double)( 1L << 20 ) );
        if( bytes >= 1L << 10 ) return String.format( Locale.ROOT, "%.1f KB", bytes / (double)( 1L << 10 ) );
        return bytes + " B";
    }

    private static String getProperty( final String key, final String def )
    {
        try
        {
            return com.group_finity.mascot.Main.getInstance().getProperties()
                .getProperty( key, def );
        }
        catch( final Exception e ) { return def; }
    }

    private static int parseInt( final String s, final int def )
    {
        try { return Integer.parseInt( s.trim() ); }
        catch( final Exception e ) { return def; }
    }
}
