package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.exception.BehaviorInstantiationException;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SyncedStay variant that watches for a nearby mascot advertising a
 * TriggerAffordance and, when one is found within Proximity pixels,
 * transitions THIS mascot to TriggerBehavior entirely from within its own
 * tick.  No cross-mascot setBehavior calls from scripts are needed.
 *
 * XML usage:
 *   <Action Name="Stand" Type="Embedded"
 *           Class="com.group_finity.mascot.action.AffordanceStay"
 *           Affordance="BlockHittable"
 *           TriggerAffordance="BlockHitSignal"
 *           TriggerBehavior="SpawnContents"
 *           Proximity="80">
 *     <Animation> ... </Animation>
 *   </Action>
 */
public class AffordanceStay extends BorderedAction {

    private static final Logger log = Logger.getLogger(AffordanceStay.class.getName());

    private static final long MS_PER_TICK = 40L;

    public static final String PARAMETER_TRIGGER_AFFORDANCE = "TriggerAffordance";
    private static final String DEFAULT_TRIGGER_AFFORDANCE = "";

    public static final String PARAMETER_TRIGGER_BEHAVIOUR = "TriggerBehavior";
    private static final String DEFAULT_TRIGGER_BEHAVIOUR = "";

    public static final String PARAMETER_PROXIMITY = "Proximity";
    private static final int DEFAULT_PROXIMITY = 80;

    public AffordanceStay(java.util.ResourceBundle schema,
                          final List<Animation> animations,
                          final VariableMap params) {
        super(schema, animations, params);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        final Animation anim = getAnimation();
        if (anim == null) return;
        final int duration = anim.getDuration();
        if (duration <= 0) return;

        final long globalTick = System.nanoTime() / (MS_PER_TICK * 1_000_000L);
        setTime((int)(globalTick % duration));
    }

    @Override
    public boolean hasNext() throws VariableException {
        return super.hasNext();
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        super.tick();

        if (getBorder() != null && !getBorder().isOn(getMascot().getAnchor())) {
            log.log(Level.INFO, "Lost Ground ({0},{1})", new Object[]{ getMascot(), this });
            throw new LostGroundException();
        }

        getAnimation().next(getMascot(), getTime());

        final String triggerAffordance = getTriggerAffordance();
        final String triggerBehavior   = getTriggerBehaviour();
        if (triggerAffordance.trim().isEmpty() || triggerBehavior.trim().isEmpty()) return;
        if (getMascot().getManager() == null) return;

        final String[] triggerAffordances = triggerAffordance.split(",");
        final int proximity = getProximity();

        for (Mascot other : getMascot().getManager().getMascotList()) {
            if (other == getMascot()) continue;
            boolean matches = false;
            for (String ta : triggerAffordances) {
                if (other.getAffordances().contains(ta.trim())) { matches = true; break; }
            }
            if (!matches) continue;
            final int dx = Math.abs(other.getAnchor().x - getMascot().getAnchor().x);
            final int signedDy = other.getAnchor().y - getMascot().getAnchor().y; // positive = Mario is below coin
            final int dy = Math.abs(signedDy);
            // Use tight vertical check when Mario is below the coin (jumping up toward it)
            // to avoid triggering too early. Looser when Mario is at or above coin level.
            final int dyLimit = signedDy > 0 ? proximity : proximity * 4;
            if (dx <= proximity && dy <= dyLimit) {
                try {
                    getMascot().setBehavior(
                        Main.getInstance()
                            .getConfiguration(getMascot().getImageSet())
                            .buildBehavior(triggerBehavior, getMascot())
                    );
                } catch (BehaviorInstantiationException | CantBeAliveException e) {
                    log.log(Level.SEVERE, "AffordanceStay: failed to set trigger behavior", e);
                }
                return;
            }
        }
    }

    @Override
    public Animation getAnimation() throws VariableException {
        for (Animation a : super.getAnimations()) {
            if (a.isEffective(getVariables())) return a;
        }
        return null;
    }

    private String getTriggerAffordance() throws VariableException {
        return eval(PARAMETER_TRIGGER_AFFORDANCE, String.class, DEFAULT_TRIGGER_AFFORDANCE);
    }

    private String getTriggerBehaviour() throws VariableException {
        return eval(PARAMETER_TRIGGER_BEHAVIOUR, String.class, DEFAULT_TRIGGER_BEHAVIOUR);
    }

    private int getProximity() throws VariableException {
        return eval(PARAMETER_PROXIMITY, Number.class, DEFAULT_PROXIMITY).intValue();
    }
}
