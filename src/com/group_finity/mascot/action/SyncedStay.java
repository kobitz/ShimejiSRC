package com.group_finity.mascot.action;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.group_finity.mascot.Manager;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * A Stay-type action whose animation phase is locked to Manager.globalSyncTick,
 * so every mascot instance using this action always displays the same frame
 * at the same moment — regardless of when each mascot spawned, was paused,
 * resumed, or had its action reset.
 *
 * The phase is re-applied on every tick(), not just init(), so any drift
 * from pausing, resuming, or behavior resets is corrected within one tick.
 *
 * XML usage:
 *   <Action Name="Stand" Type="Embedded"
 *           Class="com.group_finity.mascot.action.SyncedStay">
 *     <Animation>
 *       <Pose Image="/shime1.png" ImageAnchor="64,216" Velocity="0,0" Duration="24" />
 *       <Pose Image="/shime4.png" ImageAnchor="64,216" Velocity="0,0" Duration="8"  />
 *       <Pose Image="/shime5.png" ImageAnchor="64,216" Velocity="0,0" Duration="8"  />
 *       <Pose Image="/shime4.png" ImageAnchor="64,216" Velocity="0,0" Duration="8"  />
 *     </Animation>
 *   </Action>
 */
public class SyncedStay extends BorderedAction {

    private static final Logger log = Logger.getLogger(SyncedStay.class.getName());

    public SyncedStay(java.util.ResourceBundle schema,
                      final List<Animation> animations,
                      final VariableMap params) {
        super(schema, animations, params);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        syncPhase();
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        super.tick();

        if ((getBorder() != null) && !getBorder().isOn(getMascot().getAnchor())) {
            log.log(Level.INFO, "Lost Ground ({0},{1})", new Object[]{ getMascot(), this });
            throw new LostGroundException();
        }

        // Re-sync every tick so pause/resume and resets never cause drift.
        syncPhase();

        getAnimation().next(getMascot(), getTime());
    }

    /**
     * Sets the action's internal time so that getTime() == globalSyncTick % duration.
     * Called on init AND every tick for aggressive phase correction.
     */
    private void syncPhase() throws VariableException {
        final Animation anim = getAnimation();
        if (anim == null) return;
        final int duration = anim.getDuration();
        if (duration <= 0) return;

        final int targetPhase = (int)(Manager.globalSyncTick.get() % duration);
        setTime(targetPhase);
    }
}
