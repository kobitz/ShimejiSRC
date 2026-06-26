package com.group_finity.mascot;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.group_finity.mascot.config.Configuration;
import com.group_finity.mascot.exception.BehaviorInstantiationException;
import com.group_finity.mascot.exception.CantBeAliveException;
import java.awt.Point;

/**
 * Maintains a list of mascots and drives the tick loop.
 *
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 *
 * Changes:
 *  - The shared EnumWindows scan runs once regardless of mascot count, and (June 2026)
 *    on its own WindowScanner daemon thread — tick() only calls ensureScanner() and
 *    reads the published snapshot, so a slow scan can't stall the tick.
 *  - mascot.tick() and mascot.apply() merged into one list pass (was two loops).
 *  - mascotListSnapshot only rebuilt when the mascot list actually changes.
 *  - Hotkey hold-to-loop: HotkeyManager tracks held keys; Manager.tick() re-fires
 *    held behaviors each tick when the mascot's current behavior has finished,
 *    producing a smooth loop rather than replaying the start animation repeatedly.
 */
public class Manager {
		/**
	 * Finds the nearest mascot with a specific affordance within a range.
	 * Useful for Mario finding blocks to hit.
	 */
	public Mascot getNearestAffordance(Mascot self, String affordance, int maxDx, int maxDy) {
		// Read the per-tick affordance bucket instead of scanning + locking the whole
		// mascot list on every call. Usually only a handful of mascots broadcast a
		// given affordance, so this is O(bucket) rather than O(N). Called on the tick
		// thread after the index is built, so no lock is needed for the snapshot read.
		final java.util.Map<String,List<Mascot>> idx = affordanceIndex;
		final List<Mascot> candidates = ( idx != null ) ? idx.get( affordance ) : null;
		if( candidates == null ) return null;

		Mascot best = null;
		double bestDist = Double.MAX_VALUE;
		for( int i = 0; i < candidates.size(); i++ ) {
			final Mascot o = candidates.get( i );
			if( o == self ) continue;

			int dx = Math.abs(o.getAnchor().x - self.getAnchor().x);
			int dy = self.getAnchor().y - o.getAnchor().y; // Up is positive

			if (dx <= maxDx && dy > 0 && dy <= maxDy && dx < bestDist) {
				bestDist = dx;
				best = o;
			}
		}
		return best;
	}

	/**
	 * Triggers a behavior on another mascot (e.g., Mario hitting a Question Block).
	 */
	public void triggerBehavior(Mascot target, String behaviorName) {
		try {
			com.group_finity.mascot.config.Configuration cfg = 
				Main.getInstance().getConfiguration(target.getImageSet());
			target.setBehavior(cfg.buildBehavior(behaviorName, target));
		} catch (Exception e) {
			log.log(Level.WARNING, "Remote trigger failed", e);
		}
	}

	private static final Logger log = Logger.getLogger(Manager.class.getName());

	/** Interval between ticks in milliseconds. */
	public static final int TICK_INTERVAL = 40;

	/**
	 * Global tick counter incremented once per Manager tick.
	 * Used by SyncedStay and AffordanceStay to keep all instances
	 * in perfect animation phase lock — immune to wall-clock drift,
	 * pause/resume, and action resets.
	 */
	public static final java.util.concurrent.atomic.AtomicLong globalSyncTick =
		new java.util.concurrent.atomic.AtomicLong( 0L );

	private final List<Mascot> mascots = new ArrayList<Mascot>();

	/**
	 * Lock-free snapshot rebuilt only when mascots are added or removed.
	 * Safe to read from any thread without holding the mascots lock.
	 */
	private volatile List<Mascot> mascotListSnapshot = java.util.Collections.emptyList();

	/**
	 * Per-tick shared indices, rebuilt once at the top of every tick (O(N)) so
	 * per-mascot lookups don't each re-scan the whole list (O(N^2) across a large
	 * colony). {@code imageSetCounts} backs {@link #getCount(String)} (= mascot.count,
	 * used in breed/hunt script conditions); {@code affordanceIndex} backs
	 * {@link #getNearestAffordance}. Replaced by reference each tick (readers see a
	 * complete prior-or-current snapshot, never a partial one).
	 */
	private volatile java.util.Map<String,Integer> imageSetCounts =
		java.util.Collections.emptyMap();
	private volatile java.util.Map<String,List<Mascot>> affordanceIndex =
		java.util.Collections.emptyMap();

	private final Set<Mascot> added   = new LinkedHashSet<Mascot>();
	private final Set<Mascot> removed = new LinkedHashSet<Mascot>();

	private boolean exitOnLastRemoved = true;

	private Thread thread;

	public void setExitOnLastRemoved(boolean exitOnLastRemoved) {
		this.exitOnLastRemoved = exitOnLastRemoved;
	}

	public boolean isExitOnLastRemoved() {
		return exitOnLastRemoved;
	}

	public Manager() { }

	public void start() {
		if ( thread!=null && thread.isAlive() ) {
			return;
		}

		thread = new Thread() {
			@Override
			public void run() {
				long nextTick = System.nanoTime();
				try {
					for (;;) {
						final long now = System.nanoTime();
						final long nanosUntilTick = nextTick - now;
						if (nanosUntilTick > 0) {
							// Sleep most of the wait, wake 1ms early to spin-correct
							final long sleepMillis = (nanosUntilTick - 1_000_000L) / 1_000_000L;
							if (sleepMillis > 0) {
								Thread.sleep(sleepMillis);
							}
							// Spin the final ~1ms for precise wakeup
							while (System.nanoTime() < nextTick) {
								Thread.onSpinWait();
							}
						}
						nextTick += TICK_INTERVAL * 1_000_000L;
						// If more than one full interval behind (e.g. after a GC pause),
						// reset so we don't burst-catch-up with a pile of ticks
						if (System.nanoTime() - nextTick > TICK_INTERVAL * 1_000_000L) {
							nextTick = System.nanoTime();
						}
						tick();
					}
				} catch (final InterruptedException e) {
				} catch (final Throwable e) {
					log.log( java.util.logging.Level.SEVERE, "=== MANAGER THREAD KILLED BY UNCAUGHT EXCEPTION ===", e );
					try {
						java.io.PrintWriter pw = new java.io.PrintWriter( new java.io.FileWriter( new java.io.File("shimeji_watchdog.txt").getAbsoluteFile(), true ) );
						pw.println( new java.util.Date() + " MANAGER KILLED: " + e );
						e.printStackTrace( pw );
						pw.flush(); pw.close();
					} catch( Exception ignored ) {}
				}
			}
		};
		thread.setName("ManagerThread");
		thread.setDaemon(false);
		thread.start();
	}

	public void stop() {
		if ( thread==null || !thread.isAlive() ) {
			return;
		}
		thread.interrupt();
		try {
			thread.join();
		} catch (InterruptedException e) {
		}
	}

	private void tick( )
	{
		// ── Tick watchdog ────────────────────────────────────────────────────
		// Started before the lock so contention (e.g. EDT holding the mascots
		// lock during a menu) shows up as total time unaccounted for by phases.
		final long tickStartNs = System.nanoTime();
		long envScanNs = 0;
		long mascotsTotalNs = 0;
		long slowestMascotNs = 0;
		String slowestMascotName = null;
		// Phase split of the single slowest mascot's tick — names what the per-mascot
		// cost actually is (window lookup vs behavior/script vs render) at high counts.
		long slowestEnvNs = 0, slowestTickNs = 0, slowestApplyNs = 0;
		int mascotCount = 0;

		synchronized (this.getMascots()) {

			// ── Run the shared EnumWindows scan ONCE before any mascot ticks ──
			// This is the key to the shared-scan optimisation: beginTick() populates
			// WindowsEnvironment's static snapshot so all mascots' tickForMascot()
			// calls read from it rather than each running their own EnumWindows.
			// Advance global sync counter once per tick for SyncedStay/AffordanceStay phase lock
			globalSyncTick.incrementAndGet();
			final long envStartNs = System.nanoTime();
			// The EnumWindows scan now runs on its own WindowScanner daemon (decoupled
			// June 2026) — a slow scan under load can't stall the tick. We just ensure it's
			// running (idempotent) and read its published snapshot below; envScan ~0 now.
			com.group_finity.mascot.win.WindowsEnvironment.ensureScanner();
			envScanNs = System.nanoTime() - envStartNs;

			// ── Flush add/remove queues ───────────────────────────────────────
			boolean listChanged = !this.getAdded().isEmpty() || !this.getRemoved().isEmpty();
			for (final Mascot mascot : this.getAdded()) {
				this.getMascots().add(mascot);
			}
			this.getAdded().clear();

			for (final Mascot mascot : this.getRemoved()) {
				this.getMascots().remove(mascot);
			}
			this.getRemoved().clear();

			// Rebuild snapshot only when the list actually changed — saves one
			// ArrayList copy + unmodifiableList wrapper every 40ms during normal play.
			if( listChanged ) {
				mascotListSnapshot = java.util.Collections.unmodifiableList(
					new java.util.ArrayList<>( this.getMascots() ) );
			}

			// ── Build shared per-tick indices ONCE (O(N)) ────────────────────
			// Population counts (mascot.count in breed/hunt scripts) and affordance
			// buckets (getNearestAffordance) were each re-scanning the whole list per
			// call -> O(N^2) across the colony. Computing them here once makes those
			// O(1)/O(bucket), which is what lets the population scale.
			{
				final java.util.HashMap<String,Integer> counts = new java.util.HashMap<>();
				final java.util.HashMap<String,List<Mascot>> affs = new java.util.HashMap<>();
				for( final Mascot m : this.getMascots() )
				{
					counts.merge( m.getImageSet( ), 1, Integer::sum );
					final java.util.ArrayList<String> mAff = m.getAffordances( );
					for( int a = 0; a < mAff.size( ); a++ )
						affs.computeIfAbsent( mAff.get( a ), k -> new ArrayList<>() ).add( m );
				}
				imageSetCounts  = counts;
				affordanceIndex = affs;
			}

			// Affordance can come and go without the mascot list changing (a mascot
			// starts/stops broadcasting it as its behavior changes), so check every tick.
			// FanController de-dupes, so this only spawns a WMI call on an actual transition.
			checkCoolerBoostState( );

			// ── Tick + apply in a single pass ─────────────────────────────────
			for (final Mascot mascot : this.getMascots()) {
				mascotCount++;
				final long mascotStartNs = System.nanoTime();
				long envPhaseNs = 0, tickPhaseNs = 0, applyPhaseNs = 0;
				try {
					final long pe0 = System.nanoTime();
					mascot.getEnvironment().tick();
					envPhaseNs = System.nanoTime() - pe0;
					final long pt0 = System.nanoTime();
					mascot.tick();

					// ── Hotkey hold-to-loop ──────────────────────────────────────
					// After mascot.tick(), UserBehavior may have just called
					// buildNextBehavior() (transitioning to Fall/Idle/etc).
					//
					// For !hold bindings on Sequence behaviors:
					//   - First press: play the whole Sequence once (all steps).
					//   - While held: when the Sequence ends, re-fire ONLY its last
					//     child action as a looping single-step behavior, so the mascot
					//     stays at full speed without replaying the ramp-up.
					//   - On release: the last-step behavior ends naturally and fires
					//     its own NextBehavior (e.g. Run → DashBrake).
					//
					// The "last step loop" behavior name is stored in _holdLastStep.
					final String heldBehavior =
						HotkeyManager.getInstance().getHeldBehaviorFor( mascot.getImageSet() );
					if( heldBehavior != null )
					{
						final String currentName = mascot.getCurrentBehaviorName();
						final String lastStepBehavior = "_holdLoop_" + heldBehavior;

						// Are we on the intro, the last-step loop, or something else?
						final boolean onIntro    = heldBehavior.equals( currentName );
						final boolean onLastStep = lastStepBehavior.equals( currentName );

						if( !onIntro && !onLastStep )
						{
							// Behavior just transitioned away from the hold chain.
							// Build a synthetic last-step behavior if we can.
							try {
								com.group_finity.mascot.config.Configuration cfg =
									Main.getInstance().getConfiguration( mascot.getImageSet() );

								// Only interrupt if we JUST came from the intro or loop —
								// prevents hijacking Fall/Thrown/etc that Mario drifted into.
								// Also skip if currently in a physics state (Fall/Thrown/Dragged) —
								// these happen when the looped action had no valid branch (e.g.
								// ManualJump Select mid-air) and keeps firing fireNextBehavior->Fall
								// every tick. Let the physics state run until Mario lands.
								final String prevName = mascot.getPreviousBehaviorName();
								final boolean cameFromHoldChain =
									heldBehavior.equals( prevName ) || lastStepBehavior.equals( prevName );
								final boolean inPhysicsState =
									com.group_finity.mascot.behavior.UserBehavior.BEHAVIOURNAME_FALL.equals( currentName )
									|| com.group_finity.mascot.behavior.UserBehavior.BEHAVIOURNAME_THROWN.equals( currentName )
									|| com.group_finity.mascot.behavior.UserBehavior.BEHAVIOURNAME_DRAGGED.equals( currentName );
								// Directional nudge while falling: directly steer the running Fall
								// action's velocityX without interrupting it. This changes Mario's
								// horizontal trajectory every tick the key is held.
								if( inPhysicsState
										&& ( com.group_finity.mascot.behavior.UserBehavior.BEHAVIOURNAME_FALL.equals( currentName )
										  || com.group_finity.mascot.behavior.UserBehavior.BEHAVIOURNAME_THROWN.equals( currentName ) )
										&& !mascot.isJumping()
										&& ( heldBehavior.toLowerCase().contains( "left" ) || heldBehavior.toLowerCase().contains( "right" ) ) )
								{
									// Find the active Fall action and steer it
									if( mascot.getBehavior() instanceof com.group_finity.mascot.behavior.UserBehavior )
									{
										com.group_finity.mascot.action.Action act =
											( (com.group_finity.mascot.behavior.UserBehavior) mascot.getBehavior() ).getAction();
										// Walk the action tree to find the active Fall leaf
										while( act instanceof com.group_finity.mascot.action.ComplexAction )
											act = ( (com.group_finity.mascot.action.ComplexAction) act ).getCurrentChildAction();
										if( act instanceof com.group_finity.mascot.action.Fall )
										{
											final double nudgeVX = heldBehavior.toLowerCase().contains( "left" ) ? -5.0 : 5.0;
											( (com.group_finity.mascot.action.Fall) act ).setVelocityX( nudgeVX );
										}
									}
								}
								else if( cameFromHoldChain && !inPhysicsState
										&& mascot.isCurrentActionInterruptable() && !mascot.isJumping()
										&& cfg.getBehaviorBuilders().containsKey( heldBehavior ) )
								{
									// Try to get the last child of the held behavior's action
									com.group_finity.mascot.config.ActionBuilder ab =
										cfg.getActionBuilderFor( heldBehavior );
									com.group_finity.mascot.config.IActionBuilder lastChild =
										( ab != null ) ? ab.getLastChildBuilder() : null;

									if( lastChild != null )
									{
										// Build a minimal Sequence wrapping just the last child action,
										// registered ephemerally so UserBehavior can run it.
										// We reuse the held behavior's NextBehaviorList by wrapping in
										// a synthetic UserBehavior that delegates next-behavior to the
										// held behavior's configured NextBehaviorList.
										// Bake parent Affordance into the action's params so getAffordance()
										// returns it on every next() call, not just on init().
										// Without this, ActionBase.next() clears affordances every tick.
										String parentAffordance = ( ab != null )
											? ab.getParams().getOrDefault( "Affordance", "" )
											: "";
										java.util.Map<String,String> actionParams = new java.util.HashMap<>();
										if( !parentAffordance.isEmpty() )
											actionParams.put( "Affordance", parentAffordance );
										com.group_finity.mascot.action.Action lastAction =
											lastChild.buildAction( actionParams );
										com.group_finity.mascot.behavior.Behavior loopBehavior =
											new com.group_finity.mascot.behavior.HoldLastStepBehavior(
												lastStepBehavior, lastAction, cfg, heldBehavior, parentAffordance );
										mascot.setBehavior( loopBehavior );
									}
									else if( cfg.getBehaviorBuilders().containsKey( heldBehavior ) )
									{
										// No child actions (e.g. atomic action) — just re-fire the whole behavior
										mascot.setUserData( "_holdIntroPlayed", null );
										mascot.setBehavior( cfg.buildBehavior( heldBehavior, mascot ) );
									}
								}
							} catch( Exception ex ) {
								log.log( java.util.logging.Level.WARNING, "Hold-loop last-step failed", ex );
							}
						}
						// else: still on intro or last-step loop — let it keep running
					}
					else
					{
						// Key released — clear any hold state
						mascot.setUserData( "_holdIntroPlayed", null );
					}

					tickPhaseNs = System.nanoTime() - pt0;   // mascot.tick() + hotkey hold logic
					final long pa0 = System.nanoTime();
					mascot.apply();
					applyPhaseNs = System.nanoTime() - pa0;
				} catch (final Throwable t) {
					log.log( java.util.logging.Level.SEVERE, "Mascot tick error, disposing: " + mascot, t );
					try { mascot.dispose(); } catch( Throwable ignored ) {}
				}
				final long mascotNs = System.nanoTime() - mascotStartNs;
				mascotsTotalNs += mascotNs;
				if (mascotNs > slowestMascotNs) {
					slowestMascotNs = mascotNs;
					slowestMascotName = mascot.getImageSet();
					slowestEnvNs   = envPhaseNs;
					slowestTickNs  = tickPhaseNs;
					slowestApplyNs = applyPhaseNs;
				}
			}
		}

		// ── Tick watchdog report ─────────────────────────────────────────────
		// 250ms (raised from 200, from an original 120) — sub-250ms blips are
		// accepted residue from a large mascot population + envScan, not the
		// actionable "huge spike" we care about (the gemma4 generation
		// starvation events run 400ms-1s). Logged per slow tick — a 1s crawl
		// yields a handful of lines, which is the point: the log names the phase.
		//
		// Phase breakdown (each a measured sub-total, so they sum toward total):
		//   envScan   = the in-tick window-scan cost, now ~0 (the EnumWindows walk runs on
		//               the WindowScanner daemon, off-tick); enum= is that background scan's
		//               last native walk (informational — no longer part of the tick).
		//   mascotsTot= sum of ALL mascots' tick time (not just the slowest) — the true
		//               population cost. slowestMascot names the single worst one.
		//   unattr    = total - envScan - mascotsTot = EDT lock-wait (menu/bubble holding
		//               the mascots lock) or GC. The previously-opaque black box.
		// slowestMascot also carries a us-level split of that one mascot's cost:
		//   env=  per-mascot getEnvironment().tick() (window lookup)
		//   tick= mascot.tick() + hotkey hold logic (behavior/script eval)
		//   apply=mascot.apply() (render/cache).
		final long totalMs = (System.nanoTime() - tickStartNs) / 1_000_000L;
		if (totalMs >= 250) {
			double cpu = -1, gpu = -1;
			try {
				final com.group_finity.mascot.environment.CpuTempMonitor mon =
					com.group_finity.mascot.environment.CpuTempMonitor.getInstance();
				cpu = mon.getCpuLoad();
				gpu = mon.getGpuLoad();
			} catch (final Exception ignored) {}
			final long envScanMs = envScanNs / 1_000_000L;
			final long enumMs = com.group_finity.mascot.win.WindowsEnvironment.lastEnumWindowsNs / 1_000_000L;
			final long mascotsTotMs = mascotsTotalNs / 1_000_000L;
			final long unattrMs = Math.max( 0, totalMs - envScanMs - mascotsTotMs );
			final Runtime rt = Runtime.getRuntime();
			log.warning( "[TickWatch] Slow tick: total=" + totalMs + "ms"
				+ " envScan=" + envScanMs + "ms(enum=" + enumMs + "ms)"
				+ " mascotsTot=" + mascotsTotMs + "ms"
				+ " unattr=" + unattrMs + "ms"
				+ " slowestMascot=" + slowestMascotName + "(" + ( slowestMascotNs / 1_000_000L ) + "ms"
					+ " env=" + ( slowestEnvNs / 1000L ) + "us"
					+ " tick=" + ( slowestTickNs / 1000L ) + "us"
					+ " apply=" + ( slowestApplyNs / 1000L ) + "us)"
				+ " mascots=" + mascotCount
				+ " heap=" + ( ( rt.totalMemory() - rt.freeMemory() ) / 1_048_576L ) + "/"
				+ ( rt.totalMemory() / 1_048_576L ) + "MB"
				+ " cpu=" + (int) cpu + "% gpu=" + (int) gpu + "%" );
		}

		if (isExitOnLastRemoved()) {
			if (this.getMascots().size() == 0) {
				Main.getInstance().exit();
			}
		}
	}

	/** Toggle MSI Cooler Boost on/off based on whether any mascot is currently
	 *  broadcasting the "CoolerBoost" affordance. Any image set opts in purely via
	 *  XML -- an Action carrying Affordance="CoolerBoost" (e.g. CampfireON_blue). */
	public static final String COOLER_BOOST_AFFORDANCE = "CoolerBoost";

	private void checkCoolerBoostState( )
	{
		boolean active = false;
		for( Mascot m : this.getMascots( ) )
			if( m.getAffordances( ).contains( COOLER_BOOST_AFFORDANCE ) ) { active = true; break; }

		if( active )
			com.group_finity.mascot.environment.FanController.getInstance( ).triggerFanOn( );
		else
			com.group_finity.mascot.environment.FanController.getInstance( ).triggerFanOff( );
	}

	public void add(final Mascot mascot) {
		synchronized (this.getMascots()) {
			this.getAdded().add(mascot);
			this.getRemoved().remove(mascot);
		}
		mascot.setManager(this);
	}

	public void remove(final Mascot mascot) {
		synchronized (this.getMascots()) {
			this.getAdded().remove(mascot);
			this.getRemoved().add(mascot);
		}
		mascot.setManager(null);
	}

	public void setBehaviorAll(final String name) {
		synchronized (this.getMascots()) {
			for (final Mascot mascot : this.getMascots()) {
				try {
					Configuration configuration = Main.getInstance( ).getConfiguration( mascot.getImageSet( ) );
					mascot.setBehavior( configuration.buildBehavior( configuration.getSchema( ).getString( name ), mascot ) );
				} catch (final BehaviorInstantiationException e) {
					log.log(Level.SEVERE, "Failed to initialize the following actions", e);
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose();
				} catch (final CantBeAliveException e) {
					log.log(Level.SEVERE, "Fatal Error", e);
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose();
				}
			}
		}
	}

	public void setBehaviorAll(final Configuration configuration, final String name, String imageSet) {
		synchronized (this.getMascots()) {
			for (final Mascot mascot : this.getMascots()) {
				try {
					if( mascot.getImageSet().equals(imageSet) ) {
						mascot.setBehavior(configuration.buildBehavior( configuration.getSchema( ).getString( name ), mascot ) );
					}
				} catch (final BehaviorInstantiationException e) {
					log.log(Level.SEVERE, "Failed to initialize the following actions", e);
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose();
				} catch (final CantBeAliveException e) {
					log.log(Level.SEVERE, "Fatal Error", e);
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose();
				}
			}
		}
	}

	public void setBehaviorAllSafe( final String imageSet, final String name )
	{
		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
			{
				if( !mascot.getImageSet( ).equals( imageSet ) ) continue;
				try
				{
					com.group_finity.mascot.config.Configuration configuration =
						Main.getInstance( ).getConfiguration( mascot.getImageSet( ) );
					if( configuration.getBehaviorBuilders( ).containsKey( name ) && mascot.isCurrentActionInterruptable( ) )
						mascot.setBehavior( configuration.buildBehavior( name, mascot ) );
				}
				catch( final com.group_finity.mascot.exception.BehaviorInstantiationException e )
				{
					log.log( Level.SEVERE, "Failed to set hotkey behavior", e );
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose( );
				}
				catch( final com.group_finity.mascot.exception.CantBeAliveException e )
				{
					log.log( Level.SEVERE, "Fatal Error", e );
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose( );
				}
			}
		}
	}

	/**
	 * Fire a behavior by name for all mascots that define it.
	 * Silently skips mascots whose image set doesn't have the named behavior.
	 */
	public void setBehaviorAllSafe( final String name )
	{
		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
			{
				try
				{
					com.group_finity.mascot.config.Configuration configuration =
						Main.getInstance( ).getConfiguration( mascot.getImageSet( ) );
					if( configuration.getBehaviorBuilders( ).containsKey( name ) && mascot.isCurrentActionInterruptable( ) )
						mascot.setBehavior( configuration.buildBehavior( name, mascot ) );
				}
				catch( final com.group_finity.mascot.exception.BehaviorInstantiationException e )
				{
					log.log( Level.SEVERE, "Failed to set hotkey behavior", e );
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose( );
				}
				catch( final com.group_finity.mascot.exception.CantBeAliveException e )
				{
					log.log( Level.SEVERE, "Fatal Error", e );
					Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "FailedSetBehaviourErrorMessage" ), e );
					mascot.dispose( );
				}
			}
		}
	}

	/**
	 * Re-fires a held hotkey behavior on any mascot that matches and whose
	 * current behavior has completed its action (hasNext() == false).
	 * Called by Manager.tick() via HotkeyManager.tickHeldKeys().
	 * Caller must already hold synchronized(getMascots()).
	 */

	/**
	 * Called on !hold key release to immediately cancel the current move/jump action
	 * by re-firing it with zeroed targets (TargetX=anchor.x stops a Move in place;
	 * TargetY=anchor.y cuts a Jump short for short-hops).
	 * Only applies to mascots whose current behavior name matches heldBehaviorName
	 * or the synthetic "_holdLoop_" prefix variant.
	 */
	public void cancelHeldActions( final String imageSet, final String heldBehaviorName )
	{
		synchronized( getMascots() )
		{
			for( final Mascot mascot : getMascots() )
			{
				if( imageSet != null && !mascot.getImageSet().equals( imageSet ) ) continue;

				final String cur = mascot.getCurrentBehaviorName();
				final boolean relevant = heldBehaviorName.equals( cur )
				                      || ( "_holdLoop_" + heldBehaviorName ).equals( cur );
				if( !relevant ) continue;

				try {
					// Get the current running action
					com.group_finity.mascot.behavior.Behavior beh = mascot.getBehavior();
					com.group_finity.mascot.action.Action action = null;
					if( beh instanceof com.group_finity.mascot.behavior.UserBehavior )
						action = ( (com.group_finity.mascot.behavior.UserBehavior) beh ).getAction();
					else if( beh instanceof com.group_finity.mascot.behavior.HoldLastStepBehavior )
						action = ( (com.group_finity.mascot.behavior.HoldLastStepBehavior) beh ).getAction();

					// Drill into Sequence/Select to find the leaf action
					while( action instanceof com.group_finity.mascot.action.ComplexAction ) {
						com.group_finity.mascot.action.Action child =
							( (com.group_finity.mascot.action.ComplexAction) action ).getCurrentChildAction();
						if( child == null ) break;
						action = child;
					}

					// Cancel Move by zeroing TargetX; cancel Jump by zeroing TargetY
					if( action instanceof com.group_finity.mascot.action.Move ) {
						( (com.group_finity.mascot.action.Move) action )
							.setTargetX( mascot.getAnchor().x );
					} else if( action instanceof com.group_finity.mascot.action.Jump ) {
						( (com.group_finity.mascot.action.Jump) action )
							.setTargetY( mascot.getAnchor().y );
					}
				} catch( Exception ex ) {
					log.log( java.util.logging.Level.WARNING, "cancelHeldActions failed", ex );
				}
			}
		}
	}

	void reFireBehaviorIfFinished( final String imageSet, final String behaviorName )
	{
		for( final Mascot mascot : this.getMascots( ) )
		{
			if( imageSet != null && !mascot.getImageSet( ).equals( imageSet ) ) continue;
			try
			{
				com.group_finity.mascot.config.Configuration configuration =
					Main.getInstance( ).getConfiguration( mascot.getImageSet( ) );
				if( !configuration.getBehaviorBuilders( ).containsKey( behaviorName ) ) continue;

				// Only re-fire if the current behavior's action is exhausted.
				// This lets the animation complete naturally before looping,
				// rather than snapping back to frame 1 on every OS key-repeat.
				com.group_finity.mascot.behavior.Behavior current = mascot.getBehavior( );
				if( current instanceof com.group_finity.mascot.behavior.UserBehavior )
				{
					com.group_finity.mascot.action.Action action =
						( (com.group_finity.mascot.behavior.UserBehavior) current ).getAction( );
					if( action != null && action.hasNext( ) )
						continue;   // still running — let it finish
				}

				if( mascot.isCurrentActionInterruptable( ) )
					mascot.setBehavior( configuration.buildBehavior( behaviorName, mascot ) );
			}
			catch( final com.group_finity.mascot.exception.BehaviorInstantiationException e )
			{
				log.log( Level.SEVERE, "Failed to re-fire held hotkey behavior", e );
			}
			catch( final com.group_finity.mascot.exception.CantBeAliveException e )
			{
				log.log( Level.SEVERE, "Fatal Error in held hotkey re-fire", e );
				mascot.dispose( );
			}
			catch( final com.group_finity.mascot.exception.VariableException e )
			{
				log.log( Level.SEVERE, "Variable error in held hotkey re-fire", e );
			}
		}
	}

	public void remainOne() {
		synchronized (this.getMascots()) {
			int totalMascots = this.getMascots().size();
			for (int i = totalMascots - 1; i > 0; --i) {
				this.getMascots().get(i).dispose();
			}
		}
	}

	public void remainOne( Mascot mascot )
	{
		synchronized( this.getMascots( ) )
		{
			int totalMascots = this.getMascots( ).size( );
			for( int i = totalMascots - 1; i >= 0; --i )
			{
				if( !this.getMascots( ).get( i ).equals( mascot ) )
					this.getMascots( ).get( i ).dispose( );
			}
		}
	}

	public void remainOne( String imageSet ) {
		synchronized (this.getMascots()) {
			int totalMascots = this.getMascots().size();
			boolean isFirst = true;
			for (int i = totalMascots - 1; i >= 0; --i) {
				Mascot m = this.getMascots().get(i);
				if( m.getImageSet().equals(imageSet) ) {
					if( isFirst ) {
						isFirst = false;
					} else {
						m.dispose();
					}
				}
			}
		}
	}

	public void remainNone( String imageSet )
	{
		synchronized( this.getMascots( ) )
		{
			int totalMascots = this.getMascots( ).size( );
			for( int i = totalMascots - 1; i >= 0; --i )
			{
				Mascot m = this.getMascots( ).get( i );
				if( m.getImageSet( ).equals( imageSet ) )
					m.dispose( );
			}
		}
	}

	public void togglePauseAll( )
	{
		boolean isPaused = true;

		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
			{
				if( !mascot.isPaused( ) ) { isPaused = false; break; }
			}
			for( final Mascot mascot : this.getMascots( ) )
			{
				mascot.setPaused( !isPaused );
			}
		}
	}

	public boolean isPaused( )
	{
		boolean isPaused = true;
		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
			{
				if( !mascot.isPaused( ) ) { isPaused = false; break; }
			}
		}
		return isPaused;
	}

	public int getCount( ) { return getCount( null ); }

	public int getCount( String imageSet )
	{
		if( imageSet == null )
		{
			synchronized( getMascots( ) ) { return getMascots( ).size( ); }
		}
		// Per-tick index: O(1) population lookup (these run inside breed/hunt script
		// conditions, so an O(N) scan per call would be O(N^2) across the colony).
		final java.util.Map<String,Integer> idx = imageSetCounts;
		if( idx != null && !idx.isEmpty( ) )
		{
			final Integer c = idx.get( imageSet );
			return c == null ? 0 : c;   // absent from a populated index => genuinely zero
		}
		// Fallback before the first tick builds the index: live scan.
		synchronized( getMascots( ) )
		{
			int count = 0;
			for( int index = 0; index < getMascots( ).size( ); index++ )
				if( getMascots( ).get( index ).getImageSet( ).equals( imageSet ) ) count++;
			return count;
		}
	}

	public List<Mascot> getMascotList() { return mascotListSnapshot; }

	private List<Mascot>  getMascots() { return this.mascots; }
	private Set<Mascot>   getAdded()   { return this.added;   }
	private Set<Mascot>   getRemoved() { return this.removed; }

	public WeakReference<Mascot> getMascotWithAffordance( String affordance )
	{
		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
				if( mascot.getAffordances( ).contains( affordance ) )
					return new WeakReference<Mascot>( mascot );
		}
		return null;
	}

	public WeakReference<Mascot> getMascotById( int id )
	{
		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
				if( mascot.getId( ) == id )
					return new WeakReference<Mascot>( mascot );
		}
		return null;
	}

	public WeakReference<Mascot> getMascotNearestWithAffordance( String affordance, java.awt.Point anchor )
	{
		synchronized( this.getMascots( ) )
		{
			Mascot nearest = null;
			double nearestDistSq = Double.MAX_VALUE;
			for( final Mascot mascot : this.getMascots( ) )
			{
				if( !mascot.getAffordances( ).contains( affordance ) ) continue;
				double dx = mascot.getAnchor( ).x - anchor.x;
				double dy = mascot.getAnchor( ).y - anchor.y;
				double distSq = dx * dx + dy * dy;
				if( distSq < nearestDistSq ) { nearestDistSq = distSq; nearest = mascot; }
			}
			return nearest != null ? new WeakReference<Mascot>( nearest ) : null;
		}
	}

	public boolean hasOverlappingMascotsAtPoint( Point anchor )
	{
		int count = 0;
		synchronized( this.getMascots( ) )
		{
			for( final Mascot mascot : this.getMascots( ) )
			{
				if( mascot.getAnchor( ).equals( anchor ) ) count++;
				if( count > 1 ) return true;
			}
		}
		return false;
	}

	public void bringToFront( Mascot mascot )
	{
		synchronized( this.getMascots( ) )
		{
			if( this.getMascots( ).remove( mascot ) )
				this.getMascots( ).add( mascot );
		}
	}

	public void disposeAll() {
		synchronized (this.getMascots()) {
			for (int i = this.getMascots().size() - 1; i >= 0; --i) {
				this.getMascots().get(i).dispose();
			}
		}
	}
}
