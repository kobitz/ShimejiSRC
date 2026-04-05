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
 * 
 * Maintains a list of mascot, the object to time.
 * 
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public class Manager {

	private static final Logger log = Logger.getLogger(Manager.class.getName());

	/**
	* Interval timer is running.
	*/
	public static final int TICK_INTERVAL = 40;

	/**
	 * A list of mascot.
	 */
	private final List<Mascot> mascots = new ArrayList<Mascot>();

	/**
	* The mascot will be added later.
	* (@Link ConcurrentModificationException) to prevent the addition of the mascot (@link # tick ()) are each simultaneously reflecting.
	 */
	private final Set<Mascot> added = new LinkedHashSet<Mascot>();

	/**
	* The mascot will be added later.
	* (@Link ConcurrentModificationException) to prevent the deletion of the mascot (@link # tick ()) are each simultaneously reflecting.
	 */
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
							// Sleep most of the wait, but wake 1ms early to spin-correct
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
						// If we're more than one full interval behind (e.g. after a GC pause),
						// reset so we don't try to catch up with a burst of ticks
						if (System.nanoTime() - nextTick > TICK_INTERVAL * 1_000_000L) {
							nextTick = System.nanoTime();
						}
						tick();
					}
				} catch (final InterruptedException e) {
				} catch (final Throwable e) {
					log.log( java.util.logging.Level.SEVERE, "=== MANAGER THREAD KILLED BY UNCAUGHT EXCEPTION ===", e );
					// Write to watchdog file too in case logging is broken
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

			// Add the mascot if it should be added
			for (final Mascot mascot : this.getAdded()) {
				this.getMascots().add(mascot);
			}
			this.getAdded().clear();

			// Remove the mascot if it should be removed
			for (final Mascot mascot : this.getRemoved()) {
				this.getMascots().remove(mascot);
			}
			this.getRemoved().clear();

			// Each mascot ticks its own environment (nearest-window tracking),
			// then ticks itself
			for (final Mascot mascot : this.getMascots()) {
				mascot.getEnvironment().tick();
				mascot.tick();
			}
			
			// Advance mascot's time
			for (final Mascot mascot : this.getMascots()) {
				mascot.apply();
			}
		}

		if (isExitOnLastRemoved()) {
			if (this.getMascots().size() == 0) {
				Main.getInstance().exit();
			}
		}
	}

	public void add(final Mascot mascot) {
		synchronized (this.getAdded()) {
			this.getAdded().add(mascot);
			this.getRemoved().remove(mascot);
		}
		mascot.setManager(this);
	}

	public void remove(final Mascot mascot) {
		synchronized (this.getAdded()) {
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
					if( configuration.getBehaviorBuilders( ).containsKey( name ) )
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
	 * configuration does not define the named behavior. Used by HotkeyManager
	 * so a hotkey bound to a behavior that only some image sets have won't
	 * crash mascots that don't have it.
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
					if( configuration.getBehaviorBuilders( ).containsKey( name ) )
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
				if( m.getImageSet().equals(imageSet) && isFirst) {
					isFirst = false;
				} else if( m.getImageSet().equals(imageSet) && !isFirst) {
					m.dispose();
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
                if( !mascot.isPaused( ) )
                {
                    isPaused = false;
                    break;
                }
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
                if( !mascot.isPaused( ) )
                {
                    isPaused = false;
                    break;
                }
            }
        }
        
        return isPaused;
    }

    public int getCount( )
    {
        return getCount( null );
    }
    
    public int getCount( String imageSet )
    {
        synchronized( getMascots( ) )
        {
            if( imageSet == null )
            {
                return getMascots( ).size( );
            }
            else   
            {
                int count = 0;
                for( int index = 0; index < getMascots( ).size( ); index++ )
                {
                    Mascot m = getMascots( ).get( index );
                    if( m.getImageSet( ).equals( imageSet ) )
                        count++;
                }
                return count;
            }
        }
    }

    public List<Mascot> getMascotList() {
        synchronized (this.mascots) {
            return java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<>(this.mascots));
        }
    }

	private List<Mascot> getMascots() {
		return this.mascots;
	}

	private Set<Mascot> getAdded() {
		return this.added;
	}

	private Set<Mascot> getRemoved() {
		return this.removed;
	}
        
        /**
         * Returns a Mascot with the given affordance.
         * @param affordance
         * @return A WeakReference to a mascot with the required affordance, or null
         */
        public WeakReference<Mascot> getMascotWithAffordance( String affordance )
        {
            synchronized( this.getMascots( ) )
            {
                for( final Mascot mascot : this.getMascots( ) )
                {
                    if( mascot.getAffordances( ).contains( affordance ) )
                        return new WeakReference<Mascot>( mascot );
                }
            }
            
            return null;
        }

    public boolean hasOverlappingMascotsAtPoint( Point anchor )
    {
        int count = 0;
        
        synchronized( this.getMascots( ) )
        {
            for( final Mascot mascot : this.getMascots( ) )
            {
                if( mascot.getAnchor( ).equals( anchor ) )
                    count++;
                if( count > 1 )
                    return true;
            }
        }

        return false;
    }

	public void disposeAll() {
		synchronized (this.getMascots()) {
			for (int i = this.getMascots().size() - 1; i >= 0; --i) {
				this.getMascots().get(i).dispose();
			}
		}
	}
}
