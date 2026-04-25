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
 *  - tick() calls WindowsEnvironment.beginTick() once per tick so the shared
 *    EnumWindows scan runs exactly once regardless of mascot count.
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
		Mascot best = null;
		double bestDist = Double.MAX_VALUE;

		synchronized (this.getMascots()) {
			for (Mascot o : this.getMascots()) {
				if (o == self || !o.getAffordances().contains(affordance)) continue;

				int dx = Math.abs(o.getAnchor().x - self.getAnchor().x);
				int dy = self.getAnchor().y - o.getAnchor().y; // Up is positive

				if (dx <= maxDx && dy > 0 && dy <= maxDy) {
					if (dx < bestDist) {
						bestDist = dx;
						best = o;
					}
				}
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

	private final List<Mascot> mascots = new ArrayList<Mascot>();

	/**
	 * Lock-free snapshot rebuilt only when mascots are added or removed.
	 * Safe to read from any thread without holding the mascots lock.
	 */
	private volatile List<Mascot> mascotListSnapshot = java.util.Collections.emptyList();

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
		synchronized (this.getMascots()) {

			// ── Run the shared EnumWindows scan ONCE before any mascot ticks ──
			// This is the key to the shared-scan optimisation: beginTick() populates
			// WindowsEnvironment's static snapshot so all mascots' tickForMascot()
			// calls read from it rather than each running their own EnumWindows.
			com.group_finity.mascot.win.WindowsEnvironment.beginTick();

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

			// ── Tick + apply in a single pass ─────────────────────────────────
			for (final Mascot mascot : this.getMascots()) {
				try {
					mascot.getEnvironment().tick();
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

					mascot.apply();
				} catch (final Throwable t) {
					log.log( java.util.logging.Level.SEVERE, "Mascot tick error, disposing: " + mascot, t );
					try { mascot.dispose(); } catch( Throwable ignored ) {}
				}
			}
		}

		if (isExitOnLastRemoved()) {
			if (this.getMascots().size() == 0) {
				Main.getInstance().exit();
			}
		}
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
		synchronized( getMascots( ) )
		{
			if( imageSet == null )
				return getMascots( ).size( );
			int count = 0;
			for( int index = 0; index < getMascots( ).size( ); index++ )
			{
				Mascot m = getMascots( ).get( index );
				if( m.getImageSet( ).equals( imageSet ) ) count++;
			}
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
