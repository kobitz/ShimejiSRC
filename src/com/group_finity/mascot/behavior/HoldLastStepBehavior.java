package com.group_finity.mascot.behavior;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.awt.event.MouseEvent;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.action.Action;
import com.group_finity.mascot.action.ActionBase;
import com.group_finity.mascot.config.Configuration;
import com.group_finity.mascot.exception.BehaviorInstantiationException;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;

/**
 * A synthetic behavior used by the hold-to-loop engine to repeat only the
 * last action of a Sequence while a hotkey is held.
 *
 * When a !hold hotkey fires a Sequence behavior (e.g. MoveLeft which plays
 * Walk then Run), the whole Sequence plays once. After it ends, Manager.tick()
 * creates a HoldLastStepBehavior wrapping just the last child action (Run).
 * This behavior loops that single action while the key stays held.
 *
 * On key release, Manager stops re-creating this behavior and it ends
 * naturally, at which point buildNextBehavior() is called on the PARENT
 * behavior (the original held behavior name) so the correct NextBehaviorList
 * fires (e.g. Run → DashBrake).
 */
public class HoldLastStepBehavior implements Behavior
{
    private static final Logger log = Logger.getLogger( HoldLastStepBehavior.class.getName() );

    /** Synthetic name used for identity checks in Manager.tick(). */
    private final String name;

    /** The single action to run (e.g. the Run action). */
    private final Action action;

    /** Configuration used to look up the parent NextBehaviorList. */
    private final Configuration configuration;

    /**
     * The original held behavior name (e.g. "MoveLeft") whose NextBehaviorList
     * should fire when this behavior ends (i.e. when the key is released).
     */
    private final String parentBehaviorName;

    /**
     * Affordance from the parent action tag (e.g. "SmallMarioHittable").
     * Applied on every init() so the mascot keeps its correct affordance
     * while the loop action (which has no Affordance of its own) runs.
     */
    private final String parentAffordance;

    private Mascot mascot;

    public HoldLastStepBehavior( final String name,
                                  final Action action,
                                  final Configuration configuration,
                                  final String parentBehaviorName,
                                  final String parentAffordance )
    {
        this.name               = name;
        this.action             = action;
        this.configuration      = configuration;
        this.parentBehaviorName = parentBehaviorName;
        this.parentAffordance   = parentAffordance;
    }

    public String getName()  { return name; }
    public Action getAction() { return action; }

    @Override
    public synchronized void init( final Mascot mascot ) throws CantBeAliveException
    {
        this.mascot = mascot;
        log.log( Level.INFO, "HoldLastStepBehavior init ({0},{1})", new Object[]{ mascot, name } );
        try
        {
            // Restore parent affordance before the child action inits — the child
            // action has no Affordance of its own and would otherwise clear it.
            if( parentAffordance != null && !parentAffordance.isEmpty() )
            {
                mascot.getAffordances().clear();
                mascot.getAffordances().add( parentAffordance );
            }
            action.init( mascot );
            if( !action.hasNext() )
            {
                // Action is already done at init — go straight to next behavior
                fireNextBehavior();
            }
        }
        catch( final VariableException e )
        {
            throw new CantBeAliveException(
                Main.getInstance().getLanguageBundle().getString( "VariableEvaluationErrorMessage" ), e );
        }
    }

    @Override
    public synchronized void next() throws CantBeAliveException
    {
        try
        {
            if( action.hasNext() )
            {
                action.next();
            }

            if( !action.hasNext() )
            {
                log.log( Level.INFO, "HoldLastStepBehavior complete ({0},{1})", new Object[]{ mascot, name } );
                fireNextBehavior();
            }
        }
        catch( final LostGroundException e )
        {
            log.log( Level.INFO, "HoldLastStepBehavior lost ground ({0},{1})", new Object[]{ mascot, name } );
            try
            {
                mascot.setCursorPosition( null );
                mascot.setDragging( false );
                mascot.setBehavior( configuration.buildBehavior(
                    configuration.getSchema().getString( UserBehavior.BEHAVIOURNAME_FALL ) ) );
            }
            catch( final BehaviorInstantiationException ex )
            {
                throw new CantBeAliveException(
                    Main.getInstance().getLanguageBundle().getString( "FailedFallingActionInitialiseErrorMessage" ), ex );
            }
        }
        catch( final VariableException e )
        {
            throw new CantBeAliveException(
                Main.getInstance().getLanguageBundle().getString( "VariableEvaluationErrorMessage" ), e );
        }
    }

    /**
     * Fire the NextBehaviorList of the PARENT behavior (e.g. MoveLeft's Run → DashBrake).
     * This is called when the last-step action ends, which happens when the key is released
     * and Manager.tick() stops re-creating this behavior, letting it complete naturally.
     */
    private void fireNextBehavior() throws CantBeAliveException
    {
        // Guard: if parentBehaviorName is not in this config (e.g. Big Mario got a
        // MoveLeft hold-loop for a behavior it doesn't define), fall back to Fall.
        final String behaviorToFire = configuration.getBehaviorBuilders().containsKey( parentBehaviorName )
            ? parentBehaviorName
            : configuration.getSchema().getString( UserBehavior.BEHAVIOURNAME_FALL );
        try
        {
            mascot.setBehavior( configuration.buildNextBehavior( behaviorToFire, mascot ) );
        }
        catch( final BehaviorInstantiationException e )
        {
            throw new CantBeAliveException(
                Main.getInstance().getLanguageBundle().getString( "FailedInitialiseFollowingActionsErrorMessage" ), e );
        }
    }

    @Override
    public void mousePressed( final MouseEvent e ) throws CantBeAliveException { }

    @Override
    public void mouseReleased( final MouseEvent e ) throws CantBeAliveException { }
}
