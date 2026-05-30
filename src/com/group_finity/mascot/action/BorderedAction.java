package com.group_finity.mascot.action;

import java.util.List;
import java.util.logging.Logger;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.environment.Border;
import com.group_finity.mascot.exception.LostGroundException;
import com.group_finity.mascot.exception.VariableException;
import com.group_finity.mascot.script.VariableMap;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public abstract class BorderedAction extends ActionBase {

	private static final Logger log = Logger.getLogger(BorderedAction.class.getName());

	private static final String PARAMETER_BORDERTYPE = "BorderType";

	public static final String DEFAULT_BORDERTYPE = null;

	public static final String BORDERTYPE_CEILING = "Ceiling";

	public static final String BORDERTYPE_WALL = "Wall";

	public static final String BORDERTYPE_FLOOR = "Floor";

	private Border border;

    /** Cached border type string set at init, used by tick() for teleport handling. */
    private String cachedBorderType = null;

	public BorderedAction( java.util.ResourceBundle schema, final List<Animation> animations, final VariableMap context )
        {
            super( schema, animations, context );
	}

	@Override
	public void init(final Mascot mascot) throws VariableException {
		super.init(mascot);

		cachedBorderType = getBorderType();

		if( getSchema( ).getString( BORDERTYPE_CEILING ).equals( cachedBorderType ) ) {
			this.setBorder(getEnvironment().getCeiling());
		} else if( getSchema( ).getString( BORDERTYPE_WALL ).equals( cachedBorderType ) ) {
			this.setBorder(getEnvironment().getWall());
		} else if( getSchema( ).getString( BORDERTYPE_FLOOR ).equals( cachedBorderType ) ) {
			this.setBorder(getEnvironment().getFloor());
		}
	}

	@Override
	protected void tick() throws LostGroundException, VariableException {
        // Screen loop teleport: cancel any bordered action immediately after a teleport.
        // Wall-type actions (ClimbWall, GrabCeiling, etc.) would be targeting a workarea
        // border that no longer exists as a real wall. Floor/ceiling actions with stale
        // targets (Run, Dash, Walk) should also reset so the mascot picks a fresh behavior.
        // IE-targeting actions are unaffected since the flag is only set on workarea crossings.
        if( getMascot( ).getUserData( "screenLoopTeleportedRight" ) != null )
        {
            getMascot( ).setUserData( "screenLoopTeleportedRight", null );
            getMascot( ).setUserData( "jumpExitVelocityX", 0 );
            throw new LostGroundException( );
        }

		if (getBorder() != null) {
			getMascot().setAnchor(getBorder().move(getMascot().getAnchor()));
		}
	}

	private String getBorderType() throws VariableException {
		return eval( getSchema( ).getString( PARAMETER_BORDERTYPE ), String.class, DEFAULT_BORDERTYPE);
	}

	private void setBorder(final Border border) {
		this.border = border;
	}
	
	protected Border getBorder() {
		return this.border;
	}

}
