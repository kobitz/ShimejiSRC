package com.group_finity.mascot.action;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * An action that plays a startup animation once, then loops a second animation
 * for a configurable (optionally random) duration, then ends naturally so the
 * behavior's NextBehaviorList fires.
 *
 * XML usage:
 *   <Action Name="MyAction" Type="AnimateWithLoop"
 *           Duration="${20 + Math.floor(Math.random() * 40)}"
 *           BorderType="Floor">
 *     <Animation Condition="true">          <!-- startup: plays once -->
 *       <Pose Image="startup1.png" ... />
 *       <Pose Image="startup2.png" ... />
 *     </Animation>
 *     <Animation Condition="true">          <!-- loop: repeats until Duration ticks elapsed -->
 *       <Pose Image="loop1.png" ... />
 *       <Pose Image="loop2.png" ... />
 *     </Animation>
 *   </Action>
 *
 * Duration is in ticks (same unit as all other actions).
 * If only one Animation is provided, it is used for both startup and loop.
 * If Duration is omitted it defaults to Integer.MAX_VALUE (loops indefinitely,
 * ending only when the behavior is interrupted externally).
 */
public class AnimateWithLoop extends BorderedAction
{
    private static final Logger log = Logger.getLogger( AnimateWithLoop.class.getName() );

    /** Parameter name for how long the loop phase runs (in ticks). */
    public static final String PARAMETER_LOOP_DURATION = "Duration";
    private static final int   DEFAULT_LOOP_DURATION   = Integer.MAX_VALUE;

    // --- runtime state, reset on each init() ---

    /** Duration of the loop phase, snapshotted at init() so random expressions evaluate once. */
    private int loopDuration;

    /** True once the startup animation has finished all its frames. */
    private boolean startupDone;

    /** The mascot time value recorded when the loop phase began. */
    private int loopStartTime;

    /** Cached startup animation (animations[0]). */
    private Animation startupAnimation;

    /** Cached loop animation (animations[1], or animations[0] if only one supplied). */
    private Animation loopAnimation;

    public AnimateWithLoop( final java.util.ResourceBundle schema,
                            final List<Animation> animations,
                            final VariableMap context )
    {
        super( schema, animations, context );
    }

    @Override
    public void init( final Mascot mascot ) throws VariableException
    {
        super.init( mascot );

        // Snapshot Duration once so random expressions don't re-roll every tick
        loopDuration = eval(
            getSchema().getString( PARAMETER_LOOP_DURATION ),
            Number.class,
            DEFAULT_LOOP_DURATION
        ).intValue();

        // Resolve animations: index 0 = startup, index 1 = loop (fallback to 0 if only one)
        startupAnimation = resolveAnimation( 0 );
        loopAnimation    = resolveAnimation( getAnimations().size() > 1 ? 1 : 0 );

        startupDone   = false;
        loopStartTime = -1;

        // If there's no startup animation (or it has zero duration), skip straight to loop
        if( startupAnimation == null || startupAnimation.getDuration() <= 0 )
        {
            startupDone   = true;
            loopStartTime = getMascot().getTime();
        }

        log.log( Level.INFO, "AnimateWithLoop init ({0}): loopDuration={1}",
            new Object[]{ mascot, loopDuration } );
    }

    @Override
    public boolean hasNext() throws VariableException
    {
        if( !super.hasNext() ) return false;

        if( !startupDone )
        {
            // Still in startup phase — keep going until startup frames exhausted
            return true;
        }

        // Loop phase: run until loopDuration ticks have elapsed
        int loopElapsed = getMascot().getTime() - loopStartTime;
        return loopElapsed < loopDuration;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException
    {
        // BorderedAction.tick() handles border-following (floor/ceiling/wall)
        super.tick();

        if( getBorder() != null && !getBorder().isOn( getMascot().getAnchor() ) )
        {
            log.log( Level.INFO, "AnimateWithLoop lost ground ({0})", getMascot() );
            throw new LostGroundException();
        }

        if( !startupDone )
        {
            // Play startup animation; it cycles by time within its own duration
            if( startupAnimation != null )
                startupAnimation.next( getMascot(), getTime() );

            // Check if startup is done: all frames have played at least once
            if( getTime() >= startupAnimation.getDuration() )
            {
                startupDone   = true;
                loopStartTime = getMascot().getTime();
                log.log( Level.INFO, "AnimateWithLoop startup complete ({0})", getMascot() );
            }
        }
        else
        {
            // Loop phase: cycle the loop animation indefinitely using modulo
            if( loopAnimation != null )
            {
                int loopElapsed = getMascot().getTime() - loopStartTime;
                int loopTime    = loopElapsed % loopAnimation.getDuration();
                loopAnimation.next( getMascot(), loopTime );
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns the effective animation at the given index in the animations list,
     * or null if out of range or not effective.
     */
    private Animation resolveAnimation( final int index ) throws VariableException
    {
        List<Animation> anims = getAnimations();
        if( index < 0 || index >= anims.size() ) return null;
        Animation a = anims.get( index );
        return ( a != null && a.isEffective( getVariables() ) ) ? a : null;
    }
}
