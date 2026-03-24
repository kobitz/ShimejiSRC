package com.group_finity.mascot.image;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */

public interface NativeImage {
    /**
     * Updates the pixel content of this native image in-place.
     * Used by the runtime scaling system to avoid re-allocating
     * native bitmaps on every scale change.
     */
    default void updatePixels( java.awt.image.BufferedImage src ) { }
}
