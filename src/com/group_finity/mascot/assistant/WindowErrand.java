package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.util.List;

/**
 * Runs a {@link WindowErrandPlan.Goal} to the end by asking the plan for the next step, having the
 * {@link Stage} perform it, and asking again. The stage is the mascot's body (jump, walk, hold the
 * window, press the button); this class only sequences and guards: a hard step limit, and a clean exit
 * when the window disappears or the user takes it. {@link Stage#finish} always runs last.
 */
public final class WindowErrand
{
    private WindowErrand() { }

    public interface Stage
    {
        /** Fresh state of the target window, null once it is gone. */
        WindowControl.Info window();
        Rectangle mascot();
        /** Jump at {@code step.at} (unless direct), then apply the operation. False if the system refused it. */
        boolean press( WindowErrandPlan.Step step );
        /** Come back down after a press made in mid-air, when the errand goes on (not called after the final step). */
        default void land() { }
        /** Go to the window's bottom corner and take hold of it. False if the mascot could not get there. */
        boolean grab( WindowErrandPlan.Step step );
        /** Walk with the window to {@code step.walkX}. False if the user took it or the walk failed. */
        boolean carry( WindowErrandPlan.Step step );
        /** Put the window down at its final place. */
        void release( Rectangle target );
        /** True once the user has taken the window away while we held it. */
        boolean taken();
        /** Always last: stop holding, hand the mascot back to its normal behavior. */
        void finish();
    }

    public enum Outcome { DONE, FAILED, TAKEN, GONE }

    static final int MAX_STEPS = 12;

    /** True when this press is the one the goal asked for (not a restore made only to free a corner to grab). */
    static boolean isFinalPress( final WindowErrandPlan.Goal goal, final WindowErrandPlan.Step step, final WindowControl.Info w )
    {
        if( step.direct || step.op == null || step.op != goal.finalOp || goal.region != null ) return false;
        return goal.toMonitor == null || ( w != null && WindowErrandPlan.onTarget( w.bounds, goal.toMonitor ) );
    }

    public static Outcome run( final Stage st, final WindowErrandPlan.Goal goal, final List<Rectangle> monitors, final double scale )
    {
        boolean held = false, heldLeft = true;
        Rectangle target = null;
        try
        {
            for( int i = 0; i < MAX_STEPS; i++ )
            {
                final WindowControl.Info w = st.window();
                final Rectangle from = w == null ? null : AgentLayout.screenFor( monitors, w.bounds, w.bounds );
                final WindowErrandPlan.Step step = WindowErrandPlan.next( goal,
                    new WindowErrandPlan.State( w, st.mascot(), from, held, heldLeft, scale ) );
                switch( step.kind )
                {
                    case DONE: return Outcome.DONE;
                    case FAIL: return Outcome.GONE;
                    case PRESS:
                        if( !st.press( step ) ) return Outcome.FAILED;
                        // The press that produces the goal ends the errand right here: re-planning from the window's
                        // state after the mascot has fallen and landed would redo it if the user changed the window
                        // meanwhile (restored/minimized it again), and the mascot lands via finish() anyway.
                        if( isFinalPress( goal, step, w ) ) return Outcome.DONE;
                        st.land();
                        break;
                    case GRAB:
                        if( !st.grab( step ) ) return Outcome.FAILED;
                        held = true; heldLeft = step.left; target = step.windowTarget;
                        break;
                    case CARRY:
                        if( !st.carry( step ) ) return st.taken() ? Outcome.TAKEN : Outcome.FAILED;
                        break;
                    case RELEASE:
                        st.release( target != null ? target : w.bounds );
                        held = false;
                        break;
                    default: return Outcome.FAILED;
                }
                if( held && st.taken() ) return Outcome.TAKEN;
            }
            return Outcome.FAILED;
        }
        finally { st.finish(); }
    }
}
