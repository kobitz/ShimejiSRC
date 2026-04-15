package com.group_finity.mascot.image;

import java.awt.Component;
import java.awt.Rectangle;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */

public interface TranslucentWindow
{
    public Component asComponent( );

    public void setImage( NativeImage image );

    public void updateImage( );

    /**
     * Update image and reposition the window atomically.
     * Implementations that support atomic update (e.g. Windows UpdateLayeredWindow)
     * should override this to set both position and image in a single native call,
     * eliminating the one-frame glitch when ImageAnchor changes between poses.
     * The default falls back to setBounds + updateImage for platforms that don't support it.
     */
    default void updateImage( Rectangle bounds )
    {
        asComponent( ).setBounds( bounds );
        updateImage( );
    }
    
    public void dispose( );
    
    public void setAlwaysOnTop( boolean onTop );
}
