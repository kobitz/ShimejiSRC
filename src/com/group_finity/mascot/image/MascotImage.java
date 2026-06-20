package com.group_finity.mascot.image;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;

import com.group_finity.mascot.NativeFactory;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */

public class MascotImage {

	private final NativeImage image;
	private final Point center;
	private final Dimension size;
	private final BufferedImage bufferedImage;

	// Original source PNG for this frame, retained so dynamic (currentScale) resizing can
	// re-rasterize from the full-resolution source instead of resampling this already-scaled
	// bitmap. Null for programmatically-built images (no on-disk source). sourceFlipped marks
	// the horizontally-mirrored (right-facing) variant so the re-rasterization mirrors to match.
	private java.nio.file.Path sourcePath;
	private boolean sourceFlipped;

	public MascotImage(final NativeImage image, final Point center, final Dimension size) {
		this.image = image;
		this.center = center;
		this.size = size;
		this.bufferedImage = null;
	}

	public MascotImage(final BufferedImage image, final Point center) {
		this.image = NativeFactory.getInstance().newNativeImage(image);
		this.center = center;
		this.size = new Dimension(image.getWidth(), image.getHeight());
		this.bufferedImage = image;
	}

	public NativeImage getImage() {
		return this.image;
	}

	public BufferedImage getBufferedImage() {
		return this.bufferedImage;
	}

	public Point getCenter() {
		return this.center;
	}

	public Dimension getSize() {
		return this.size;
	}

	public void setSource(final java.nio.file.Path sourcePath, final boolean flipped) {
		this.sourcePath = sourcePath;
		this.sourceFlipped = flipped;
	}

	public java.nio.file.Path getSourcePath() {
		return this.sourcePath;
	}

	public boolean isSourceFlipped() {
		return this.sourceFlipped;
	}

}
