package com.group_finity.mascot.assistant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Shared registry of recent mascot utterances enabling three tiers of inter-mascot awareness:
 *
 *  1. Passive   - buildContext() injects what peers recently said into any prompt.
 *  2. Reactive  - record() notifies registered listeners; they fire a peer reaction
 *                 after a random delay.
 *  3. Back-and-forth - each peer reaction is itself recorded at chainDepth+1, allowing
 *                      short exchanges that self-limit at MAX_CHAIN_DEPTH.
 */
public class MascotSpeechRegistry
{
    private static final Logger log = Logger.getLogger( MascotSpeechRegistry.class.getName() );

    private static final long   STALE_MS         = 120_000L; // 2 min before entry expires
    private static final int    MAX_CHAIN_DEPTH   = 4;        // max peer-reaction hops
    private static final long   PAIR_COOLDOWN_MS  = 10_000L;  // min gap between same pair
    private static final double[] REACTION_PROB   = { 0.35, 0.25, 0.15, 0.05 }; // per chain depth

    // ── State ─────────────────────────────────────────────────────────────────

    private static final Map<String, Entry>
        recent = new ConcurrentHashMap<>();

    private static final Map<String, java.lang.ref.WeakReference<PeerListener>>
        listeners = new ConcurrentHashMap<>();

    // order-independent pair key -> last reaction timestamp
    private static final Map<String, Long>
        pairCooldown = new ConcurrentHashMap<>();

    private static final java.util.Random rng = new java.util.Random();

    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor( r ->
        {
            final Thread t = new Thread( r, "mascot-peer-reaction" );
            t.setDaemon( true );
            return t;
        });

    // ── Types ─────────────────────────────────────────────────────────────────

    public static class Entry
    {
        public final String mascotName;
        public final String imageSet;
        public final String text;
        public final long   timestamp;

        public Entry( final String mascotName, final String imageSet, final String text )
        {
            this.mascotName = mascotName;
            this.imageSet   = imageSet;
            this.text       = text;
            this.timestamp  = System.currentTimeMillis();
        }
    }

    /**
     * Mascots that want to receive peer-speech notifications implement this and
     * call register(). The callback arrives on the peer-reaction scheduler thread.
     */
    public interface PeerListener
    {
        String getImageSet();
        void onPeerSpeech( String speakerName, String speakerImageSet,
                           String text, int chainDepth );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void register( final PeerListener listener )
    {
        listeners.put( listener.getImageSet(),
            new java.lang.ref.WeakReference<>( listener ) );
        log.fine( "[PeerRegistry] Registered: " + listener.getImageSet() );
    }

    public static void unregister( final String imageSet )
    {
        listeners.remove( imageSet );
        recent.remove( imageSet );
        log.fine( "[PeerRegistry] Unregistered: " + imageSet );
    }

    /**
     * Record an utterance. If triggerPeers is true, eligible peers are notified
     * after a random delay. Pass triggerPeers=false for user-conversation replies
     * so peers do not interrupt an ongoing human<->mascot exchange.
     */
    public static void record( final String imageSet, final String mascotName,
                                final String text, final int chainDepth,
                                final boolean triggerPeers )
    {
        recent.put( imageSet, new Entry( mascotName, imageSet, text ) );

        if( !triggerPeers || chainDepth >= MAX_CHAIN_DEPTH ) return;

        final double prob = chainDepth < REACTION_PROB.length
            ? REACTION_PROB[ chainDepth ] : 0.0;

        for( final Map.Entry<String, java.lang.ref.WeakReference<PeerListener>> e
                : listeners.entrySet() )
        {
            if( e.getKey().equals( imageSet ) ) continue;

            final PeerListener peer = e.getValue().get();
            if( peer == null ) continue;

            if( rng.nextDouble() > prob ) continue;

            final String pairKey = pairKey( imageSet, e.getKey() );
            final Long lastMs = pairCooldown.get( pairKey );
            if( lastMs != null
                    && System.currentTimeMillis() - lastMs < PAIR_COOLDOWN_MS ) continue;
            pairCooldown.put( pairKey, System.currentTimeMillis() );

            final int delayMs = 4_000 + rng.nextInt( 14_000 ); // 4-18 s
            final String nm   = mascotName;
            final String tx   = text;
            final int depth   = chainDepth;

            scheduler.schedule(
                () -> peer.onPeerSpeech( nm, imageSet, tx, depth + 1 ),
                delayMs, TimeUnit.MILLISECONDS );
        }
    }

    /** Convenience: always triggers peers. */
    public static void record( final String imageSet, final String mascotName,
                                final String text, final int chainDepth )
    {
        record( imageSet, mascotName, text, chainDepth, true );
    }

    /**
     * Returns a prompt context block listing what other mascots said recently.
     * Empty string if no peers have spoken within STALE_MS.
     */
    public static String buildContext( final String excludeImageSet )
    {
        final long now = System.currentTimeMillis();
        final StringBuilder sb = new StringBuilder();
        for( final Entry e : recent.values() )
        {
            if( e.imageSet.equals( excludeImageSet ) ) continue;
            if( now - e.timestamp > STALE_MS ) continue;
            sb.append( "\n[" ).append( e.mascotName )
              .append( " recently said: \"" ).append( e.text ).append( "\"]" );
        }
        return sb.toString();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static String pairKey( final String a, final String b )
    {
        return a.compareTo( b ) < 0 ? a + "|" + b : b + "|" + a;
    }
}
