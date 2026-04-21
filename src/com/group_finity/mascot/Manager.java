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
					// We detect that transition: if a !hold key is held for this
					// mascot's imageSet AND the current behavior name is no longer
					// the held behavior name, reset it to the held behavior.
					// apply() then renders frame 1 of the fresh loop iteration,
					// and next tick it plays through normally to completion again.
					final String heldBehavior =
						HotkeyManager.getInstance().getHeldBehaviorFor( mascot.getImageSet() );
					if( heldBehavior != null )
					{
						final String currentName = mascot.getCurrentBehaviorName();
						if( !heldBehavior.equals( currentName ) )
						{
							// Behavior just transitioned away — loop back to held behavior
							try {
								com.group_finity.mascot.config.Configuration cfg =
									Main.getInstance().getConfiguration( mascot.getImageSet() );
								if( cfg.getBehaviorBuilders().containsKey( heldBehavior ) && mascot.isCurrentActionInterruptable( ) )
									mascot.setBehavior( cfg.buildBehavior( heldBehavior, mascot ) );
							} catch( Exception ex ) {
								log.log( java.util.logging.Level.WARNING, "Hold-loop reset failed", ex );
							}
						}
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
