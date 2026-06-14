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
 * buildContextBlock(userText) returns a small prompt block listing the best-matching
 * paths, modeled on the permanentMemories keyword gating in MascotMemory. It blends
 * two retrievers and returns "" when neither matches, so callers can append it
 * unconditionally:
 *   1. KEYWORD (always on): exact substring scoring (filename hit x3) -- high precision
 *      for the words a user remembers verbatim.
 *   2. SEMANTIC (Phase 2, optional): cosine similarity over local nomic-embed-text
 *      embeddings of media/document filenames, for vague recall like "that video I
 *      downloaded last month" where no exact token matches. Embeddings are built in
 *      the background (CPU-only) and cached to driveembed.txt keyed by path|mtime, so
 *      rescans only embed new/changed files. If the embed model isn't pulled, semantic
 *      silently disables and keyword retrieval is unchanged.
 *
 * Settings (settings.properties): DriveIndexEnabled (default true),
 * DriveIndexDepth (default 4 directory levels per root), DriveSemanticEnabled
 * (default true), DriveSemanticModel (default nomic-embed-text),
 * DriveSemanticThreshold (default 0.6 cosine).
 *
 * Everything stays on the local machine - the index and its embeddings are only ever
 * injected into prompts sent to the local Ollama endpoint.
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

    // ── Semantic search (Phase 2) ──────────────────────────────────────────────
    private static final File   EMBED_FILE             = new File( "driveembed.txt" );
    private static final int    MAX_EMBED              = 6000;          // most-recent eligible files embedded
    private static final int    EMBED_BATCH            = 24;            // inputs per /api/embed call
    private static final long   EMBED_BUDGET_MS        = 60_000L;       // max embedding time per cycle
    private static final String DEFAULT_SEMANTIC_MODEL = "nomic-embed-text";
    private static final double DEFAULT_THRESHOLD      = 0.6;           // cosine cutoff for a match

    // Typed extension sets. Used BOTH for embedding eligibility (their union) and for
    // "how many videos / biggest photos" type filters in the aggregate retriever.
    private static final Set<String> VIDEO_EXTS = new HashSet<>( Arrays.asList(
        "mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "m4v", "mpg", "mpeg" ) );
    private static final Set<String> IMAGE_EXTS = new HashSet<>( Arrays.asList(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg", "heic" ) );
    private static final Set<String> AUDIO_EXTS = new HashSet<>( Arrays.asList(
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma" ) );
    private static final Set<String> DOC_EXTS = new HashSet<>( Arrays.asList(
        "pdf", "doc", "docx", "txt", "md", "rtf", "odt", "xls", "xlsx", "csv",
        "ppt", "pptx", "epub", "mobi" ) );
    private static final Set<String> ARCHIVE_EXTS = new HashSet<>( Arrays.asList(
        "zip", "7z", "rar", "tar", "gz" ) );

    // Only media + documents are embedded: those are the files a user describes from
    // memory ("that video", "the tax pdf"). Code/executables/temp files dominate by
    // count and only dilute the nearest-neighbour matches, so they are excluded.
    private static final Set<String> MEDIA_DOC_EXTS = new HashSet<>();
    static
    {
        MEDIA_DOC_EXTS.addAll( VIDEO_EXTS );
        MEDIA_DOC_EXTS.addAll( IMAGE_EXTS );
        MEDIA_DOC_EXTS.addAll( AUDIO_EXTS );
        MEDIA_DOC_EXTS.addAll( DOC_EXTS );
        MEDIA_DOC_EXTS.addAll( ARCHIVE_EXTS );
    }

    /**
     * True for factual drive lookups (recency / aggregate / location) whose answer
     * changes as the filesystem changes. Mascot.isEphemeralQuery() gates memory
     * recording on this so a one-off "what's the last download" / "how much space"
     * answer is never stored as a durable fact (it would entrench a stale/wrong value).
     * Topical HYBRID queries are NOT ephemeral — they read more like conversation.
     */
    public static boolean isFactualDriveQuery( final String userText )
    {
        if( userText == null || userText.isEmpty() ) return false;
        final DriveIntent i = classifyDriveQuery( userText );
        return i == DriveIntent.RECENCY || i == DriveIntent.AGGREGATE || i == DriveIntent.LOCATION;
    }

    // ── Query router (recency vs hybrid) ───────────────────────────────────────
    // "what was the LAST thing I downloaded" is a recency question: it wants newest-by-
    // time, which similarity search cannot answer (it ranks by topic, not mtime). The
    // router detects that shape and routes it to a fresh mtime lookup instead.
    private enum DriveIntent { RECENCY, AGGREGATE, LOCATION, HYBRID }

    private static final Set<String> RECENCY_TOKENS = new HashSet<>( Arrays.asList(
        "last", "latest", "newest", "recent", "recently", "most" ) );
    // Single-word aggregate cues (phrases like "how many" are matched on raw text).
    private static final Set<String> AGGREGATE_TOKENS = new HashSet<>( Arrays.asList(
        "biggest", "largest", "smallest", "size", "sizes", "space", "storage" ) );
    private static final Set<String> LOCATION_TOKENS = new HashSet<>( Arrays.asList(
        "list", "contents", "content", "inside" ) );
    // File-category words -> let classify treat them as scaffolding, not topics.
    private static final Set<String> TYPE_WORDS = new HashSet<>( Arrays.asList(
        "video", "videos", "movie", "movies", "photo", "photos", "picture", "pictures",
        "image", "images", "song", "songs", "music", "audio", "document", "documents",
        "doc", "docs", "pdf", "pdfs" ) );
    // After "last"/"recent" these mean a time span ("last month"), not "newest file".
    private static final Set<String> TIME_WORDS = new HashSet<>( Arrays.asList(
        "month", "months", "week", "weeks", "day", "days", "year", "years",
        "night", "morning", "evening", "yesterday", "ago", "time", "while" ) );
    private static final Set<String> ACQUIRE_WORDS = new HashSet<>( Arrays.asList(
        "download", "downloaded", "downloads", "downloading", "save", "saved", "saving",
        "add", "added", "install", "installed", "grab", "grabbed", "got", "get",
        "getting", "made", "created" ) );
    // Generic placeholders for "the file/thing", carrying no topic of their own.
    private static final Set<String> FILE_NOUNS = new HashSet<>( Arrays.asList(
        "thing", "things", "one", "ones", "file", "files", "item", "items",
        "download", "downloads" ) );
    // word -> canonical user folder to scan for a recency answer.
    private static final java.util.Map<String, String> FOLDER_HINTS = new java.util.HashMap<>();
    static
    {
        FOLDER_HINTS.put( "downloads", "Downloads" ); FOLDER_HINTS.put( "download", "Downloads" );
        FOLDER_HINTS.put( "desktop", "Desktop" );
        FOLDER_HINTS.put( "documents", "Documents" ); FOLDER_HINTS.put( "document", "Documents" );
        FOLDER_HINTS.put( "pictures", "Pictures" ); FOLDER_HINTS.put( "picture", "Pictures" );
        FOLDER_HINTS.put( "photos", "Pictures" ); FOLDER_HINTS.put( "photo", "Pictures" );
        FOLDER_HINTS.put( "music", "Music" ); FOLDER_HINTS.put( "songs", "Music" ); FOLDER_HINTS.put( "song", "Music" );
        FOLDER_HINTS.put( "videos", "Videos" ); FOLDER_HINTS.put( "video", "Videos" );
        FOLDER_HINTS.put( "movies", "Videos" ); FOLDER_HINTS.put( "movie", "Videos" );
    }

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
        "disk", "computer", "anything", "something", "stuff", "there",
        // informal contractions WITHOUT apostrophes (apostrophe forms split away to
        // harmless 1-char fragments, but "whats"/"wheres" survive as one token and would
        // otherwise read as a topic word, mis-routing "whats the last thing I downloaded")
        "whats", "wheres", "hows", "whos", "thats", "theres", "dont", "doesnt", "didnt",
        "wont", "cant", "couldnt", "wouldnt", "shouldnt", "isnt", "arent", "wasnt",
        "werent", "youre", "youve", "ive", "ill", "lemme", "gimme" ) );

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

    /** path -> cached embedding (+ the mtime/size it was embedded at). Mutated only by
     *  the indexer daemon; read concurrently by query threads, hence concurrent. */
    private static final class EmbeddedVec
    {
        final long    mtime;
        final long    size;
        final float[] vec;
        EmbeddedVec( final long mtime, final long size, final float[] vec )
        {
            this.mtime = mtime;
            this.size  = size;
            this.vec   = vec;
        }
    }
    private static final java.util.Map<String, EmbeddedVec> embedStore =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean embedStoreLoaded = false;

    /** The install/working dir (where the app runs). Files here are excluded from deletion
     *  suggestions so the mascot never recommends deleting its own install. */
    private static final String INSTALL_DIR_LOWER = computeInstallDir();

    private static String computeInstallDir()
    {
        try
        {
            return new File( System.getProperty( "user.dir", "." ) )
                .getCanonicalPath().toLowerCase( Locale.ROOT );
        }
        catch( final Exception e )
        {
            return System.getProperty( "user.dir", "" ).toLowerCase( Locale.ROOT );
        }
    }

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
            if( isSemanticOn() ) loadEmbeddings();
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
                    // Incremental, budgeted: only embeds files not already cached at their
                    // current mtime, so most cycles are a cheap no-op. Independent of index
                    // staleness so freshly-added media gets embedded within RECHECK_MS.
                    if( isSemanticOn() ) runEmbedPass();
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

        // 0) Route specialised question shapes to a fresh, exact lookup instead of
        //    similarity search. Each falls through to the hybrid path below if it
        //    produces nothing (e.g. an unresolved folder), so nothing is ever lost.
        switch( classifyDriveQuery( userText ) )
        {
            case RECENCY:
            {
                final String r = recencyBlock( userText );
                if( !r.isEmpty() ) return r;
                break;
            }
            case AGGREGATE:
            {
                final String r = aggregateBlock( userText );
                if( !r.isEmpty() ) return r;
                break;
            }
            case LOCATION:
            {
                final String r = locationBlock( userText );
                if( !r.isEmpty() ) return r;
                break;
            }
            default:
                break;
        }

        final List<String> tokens = tokenize( userText );

        // 1) Keyword matches (exact substring, high precision) - unchanged behaviour.
        final List<Entry> keyword = tokens.isEmpty()
            ? java.util.Collections.emptyList()
            : keywordMatches( tokens, snapshot );

        // 2) Semantic matches fill the remaining slots: fuzzy recall for vague queries
        //    ("that video I downloaded"). Excludes paths keyword already matched.
        final Set<String> have = new HashSet<>();
        for( final Entry e : keyword ) have.add( e.path );
        final List<Entry> semantic = semanticMatches( userText, have, MAX_RESULTS - keyword.size() );

        if( keyword.isEmpty() && semantic.isEmpty() ) return "";

        final List<Entry> merged = new ArrayList<>( keyword );
        merged.addAll( semantic );
        return formatBlock( merged, "FILES ON THE USER'S DRIVES (matched their words)" );
    }

    /** Exact-substring scoring (filename hit x3). Returns the top MAX_RESULTS entries,
     *  best first, or an empty list when nothing clears the threshold. */
    private static List<Entry> keywordMatches( final List<String> tokens, final List<Entry> snapshot )
    {
        final Entry[] best   = new Entry[ MAX_RESULTS ];
        final int[]   scores = new int[ MAX_RESULTS ];
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
        final List<Entry> out = new ArrayList<>();
        for( int i = 0; i < MAX_RESULTS && best[ i ] != null; i++ ) out.add( best[ i ] );
        return out;
    }

    /** Renders matched entries into the prompt block (format shared by all retrievers). */
    private static String formatBlock( final List<Entry> matches, final String title )
    {
        final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat( "yyyy-MM-dd" );
        final StringBuilder sb = new StringBuilder( "\n\n--- " + title + " ---" );
        for( final Entry e : matches )
        {
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

    // ── Recency lookup + router ────────────────────────────────────────────────

    private static String[] words( final String s )
    {
        return s.toLowerCase( Locale.ROOT ).split( "[^a-z0-9]+" );
    }

    /**
     * Routes a drive question to the cheapest retriever that fits its shape:
     *   AGGREGATE  - counts/sizes/superlatives ("how many videos", "biggest files")
     *   RECENCY    - newest-by-time with no topic ("the last thing I downloaded")
     *   LOCATION   - list a named folder ("what's in my Downloads")
     *   HYBRID     - everything else -> keyword + semantic similarity
     * Specialised intents COMPUTE an answer regardless of match, so they are gated behind
     * a files-context check: a query that doesn't mention files/folders/a drive/a media
     * type ("how much do you like me", "the last thing you said") falls through to HYBRID,
     * which self-gates on a relevance threshold and injects nothing when irrelevant.
     */
    private static DriveIntent classifyDriveQuery( final String userText )
    {
        final String raw = userText.toLowerCase( Locale.ROOT );
        final String[] ws = words( userText );

        if( !mentionsFiles( raw, ws ) ) return DriveIntent.HYBRID;

        // AGGREGATE first: "how many videos did I download" is a count, not a recency ask;
        // cleanup/deletion phrasing ("cleaning up my c drive") also belongs here.
        if( raw.contains( "how many" ) || raw.contains( "how much" )
                || raw.contains( "how big" ) || raw.contains( "how large" )
                || raw.contains( "how full" ) || raw.contains( "taking up" )
                || raw.contains( "clean" ) || raw.contains( "delete" ) || raw.contains( "declutter" )
                || raw.contains( "tidy" ) || raw.contains( "get rid" ) || raw.contains( "free up" )
                || hasAny( ws, AGGREGATE_TOKENS ) )
            return DriveIntent.AGGREGATE;

        // RECENCY: a recency word that isn't qualifying a time span ("last month").
        boolean recency = false;
        for( int i = 0; i < ws.length; i++ )
        {
            final String w = ws[ i ];
            if( w.equals( "latest" ) || w.equals( "newest" )
                    || w.equals( "recent" ) || w.equals( "recently" ) )
                recency = true;
            else if( w.equals( "last" ) )
            {
                final String next = i + 1 < ws.length ? ws[ i + 1 ] : "";
                if( !TIME_WORDS.contains( next ) ) recency = true;
            }
        }
        if( recency && !hasTopicLeftover( ws ) ) return DriveIntent.RECENCY;

        // LOCATION: explicit "list/what's in" against a named folder, no topic.
        if( folderHint( userText ) != null
                && ( raw.contains( "what's in" ) || raw.contains( "whats in" )
                     || raw.contains( "what is in" ) || hasAny( ws, LOCATION_TOKENS ) )
                && !hasTopicLeftover( ws ) )
            return DriveIntent.LOCATION;

        return DriveIntent.HYBRID;
    }

    /** True when the question is plausibly about the user's files (gates the specialised
     *  retrievers so ordinary conversation never triggers a drive lookup). */
    private static boolean mentionsFiles( final String raw, final String[] ws )
    {
        if( folderHint0( ws ) != null || typeFilter( ws ) != null ) return true;
        if( hasAny( ws, FILE_NOUNS ) ) return true;
        return raw.contains( "file" ) || raw.contains( "folder" ) || raw.contains( "disk" )
            || raw.contains( "drive" ) || raw.contains( "download" )
            || raw.contains( "storage" ) || raw.contains( "space" );
    }

    private static boolean hasAny( final String[] ws, final Set<String> set )
    {
        for( final String w : ws ) if( set.contains( w ) ) return true;
        return false;
    }

    /** True if a real topic word survives stripping all the function/scaffolding words -
     *  used to keep "newest FIREFIGHTER video" out of recency/location (it's topical). */
    private static boolean hasTopicLeftover( final String[] ws )
    {
        for( final String w : ws )
        {
            if( w.length() < 3 ) continue;
            if( STOPWORDS.contains( w ) || TIME_WORDS.contains( w ) || ACQUIRE_WORDS.contains( w )
                    || FILE_NOUNS.contains( w ) || RECENCY_TOKENS.contains( w )
                    || AGGREGATE_TOKENS.contains( w ) || LOCATION_TOKENS.contains( w )
                    || TYPE_WORDS.contains( w ) || FOLDER_HINTS.containsKey( w )
                    || w.equals( "many" ) || w.equals( "much" ) )
                continue;
            return true;
        }
        return false;
    }

    /** Map a query word to a typed extension set ("videos" -> VIDEO_EXTS); null = all types. */
    private static Set<String> typeFilter( final String[] ws )
    {
        for( final String w : ws )
        {
            if( w.equals( "video" ) || w.equals( "videos" ) || w.equals( "movie" ) || w.equals( "movies" ) )
                return VIDEO_EXTS;
            if( w.equals( "photo" ) || w.equals( "photos" ) || w.equals( "picture" )
                    || w.equals( "pictures" ) || w.equals( "image" ) || w.equals( "images" ) )
                return IMAGE_EXTS;
            if( w.equals( "song" ) || w.equals( "songs" ) || w.equals( "music" ) || w.equals( "audio" ) )
                return AUDIO_EXTS;
            if( w.equals( "document" ) || w.equals( "documents" ) || w.equals( "doc" )
                    || w.equals( "docs" ) || w.equals( "pdf" ) || w.equals( "pdfs" ) )
                return DOC_EXTS;
        }
        return null;
    }

    private static String typeLabel( final Set<String> typeExts )
    {
        if( typeExts == VIDEO_EXTS ) return "videos";
        if( typeExts == IMAGE_EXTS ) return "images";
        if( typeExts == AUDIO_EXTS ) return "audio files";
        if( typeExts == DOC_EXTS )   return "documents";
        return "files";
    }

    private static String extOf( final String pathLower )
    {
        final int dot = pathLower.lastIndexOf( '.' );
        final int sep = pathLower.lastIndexOf( '\\' );
        return ( dot < 0 || dot < sep ) ? "" : pathLower.substring( dot + 1 );
    }

    /** Canonical folder to scan, from an explicit hint ("in Downloads") or the
     *  "download" verb (which implies the Downloads folder); null if unscoped. */
    private static String folderHint( final String userText )
    {
        return folderHint0( words( userText ) );
    }

    private static String folderHint0( final String[] ws )
    {
        String hint = null;
        boolean impliesDownloads = false;
        for( final String w : ws )
        {
            final String canon = FOLDER_HINTS.get( w );
            if( canon != null ) hint = canon;
            if( w.startsWith( "download" ) ) impliesDownloads = true;
        }
        if( hint != null ) return hint;
        return impliesDownloads ? "Downloads" : null;
    }

    /** Resolve a canonical folder name to actual directories: prefer ones already in
     *  the index (handles non-default locations like D:\Downloads), else the standard
     *  user-profile folder. */
    private static List<File> resolveFolders( final String folderName )
    {
        final List<File> out = new ArrayList<>();
        if( folderName == null ) return out;
        final String want = folderName.toLowerCase( Locale.ROOT );
        final Set<String> seen = new HashSet<>();
        for( final Entry e : entries )
        {
            if( e.size >= 0 ) continue; // directories only
            if( isSystemProfileDir( e.pathLower ) ) continue; // skip empty Default/Public skeletons
            final int sep = e.pathLower.lastIndexOf( '\\' );
            final String name = sep >= 0 ? e.pathLower.substring( sep + 1 ) : e.pathLower;
            if( name.equals( want ) )
            {
                final File d = new File( e.path );
                if( d.isDirectory() && seen.add( e.pathLower ) ) out.add( d );
                if( out.size() >= 8 ) break; // scan every real match (e.g. C: AND D: Downloads)
            }
        }
        if( out.isEmpty() )
        {
            final File home = new File( System.getProperty( "user.home", "" ), folderName );
            if( home.isDirectory() ) out.add( home );
        }
        return out;
    }

    /** The Default/Public/All-Users profile skeletons are never the user's real folder;
     *  scanning them only wastes slots (the bug that hid D:\Downloads behind a 3-cap). */
    private static boolean isSystemProfileDir( final String pathLower )
    {
        return pathLower.contains( "\\users\\default" )
            || pathLower.contains( "\\users\\public" )
            || pathLower.contains( "\\users\\all users" );
    }

    /** Fresh listing of folders' immediate children. Authoritative for "newest"/"what's
     *  in" -- bypasses the up-to-24h-stale, 300-per-dir-capped index. */
    private static List<Entry> scanChildren( final List<File> roots )
    {
        final List<Entry> all = new ArrayList<>();
        for( final File dir : roots )
        {
            final File[] kids = dir.listFiles();
            if( kids == null ) continue;
            for( final File f : kids )
            {
                final String n = f.getName();
                if( n.isEmpty() || n.startsWith( "$" ) || n.startsWith( "." ) ) continue;
                all.add( new Entry( f.getAbsolutePath(),
                    f.isDirectory() ? -1 : f.length(), f.lastModified() ) );
            }
        }
        return all;
    }

    private static List<Entry> liveRecency( final List<File> roots, final int limit )
    {
        final List<Entry> all = scanChildren( roots );
        all.sort( ( a, b ) -> Long.compare( b.mtime, a.mtime ) );
        return all.size() > limit ? new ArrayList<>( all.subList( 0, limit ) ) : all;
    }

    /** Fallback when no folder is named: newest files across the whole index. Subject to
     *  index staleness, but better than nothing for an unscoped "what did I just save". */
    private static List<Entry> indexRecency( final int limit )
    {
        final List<Entry> files = new ArrayList<>();
        for( final Entry e : entries ) if( e.size >= 0 ) files.add( e );
        files.sort( ( a, b ) -> Long.compare( b.mtime, a.mtime ) );
        return files.size() > limit ? new ArrayList<>( files.subList( 0, limit ) ) : files;
    }

    private static String recencyBlock( final String userText )
    {
        final String folder   = folderHint( userText );
        final List<File> roots = resolveFolders( folder );
        final List<Entry> results = !roots.isEmpty()
            ? liveRecency( roots, MAX_RESULTS )
            : indexRecency( MAX_RESULTS );
        if( results.isEmpty() ) return "";
        final String where = folder != null ? " in " + folder : "";
        final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat( "yyyy-MM-dd" );
        final Entry top = results.get( 0 );
        // Answer-first: lead with THE single newest item so the 4B model commits to it
        // instead of listing several and hedging ("you haven't said which folder...").
        final StringBuilder sb = new StringBuilder( "\n\n--- MOST RECENT ITEM" + where + " ---" );
        sb.append( "\nThe single most recently modified item is: " ).append( top.path )
          .append( top.size < 0 ? " (folder)" : " (" + humanSize( top.size ) + ")" )
          .append( ", modified " ).append( sdf.format( new java.util.Date( top.mtime ) ) ).append( "." );
        if( results.size() > 1 )
        {
            sb.append( "\nOlder items, for reference only:" );
            for( int i = 1; i < results.size(); i++ )
            {
                final Entry e = results.get( i );
                sb.append( "\n- " ).append( e.path )
                  .append( e.size < 0 ? " (folder)" : " (" + humanSize( e.size ) + ")" )
                  .append( ", " ).append( sdf.format( new java.util.Date( e.mtime ) ) );
            }
        }
        sb.append( "\n--- END ---" )
          .append( "\nAnswer with the single most recent item (the first one) by name. "
                 + "Do not hedge or ask which folder." );
        return sb.toString();
    }

    /** LOCATION: list a named folder's contents (a newest-first sample + total count). */
    private static String locationBlock( final String userText )
    {
        final String folder = folderHint( userText );
        final List<File> roots = resolveFolders( folder );
        if( roots.isEmpty() ) return "";
        final List<Entry> kids = scanChildren( roots );
        if( kids.isEmpty() ) return "";
        kids.sort( ( a, b ) -> Long.compare( b.mtime, a.mtime ) );
        final List<Entry> sample = topN( kids );
        return formatBlock( sample, "CONTENTS OF " + folder + " (" + kids.size()
            + " items total; newest " + sample.size() + " shown)" );
    }

    // Recursive folder walk for aggregates -- fresh and uncapped (unlike the index),
    // but bounded in depth/time/count so a huge tree can't stall the reply thread.
    private static final long AGG_BUDGET_MS = 3000;
    private static final int  AGG_MAX_FILES = 100_000;
    private static final int  AGG_MAX_DEPTH = 8;

    /** AGGREGATE: counts, total size, and biggest/smallest. Computes over a fresh
     *  recursive walk of the named folder, or the index when unscoped. */
    private static String aggregateBlock( final String userText )
    {
        final String raw = userText.toLowerCase( Locale.ROOT );
        final String[] ws = words( userText );
        final String folder = folderHint( userText );
        final List<File> roots = resolveFolders( folder );
        final Set<String> typeExts = typeFilter( ws );
        final String scope = folder != null ? folder : "your indexed drives";
        final String label = typeLabel( typeExts );

        // "what should I delete / free up space" -> the actionable answer is the biggest
        // files (deletion candidates), not a total.
        final boolean deletionIntent = raw.contains( "delete" ) || raw.contains( "save space" )
            || raw.contains( "free up" ) || raw.contains( "freeing up" )
            || raw.contains( "clean" ) || raw.contains( "clear space" )      // clean/cleaning/cleanup
            || raw.contains( "declutter" ) || raw.contains( "tidy" ) || raw.contains( "get rid" );
        final boolean wantBiggest = raw.contains( "biggest" ) || raw.contains( "largest" ) || deletionIntent;

        // Unscoped "how much space / how full is the drive" -> REAL disk usage from the OS,
        // never the indexed-file sum (a depth-capped undercount that only LOOKS authoritative).
        if( !wantBiggest && !raw.contains( "smallest" ) && !raw.contains( "how many" )
                && folder == null
                && ( raw.contains( "how much" ) || raw.contains( "how full" )
                     || raw.contains( "space" ) || raw.contains( "storage" )
                     || hasAny( ws, AGGREGATE_TOKENS ) ) )
        {
            final String disk = diskStatsBlock();
            if( !disk.isEmpty() ) return disk;
        }

        final List<Entry> source = !roots.isEmpty() ? liveWalk( roots ) : indexFilesOnly();
        final List<Entry> sel = new ArrayList<>();
        for( final Entry e : source )
        {
            if( e.size < 0 ) continue;
            if( typeExts != null && !typeExts.contains( extOf( e.pathLower ) ) ) continue;
            sel.add( e );
        }
        if( sel.isEmpty() ) return folder == null ? diskStatsBlock() : "";

        if( raw.contains( "smallest" ) )
        {
            sel.sort( ( a, b ) -> Long.compare( a.size, b.size ) );
            return formatBlock( topN( sel ), "SMALLEST " + label + " in " + scope );
        }
        if( wantBiggest )
        {
            if( deletionIntent )
            {
                // Deletion candidates weight size AND staleness (a big file untouched for
                // years beats one modified yesterday, which is probably in use) and boost
                // leftover installers/archives. mtime is last-MODIFIED, not last-opened
                // (Windows disables NTFS last-access by default), so it's the reliable
                // "how long since use" proxy. The Shimeji install folder is excluded so the
                // mascot never suggests deleting its own files.
                final long now = System.currentTimeMillis();
                final boolean excludeInstall = INSTALL_DIR_LOWER.length() > 3
                    && INSTALL_DIR_LOWER.contains( ":" );
                final List<Entry> cand = new ArrayList<>();
                for( final Entry e : sel )
                    if( !( excludeInstall && e.pathLower.startsWith( INSTALL_DIR_LOWER ) ) )
                        cand.add( e );
                // Whole-drive deletion: also size the real space hogs (game installs etc.)
                // that the file-level index can't see -- this is what surfaces EldenRing/
                // DarkSouls3 sitting under the (un-indexed) Program Files Steam library.
                if( folder == null )
                {
                    for( final Entry e : collectBigFolders( now + FOLDER_BUDGET_MS ) )
                        if( !( excludeInstall && e.pathLower.startsWith( INSTALL_DIR_LOWER ) ) )
                            cand.add( e );
                }
                if( cand.isEmpty() ) return "";
                cand.sort( ( a, b ) -> Double.compare( deletionScore( b, now ), deletionScore( a, now ) ) );
                return deletionFormat( topN( cand ), "BEST DELETION CANDIDATES"
                    + ( folder != null ? " in " + folder : "" )
                    + " (largest, weighted by how long untouched + leftover installers)" );
            }
            sel.sort( ( a, b ) -> Long.compare( b.size, a.size ) );
            return formatBlock( topN( sel ), "LARGEST " + label + " in " + scope );
        }
        if( raw.contains( "how many" ) )
            return statBlock( scope + " contains " + sel.size() + " " + label + "." );

        // Folder-scoped total: a fresh recursive walk sum is the folder's REAL size
        // (like treefile), within the walk budget.
        long total = 0;
        for( final Entry e : sel ) total += e.size;
        return statBlock( scope + " holds " + sel.size() + " " + label
            + " totaling " + humanSize( total ) + " (measured by a live folder scan)." );
    }

    /** Deletion priority: bigger AND longer-untouched ranks higher, with leftover
     *  installers/archives boosted. age+1 day means a freshly-modified file (probably in
     *  use) falls back to size-only ranking and never dominates, while a large old file
     *  floats to the top. */
    private static double deletionScore( final Entry e, final long now )
    {
        // Size dominates; staleness is a soft, capped nudge (1x fresh .. 4x for 3+ years
        // untouched) so a huge recent file still outranks a tiny ancient one -- and a game
        // folder whose mtime was bumped by a background Steam update isn't wrongly demoted.
        final double ageYears  = Math.max( 0, ( now - e.mtime ) / ( 365.0 * 86_400_000.0 ) );
        final double staleness = 1.0 + Math.min( ageYears, 3.0 );
        return (double) e.size * staleness * installerBoost( e );
    }

    /** Installers and downloaded archives are prime deletion candidates -- once you've
     *  installed/extracted them the original just occupies space. */
    private static double installerBoost( final Entry e )
    {
        final int sep = e.pathLower.lastIndexOf( '\\' );
        final String name = sep >= 0 ? e.pathLower.substring( sep + 1 ) : e.pathLower;
        final String ext  = extOf( e.pathLower );
        final boolean installer = name.contains( "setup" ) || name.contains( "install" )
            || ext.equals( "msi" ) || ext.equals( "iso" ) || ARCHIVE_EXTS.contains( ext )
            || ( ext.equals( "exe" ) && e.pathLower.contains( "\\downloads\\" ) );
        return installer ? 2.0 : 1.0;
    }

    /** Real per-drive usage straight from the OS (accurate, unlike the capped index). */
    private static String diskStatsBlock()
    {
        long total = 0, free = 0;
        for( final File r : File.listRoots() )
        {
            final long t = r.getTotalSpace();
            if( t <= 0 ) continue;
            total += t;
            free  += r.getUsableSpace();
        }
        if( total <= 0 ) return "";
        return statBlock( "Across all drives: " + humanSize( total - free ) + " used of "
            + humanSize( total ) + " total (" + humanSize( free ) + " free)." );
    }

    // ── Folder-level deletion candidates (treefile-style) ──────────────────────
    // The index deliberately skips Program Files and stops at depth 4, so big installs
    // (Steam games etc.) are invisible to it. For deletion/space questions we instead
    // live-size the real space hogs: each game-library child folder + Downloads subfolders.
    // Sizes are cached by path|mtime so only the first such query pays the recursive walk.

    private static final String[] GAME_LIB_SUBPATHS = {
        "steamapps\\common", "SteamLibrary\\steamapps\\common",
        "Program Files (x86)\\Steam\\steamapps\\common",
        "Program Files\\Epic Games", "Games"
    };
    private static final long FOLDER_BUDGET_MS      = 5000;
    private static final int  MAX_FOLDER_CANDIDATES = 60;
    private static final java.util.Map<String, long[]> folderSizeCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static long folderSize( final File dir, final long deadline )
    {
        long total = 0;
        final File[] kids = dir.listFiles();
        if( kids == null ) return 0;
        for( final File f : kids )
        {
            if( System.currentTimeMillis() > deadline ) break;
            if( f.getName().startsWith( "$" ) ) continue;
            if( f.isDirectory() ) { if( !isReparsePoint( f ) ) total += folderSize( f, deadline ); }
            else total += f.length();
        }
        return total;
    }

    private static long cachedFolderSize( final File dir, final long deadline )
    {
        final String key   = dir.getPath().toLowerCase( Locale.ROOT );
        final long   mtime = dir.lastModified();
        final long[] hit   = folderSizeCache.get( key );
        if( hit != null && hit[ 0 ] == mtime ) return hit[ 1 ];
        final long sz = folderSize( dir, deadline );
        if( System.currentTimeMillis() <= deadline ) // only cache a complete (non-truncated) walk
            folderSizeCache.put( key, new long[]{ mtime, sz } );
        return sz;
    }

    /** Big deletable folders: each child of a game library + each Downloads subfolder,
     *  with its real recursive size. Budget-bounded so it can't stall the reply. */
    private static List<Entry> collectBigFolders( final long deadline )
    {
        final List<Entry> out = new ArrayList<>();
        final java.util.LinkedHashSet<String> parents = new java.util.LinkedHashSet<>();
        for( final File root : File.listRoots() )
        {
            if( root.getTotalSpace() <= 0 ) continue;
            for( final String sub : GAME_LIB_SUBPATHS )
                parents.add( new File( root, sub ).getPath() );
        }
        for( final File dl : resolveFolders( "Downloads" ) ) parents.add( dl.getPath() );

        int sized = 0;
        for( final String pp : parents )
        {
            if( System.currentTimeMillis() > deadline || sized >= MAX_FOLDER_CANDIDATES ) break;
            final File[] children = new File( pp ).listFiles();
            if( children == null ) continue;
            for( final File c : children )
            {
                if( System.currentTimeMillis() > deadline || sized >= MAX_FOLDER_CANDIDATES ) break;
                if( !c.isDirectory() ) continue;
                final String n = c.getName();
                if( n.startsWith( "$" ) || n.startsWith( "." ) ) continue;
                final long sz = cachedFolderSize( c, deadline );
                if( sz <= 0 ) continue;
                out.add( new Entry( c.getAbsolutePath(), sz, c.lastModified() ) );
                sized++;
            }
        }
        return out;
    }

    /** Like formatBlock but labels folders (which carry a real total size here, unlike the
     *  size=-1 dirs elsewhere) and frames the items as suggestions, never as done deletions. */
    private static String deletionFormat( final List<Entry> matches, final String title )
    {
        final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat( "yyyy-MM-dd" );
        final StringBuilder sb = new StringBuilder( "\n\n--- " + title + " ---" );
        for( final Entry e : matches )
        {
            final boolean isDir = new File( e.path ).isDirectory();
            sb.append( "\n- " ).append( e.path )
              .append( " (" ).append( isDir ? "folder, " : "" )
              .append( humanSize( Math.max( 0, e.size ) ) )
              .append( ", modified " ).append( sdf.format( new java.util.Date( e.mtime ) ) )
              .append( ")" );
        }
        sb.append( "\n--- END ---" )
          .append( "\nSuggest one or two of these to delete, with the full path and size. "
                 + "These are candidates only -- never imply they are already deleted." );
        return sb.toString();
    }

    private static List<Entry> liveWalk( final List<File> roots )
    {
        final List<Entry> out = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + AGG_BUDGET_MS;
        for( final File r : roots ) aggWalk( r, 0, deadline, out );
        return out;
    }

    private static void aggWalk( final File dir, final int level,
                                 final long deadline, final List<Entry> out )
    {
        if( out.size() >= AGG_MAX_FILES || System.currentTimeMillis() > deadline ) return;
        final File[] kids = dir.listFiles();
        if( kids == null ) return;
        for( final File f : kids )
        {
            if( out.size() >= AGG_MAX_FILES ) return;
            final String n = f.getName();
            if( n.isEmpty() || n.startsWith( "$" ) || n.startsWith( "." ) ) continue;
            if( f.isDirectory() )
            {
                if( isReparsePoint( f ) ) continue;
                if( level + 1 < AGG_MAX_DEPTH ) aggWalk( f, level + 1, deadline, out );
            }
            else out.add( new Entry( f.getAbsolutePath(), f.length(), f.lastModified() ) );
        }
    }

    private static List<Entry> indexFilesOnly()
    {
        final List<Entry> out = new ArrayList<>();
        for( final Entry e : entries ) if( e.size >= 0 ) out.add( e );
        return out;
    }

    private static List<Entry> topN( final List<Entry> list )
    {
        return new ArrayList<>( list.subList( 0, Math.min( MAX_RESULTS, list.size() ) ) );
    }

    private static String statBlock( final String line )
    {
        return "\n\n--- DRIVE STATS (computed for the user's question) ---\n- " + line
             + "\n--- END STATS ---\nState this figure directly in your answer.";
    }

    // ── Semantic search ────────────────────────────────────────────────────────

    private static boolean isSemanticOn()
    {
        return "true".equalsIgnoreCase( getProperty( "DriveSemanticEnabled", "true" ) );
    }

    private static String semanticModel()
    {
        return getProperty( "DriveSemanticModel", DEFAULT_SEMANTIC_MODEL ).trim();
    }

    private static double semanticThreshold()
    {
        try { return Double.parseDouble(
                getProperty( "DriveSemanticThreshold", String.valueOf( DEFAULT_THRESHOLD ) ).trim() ); }
        catch( final Exception e ) { return DEFAULT_THRESHOLD; }
    }

    /** A file (not a directory) whose extension is in the media/document whitelist. */
    private static boolean eligible( final Entry e )
    {
        if( e.size < 0 ) return false;
        final int dot = e.pathLower.lastIndexOf( '.' );
        final int sep = e.pathLower.lastIndexOf( '\\' );
        if( dot < 0 || dot < sep ) return false; // no extension (dot must follow the last separator)
        return MEDIA_DOC_EXTS.contains( e.pathLower.substring( dot + 1 ) );
    }

    /** Text handed to the embedder for one file: parent folder + humanised filename,
     *  with nomic-embed-text's document task prefix. The folder adds light topical
     *  context ("Downloads / trip to maui") without embedding the whole path. */
    private static String docText( final Entry e )
    {
        final int sep = e.path.lastIndexOf( '\\' );
        final String name = sep >= 0 ? e.path.substring( sep + 1 ) : e.path;
        String parent = "";
        if( sep > 0 )
        {
            final int sep2 = e.path.lastIndexOf( '\\', sep - 1 );
            parent = e.path.substring( sep2 + 1, sep );
        }
        final String human = humanizeName( name );
        return "search_document: " + ( parent.isEmpty() ? human : parent + " / " + human );
    }

    /** Strip the extension and turn separators into spaces so the embedder sees words
     *  ("trip_to-maui.mp4" -> "trip to maui") rather than one run-on token. */
    private static String humanizeName( final String fileName )
    {
        final int dot = fileName.lastIndexOf( '.' );
        String base = dot > 0 ? fileName.substring( 0, dot ) : fileName;
        base = base.replace( '_', ' ' ).replace( '-', ' ' ).replace( '.', ' ' );
        return base.trim();
    }

    /**
     * Embed eligible media/document files that aren't already cached at their current
     * mtime. Budgeted (EMBED_BUDGET_MS) so a first run on a large drive spreads over
     * several cycles instead of blocking. Prunes vectors for files that dropped out of
     * the most-recent MAX_EMBED set. A null embed result (model not pulled / Ollama
     * down) ends the pass quietly -- keyword retrieval is unaffected.
     */
    private static void runEmbedPass()
    {
        if( !embedStoreLoaded ) loadEmbeddings();
        final List<Entry> snapshot = entries;
        if( snapshot.isEmpty() ) return;

        // Eligible files, newest first, capped.
        final List<Entry> elig = new ArrayList<>();
        for( final Entry e : snapshot ) if( eligible( e ) ) elig.add( e );
        elig.sort( ( a, b ) -> Long.compare( b.mtime, a.mtime ) );
        final int cap = Math.min( elig.size(), MAX_EMBED );

        // Prune vectors no longer in the eligible top set (deleted/aged-out files).
        final Set<String> keep = new HashSet<>();
        for( int i = 0; i < cap; i++ ) keep.add( elig.get( i ).path );
        boolean changed = embedStore.keySet().retainAll( keep );

        // Files needing (re)embedding: never embedded, or modified since.
        final List<Entry> todo = new ArrayList<>();
        for( int i = 0; i < cap; i++ )
        {
            final Entry e = elig.get( i );
            final EmbeddedVec have = embedStore.get( e.path );
            if( have == null || have.mtime != e.mtime ) todo.add( e );
        }
        if( todo.isEmpty() )
        {
            if( changed ) saveEmbeddings();
            return;
        }

        final String model    = semanticModel();
        final long   deadline = System.currentTimeMillis() + EMBED_BUDGET_MS;
        int embedded = 0;
        for( int start = 0; start < todo.size(); start += EMBED_BATCH )
        {
            if( System.currentTimeMillis() > deadline ) break;
            final int end = Math.min( start + EMBED_BATCH, todo.size() );
            final List<Entry> batch = todo.subList( start, end );
            final List<String> texts = new ArrayList<>( batch.size() );
            for( final Entry e : batch ) texts.add( docText( e ) );

            final float[][] vecs = OllamaEmbeddings.embed( model, texts );
            if( vecs == null )
            {
                log.info( "[DriveSemantic] Embedding unavailable (model=" + model
                    + "); keyword retrieval only. Pull it with: ollama pull " + model );
                break;
            }
            for( int j = 0; j < batch.size() && j < vecs.length; j++ )
            {
                final Entry e = batch.get( j );
                embedStore.put( e.path, new EmbeddedVec( e.mtime, e.size, vecs[ j ] ) );
                embedded++;
            }
            changed = true;
        }
        if( changed ) saveEmbeddings();
        if( embedded > 0 )
            log.info( "[DriveSemantic] Embedded " + embedded + " file(s); store size "
                + embedStore.size() + ( embedded < todo.size()
                    ? " (" + ( todo.size() - embedded ) + " queued for next cycle)" : "" ) );
    }

    /** Cosine-similarity retrieval over cached embeddings. Returns up to `limit`
     *  entries above the threshold, best first, excluding paths already supplied by
     *  the keyword retriever. Empty when semantic is off, the store is empty, or the
     *  query embed fails. */
    private static List<Entry> semanticMatches( final String userText,
                                                final Set<String> exclude, final int limit )
    {
        if( limit <= 0 || !isSemanticOn() || embedStore.isEmpty() )
            return java.util.Collections.emptyList();

        final float[][] q = OllamaEmbeddings.embed( semanticModel(),
            java.util.Collections.singletonList( "search_query: " + userText ) );
        if( q == null || q.length == 0 || q[ 0 ] == null )
            return java.util.Collections.emptyList();

        final float[] qv        = q[ 0 ];
        final double  threshold = semanticThreshold();
        final Entry[]  best = new Entry[ limit ];
        final double[] sims = new double[ limit ];
        for( final java.util.Map.Entry<String, EmbeddedVec> en : embedStore.entrySet() )
        {
            if( exclude.contains( en.getKey() ) ) continue;
            final double sim = OllamaEmbeddings.cosine( qv, en.getValue().vec );
            if( sim < threshold ) continue;
            for( int i = 0; i < limit; i++ )
            {
                if( sim > sims[ i ] )
                {
                    System.arraycopy( sims, i, sims, i + 1, limit - i - 1 );
                    System.arraycopy( best, i, best, i + 1, limit - i - 1 );
                    sims[ i ] = sim;
                    best[ i ] = new Entry( en.getKey(), en.getValue().size, en.getValue().mtime );
                    break;
                }
            }
        }
        final List<Entry> out = new ArrayList<>();
        for( int i = 0; i < limit && best[ i ] != null; i++ )
        {
            out.add( best[ i ] );
            if( log.isLoggable( java.util.logging.Level.FINE ) )
                log.fine( "[DriveSemantic] match sim="
                    + String.format( Locale.ROOT, "%.3f", sims[ i ] ) + " " + best[ i ].path );
        }
        return out;
    }

    private static void saveEmbeddings()
    {
        try( final BufferedWriter w = new BufferedWriter( new OutputStreamWriter(
                new FileOutputStream( EMBED_FILE ), "UTF-8" ) ) )
        {
            for( final java.util.Map.Entry<String, EmbeddedVec> en : embedStore.entrySet() )
            {
                final EmbeddedVec v = en.getValue();
                // mtime|size|base64-floats|path  ('|' is illegal in Windows paths)
                w.write( v.mtime + "|" + v.size + "|" + encodeVec( v.vec ) + "|" + en.getKey() );
                w.newLine();
            }
        }
        catch( final Exception e )
        {
            log.warning( "[DriveSemantic] Embedding save failed: " + e.getMessage() );
        }
    }

    private static void loadEmbeddings()
    {
        embedStoreLoaded = true;
        if( !EMBED_FILE.exists() ) return;
        try( final BufferedReader r = new BufferedReader( new InputStreamReader(
                new FileInputStream( EMBED_FILE ), "UTF-8" ) ) )
        {
            String line;
            while( ( line = r.readLine() ) != null )
            {
                final int p1 = line.indexOf( '|' );
                final int p2 = line.indexOf( '|', p1 + 1 );
                final int p3 = line.indexOf( '|', p2 + 1 );
                if( p1 < 0 || p2 < 0 || p3 < 0 ) continue;
                try
                {
                    final long    mtime = Long.parseLong( line.substring( 0, p1 ) );
                    final long    size  = Long.parseLong( line.substring( p1 + 1, p2 ) );
                    final float[] vec   = decodeVec( line.substring( p2 + 1, p3 ) );
                    final String  path  = line.substring( p3 + 1 );
                    if( vec != null ) embedStore.put( path, new EmbeddedVec( mtime, size, vec ) );
                }
                catch( final Exception ignored ) {}
            }
            log.info( "[DriveSemantic] Loaded " + embedStore.size() + " embeddings from " + EMBED_FILE );
        }
        catch( final Exception e )
        {
            log.warning( "[DriveSemantic] Embedding load failed: " + e.getMessage() );
        }
    }

    /** Vector <-> compact base64 of its IEEE-754 float bytes (exact, ~4 KB per 768-dim). */
    private static String encodeVec( final float[] v )
    {
        final java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate( v.length * 4 );
        for( final float f : v ) bb.putFloat( f );
        return java.util.Base64.getEncoder().encodeToString( bb.array() );
    }

    private static float[] decodeVec( final String b64 )
    {
        try
        {
            final byte[] bytes = java.util.Base64.getDecoder().decode( b64 );
            final java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap( bytes );
            final float[] v = new float[ bytes.length / 4 ];
            for( int i = 0; i < v.length; i++ ) v[ i ] = bb.getFloat();
            return v;
        }
        catch( final Exception e ) { return null; }
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
        if( bytes >= 1L << 40 ) return String.format( Locale.ROOT, "%.2f TB", bytes / (double)( 1L << 40 ) );
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
