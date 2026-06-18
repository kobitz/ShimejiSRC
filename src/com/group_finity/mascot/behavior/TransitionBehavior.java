package com.group_finity.mascot.behavior;

import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

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
 * A short "tween" behavior inserted between two ordinary behaviors.
 *
 * It plays its own (usually 1-2 frame) action, then hands control to a fixed
 * successor behavior &mdash; the behavior originally chosen by
 * {@link Configuration#buildNextBehavior}. The successor is reached via
 * {@code buildBehavior} (NOT {@code buildNextBehavior}), so a transition can
 * never itself trigger another transition, and it is deliberately not reported
 * by {@code Mascot.getCurrentBehaviorName()} so tweens stay out of the behavior
 * history and tooltip. Drag/throw still interrupt it like any other behavior.
 */
public class TransitionBehavior implements Behavior
{
    private final String name;

    private final Action action;

    private final Configuration configuration;

    private final String targetBehaviorName;

    private Mascot mascot;

    public TransitionBehavior( final String name, final Action action,
                               final Configuration configuration, final String targetBehaviorName )
    {
        this.name = name;
        this.action = action;
        this.configuration = configuration;
        this.targetBehaviorName = targetBehaviorName;
    }

    public String getName( )
    {
        return name;
    }

    @Override
    public String toString( )
    {
        return "Transition(" + name + "->" + targetBehaviorName + ")";
    }

    @Override
    public synchronized void init( final Mascot mascot ) throws CantBeAliveException
    {
        this.mascot = mascot;
        try
        {
            action.init( mascot );
            if( !action.hasNext( ) )
                advanceToTarget( );
        }
        catch( final VariableException e )
        {
            throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "VariableEvaluationErrorMessage" ), e );
        }
    }

    @Override
    public synchronized void next( ) throws CantBeAliveException
    {
        try
        {
            if( action.hasNext( ) )
                action.next( );

            if( !action.hasNext( ) )
                advanceToTarget( );
        }
        catch( final LostGroundException e )
        {
            // The tween lost its footing (e.g. surface vanished) — fall, like UserBehavior.
            try
            {
                mascot.setCursorPosition( null );
                mascot.setDragging( false );
                mascot.setBehavior( configuration.buildBehavior( configuration.getSchema( ).getString( UserBehavior.BEHAVIOURNAME_FALL ) ) );
            }
            catch( final BehaviorInstantiationException ex )
            {
                throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "FailedFallingActionInitialiseErrorMessage" ), ex );
            }
        }
        catch( final VariableException e )
        {
            throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "VariableEvaluationErrorMessage" ), e );
        }
    }

    private void advanceToTarget( ) throws CantBeAliveException
    {
        try
        {
            mascot.setBehavior( configuration.buildBehavior( targetBehaviorName, mascot ) );
        }
        catch( final BehaviorInstantiationException e )
        {
            throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "FailedInitialiseFollowingBehaviourErrorMessage" ), e );
        }
    }

    @Override
    public synchronized void mousePressed( final MouseEvent event ) throws CantBeAliveException
    {
        if( !SwingUtilities.isLeftMouseButton( event ) )
            return;

        boolean handled = false;
        if( action instanceof ActionBase )
        {
            try
            {
                handled = !( (ActionBase) action ).isDraggable( );
            }
            catch( final VariableException ex )
            {
                throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "FailedDragActionInitialiseErrorMessage" ), ex );
            }
        }

        if( !handled )
        {
            try
            {
                mascot.setBehavior( configuration.buildBehavior( configuration.getSchema( ).getString( UserBehavior.BEHAVIOURNAME_DRAGGED ) ) );
            }
            catch( final BehaviorInstantiationException e )
            {
                throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "FailedDragActionInitialiseErrorMessage" ), e );
            }
        }
    }

    @Override
    public synchronized void mouseReleased( final MouseEvent event ) throws CantBeAliveException
    {
        if( !SwingUtilities.isLeftMouseButton( event ) )
            return;

        if( mascot.isDragging( ) )
        {
            try
            {
                mascot.setDragging( false );
                mascot.setBehavior( configuration.buildBehavior( configuration.getSchema( ).getString( UserBehavior.BEHAVIOURNAME_THROWN ) ) );
            }
            catch( final BehaviorInstantiationException e )
            {
                throw new CantBeAliveException( Main.getInstance( ).getLanguageBundle( ).getString( "FailedDropActionInitialiseErrorMessage" ), e );
            }
        }
    }
}
