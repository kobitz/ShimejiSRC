package com.group_finity.mascot;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.ResourceBundle;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.sound.sampled.Clip;

import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.environment.MascotEnvironment;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.hotspot.Hotspot;
import com.group_finity.mascot.image.ImagePairs;
import com.group_finity.mascot.image.MascotImage;
import com.group_finity.mascot.image.NativeImage;
import com.group_finity.mascot.image.TranslucentWindow;
import com.group_finity.mascot.menu.JLongMenu;
import com.group_finity.mascot.script.VariableMap;
import com.group_finity.mascot.sound.Sounds;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

/**
 * Mascot object.
 *
 * The mascot represents the long-term, complex behavior and (@link Behavior),
 * Represents a short-term movements in the monotonous work with (@link Action).
 *
 * The mascot they have an internal timer, at a constant interval to call (@link Action).
 * (@link Action) is (@link #animate (Point, MascotImage, boolean)) method or by calling
 * To animate the mascot.
 *
 * (@link Action) or exits, the other at a certain time is called (@link Behavior), the next move to (@link Action).
 *
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public class Mascot
{
    private static final long serialVersionUID = 1L;

    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private static AtomicInteger lastId = new AtomicInteger( );

    private final int id;

    private String imageSet = "";
    /**
     * A window that displays the mascot.
     */
    private final TranslucentWindow window = NativeFactory.getInstance( ).newTransparentWindow( );

    /**
     * Managers are managing the mascot.
     */
    private Manager manager = null;

    /**
     * Mascot ground coordinates.
     * Or feet, for example, when part of the hand is hanging.
     */
    private Point anchor = new Point( 0, 0 );

    /**
     * Last known good on-screen anchor position. Updated every tick after a
     * successful behavior step while the mascot is within screen bounds.
     * Used as the recovery position instead of a random off-screen drop when
     * the engine would otherwise teleport the mascot to a random location.
     */
    private Point savedAnchor = null;

    /**
     * Behavior name recorded at the same time as savedAnchor, so recovery
     * can restart the correct behavior rather than defaulting to Fall.
     */
    private String savedBehaviorName = null;

    /**
     * Image to display.
     */
    private MascotImage image = null;

    private double currentScale = 1.0;
    private MascotImage lastScaledImage = null;
    private java.util.LinkedHashMap<MascotImage, com.group_finity.mascot.image.ScalableNativeImage> scalables = null;

    /**
     * Whether looking right or left.
     * The original image is treated as left, true means picture must be inverted.
     */
    private boolean lookRight = false;

    /**
     * Object representing the long-term behavior.
     */
    private Behavior behavior = null;

    /**
     * Whether this mascot's location (and behavior) is pinned and will be
     * restored on the next program launch.
     */
    private boolean pinnedLocation = false;

    // ── Jump air-steer ────────────────────────────────────────────────────────
    // Written by HotkeyManager on a directional key-press, consumed once by
    // Jump.getTargetX() each tick. Volatile so the JNH thread and tick thread
    // see it without locking.
    private volatile int jumpTargetXOffset = 0;

    // ── Manual-only mode ──────────────────────────────────────────────────────
    // When true, buildNextBehavior only allows Fall/Dragged/Thrown/Stand/GrabWall.
    private boolean manualOnly = false;
    // The property key under which this mascot's location is saved.
    // Set at save time and carried across restarts so removePinnedLocation()
    // always deletes the right key regardless of the current runtime id.
    private String pinnedKey = null;

    /**
     * Increases with each tick of the timer.
     */
    private int time = 0;

    /**
     * Whether the animation is running.
     */
    private boolean animating = true;

    private boolean paused = false;
    
    /**
     * Set by behaviours when the shimeji is being dragged by the mouse cursor, 
     * as opposed to hotspots or the like.
     */
    private boolean dragging = false;

    private MascotEnvironment environment = new MascotEnvironment( this );

    private String sound = null;
    
    protected DebugWindow debugWindow = null;

    private JPopupMenu activePopup = null;
    private NativeMouseListener popupDismissListener = null;
    
    private ArrayList<String> affordances = new ArrayList( 5 );
    
    private ArrayList<Hotspot> hotspots = new ArrayList( 5 );
    
    /**
     * Set by behaviours when the user has triggered a hotspot on this shimeji, 
     * so that the shimeji knows to check for any new hotspots that emerge while 
     * the mouse is held down.
     */
    private Point cursor = null;
    
    private VariableMap variables = null;

    public Mascot( final String imageSet )
    {
        id = lastId.incrementAndGet( );
        this.imageSet = imageSet;

        // Restore persisted dynamic scale for this image set, if any.
        try
        {
            String saved = Main.getInstance( ).getProperties( )
                .getProperty( "Scale." + imageSet );
            if( saved != null )
                currentScale = Double.parseDouble( saved );
        }
        catch( NumberFormatException ignored ) { }

        // Restore per-mascot manualOnly flag if it was persisted
        String savedManualOnly = Main.getInstance( ).getProperties( ).getProperty( "ManualOnly.mascot" + id );
        if( savedManualOnly != null )
            manualOnly = Boolean.parseBoolean( savedManualOnly );

        log.log( Level.INFO, "Created a mascot ({0})", this );

        // Always on top — global setting AND per-imageset override, both default true
        applyAlwaysOnTop( );

        // Register the mouse handler
        getWindow( ).asComponent( ).addMouseListener( new MouseAdapter( )
        {
            @Override
            public void mousePressed( final MouseEvent e )
            {
                Mascot.this.mousePressed( e );
            }

            @Override
            public void mouseReleased( final MouseEvent e )
            {
                Mascot.this.mouseReleased( e );
            }
        } );
        getWindow( ).asComponent( ).addMouseMotionListener( new MouseMotionListener( )
        {
            @Override
            public void mouseMoved( final MouseEvent e )
            {
                if( paused )
                    refreshCursor( false );
                else
                {
                    if( isHotspotClicked( ) )
                        setCursorPosition( e.getPoint( ) );
                    else
                        refreshCursor( e.getPoint( ) );
                }
            }

            @Override
            public void mouseDragged( final MouseEvent e )
            {
                if( paused )
                    refreshCursor( false );
                else
                {
                    if( isHotspotClicked( ) )
                        setCursorPosition( e.getPoint( ) );
                    else
                        refreshCursor( e.getPoint( ) );
                }
            }
        } );
    }

    @Override
    public String toString( )
    {
        return "mascot" + id;
    }

    public int getId( )
    {
        return id;
    }

    private void mousePressed( final MouseEvent event )
    {
        // Left-click dismisses any open right-click popup
        if( SwingUtilities.isLeftMouseButton( event ) && activePopup != null && activePopup.isVisible( ) )
        {
            activePopup.setVisible( false );
            activePopup = null;
            return;
        }
        // Switch to drag the animation when the mouse is down
        if( !isPaused( ) && getBehavior( ) != null )
        {
            try
            {
                getBehavior( ).mousePressed( event );
            }
            catch( final CantBeAliveException e )
            {
                log.log( Level.SEVERE, "Fatal Error", e );
                Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "SevereShimejiErrorErrorMessage" ), e );
                dispose( );
            }
        }
    }

    private void mouseReleased(final MouseEvent event)
    {
        if( event.isPopupTrigger( ) )
        {
            SwingUtilities.invokeLater( new Runnable( )
            {
                @Override
                public void run( )
                {
                    showPopup( event.getX( ), event.getY( ) );
                }
            } );
        }
        else
        {
            if( !isPaused( ) && getBehavior( ) != null )
            {
                try
                {
                    getBehavior( ).mouseReleased( event );
                }
                catch( final CantBeAliveException e )
                {
                    log.log( Level.SEVERE, "Fatal Error", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "SevereShimejiErrorErrorMessage" ), e );
                    dispose( );
                }
            }
        }
    }

    private void showPopup( final int x, final int y )
    {
        final JPopupMenu popup = new JPopupMenu( );
        final ResourceBundle languageBundle = Main.getInstance( ).getLanguageBundle( );

        popup.addPopupMenuListener( new PopupMenuListener( )
        {
            @Override
            public void popupMenuCanceled( final PopupMenuEvent e )
            {
            }

            @Override
            public void popupMenuWillBecomeInvisible( final PopupMenuEvent e )
            {
                setAnimating( true );
                activePopup = null;
                if( popupDismissListener != null )
                {
                    GlobalScreen.removeNativeMouseListener( popupDismissListener );
                    popupDismissListener = null;
                }
            }

            @Override
            public void popupMenuWillBecomeVisible( final PopupMenuEvent e )
            {
                setAnimating( false );
            }
        } );

        // "Another One!" menu item
        final JMenuItem increaseMenu = new JMenuItem( languageBundle.getString( "CallAnother" ) );
        increaseMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                Main.getInstance( ).createMascot( imageSet );
            }
        } );

        // "Bye Bye!" menu item
        final JMenuItem disposeMenu = new JMenuItem( languageBundle.getString( "Dismiss" ) );
        disposeMenu.addActionListener( new ActionListener( )
        {
            @Override
            public void actionPerformed( final ActionEvent e )
            {
                // Clear any saved pin so a dismissed mascot doesn't respawn next launch.
                if( pinnedLocation )
                    removePinnedLocation( );
                dispose( );
            }
        } );

        // "Follow Mouse!" Menu item
        final JMenuItem gatherMenu = new JMenuItem( languageBundle.getString( "FollowCursor" ) );
        gatherMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                getManager( ).setBehaviorAll( Main.getInstance( ).getConfiguration(imageSet), Main.BEHAVIOR_GATHER, imageSet );
            }
        } );

        // "Reduce to One!" menu item
        final JMenuItem oneMenu = new JMenuItem( languageBundle.getString( "DismissOthers" ) );
        oneMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                getManager( ).remainOne( imageSet );
            }
        } );

        // "Reduce to One!" menu item
        final JMenuItem onlyOneMenu = new JMenuItem( languageBundle.getString( "DismissAllOthers" ) );
        onlyOneMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                getManager( ).remainOne( Mascot.this );
            }
        } );

        // "Restore IE!" menu item
        final JMenuItem restoreMenu = new JMenuItem( languageBundle.getString( "RestoreWindows" ) );
        restoreMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                NativeFactory.getInstance( ).getEnvironment( ).restoreIE( );
            }
        } );

        // Debug menu item
        final JMenuItem debugMenu = new JMenuItem( languageBundle.getString( "RevealStatistics" ) );
        debugMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                if( debugWindow == null )
                {
                    debugWindow = new DebugWindow( );
                    //debugWindow.setIcon
                }
                debugWindow.setAlwaysOnTop( Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "AlwaysOnTopDebugWindow", "false" ) ) );
                debugWindow.setVisible( true );
            }
        } );
        
        // "Bye Everyone!" menu item
        final JMenuItem closeMenu = new JMenuItem( languageBundle.getString( "DismissAll" ) );
        closeMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent e )
            {
                Main.getInstance( ).exit( );
            }
        } );

        // "Save Location" checkbox — pins this mascot's current position and behavior
        // so it is restored at the same spot on next launch.
        final JCheckBoxMenuItem saveLocationMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "SaveLocation" ), pinnedLocation );
        saveLocationMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                pinnedLocation = saveLocationMenu.isSelected( );
                if( pinnedLocation )
                    savePinnedLocation( );
                else
                    removePinnedLocation( );
            }
        } );

        // "Manual Only" checkbox — restricts autonomous behavior to Stand/Fall/Dragged/Thrown/GrabWall.
        // All other behaviors can still be triggered manually via hotkey or right-click menu.
        final JCheckBoxMenuItem manualOnlyMenu = new JCheckBoxMenuItem( "Manual Only", manualOnly );
        manualOnlyMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                manualOnly = manualOnlyMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "ManualOnly.mascot" + id, String.valueOf( manualOnly ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        // "Paused" Menu item
        final JMenuItem pauseMenu = new JMenuItem( isAnimating( ) ? languageBundle.getString( "PauseAnimations" ) : languageBundle.getString( "ResumeAnimations" ) );
        pauseMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                Mascot.this.setPaused( !Mascot.this.isPaused( ) );
            }
        } );

        // Add the Behaviors submenu.  Currently slightly buggy, sometimes the menu ghosts.
        JLongMenu submenu = new JLongMenu( languageBundle.getString( "SetBehaviour" ), 30 );
        JLongMenu allowedSubmenu = new JLongMenu( languageBundle.getString( "PersonalBehaviours" ), 30 );
        // The MenuScroller would look better than the JLongMenu, but the initial positioning is not working correctly.
        //MenuScroller.setScrollerFor(submenu, 30, 125);
        submenu.setAutoscrolls( true );
        JMenuItem item;
        JCheckBoxMenuItem toggleItem;
        final com.group_finity.mascot.config.Configuration config = Main.getInstance( ).getConfiguration( getImageSet( ) );
        for( String behaviorName : config.getBehaviorNames( ) )
        {
            final String command = behaviorName;
            try
            {
                if( !config.isBehaviorHidden( command ) )
                {
                    String caption = behaviorName.replaceAll( "([a-z])(IE)?([A-Z])", "$1 $2 $3" ).replaceAll( "  ", " " );
                    if( config.isBehaviorEnabled( command, Mascot.this ) && !command.contains( "/" ) )
                    {
                        item = new JMenuItem( languageBundle.containsKey( behaviorName ) ? 
                                              languageBundle.getString( behaviorName ) : 
                                              caption );
                        item.addActionListener( new ActionListener( )
                        {
                            @Override
                            public void actionPerformed( final ActionEvent e )
                            {
                                try
                                {	
                                    if( isCurrentActionInterruptable( ) )
                                        setBehavior( config.buildBehavior( command ) );
                                }
                                catch( Exception err )
                                {
                                    log.log( Level.SEVERE, "Error ({0})", this );
                                    Main.showError( languageBundle.getString( "CouldNotSetBehaviourErrorMessage" ), err );
                                }
                            }
                        } );
                        submenu.add( item );
                    }
                    
                    if( config.isBehaviorToggleable( command ) && !command.contains( "/" ) )
                    {
                        toggleItem = new JCheckBoxMenuItem( caption, config.isBehaviorEnabled( command, Mascot.this ) );
                        toggleItem.addItemListener( new ItemListener( )
                        {
                            public void itemStateChanged( final ItemEvent e )
                            {
                                Main.getInstance( ).setMascotBehaviorEnabled( command, Mascot.this, !config.isBehaviorEnabled( command, Mascot.this ) );
                            }
                        } );
                        allowedSubmenu.add( toggleItem );
                    }
                }
            }
            catch( Exception err )
            {
                // just skip if something goes wrong
            }
        }

        // Per-mascot universal behavior toggles
        final String mascotKey = "mascot" + id;
        final java.util.Properties props = Main.getInstance( ).getProperties( );

        final JCheckBoxMenuItem breedingMenu = new JCheckBoxMenuItem( languageBundle.getString( "BreedingCloning" ),
            ( props.getProperty( "Breeding.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Breeding.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Breeding", "true" ) ) ) );
        breedingMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Breeding.imageset." + getImageSet( ), String.valueOf( breedingMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem transientMenu = new JCheckBoxMenuItem( languageBundle.getString( "BreedingTransient" ),
            ( props.getProperty( "Transients.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Transients.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Transients", "true" ) ) ) );
        transientMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Transients.imageset." + getImageSet( ), String.valueOf( transientMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem transformationMenu = new JCheckBoxMenuItem( languageBundle.getString( "Transformation" ),
            ( props.getProperty( "Transformation.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Transformation.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Transformation", "true" ) ) ) );
        transformationMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Transformation.imageset." + getImageSet( ), String.valueOf( transformationMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem throwingMenu = new JCheckBoxMenuItem( languageBundle.getString( "ThrowingWindows" ),
            ( props.getProperty( "Throwing.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Throwing.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Throwing", "true" ) ) ) );
        throwingMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Throwing.imageset." + getImageSet( ), String.valueOf( throwingMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem soundsMenu = new JCheckBoxMenuItem( languageBundle.getString( "SoundEffects" ),
            ( props.getProperty( "Sounds.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Sounds.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Sounds", "true" ) ) ) );
        soundsMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Sounds.imageset." + getImageSet( ), String.valueOf( soundsMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem multiscreenMenu = new JCheckBoxMenuItem( languageBundle.getString( "Multiscreen" ),
            ( props.getProperty( "Multiscreen.imageset." + getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Multiscreen.imageset." + getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Multiscreen", "true" ) ) ) );
        multiscreenMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Multiscreen.imageset." + getImageSet( ), String.valueOf( multiscreenMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        JLongMenu universalSubmenu = new JLongMenu( languageBundle.getString( "UniversalBehaviours" ), 30 );
        universalSubmenu.add( breedingMenu );
        universalSubmenu.add( transientMenu );
        universalSubmenu.add( transformationMenu );
        universalSubmenu.add( throwingMenu );
        universalSubmenu.add( soundsMenu );
        universalSubmenu.add( multiscreenMenu );

        // Personal Filter submenu — lets users override the global filter for this imageSet
        final String imageSetFilterKey = "Filter.imageset." + getImageSet( );
        final String globalFilter = props.getProperty( "Filter", "false" );
        final String currentFilter = props.getProperty( imageSetFilterKey, globalFilter );

        final JCheckBoxMenuItem filterNearestMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "PersonalFilterNearest" ),
            currentFilter.equalsIgnoreCase( "false" ) || currentFilter.equalsIgnoreCase( "nearest" ) );
        final JCheckBoxMenuItem filterBicubicMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "PersonalFilterBicubic" ),
            currentFilter.equalsIgnoreCase( "bicubic" ) );
        final JCheckBoxMenuItem filterHqxMenu = new JCheckBoxMenuItem(
            languageBundle.getString( "PersonalFilterHqx" ),
            currentFilter.equalsIgnoreCase( "true" ) || currentFilter.equalsIgnoreCase( "hqx" ) );

        final Runnable applyPersonalFilter = new Runnable( )
        {
            public void run( )
            {
                // Determine which filter is now selected
                String chosen;
                if( filterBicubicMenu.isSelected( ) )
                    chosen = "bicubic";
                else if( filterHqxMenu.isSelected( ) )
                    chosen = "hqx";
                else
                    chosen = "false";

                props.setProperty( imageSetFilterKey, chosen );

                // Clear cached images for this imageSet so they reload with the new filter
                ImagePairs.removeAll( getImageSet( ) );

                // Reload configuration and respawn
                final String imageSet = getImageSet( );
                final boolean isExit = Main.getInstance( ).getManager( ).isExitOnLastRemoved( );
                Main.getInstance( ).getManager( ).setExitOnLastRemoved( false );
                dispose( );
                Main.getInstance( ).loadConfiguration( imageSet );
                Main.getInstance( ).createMascot( imageSet );
                Main.getInstance( ).getManager( ).setExitOnLastRemoved( isExit );
            }
        };

        filterNearestMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                if( filterNearestMenu.isSelected( ) )
                {
                    filterBicubicMenu.setSelected( false );
                    filterHqxMenu.setSelected( false );
                    applyPersonalFilter.run( );
                }
                else if( !filterBicubicMenu.isSelected( ) && !filterHqxMenu.isSelected( ) )
                    filterNearestMenu.setSelected( true );
            }
        } );
        filterBicubicMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                if( filterBicubicMenu.isSelected( ) )
                {
                    filterNearestMenu.setSelected( false );
                    filterHqxMenu.setSelected( false );
                    applyPersonalFilter.run( );
                }
                else if( !filterNearestMenu.isSelected( ) && !filterHqxMenu.isSelected( ) )
                    filterBicubicMenu.setSelected( true );
            }
        } );
        filterHqxMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                if( filterHqxMenu.isSelected( ) )
                {
                    filterNearestMenu.setSelected( false );
                    filterBicubicMenu.setSelected( false );
                    applyPersonalFilter.run( );
                }
                else if( !filterNearestMenu.isSelected( ) && !filterBicubicMenu.isSelected( ) )
                    filterHqxMenu.setSelected( true );
            }
        } );

        JLongMenu personalFilterSubmenu = new JLongMenu( languageBundle.getString( "PersonalFilter" ), 30 );
        personalFilterSubmenu.add( filterNearestMenu );
        personalFilterSubmenu.add( filterBicubicMenu );
        personalFilterSubmenu.add( filterHqxMenu );

        popup.add( increaseMenu );
        popup.add( new JSeparator( ) );
        popup.add( gatherMenu );
        popup.add( restoreMenu );
        popup.add( debugMenu );
        popup.add( new JSeparator( ) );
        if( submenu.getMenuComponentCount( ) > 0 )
            popup.add( submenu );
        popup.add( universalSubmenu );
        if( allowedSubmenu.getMenuComponentCount( ) > 0 )
            popup.add( allowedSubmenu );
        popup.add( personalFilterSubmenu );
        popup.add( new JSeparator( ) );
        popup.add( saveLocationMenu );
        popup.add( manualOnlyMenu );
        popup.add( pauseMenu );

        final boolean currentAlwaysOnTop = Boolean.parseBoolean(
            props.getProperty( "AlwaysOnTop.imageset." + getImageSet( ), "true" ) );
        final JCheckBoxMenuItem alwaysOnTopMenu = new JCheckBoxMenuItem(
            ( languageBundle.containsKey( "AlwaysOnTop" ) ? languageBundle.getString( "AlwaysOnTop" ) : "Always On Top" ), currentAlwaysOnTop );
        alwaysOnTopMenu.addItemListener( new ItemListener( )
        {
            @Override
            public void itemStateChanged( final ItemEvent e )
            {
                final boolean onTop = alwaysOnTopMenu.isSelected( );
                props.setProperty( "AlwaysOnTop.imageset." + getImageSet( ), String.valueOf( onTop ) );
                applyAlwaysOnTop( );
            }
        } );
        popup.add( alwaysOnTopMenu );
        popup.add( new JSeparator( ) );
        popup.add( disposeMenu );
        popup.add( oneMenu );
        popup.add( onlyOneMenu );
        popup.add( closeMenu );

        getWindow( ).asComponent( ).requestFocus( );

        // lightweight popups expect the shimeji window to draw them if they fall inside the shimeji window boundary
        // as the shimeji window can't support this we need to set them to heavyweight
        popup.setLightWeightPopupEnabled( false );
        activePopup = popup;

        // jnativehook intercepts native mouse events OS-wide, so this fires for clicks
        // on the desktop or other windows where Java's AWTEventListener cannot reach.
        // The Timer delay lets Swing process any menu-item ActionListener first before
        // we force-close the popup, ensuring menu actions are never swallowed.
        popupDismissListener = new NativeMouseListener( )
        {
            @Override
            public void nativeMousePressed( final NativeMouseEvent e )
            {
                if( activePopup != null )
                {
                    SwingUtilities.invokeLater( new Runnable( )
                    {
                        @Override
                        public void run( )
                        {
                            javax.swing.Timer t = new javax.swing.Timer( 150, new ActionListener( )
                            {
                                @Override
                                public void actionPerformed( ActionEvent ae )
                                {
                                    if( activePopup != null )
                                        activePopup.setVisible( false );
                                }
                            } );
                            t.setRepeats( false );
                            t.start( );
                        }
                    } );
                }
            }
            @Override public void nativeMouseReleased( NativeMouseEvent e ) { }
            @Override public void nativeMouseClicked( NativeMouseEvent e )  { }
        };
        GlobalScreen.addNativeMouseListener( popupDismissListener );

        popup.show( getWindow( ).asComponent( ), x, y );
    }

    void tick( )
    {
        if( isAnimating( ) )
        {
            if( getBehavior( ) != null )
            {
                try
                {
                    getBehavior( ).next( );
                }
                catch( final CantBeAliveException e )
                {
                    log.log( Level.SEVERE, "Fatal Error.", e );
                    Main.showError( Main.getInstance( ).getLanguageBundle( ).getString( "CouldNotGetNextBehaviourErrorMessage" ), e );
                    dispose( );
                }

                // Track last known good on-screen position for recovery.
                final java.awt.Rectangle b = getBounds( );
                if( b != null && environment.getScreen( ).toRectangle( ).intersects( b ) )
                {
                    savedAnchor = new Point( anchor.x, anchor.y );
                    savedBehaviorName = getCurrentBehaviorName( );
                }

                setTime( getTime( ) + 1 );
            }
            
            if( debugWindow != null )
            {
                debugWindow.setBehaviour( behavior.toString( ).substring( 9, behavior.toString( ).length( ) - 1 ).replaceAll( "([a-z])(IE)?([A-Z])", "$1 $2 $3" ).replaceAll( "  ", " " ) );
                debugWindow.setShimejiX( anchor.x );
                debugWindow.setShimejiY( anchor.y );
                
                Area activeWindow = environment.getActiveIE( );
                debugWindow.setWindowTitle( environment.getActiveIETitle( ) );
                debugWindow.setWindowX( activeWindow.getLeft( ) );
                debugWindow.setWindowY( activeWindow.getTop( ) );
                debugWindow.setWindowWidth( activeWindow.getWidth( ) );
                debugWindow.setWindowHeight( activeWindow.getHeight( ) );
                
                Area workArea = environment.getWorkArea( );
                debugWindow.setEnvironmentX( workArea.getLeft( ) );
                debugWindow.setEnvironmentY( workArea.getTop( ) );
                debugWindow.setEnvironmentWidth( workArea.getWidth( ) );
                debugWindow.setEnvironmentHeight( workArea.getHeight( ) );
                debugWindow.setCpuTemp( environment.getCpuTemp( ) );
                debugWindow.setCpuLoad( environment.getCpuLoad( ) );
                debugWindow.setGpuTemp( environment.getGpuTemp( ) );
                debugWindow.setGpuLoad( environment.getGpuLoad( ) );
                debugWindow.setRamLoad( environment.getRamLoad( ) );
                debugWindow.setBatteryLevel( environment.getBatteryLevel( ) );
                debugWindow.setPopulation( getTotalCount( ), getCount( ) );
                if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
                {
                    com.group_finity.mascot.action.Action act = ( (com.group_finity.mascot.behavior.UserBehavior)behavior ).getAction( );
                    // Drill into ComplexAction (Sequence/Select) to get the active leaf action
                    while( act instanceof com.group_finity.mascot.action.ComplexAction )
                    {
                        com.group_finity.mascot.action.Action child = ( (com.group_finity.mascot.action.ComplexAction)act ).getCurrentChildAction( );
                        if( child == null ) break;
                        act = child;
                    }
                    if( act instanceof com.group_finity.mascot.action.ActionBase )
                    {
                        com.group_finity.mascot.action.ActionBase ab = (com.group_finity.mascot.action.ActionBase)act;
                        try
                        {
                            int remaining;
                            if( act instanceof com.group_finity.mascot.action.Move )
                            {
                                // For Move/Walk: live distance-to-target / velocity countdown
                                remaining = ( (com.group_finity.mascot.action.Move)act ).getEstimatedDuration( );
                            }
                            else
                            {
                                int duration = ab.getDuration( );
                                if( duration == Integer.MAX_VALUE )
                                {
                                    // No Duration attr: try animation pose sum (covers Animate type)
                                    try
                                    {
                                        com.group_finity.mascot.animation.Animation anim = ab.getAnimation( );
                                        duration = ( anim != null ) ? anim.getDuration( ) : Integer.MAX_VALUE;
                                    }
                                    catch( Exception ex2 ) { }
                                }
                                remaining = ( duration == Integer.MAX_VALUE )
                                    ? Integer.MAX_VALUE
                                    : duration - ab.getTime( );
                            }
                            debugWindow.setActionTimer( remaining );
                        }
                        catch( com.group_finity.mascot.exception.VariableException ex ) { }
                    }
                }
            }
        }
    }

    public void apply( )
    {
        if (isAnimating())
        {
            // currentScale is updated directly by Scale.tick() — nothing to do here

            // Make sure there's an image
            if (getImage() != null)
            {
                MascotImage displayImage = getImage();
                java.awt.Rectangle bounds = getBounds();

                if( Math.abs( currentScale - 1.0 ) > 0.005 )
                {
                    if( scalables == null )
                    {
                        // Cap at 16 frames to bound memory use
                        scalables = new java.util.LinkedHashMap<MascotImage, com.group_finity.mascot.image.ScalableNativeImage>( 16, 0.75f, true )
                        {
                            protected boolean removeEldestEntry( java.util.Map.Entry<MascotImage, com.group_finity.mascot.image.ScalableNativeImage> eldest )
                            { return size() > 16; }
                        };
                    }
                    com.group_finity.mascot.image.ScalableNativeImage scalable =
                        scalables.computeIfAbsent( getImage(), img -> new com.group_finity.mascot.image.ScalableNativeImage( img, getImageSet() ) );

                    MascotImage scaled = scalable.get( currentScale );
                    if( scaled != null )
                    {
                        lastScaledImage = scaled;
                        displayImage = scaled;
                        int w  = displayImage.getSize().width;
                        int h  = displayImage.getSize().height;
                        int cx = displayImage.getCenter().x;
                        int cy = displayImage.getCenter().y;
                        bounds = new java.awt.Rectangle( getAnchor().x - cx, getAnchor().y - cy, w, h );
                    }
                    else if( lastScaledImage != null )
                    {
                        // Still building new scale — hold last good frame, no flicker
                        displayImage = lastScaledImage;
                        int w  = displayImage.getSize().width;
                        int h  = displayImage.getSize().height;
                        int cx = displayImage.getCenter().x;
                        int cy = displayImage.getCenter().y;
                        bounds = new java.awt.Rectangle( getAnchor().x - cx, getAnchor().y - cy, w, h );
                    }
                }
                else
                {
                    scalables = null;
                    lastScaledImage = null;
                }

                // Set Images
                getWindow().setImage(displayImage.getImage());

                // Display
                if (!getWindow().asComponent().isVisible())
                {
                    getWindow().asComponent().setVisible(true);
                }

                // Reposition and redraw atomically — prevents one-frame glitch when ImageAnchor changes.
                // setBounds is called after so the Swing component stays in sync for layout/bounds queries.
                getWindow().updateImage(bounds);
                getWindow().asComponent().setBounds(bounds);
            }
            else
            {
                if( getWindow().asComponent().isVisible() )
                {
                    getWindow().asComponent().setVisible(false);
                }
            }
            
            // play sound if requested
            if( !Sounds.isMuted( ) && sound != null && Sounds.contains( sound ) )
            {
                synchronized( log )
                {
                    Clip clip = Sounds.getSound( sound );
                    if( !clip.isRunning( ) )
                    {
                        clip.stop( );
                        clip.setMicrosecondPosition( 0 );
                        clip.start( );
                    }
                }
            }
        }
    }

    public void dispose( )
    {
        log.log( Level.INFO, "destroy mascot ({0})", this );
        
        if( debugWindow != null )
        {
            debugWindow.setVisible( false );
            debugWindow = null;
        }

        // Persist the current dynamic scale so it's restored on next spawn.
        // Only write if scale differs meaningfully from 1.0 to keep the
        // properties file clean for mascots that were never scaled.
        if( Math.abs( currentScale - 1.0 ) > 0.005 )
            Main.getInstance( ).getProperties( ).setProperty(
                "Scale." + getImageSet( ), String.valueOf( currentScale ) );

        animating = false;
        Main.getInstance( ).getProperties( ).remove( "DisabledBehaviours.mascot" + id );
        Main.getInstance( ).getProperties( ).remove( "ManualOnly.mascot" + id );
        for( String key : new String[]{ "Breeding", "Transients", "Transformation", "Throwing", "Sounds", "Multiscreen" } )
            Main.getInstance( ).getProperties( ).remove( key + ".mascot" + id );
        getWindow( ).dispose( );
        affordances.clear( );
        if( getManager( ) != null )
        {
            getManager( ).remove( Mascot.this );
        }
    }
        
    private void refreshCursor( Point position )
    {
        boolean useHand = false;
        for( final Hotspot hotspot : hotspots )
        {
            if( hotspot.contains( this, position ) && 
                Main.getInstance( ).getConfiguration( imageSet ).isBehaviorEnabled( hotspot.getBehaviour( ), this ) )
            {
                useHand = true;
                break;
            }
        }

        refreshCursor( useHand );
    }
    
    private void refreshCursor( Boolean useHand )
    {
        getWindow( ).asComponent( ).setCursor( Cursor.getPredefinedCursor( useHand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR ) );
    }

    public Manager getManager( )
    {
        return manager;
    }

    public void setManager( final Manager manager )
    {
        this.manager = manager;
    }

    public Point getAnchor( )
    {
        return anchor;
    }

    public void setAnchor( Point anchor )
    {
        this.anchor = anchor;
    }

    public Point getSavedAnchor( )
    {
        return savedAnchor;
    }

    public String getSavedBehaviorName( )
    {
        return savedBehaviorName;
    }

    public MascotImage getImage( )
    {
        return image;
    }

    public void setImage( final MascotImage image )
    {
        this.image = image;
    }

    public boolean isLookRight( )
    {
        return lookRight;
    }

    public void setLookRight( final boolean lookRight )
    {
        this.lookRight = lookRight;
    }

    public Rectangle getBounds( )
    {
        if( getImage( ) != null )
        {
            // Central area of the window find the image coordinates and ground coordinates. The centre has already been adjusted for scaling
            final int top = getAnchor( ).y - getImage( ).getCenter( ).y;
            final int left = getAnchor( ).x - getImage( ).getCenter( ).x;

            final Rectangle result = new Rectangle( left, top, getImage( ).getSize( ).width, getImage( ).getSize( ).height );

            return result;
        }
        else
        {
            // as we have no image let's return what we were last frame
            return getWindow( ).asComponent( ).getBounds( );
        }
    }

    public int getTime( )
    {
        return time;
    }

    private void setTime( final int time )
    {
        this.time = time;
    }

    public Behavior getBehavior( )
    {
        return behavior;
    }

    /**
     * Returns false if the current action has Draggable="false", meaning it
     * should not be interrupted by external triggers (hotkeys, right-click menu).
     * Internal transitions from UserBehavior (natural completion, fall, etc.) bypass this.
     */
    public boolean isCurrentActionInterruptable( )
    {
        if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
        {
            com.group_finity.mascot.action.Action action =
                ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getAction( );
            if( action instanceof com.group_finity.mascot.action.ActionBase )
            {
                try
                {
                    return ( (com.group_finity.mascot.action.ActionBase) action ).isDraggable( );
                }
                catch( com.group_finity.mascot.exception.VariableException ignored ) { }
            }
        }
        return true;
    }

    public void setBehavior( final Behavior behavior ) throws CantBeAliveException
    {
        this.behavior = behavior;
        this.behavior.init( this );
    }

    public int getCount( )
    {
        return manager != null ? getManager( ).getCount( imageSet ) : 0;
    }

    public int getTotalCount( )
    {
        return manager != null ? getManager( ).getCount( ) : 0;
    }

    private boolean isAnimating( )
    {
        return animating && !paused;
    }

    private void setAnimating( final boolean animating )
    {
        this.animating = animating;
    }

    private TranslucentWindow getWindow( )
    {
        return this.window;
    }

    public MascotEnvironment getEnvironment( )
    {
        return environment;
    }

    /**
     * Applies the always-on-top state based on the global setting AND the per-imageset override.
     * Global off overrides everything. Global on respects per-imageset opt-out.
     */
    public void applyAlwaysOnTop( )
    {
        final java.util.Properties props = Main.getInstance( ).getProperties( );
        final boolean globalOnTop = Boolean.parseBoolean( props.getProperty( "AlwaysOnTop", "true" ) );
        final boolean perImageSet = Boolean.parseBoolean(
            props.getProperty( "AlwaysOnTop.imageset." + getImageSet( ), "true" ) );
        getWindow( ).setAlwaysOnTop( globalOnTop && perImageSet );
    }

    public ArrayList<String> getAffordances( )
    {
	return affordances;
    }

    public ArrayList<Hotspot> getHotspots( )
    {
	return hotspots;
    }

    public void setCurrentScale( double scale ) { this.currentScale = scale; }
    public double getCurrentScale( )            { return currentScale; }

    /** Consume the queued jump X nudge (returns offset and resets to 0). */
    public int consumeJumpTargetXOffset( )
    {
        int v = jumpTargetXOffset;
        jumpTargetXOffset = 0;
        return v;
    }
    /** Queue a one-shot nudge to the active Jump's targetX. Called from JNH thread. */
    public void addJumpTargetXOffset( int dx ) { jumpTargetXOffset += dx; }

    public boolean isManualOnly( )          { return manualOnly; }
    public void setManualOnly( boolean v )  { manualOnly = v; }

    public boolean isPinnedLocation( )         { return pinnedLocation; }
    public void setPinnedLocation( boolean v ) { pinnedLocation = v; }
    public void setPinnedKey( String key )     { pinnedKey = key; }

    /** Returns the name of the current UserBehavior, or null if unavailable. */
    public String getCurrentBehaviorName( )
    {
        if( behavior instanceof com.group_finity.mascot.behavior.UserBehavior )
            return ( (com.group_finity.mascot.behavior.UserBehavior) behavior ).getName( );
        return null;
    }

    /**
     * Saves this mascot's current anchor, direction, and behavior name into the
     * "PinnedMascots" property so it is restored on next launch.
     * Writes through to disk immediately via updateConfigFile().
     */
    public void savePinnedLocation( )
    {
        final String behaviorName = getCurrentBehaviorName( );
        final String entry = imageSet + "|"
            + ( behaviorName != null ? behaviorName : "" ) + "|"
            + getAnchor( ).x + "|"
            + getAnchor( ).y + "|"
            + isLookRight( );

        // Each mascot gets its own property slot keyed by its unique id so that
        // multiple pinned mascots (even of the same imageSet) coexist cleanly.
        pinnedKey = "PinnedMascot." + id;
        Main.getInstance( ).getProperties( ).setProperty( pinnedKey, entry );
        Main.getInstance( ).updateConfigFile( );
    }

    /**
     * Removes this mascot's saved location entry and writes through to disk.
     */
    public void removePinnedLocation( )
    {
        // Use pinnedKey (the key that was actually written) rather than the
        // current runtime id, which changes between sessions.
        final String key = pinnedKey != null ? pinnedKey : "PinnedMascot." + id;
        Main.getInstance( ).getProperties( ).remove( key );
        pinnedKey = null;
        Main.getInstance( ).updateConfigFile( );
    }

    public void setImageSet( final String set )
    {
        imageSet = set;
    }

    public String getImageSet( )
    {
        return imageSet;
    }

    public String getSound( )
    {
        return sound;
    }

    public void setSound( final String name )
    {
        sound = name;
    }
    
    public boolean isPaused( )
    {
        return paused;
    }
    
    public void setPaused( final boolean paused )
    {
        this.paused = paused;
    }

    public boolean isDragging( )
    {
        return dragging;
    }

    public void setDragging( final boolean isDragging )
    {
        dragging = isDragging;
    }

    public boolean isHotspotClicked( )
    {
        return cursor != null;
    }
    
    public Point getCursorPosition( )
    {
        return cursor;
    }

    public void setCursorPosition( final Point point )
    {
        cursor = point;
        
        if( point == null )
            refreshCursor( false );
        else
            refreshCursor( point );
    }

    public VariableMap getVariables( )
    {
        if( variables == null )
            variables = new VariableMap( );
        return variables;
    }
}
