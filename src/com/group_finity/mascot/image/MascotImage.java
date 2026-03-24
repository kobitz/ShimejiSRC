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

}
