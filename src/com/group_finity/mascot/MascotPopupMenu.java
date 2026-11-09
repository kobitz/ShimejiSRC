package com.group_finity.mascot;

import static com.group_finity.mascot.assistant.ReplyText.isAudioTranscriptDuplicate;
import static com.group_finity.mascot.assistant.ReplyText.stripActionTag;
import static com.group_finity.mascot.assistant.ReplyText.rewriteFirstPerson;
import static com.group_finity.mascot.assistant.ReplyText.extractTimerTag;
import static com.group_finity.mascot.assistant.ReplyText.parseTimerDurationMs;
import static com.group_finity.mascot.assistant.ReplyText.sanitizeResponse;
import static com.group_finity.mascot.assistant.ReplyText.editDistance;
import static com.group_finity.mascot.assistant.ReplyText.trimToFirstSentence;
import static com.group_finity.mascot.assistant.ReplyText.isTrivialAcknowledgement;
import static com.group_finity.mascot.assistant.ReplyText.polishUtterance;
import static com.group_finity.mascot.assistant.ReplyText.isIncompleteUtterance;
import static com.group_finity.mascot.assistant.ReplyText.isEphemeralQuery;
import static com.group_finity.mascot.assistant.ReplyText.isScreenQuery;
import static com.group_finity.mascot.assistant.ReplyText.isWeatherQuery;
import static com.group_finity.mascot.assistant.ReplyText.isValidTone;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import com.group_finity.mascot.assistant.AssistantBubble;
import com.group_finity.mascot.assistant.AssistantInputDialog;
import com.group_finity.mascot.assistant.OllamaClient;
import com.group_finity.mascot.script.VariableMap;
import com.group_finity.mascot.sound.Sounds;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

/**
 * The right-click context menu for a {@link Mascot}. Extracted from {@code Mascot.showPopup}.
 *
 * <p>Builds the menu and shows it. It reads and writes the mascot's state through package-visible
 * members, exactly as the original in-class code did, so behavior is unchanged.
 */
final class MascotPopupMenu
{
    private static final Logger log = Logger.getLogger( Mascot.class.getName( ) );

    private final Mascot m;

    MascotPopupMenu( final Mascot m )
    {
        this.m = m;
    }

    void show( final int x, final int y )
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
                m.setAnimating( true );
                m.activePopup = null;
                m.activePopupBounds = null;
                if( m.popupDismissListener != null )
                {
                    GlobalScreen.removeNativeMouseListener( m.popupDismissListener );
                    m.popupDismissListener = null;
                }
            }

            @Override
            public void popupMenuWillBecomeVisible( final PopupMenuEvent e )
            {
                m.setAnimating( false );
            }
        } );

        // "Another One!" menu item
        final JMenuItem increaseMenu = new JMenuItem( languageBundle.getString( "CallAnother" ) );
        increaseMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                Main.getInstance( ).createMascot( m.imageSet );
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
                if( m.pinnedLocation )
                    m.removePinnedLocation( );
                m.dispose( );
            }
        } );

        // "Follow Mouse!" Menu item
        final JMenuItem gatherMenu = new JMenuItem( languageBundle.getString( "FollowCursor" ) );
        gatherMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                m.getManager( ).setBehaviorAll( Main.getInstance( ).getConfiguration(m.imageSet), Main.BEHAVIOR_GATHER, m.imageSet );
            }
        } );

        // "Reduce to One!" menu item
        final JMenuItem oneMenu = new JMenuItem( languageBundle.getString( "DismissOthers" ) );
        oneMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                m.getManager( ).remainOne( m.imageSet );
            }
        } );

        // "Reduce to One!" menu item
        final JMenuItem onlyOneMenu = new JMenuItem( languageBundle.getString( "DismissAllOthers" ) );
        onlyOneMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                m.getManager( ).remainOne( m );
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
                if( m.debugWindow == null )
                {
                    m.debugWindow = new DebugWindow( );
                    //debugWindow.setIcon
                }
                m.debugWindow.setAlwaysOnTop( Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "AlwaysOnTopDebugWindow", "false" ) ) );
                m.debugWindow.setVisible( true );
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
            languageBundle.getString( "SaveLocation" ), m.pinnedLocation );
        saveLocationMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                m.pinnedLocation = saveLocationMenu.isSelected( );
                if( m.pinnedLocation )
                    m.savePinnedLocation( );
                else
                    m.removePinnedLocation( );
            }
        } );

        // "Manual Only" checkbox — restricts autonomous behavior to Stand/Fall/Dragged/Thrown/GrabWall.
        // All other behaviors can still be triggered manually via hotkey or right-click menu.
        final JCheckBoxMenuItem manualOnlyMenu = new JCheckBoxMenuItem( "Manual Only", m.manualOnly );
        manualOnlyMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                m.manualOnly = manualOnlyMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "ManualOnly.mascot" + m.id, String.valueOf( m.manualOnly ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        // "Paused" Menu item
        final JMenuItem pauseMenu = new JMenuItem( m.isAnimating( ) ? languageBundle.getString( "PauseAnimations" ) : languageBundle.getString( "ResumeAnimations" ) );
        pauseMenu.addActionListener( new ActionListener( )
        {
            public void actionPerformed( final ActionEvent event )
            {
                m.setPaused( !m.isPaused( ) );
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
        final com.group_finity.mascot.config.Configuration config = Main.getInstance( ).getConfiguration( m.getImageSet( ) );
        for( String behaviorName : config.getBehaviorNames( ) )
        {
            final String command = behaviorName;
            try
            {
                final String caption = behaviorName.replaceAll( "([a-z])(IE)?([A-Z])", "$1 $2 $3" ).replaceAll( "  ", " " );
                if( !config.isBehaviorHidden( command ) )
                {
                    if( config.isBehaviorEnabled( command, m ) && !command.contains( "/" ) && !config.isBehaviorToggleable( command ) )
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
                                    if( m.isCurrentActionInterruptable( ) )
                                        m.setBehavior( config.buildBehavior( command ) );
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
                }

                // Toggleable behaviours always get a checkbox, even when marked Hidden (Hidden only keeps them out of Set Behaviour)
                {
                    if( config.isBehaviorToggleable( command ) && !command.contains( "/" ) )
                    {
                        toggleItem = new JCheckBoxMenuItem( caption, config.isBehaviorEnabled( command, m ) );
                        toggleItem.addItemListener( new ItemListener( )
                        {
                            public void itemStateChanged( final ItemEvent e )
                            {
                                final boolean nowEnabled = !config.isBehaviorEnabled( command, m );
                                Main.getInstance( ).setMascotBehaviorEnabled( command, m, nowEnabled );
                                if( config.isBehaviorClearTintOnDisable( command ) )
                                {
                                    if( nowEnabled )
                                    {
                                        try { m.setBehavior( config.buildBehavior( command ) ); }
                                        catch( Exception ignored ) {}
                                    }
                                    else
                                    {
                                        m.snapClearTint( );
                                        // Break out of the running Loop=true behavior so it cannot
                                        // re-call Tint.init() and re-register the color expression.
                                        try
                                        {
                                            m.setBehavior( config.buildNextBehavior( command, m ) );
                                        }
                                        catch( Exception ignored ) {}
                                    }
                                }
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
        final String mascotKey = "mascot" + m.id;
        final java.util.Properties props = Main.getInstance( ).getProperties( );

        final JCheckBoxMenuItem breedingMenu = new JCheckBoxMenuItem( languageBundle.getString( "BreedingCloning" ),
            ( props.getProperty( "Breeding.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Breeding.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Breeding", "true" ) ) ) );
        breedingMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Breeding.imageset." + m.getImageSet( ), String.valueOf( breedingMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem transientMenu = new JCheckBoxMenuItem( languageBundle.getString( "BreedingTransient" ),
            ( props.getProperty( "Transients.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Transients.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Transients", "true" ) ) ) );
        transientMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Transients.imageset." + m.getImageSet( ), String.valueOf( transientMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem transformationMenu = new JCheckBoxMenuItem( languageBundle.getString( "Transformation" ),
            ( props.getProperty( "Transformation.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Transformation.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Transformation", "true" ) ) ) );
        transformationMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Transformation.imageset." + m.getImageSet( ), String.valueOf( transformationMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem throwingMenu = new JCheckBoxMenuItem( languageBundle.getString( "ThrowingWindows" ),
            ( props.getProperty( "Throwing.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Throwing.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Throwing", "true" ) ) ) );
        throwingMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Throwing.imageset." + m.getImageSet( ), String.valueOf( throwingMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem soundsMenu = new JCheckBoxMenuItem( languageBundle.getString( "SoundEffects" ),
            ( props.getProperty( "Sounds.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Sounds.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Sounds", "true" ) ) ) );
        soundsMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Sounds.imageset." + m.getImageSet( ), String.valueOf( soundsMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem multiscreenMenu = new JCheckBoxMenuItem( languageBundle.getString( "Multiscreen" ),
            ( props.getProperty( "Multiscreen.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Multiscreen.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Multiscreen", "true" ) ) ) );
        multiscreenMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Multiscreen.imageset." + m.getImageSet( ), String.valueOf( multiscreenMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem screenLoopMenu = new JCheckBoxMenuItem( languageBundle.getString( "ScreenLoop" ),
            ( props.getProperty( "ScreenLoop.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "ScreenLoop.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "ScreenLoop", "false" ) ) ) );
        screenLoopMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "ScreenLoop.imageset." + m.getImageSet( ), String.valueOf( screenLoopMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem collisionMenu = new JCheckBoxMenuItem( Main.getInstance( ).getLanguageString( "MascotCollision", "Mascot Collision" ),
            ( props.getProperty( "Collision.imageset." + m.getImageSet( ) ) != null ? Boolean.parseBoolean( props.getProperty( "Collision.imageset." + m.getImageSet( ) ) ) : Boolean.parseBoolean( props.getProperty( "Collision", "false" ) ) ) );
        collisionMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                props.setProperty( "Collision.imageset." + m.getImageSet( ), String.valueOf( collisionMenu.isSelected( ) ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem floorMenu = new JCheckBoxMenuItem( "Floor Collision", m.floorEnabled );
        floorMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                m.floorEnabled = floorMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "FloorEnabled.mascot" + m.id, String.valueOf( m.floorEnabled ) );
                Main.getInstance( ).updateConfigFile( );
            }
        } );

        final JCheckBoxMenuItem tooltipMenu = new JCheckBoxMenuItem( "Tooltip", m.tooltipEnabled );
        tooltipMenu.addItemListener( new ItemListener( )
        {
            public void itemStateChanged( final ItemEvent e )
            {
                m.tooltipEnabled = tooltipMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty( "Tooltip.mascot" + m.id, String.valueOf( m.tooltipEnabled ) );
                Main.getInstance( ).updateConfigFile( );
                if( !m.tooltipEnabled && m.mascotTooltip != null )
                {
                    m.mascotTooltip.dispose( );
                    m.mascotTooltip = null;
                }
            }
        } );

        JLongMenu universalSubmenu = new JLongMenu( languageBundle.getString( "UniversalBehaviours" ), 30 );
        universalSubmenu.add( breedingMenu );
        universalSubmenu.add( transientMenu );
        universalSubmenu.add( transformationMenu );
        universalSubmenu.add( throwingMenu );
        universalSubmenu.add( soundsMenu );
        universalSubmenu.add( multiscreenMenu );
        universalSubmenu.add( screenLoopMenu );
        universalSubmenu.add( floorMenu );
        universalSubmenu.add( collisionMenu );

        // Personal Filter submenu — lets users override the global filter for this imageSet
        final String imageSetFilterKey = "Filter.imageset." + m.getImageSet( );
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
                ImagePairs.removeAll( m.getImageSet( ) );

                // Reload configuration and respawn
                final String imageSet = m.getImageSet( );
                final boolean isExit = Main.getInstance( ).getManager( ).isExitOnLastRemoved( );
                Main.getInstance( ).getManager( ).setExitOnLastRemoved( false );
                m.dispose( );
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
        popup.add( tooltipMenu );
        // Per-image-set sound-effect volume (0-100%) in the main menu, under the tooltip toggle.
        // Persisted as Volume.imageset.<name>. Uses MenuSlider so dragging it does not dismiss the
        // popup. Applied live at playback; written to disk only when the drag settles.
        int initVolPct;
        try { initVolPct = Math.max( 0, Math.min( 100, Integer.parseInt(
            props.getProperty( "Volume.imageset." + m.getImageSet( ), "100" ).trim( ) ) ) ); }
        catch( final Exception ex ) { initVolPct = 100; }
        final javax.swing.JSlider volumeSlider = new javax.swing.JSlider( 0, 100, initVolPct );
        // The "Volume: X%" label is a real (non-activating) menu item — LabelMenuItem — so the L&F
        // positions its text in EXACTLY the same column as every other item, with no hand-computed
        // gutter offset to get wrong. The slider sits on its own row below (SliderMenuItem). A titled
        // border was avoided earlier because a framed box reads as a nested sub-category.
        final LabelMenuItem volumeLabel = new LabelMenuItem( "Volume: " + initVolPct + "%" );
        volumeSlider.setOpaque( false );
        volumeSlider.setToolTipText( "Sound effect volume for " + m.getImageSet( ) );
        final java.awt.Dimension volPref = volumeSlider.getPreferredSize( );
        volumeSlider.setPreferredSize( new java.awt.Dimension( 130, volPref.height ) );
        volumeSlider.addChangeListener( new javax.swing.event.ChangeListener( )
        {
            @Override
            public void stateChanged( final javax.swing.event.ChangeEvent e )
            {
                final int v = volumeSlider.getValue( );
                volumeLabel.setText( "Volume: " + v + "%" );
                props.setProperty( "Volume.imageset." + m.getImageSet( ), String.valueOf( v ) );
                if( !volumeSlider.getValueIsAdjusting( ) )
                    Main.getInstance( ).updateConfigFile( );
            }
        } );
        popup.add( volumeLabel );
        popup.add( new SliderMenuItem( volumeSlider ) );
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
        // Assistant mode toggle — lets the mascot respond to left-clicks via local LLM
        final JCheckBoxMenuItem assistantModeMenu = new JCheckBoxMenuItem( "Assistant Mode", m.assistantMode );
        assistantModeMenu.addItemListener( new ItemListener( )
        {
            @Override
            public void itemStateChanged( final ItemEvent e )
            {
                m.assistantMode = assistantModeMenu.isSelected( );
                Main.getInstance( ).getProperties( ).setProperty(
                    "AssistantMode." + m.getImageSet(), String.valueOf( m.assistantMode ) );
                if( m.assistantMode )
                    m.registerVoiceCommand();
                else
                    m.unregisterVoiceCommand();
                // Dismiss all open bubbles when turning off
                if( !m.assistantMode )
                {
                    for( final com.group_finity.mascot.assistant.AssistantBubble b : m.activeBubbles )
                        b.dismiss();
                    m.assistantBubble = null;
                    m.timerBubble     = null;
                }
                // The console readout follows assistant mode on/off (for any mascot
                // that wants one — see wantsConsoleReadout()).
                if( !m.assistantMode )
                {
                    if( m.consoleReadout != null ) { m.consoleReadout.dispose(); m.consoleReadout = null; }
                }
                else if( m.consoleReadout == null && m.wantsConsoleReadout() )
                {
                    m.consoleReadout = new com.group_finity.mascot.assistant.ConsoleReadout( m );
                }
                if( !m.assistantMode && m.activeDialog != null )
                {
                    m.activeDialog.close( );
                    m.activeDialog = null;
                }
            }
        } );
        popup.add( assistantModeMenu );
        popup.add( pauseMenu );

        final boolean currentAlwaysOnTop = Boolean.parseBoolean(
            props.getProperty( "AlwaysOnTop.imageset." + m.getImageSet( ), "true" ) );
        final JCheckBoxMenuItem alwaysOnTopMenu = new JCheckBoxMenuItem(
            ( languageBundle.containsKey( "AlwaysOnTop" ) ? languageBundle.getString( "AlwaysOnTop" ) : "Always On Top" ), currentAlwaysOnTop );
        alwaysOnTopMenu.addItemListener( new ItemListener( )
        {
            @Override
            public void itemStateChanged( final ItemEvent e )
            {
                final boolean onTop = alwaysOnTopMenu.isSelected( );
                props.setProperty( "AlwaysOnTop.imageset." + m.getImageSet( ), String.valueOf( onTop ) );
                m.applyAlwaysOnTop( );
            }
        } );
        popup.add( alwaysOnTopMenu );
        popup.add( new JSeparator( ) );
        popup.add( disposeMenu );
        popup.add( oneMenu );
        popup.add( onlyOneMenu );
        popup.add( closeMenu );

        m.getWindow( ).asComponent( ).requestFocus( );

        // lightweight popups expect the shimeji window to draw them if they fall inside the shimeji window boundary
        // as the shimeji window can't support this we need to set them to heavyweight
        popup.setLightWeightPopupEnabled( false );
        m.activePopup = popup;

        // jnativehook intercepts native mouse events OS-wide, so this fires for clicks
        // on the desktop or other windows where Java's AWTEventListener cannot reach.
        // The Timer delay lets Swing process any menu-item ActionListener first before
        // we force-close the popup, ensuring menu actions are never swallowed.
        m.popupDismissListener = new NativeMouseListener( )
        {
            @Override
            public void nativeMousePressed( final NativeMouseEvent e )
            {
                // Ignore presses INSIDE the popup — Swing handles those (menu-item clicks
                // close it; the volume slider interacts with it). Only outside presses
                // (desktop / other windows) should force-close the menu.
                final java.awt.Rectangle b = m.activePopupBounds;
                if( b != null && b.contains( e.getX( ), e.getY( ) ) )
                    return;

                if( m.activePopup != null )
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
                                    if( m.activePopup != null )
                                    {
                                        try
                                        {
                                            m.activePopup.setVisible( false );
                                        }
                                        catch( final java.awt.IllegalComponentStateException ex )
                                        {
                                            // The popup was already closing (e.g. a menu item was just
                                            // clicked); the NimROD look-and-feel then asks for the screen
                                            // position of a component that is no longer showing. Harmless.
                                            log.log( java.util.logging.Level.FINE, "Popup already hidden", ex );
                                        }
                                    }
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
        GlobalScreen.addNativeMouseListener( m.popupDismissListener );

        popup.show( m.getWindow( ).asComponent( ), x, y );
        // Capture the popup's screen rectangle so the native dismiss listener can tell
        // inside-clicks (slider/menu items, handled by Swing) from outside-clicks.
        try
        {
            if( popup.isShowing( ) )
                m.activePopupBounds = new java.awt.Rectangle( popup.getLocationOnScreen( ), popup.getSize( ) );
        }
        catch( final Exception ex ) { m.activePopupBounds = null; }
    }

    /**
     * Hosts a JSlider inside a JPopupMenu without the popup closing on click. A JMenuItem is
     * fully recognized by the popup's mouse grabber as part of the menu (so the grabber won't
     * cancel the popup), while we override its event handling to (a) forward every mouse event
     * to the embedded slider so the thumb tracks, and (b) never arm/activate the item, since
     * activation is what calls clearSelectedPath() and closes the menu. menuSelectionChanged is
     * a no-op so the item never highlights or auto-closes.
     */
    private static final class SliderMenuItem extends javax.swing.JMenuItem
    {
        private final javax.swing.JSlider slider;

        SliderMenuItem( final javax.swing.JSlider slider )
        {
            this.slider = slider;

            // Indent past the L&F's check-icon gutter so the slider's track lines up under the menu
            // items' text column rather than bleeding into the checkbox column. Robust via UIManager,
            // with a sane fallback.
            int checkGutter = 22;
            try
            {
                final javax.swing.Icon checkIcon = javax.swing.UIManager.getIcon( "CheckBoxMenuItem.checkIcon" );
                final Object gap = javax.swing.UIManager.get( "MenuItem.textIconGap" );
                if( checkIcon != null )
                    checkGutter = checkIcon.getIconWidth( ) + ( gap instanceof Integer ? (Integer) gap : 4 );
            }
            catch( final Exception ignore ) { }

            // KEEP the default JMenuItem border (its left inset is where every item's content starts)
            // and add the check-icon gutter ON TOP.
            final javax.swing.border.Border base = getBorder( );
            final java.awt.Insets bi = base != null ? base.getBorderInsets( this )
                                                    : new java.awt.Insets( 0, 0, 0, 0 );
            setBorder( javax.swing.BorderFactory.createCompoundBorder(
                base, javax.swing.BorderFactory.createEmptyBorder( 0, checkGutter, 2, 6 ) ) );

            setLayout( new java.awt.BorderLayout( ) );
            add( slider, java.awt.BorderLayout.CENTER );
            setOpaque( false );

            // JMenuItem's UI sizes from text/icon, ignoring our child layout, so set it explicitly.
            final java.awt.Dimension sp = slider.getPreferredSize( );
            setPreferredSize( new java.awt.Dimension(
                bi.left + checkGutter + sp.width + 6 + bi.right,
                bi.top + sp.height + 2 + bi.bottom ) );
        }

        @Override
        public void processMouseEvent( final java.awt.event.MouseEvent e,
                                       final javax.swing.MenuElement[] path,
                                       final javax.swing.MenuSelectionManager manager )
        {
            // Redirect the interaction to the slider (with coordinates converted into the
            // slider's space); deliberately do NOT call super, so the item never activates/closes.
            final java.awt.Point p = javax.swing.SwingUtilities.convertPoint( this, e.getPoint( ), slider );
            slider.dispatchEvent( new java.awt.event.MouseEvent(
                slider, e.getID( ), e.getWhen( ), e.getModifiersEx( ),
                p.x, p.y, e.getClickCount( ), e.isPopupTrigger( ), e.getButton( ) ) );
        }

        @Override
        public void processKeyEvent( final java.awt.event.KeyEvent e,
                                     final javax.swing.MenuElement[] path,
                                     final javax.swing.MenuSelectionManager manager ) { }

        @Override
        public void menuSelectionChanged( final boolean isIncluded ) { }
    }

    /**
     * A non-interactive text row inside a JPopupMenu. It is a real JMenuItem, so the L&F renders
     * its text in the exact same column as every other menu item — no hand-computed gutter offset
     * to get wrong (that guesswork is what kept the volume label misaligned). Event handling is
     * overridden so it never activates/highlights/closes the popup; it's purely a label.
     */
    private static final class LabelMenuItem extends javax.swing.JMenuItem
    {
        LabelMenuItem( final String text )
        {
            super( text );
            setOpaque( false );
        }

        @Override
        public void processMouseEvent( final java.awt.event.MouseEvent e,
                                       final javax.swing.MenuElement[] path,
                                       final javax.swing.MenuSelectionManager manager ) { }

        @Override
        public void processKeyEvent( final java.awt.event.KeyEvent e,
                                     final javax.swing.MenuElement[] path,
                                     final javax.swing.MenuSelectionManager manager ) { }

        @Override
        public void menuSelectionChanged( final boolean isIncluded ) { }
    }
}
