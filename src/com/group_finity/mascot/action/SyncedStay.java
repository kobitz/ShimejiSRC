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
 * A Stay-type action whose animation phase is locked to a global wall-clock,
 * so every mascot instance using this action always displays the same frame
 * at the same moment — regardless of when each mascot spawned or had its
 * action reset.
 *
 * Usage in actions.xml:
 *   <Action Name="Stand" Type="Embedded"
 *           Class="com.group_finity.mascot.action.SyncedStay">
 *     <Animation>
 *       <Pose Image="/shime1.png" ImageAnchor="64,216" Velocity="0,0" Duration="24" />
 *       <Pose Image="/shime4.png" ImageAnchor="64,216" Velocity="0,0" Duration="8"  />
 *       <Pose Image="/shime5.png" ImageAnchor="64,216" Velocity="0,0" Duration="8"  />
 *       <Pose Image="/shime4.png" ImageAnchor="64,216" Velocity="0,0" Duration="8"  />
 *     </Animation>
 *   </Action>
 *
 * How it works:
 *   Manager.TICK_INTERVAL is 40 ms per tick.
 *   We divide System.nanoTime() by that interval to get a "global tick counter"
 *   that advances at the same rate as mascot.getTime().
 *   On init() we set the action's internal startTime so that:
 *     getTime() = mascot.getTime() - startTime
 *                = globalTick % animationDuration
 *   meaning getPoseAt(getTime()) resolves to the same frame for every instance.
 *
 *   Because all mascots tick in lockstep (same Manager thread, same interval),
 *   they will stay in sync as long as they keep running SyncedStay.
 */
public class SyncedStay extends BorderedAction {

    private static final Logger log = Logger.getLogger(SyncedStay.class.getName());

    /** Milliseconds per tick, matching Manager.TICK_INTERVAL. */
    private static final long MS_PER_TICK = 40L;

    public SyncedStay(java.util.ResourceBundle schema,
                      final List<Animation> animations,
                      final VariableMap params) {
        super(schema, animations, params);
    }

    /**
     * Overrides init() to fast-forward the action's internal time so the
     * animation phase matches the global wall-clock phase.
     */
    @Override
    public void init(final Mascot mascot) throws VariableException {
        // Let the superclass do its normal setup (sets mascot, time=0, etc.)
        super.init(mascot);

        // Compute total animation duration in ticks.
        final Animation anim;
        try {
            anim = getAnimation();
        } catch (VariableException e) {
            // Can't determine animation — leave phase at 0.
            return;
        }
        if (anim == null) {
            return;
        }
        final int duration = anim.getDuration();
        if (duration <= 0) {
            return;
        }

        // Convert wall-clock time to a tick count at the same rate the
        // Manager advances mascot.getTime().
        final long globalTick = System.nanoTime() / (MS_PER_TICK * 1_000_000L);

        // The phase we want: how far into the animation cycle we should be right now.
        final int targetPhase = (int)(globalTick % duration);

        // ActionBase.getTime() = mascot.getTime() - startTime.
        // We want getTime() == targetPhase at this moment, so:
        //   startTime = mascot.getTime() - targetPhase
        // setTime(x) sets startTime = mascot.getTime() - x, so:
        setTime(targetPhase);
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        super.tick();

        // Throw if we've left the required border (same as Stay).
        if ((getBorder() != null) && !getBorder().isOn(getMascot().getAnchor())) {
            log.log(Level.INFO, "Lost Ground ({0},{1})", new Object[]{ getMascot(), this });
            throw new LostGroundException();
        }

        getAnimation().next(getMascot(), getTime());
    }
}
