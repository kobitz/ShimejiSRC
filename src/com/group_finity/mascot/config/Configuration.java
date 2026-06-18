package com.group_finity.mascot.config;

import com.group_finity.mascot.Main;
import java.awt.Point;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.action.Action;
import com.group_finity.mascot.action.Animate;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.behavior.TransitionBehavior;
import com.group_finity.mascot.behavior.UserBehavior;
import com.group_finity.mascot.exception.ActionInstantiationException;
import com.group_finity.mascot.exception.BehaviorInstantiationException;
import com.group_finity.mascot.exception.ConfigurationException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.Variable;
import com.group_finity.mascot.script.VariableMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import com.joconner.i18n.Utf8ResourceBundleControl;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */

public class Configuration
{
    private static final Logger log = Logger.getLogger( Configuration.class.getName( ) );
    private final Map<String, String> constants = new LinkedHashMap<String, String>( 2 );
    private final Map<String, ActionBuilder> actionBuilders = new LinkedHashMap<String, ActionBuilder>( );
    private final Map<String, BehaviorBuilder> behaviorBuilders = new LinkedHashMap<String, BehaviorBuilder>( );
    private final Map<String, String> information = new LinkedHashMap<String, String>( 8 );
    private final Map<String, java.util.List<Entry>> animationTemplates = new LinkedHashMap<String, java.util.List<Entry>>( );
    private final List<TransitionEntry> transitions = new ArrayList<TransitionEntry>( );
    private ResourceBundle schema;

    public void load( final Entry configurationNode, final String imageSet ) throws IOException, ConfigurationException
    {
        log.log( Level.INFO, "Start Reading Configuration File..." );
        
        // prepare schema
        ResourceBundle.Control utf8Control = new Utf8ResourceBundleControl( false );
        Locale locale;

        // check for Japanese XML tag and adapt locale accordingly
        if( configurationNode.hasChild( "\u52D5\u4F5C\u30EA\u30B9\u30C8" ) ||
            configurationNode.hasChild( "\u884C\u52D5\u30EA\u30B9\u30C8" ) )
        {
            log.log( Level.INFO, "Using ja-JP schema" );
            locale = Locale.forLanguageTag( "ja-JP" );
        }
        else
        {
            log.log( Level.INFO, "Using en-US schema" );
            locale = Locale.forLanguageTag( "en-US" );
        }
        
        schema = ResourceBundle.getBundle( "schema", locale, utf8Control );
        
        for( Entry constant : configurationNode.selectChildren( schema.getString( "Constant" ) ) )
        {
            getConstants( ).put( constant.getAttribute( schema.getString( "Name" ) ),
                                 constant.getAttribute( schema.getString( "Value" ) ) );
        }

        for( final Entry list : configurationNode.selectChildren( schema.getString( "ActionList" ) ) )
        {
            for( final Entry tmpl : list.selectChildren( schema.getString( "AnimationTemplate" ) ) )
            {
                final String tmplName = tmpl.getAttribute( schema.getString( "Name" ) );
                if( tmplName != null )
                    animationTemplates.put( tmplName,
                        tmpl.selectChildren( schema.getString( "Pose" ) ) );
            }
        }

        for( final Entry list : configurationNode.selectChildren( schema.getString( "ActionList" ) ) )
        {
            log.log( Level.INFO, "Action List..." );

            for( final Entry node : list.selectChildren( schema.getString( "Action" ) ) )
            {
                final ActionBuilder action = new ActionBuilder( this, node, imageSet );

                if( getActionBuilders( ).containsKey( action.getName( ) ) )
                {
                    // Per-image-set actions override global ones — skip duplicates silently
                    // rather than throwing, so additive loading works correctly.
                    log.log( Level.INFO, "Action already defined, overriding: " + action.getName( ) );
                }

                getActionBuilders( ).put( action.getName( ), action );
            }
        }

        for( final Entry list : configurationNode.selectChildren( schema.getString( "BehaviourList" ) ) )
        {
            log.log( Level.INFO, "Behavior List..." );

            loadBehaviors( list, new ArrayList<String>( ) );
        }

        for( final Entry list : configurationNode.selectChildren( schema.getString( "TransitionList" ) ) )
        {
            log.log( Level.INFO, "Transition List..." );

            loadTransitions( list, imageSet );
        }

        for( final Entry list : configurationNode.selectChildren( schema.getString( "Information" ) ) )
        {
            log.log( Level.INFO, "Information List..." );
            
            loadInformation( list );
        }

        log.log( Level.INFO, "Configuration loaded successfully" );
    }

	private void loadBehaviors( final Entry list, final List<String> conditions )
        {
            for( final Entry node : list.getChildren( ) )
            {
                if( node.getName( ).equals( schema.getString( "Condition" ) ) )
                {
                    final List<String> newConditions = new ArrayList<String>( conditions );
                    newConditions.add( node.getAttribute( schema.getString( "Condition" ) ) );

                    loadBehaviors(node, newConditions);
                }
                else if( node.getName( ).equals( schema.getString( "Behaviour" ) ) )
                {
                    final BehaviorBuilder behavior = new BehaviorBuilder( this, node, conditions );
                    this.getBehaviorBuilders( ).put( behavior.getName( ), behavior );
                }
            }
	}

    /**
     * Parses a {@code <TransitionList>} of {@code <Transition>} elements. Each transition
     * names a set of {@code Before} behaviors and {@code After} behaviors (comma-separated);
     * when the mascot naturally moves from a Before behavior into an After behavior, a short
     * tween behavior is inserted first (see {@link #maybeTransition}). The tween's frames come
     * either from inline {@code <Animation>} children (built as an Animate action) or from a
     * named {@code Action="..."}. {@code Bidirectional="true"} also matches the reverse direction.
     */
    private void loadTransitions( final Entry list, final String imageSet ) throws ConfigurationException
    {
        for( final Entry node : list.selectChildren( schema.getString( "Transition" ) ) )
        {
            final String beforeRaw = node.getAttribute( schema.getString( "Before" ) );
            final String afterRaw  = node.getAttribute( schema.getString( "After" ) );
            if( beforeRaw == null || afterRaw == null )
            {
                log.log( Level.WARNING, "Transition missing Before/After attribute — skipping" );
                continue;
            }

            final Set<String> before = splitNames( beforeRaw );
            final Set<String> after  = splitNames( afterRaw );
            final boolean bidirectional = Boolean.parseBoolean( node.getAttribute( schema.getString( "Bidirectional" ) ) );
            final String actionName = node.getAttribute( schema.getString( "Action" ) );

            List<AnimationBuilder> animationBuilders = null;
            Map<String, String> params = null;

            if( actionName == null )
            {
                animationBuilders = new ArrayList<AnimationBuilder>( );
                for( final Entry anim : node.selectChildren( schema.getString( "Animation" ) ) )
                    animationBuilders.add( new AnimationBuilder( schema, anim, imageSet, animationTemplates ) );

                if( animationBuilders.isEmpty( ) )
                {
                    log.log( Level.WARNING, "Transition {0} -> {1} has neither Action nor Animation — skipping",
                             new Object[ ] { beforeRaw, afterRaw } );
                    continue;
                }

                // Remaining attributes become Animate params (BorderType, Draggable, Condition, etc.).
                params = new LinkedHashMap<String, String>( node.getAttributes( ) );
                params.remove( schema.getString( "Before" ) );
                params.remove( schema.getString( "After" ) );
                params.remove( schema.getString( "Bidirectional" ) );
            }

            transitions.add( new TransitionEntry( before, after, bidirectional, actionName, animationBuilders, params ) );
            log.log( Level.INFO, "Loaded transition {0} -> {1}{2}",
                     new Object[ ] { beforeRaw, afterRaw, bidirectional ? " (bidirectional)" : "" } );
        }
    }

    private Set<String> splitNames( final String raw )
    {
        final Set<String> set = new LinkedHashSet<String>( );
        for( final String s : raw.split( "," ) )
        {
            final String trimmed = s.trim( );
            if( !trimmed.isEmpty( ) )
                set.add( trimmed );
        }
        return set;
    }

    /**
     * If a registered transition matches the move from {@code previousName} into
     * {@code nextName}, returns a {@link TransitionBehavior} that plays the tween and then
     * advances to {@code nextName}. Returns null when nothing matches (or building the tween
     * fails), so the caller falls back to the directly-chosen behavior.
     */
    private Behavior maybeTransition( final String previousName, final String nextName, final Mascot mascot )
    {
        if( transitions.isEmpty( ) || previousName == null || nextName == null )
            return null;

        for( final TransitionEntry t : transitions )
        {
            if( t.matches( previousName, nextName ) )
            {
                try
                {
                    return buildTransitionBehavior( t, nextName );
                }
                catch( final BehaviorInstantiationException e )
                {
                    log.log( Level.WARNING, "Failed to build transition " + previousName + " -> " + nextName
                                          + "; using direct behavior instead", e );
                    return null;
                }
            }
        }
        return null;
    }

    private Behavior buildTransitionBehavior( final TransitionEntry t, final String targetName )
        throws BehaviorInstantiationException
    {
        try
        {
            return new TransitionBehavior( targetName, buildTweenAction( t ), this, targetName );
        }
        catch( final Exception e )
        {
            throw new BehaviorInstantiationException( "Failed to build transition to " + targetName, e );
        }
    }

    /**
     * Action-boundary counterpart to {@link #maybeTransition}: if a registered transition matches
     * the step from action {@code previousName} into action {@code nextName} (as named by their
     * {@code <ActionReference>}s inside a Sequence), returns the tween Action to splice between
     * them; otherwise null. Called by {@link ActionBuilder} when building a Sequence's children, so
     * one {@code <TransitionList>} drives both behavior-level and action-level (intra-sequence) tweens.
     */
    Action buildActionTween( final String previousName, final String nextName )
    {
        if( transitions.isEmpty( ) || previousName == null || nextName == null )
            return null;

        for( final TransitionEntry t : transitions )
        {
            if( t.matches( previousName, nextName ) )
            {
                try
                {
                    return buildTweenAction( t );
                }
                catch( final Exception e )
                {
                    log.log( Level.WARNING, "Failed to build action tween " + previousName + " -> " + nextName
                                          + "; leaving sequence unchanged", e );
                    return null;
                }
            }
        }
        return null;
    }

    /** Builds the tween's Action from a transition rule (named Action, or inline Animate). */
    private Action buildTweenAction( final TransitionEntry t ) throws Exception
    {
        if( t.actionName != null )
            return buildAction( t.actionName, new HashMap<String, String>( ) );

        final List<Animation> animations = new ArrayList<Animation>( );
        for( final AnimationBuilder ab : t.animationBuilders )
            animations.add( ab.buildAnimation( ) );

        final VariableMap variables = new VariableMap( );
        for( final Map.Entry<String, String> param : t.params.entrySet( ) )
            variables.put( param.getKey( ), Variable.parse( param.getValue( ) ) );

        return new Animate( schema, animations, variables );
    }

    /** A single before/after transition rule plus the tween it plays. */
    private static final class TransitionEntry
    {
        private final Set<String> before;
        private final Set<String> after;
        private final boolean bidirectional;
        private final String actionName;                       // null -> use inline animations
        private final List<AnimationBuilder> animationBuilders; // used when actionName == null
        private final Map<String, String> params;              // inline Animate params

        TransitionEntry( final Set<String> before, final Set<String> after, final boolean bidirectional,
                         final String actionName, final List<AnimationBuilder> animationBuilders,
                         final Map<String, String> params )
        {
            this.before = before;
            this.after = after;
            this.bidirectional = bidirectional;
            this.actionName = actionName;
            this.animationBuilders = animationBuilders;
            this.params = params;
        }

        boolean matches( final String previousName, final String nextName )
        {
            if( before.contains( previousName ) && after.contains( nextName ) )
                return true;
            return bidirectional && after.contains( previousName ) && before.contains( nextName );
        }
    }

	public Action buildAction(final String name, final Map<String, String> params) throws ActionInstantiationException {

		final ActionBuilder factory = this.actionBuilders.get(name);
		if (factory == null) {
			throw new ActionInstantiationException( Main.getInstance( ).getLanguageBundle( ).getString( "NoCorrespondingActionFoundErrorMessage" ) + ": " + name);
		}

		return factory.buildAction( params );
	}
        
    private void loadInformation( final Entry list )
    {
        for( final Entry node : list.getChildren( ) )
        {
            if( node.getName( ).equals( schema.getString( "Name" ) ) ||
                node.getName( ).equals( schema.getString( "PreviewImage" ) ) ||
                node.getName( ).equals( schema.getString( "SplashImage" ) ) ||
                node.getName( ).equals( schema.getString( "Personality" ) ) ||
                node.getName( ).equals( schema.getString( "VoiceTrigger" ) ) ||
                node.getName( ).equals( schema.getString( "SpeechRule" ) ) ||
                node.getName( ).equals( schema.getString( "ThirdPersonRewrite" ) ) ||
                node.getName( ).equals( schema.getString( "PersonalityBrief" ) ) )
            {
                information.put( node.getName( ), node.getText( ) );
            }
            else if( node.getName( ).equals( schema.getString( "Artist" ) ) ||
                     node.getName( ).equals( schema.getString( "Scripter" ) ) || 
                     node.getName( ).equals( schema.getString( "Commissioner" ) ) ||
                     node.getName( ).equals( schema.getString( "Support" ) ) )
            {
                String nameText = node.getAttribute( schema.getString( "Name" ) ) != null ? node.getAttribute( schema.getString( "Name" ) ) : null;
                String linkText = node.getAttribute( schema.getString( "URL" ) ) != null ? node.getAttribute( schema.getString( "URL" ) ) : null;
                
                if( nameText != null )
                {
                    information.put( node.getName( ) + schema.getString( "Name" ), nameText );
                    if( linkText != null )
                        information.put( node.getName( ) + schema.getString( "URL" ), linkText );
                }
            }
        }
    }

	public void validate() throws ConfigurationException{

		for(final ActionBuilder builder : getActionBuilders().values()) {
			builder.validate();
		}
		for(final BehaviorBuilder builder : getBehaviorBuilders().values()) {
			builder.validate();
		}
	}

    /**
     * Returns true if the behavior is allowed to run when the mascot is in manualOnly mode.
     * Allowed set: Fall, Dragged, Thrown, behaviors containing "Stand" or "GrabWall",
     * and transition/utility behaviors used as successors of hotkey actions
     * (DashBrake, Move* variants, etc.).
     */
    private boolean isManualOnlyAllowed( final String behaviorName )
    {
        String n = behaviorName.toLowerCase( );
        return n.equals( schema.getString( UserBehavior.BEHAVIOURNAME_FALL    ).toLowerCase( ) )
            || n.equals( schema.getString( UserBehavior.BEHAVIOURNAME_DRAGGED ).toLowerCase( ) )
            || n.equals( schema.getString( UserBehavior.BEHAVIOURNAME_THROWN  ).toLowerCase( ) )
            || n.contains( "stand" )
            || n.contains( "grabwall" )
            || n.contains( "wall" )
            || n.contains( "ceiling" )
            || n.contains( "fall" )
            || n.contains( "slide" )
            || n.contains( "dashbrake" )
            || n.contains( "dash" )
            || n.contains( "moveleft" )
            || n.contains( "moveright" );
    }

    public Behavior buildNextBehavior( final String previousName, final Mascot mascot ) throws BehaviorInstantiationException
    {
        final VariableMap context = new VariableMap( );
        context.putAll( getConstants( ) ); // put first so they can't override mascot
        context.put( "mascot", mascot );
        context.put( "scaling", Double.parseDouble( Main.getInstance( ).getProperties( ).getProperty( "Scaling", "1.0" ) ) );

        final List<BehaviorBuilder> candidates = new ArrayList<BehaviorBuilder>( );
        long totalFrequency = 0;

        // In ManualOnly mode, skip the global random pool entirely — the mascot should
        // only follow explicitly defined NextBehaviorList entries.  The global pool is
        // still used in normal mode.
        if( !mascot.isManualOnly( ) )
        {
            for( final BehaviorBuilder behaviorFactory : this.getBehaviorBuilders( ).values( ) )
            {
                try
                {
                    if( behaviorFactory.isEffective( context ) && isBehaviorEnabled( behaviorFactory, mascot ) )
                    {
                        candidates.add( behaviorFactory );
                        totalFrequency += behaviorFactory.getFrequency( );
                    }
                }
                catch( final VariableException e )
                {
                    log.log( Level.WARNING, "An error occurred calculating the frequency of the action", e );
                }
            }
        }

        if( previousName != null )
        {
            final BehaviorBuilder previousBehaviorFactory = this.getBehaviorBuilders( ).get( previousName );
            if( previousBehaviorFactory == null )
            {
                // previousName not in this config (e.g. synthetic hold-loop behavior) —
                // return null so the mascot disposes cleanly (e.g. after SelfDestruct).
                return null;
            }
            else if( !previousBehaviorFactory.isNextAdditive( ) )
            {
                totalFrequency = 0;
                candidates.clear( );
            }
            for( final BehaviorBuilder behaviorFactory : previousBehaviorFactory.getNextBehaviorBuilders( ) )
            {
                try
                {
                    if( behaviorFactory.isEffective( context ) && isBehaviorEnabled( behaviorFactory, mascot ) )
                    {
                        candidates.add( behaviorFactory );
                        totalFrequency += behaviorFactory.getFrequency( );
                    }
                }
                catch( final VariableException e )
                {
                    log.log( Level.WARNING, "An error occurred calculating the frequency of the behavior", e );
                }
            }
        }

        // In ManualOnly mode with no NextBehaviorList candidates, fall back to StandUp
        // so the mascot waits for input rather than teleporting to the top of the screen.
        if( mascot.isManualOnly( ) && totalFrequency == 0 )
        {
            // Try common stand behavior names
            for( String standName : new String[]{ "StandUp", "Stand", "Idle" } )
            {
                if( getBehaviorBuilders( ).containsKey( standName ) )
                    return getBehaviorBuilders( ).get( standName ).buildBehavior( );
            }
            // No stand behavior found — return null and let the mascot idle in place
            return null;
        }

        if( totalFrequency == 0 )
{
        if( Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "Multiscreen", "true" ) ) )
            {
                mascot.setAnchor( new Point( (int)( Math.random( ) * ( mascot.getEnvironment( ).getScreen( ).getRight( ) - mascot.getEnvironment( ).getScreen( ).getLeft( ) ) ) + mascot.getEnvironment( ).getScreen( ).getLeft( ),
                                             mascot.getEnvironment( ).getScreen( ).getTop( ) - 256 ) );
            }
            else
            {
                mascot.setAnchor( new Point( (int)( Math.random( ) * ( mascot.getEnvironment( ).getWorkArea( ).getRight( ) - mascot.getEnvironment( ).getWorkArea( ).getLeft( ) ) ) + mascot.getEnvironment( ).getWorkArea( ).getLeft( ),
                                             mascot.getEnvironment( ).getWorkArea( ).getTop( ) - 256 ) );
            }
            return buildBehavior( schema.getString( UserBehavior.BEHAVIOURNAME_FALL ) );
        }

        double random = Math.random( ) * totalFrequency;

        for( final BehaviorBuilder behaviorFactory : candidates )
        {
            random -= behaviorFactory.getFrequency( );
            if( random < 0 )
            {
                final Behavior transition = maybeTransition( previousName, behaviorFactory.getName( ), mascot );
                if( transition != null )
                    return transition;
                return behaviorFactory.buildBehavior( );
            }
        }

        return null;
    }

    public Behavior buildBehavior( final String name, final Mascot mascot ) throws BehaviorInstantiationException
    {
        if( behaviorBuilders.containsKey( name ) )
        {
            if( isBehaviorEnabled( name, mascot ) )
            {
                return getBehaviorBuilders( ).get( name ).buildBehavior( );
            }
            else
            {
                final java.awt.Point recovery = mascot.getSavedAnchor( );
                if( recovery != null )
                {
                    mascot.setAnchor( new java.awt.Point( recovery.x, recovery.y ) );
                    final String savedBehavior = mascot.getSavedBehaviorName( );
                    if( savedBehavior != null && behaviorBuilders.containsKey( savedBehavior ) )
                        return buildBehavior( savedBehavior );
                }
                else if( Boolean.parseBoolean( Main.getInstance( ).getProperties( ).getProperty( "Multiscreen", "true" ) ) )
                {
                    mascot.setAnchor( new Point( (int)( Math.random( ) * ( mascot.getEnvironment( ).getScreen( ).getRight( ) - mascot.getEnvironment( ).getScreen( ).getLeft( ) ) ) + mascot.getEnvironment( ).getScreen( ).getLeft( ),
                                                 mascot.getEnvironment( ).getScreen( ).getTop( ) - 256 ) );
                }
                else
                {
                    mascot.setAnchor( new Point( (int)( Math.random( ) * ( mascot.getEnvironment( ).getWorkArea( ).getRight( ) - mascot.getEnvironment( ).getWorkArea( ).getLeft( ) ) ) + mascot.getEnvironment( ).getWorkArea( ).getLeft( ),
                                                 mascot.getEnvironment( ).getWorkArea( ).getTop( ) - 256 ) );
                }
                return buildBehavior( schema.getString( UserBehavior.BEHAVIOURNAME_FALL ) );
            }
        }
        else
            throw new BehaviorInstantiationException( Main.getInstance( ).getLanguageBundle( ).getString( "NoBehaviourFoundErrorMessage" ) + " (" + name + ")" );
    }

    public Behavior buildBehavior( final String name ) throws BehaviorInstantiationException
    {
        if( behaviorBuilders.containsKey( name ) )
            return getBehaviorBuilders( ).get( name ).buildBehavior( );
        else
            throw new BehaviorInstantiationException( Main.getInstance( ).getLanguageBundle( ).getString( "NoBehaviourFoundErrorMessage" ) + " (" + name + ")" );
    }
    
    public boolean hasBehavior( final String name )
    {
        return behaviorBuilders.containsKey( name );
    }

    public boolean isBehaviorEnabled( final BehaviorBuilder builder, final Mascot mascot )
    {
        if( builder.isToggleable( ) )
        {
            // Check per-mascot disabled list first
            String perMascotRaw = Main.getInstance( ).getProperties( ).getProperty( "DisabledBehaviours.mascot" + mascot.getId( ), null );
            String raw = perMascotRaw != null ? perMascotRaw
                : Main.getInstance( ).getProperties( ).getProperty( "DisabledBehaviours.imageset." + mascot.getImageSet( ), "" );
            for( String behaviour : raw.split( "/" ) )
            {
                if( behaviour.equals( builder.getName( ) ) )
                    return false;
            }
        }
        return true;
    }
    
    public boolean isBehaviorEnabled( final String name, final Mascot mascot )
    {
        if( behaviorBuilders.containsKey( name ) )
            return isBehaviorEnabled( getBehaviorBuilders( ).get( name ), mascot );
        else
            return false;
    }
    
    public boolean isBehaviorHidden( final String name )
    {
        if( behaviorBuilders.containsKey( name ) )
            return getBehaviorBuilders( ).get( name ).isHidden( );
        else
            return false;
    }
    
    public boolean isBehaviorToggleable( final String name )
    {
        if( behaviorBuilders.containsKey( name ) )
            return getBehaviorBuilders( ).get( name ).isToggleable( );
        else
            return false;
    }

    public boolean isBehaviorClearTintOnDisable( final String name )
    {
        if( behaviorBuilders.containsKey( name ) )
            return getBehaviorBuilders( ).get( name ).isClearTintOnDisable( );
        else
            return false;
    }
    
    private Map<String, String> getConstants( )
    {
        return constants;
    }

    public Map<String, ActionBuilder> getActionBuilders( )
    {
        return actionBuilders;
    }

    /** Returns the ActionBuilder for the named action, or null if not found. */
    public ActionBuilder getActionBuilderFor( final String name )
    {
        return actionBuilders.get( name );
    }

    public Map<String, BehaviorBuilder> getBehaviorBuilders( )
    {
        return behaviorBuilders;
    }

    public java.util.Set<String> getBehaviorNames( )
    {
        return behaviorBuilders.keySet( );
    }

    public Map<String, java.util.List<Entry>> getAnimationTemplates()
    {
        return animationTemplates;
    }

    public boolean containsInformationKey( String key )
    {
        return information.containsKey( key );
    }

    public String getInformation( String key )
    {
        return information.get( key );
    }
        
    public java.util.ResourceBundle getSchema( )
    {
        return schema;
    }
}
