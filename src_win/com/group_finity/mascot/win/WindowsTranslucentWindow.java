package com.group_finity.mascot.win;

import java.awt.Graphics;
import java.awt.Component;
import java.awt.Rectangle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JWindow;

import com.group_finity.mascot.image.NativeImage;
import com.group_finity.mascot.image.TranslucentWindow;
import com.group_finity.mascot.win.jna.BLENDFUNCTION;
import com.group_finity.mascot.win.jna.Gdi32;
import com.group_finity.mascot.win.jna.POINT;
import com.group_finity.mascot.win.jna.RECT;
import com.group_finity.mascot.win.jna.SIZE;
import com.group_finity.mascot.win.jna.User32;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * The image window with alpha.
 * {@link #setImage(WindowsNativeImage)} set in {@link WindowsNativeImage} can be displayed on the desktop.
 * 
 * {@link #setAlpha(int)} may be specified when the concentration of view.
 *
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
class WindowsTranslucentWindow extends JWindow implements TranslucentWindow {

	private static final long serialVersionUID = 1L;
	
	@Override
	public Component asComponent() {
		return this;
	}

	/**
	 * Draw a picture with a value of alpha.
	 * @param imageHandle bitmap handle.
	 * @param alpha concentrations shown. 0 = not at all, 255 = full display.
	 */
	private void paint(final Pointer imageHandle, final int alpha, final Rectangle targetBounds) {

		final Pointer hWnd = Native.getComponentPointer(this);

		if ( User32.INSTANCE.IsWindow(hWnd)!=0 ) {

			final int exStyle = User32.INSTANCE.GetWindowLongW(hWnd, User32.GWL_EXSTYLE);
			if ( (exStyle&User32.WS_EX_LAYERED)==0 ) {
				User32.INSTANCE.SetWindowLongW(hWnd, User32.GWL_EXSTYLE, exStyle | User32.WS_EX_LAYERED);
			}

			final Pointer clientDC = User32.INSTANCE.GetDC(hWnd);
			final Pointer memDC = Gdi32.INSTANCE.CreateCompatibleDC(clientDC);
			final Pointer oldBmp = Gdi32.INSTANCE.SelectObject(memDC, imageHandle);

			User32.INSTANCE.ReleaseDC(hWnd, clientDC);

			final BLENDFUNCTION bf = new BLENDFUNCTION();
			bf.BlendOp = BLENDFUNCTION.AC_SRC_OVER;
			bf.BlendFlags = 0;
			bf.SourceConstantAlpha = (byte)alpha;
			bf.AlphaFormat = BLENDFUNCTION.AC_SRC_ALPHA;

			final POINT lt = new POINT();
			final SIZE size = new SIZE();

			if ( targetBounds != null ) {
				// Atomic reposition + image update in one native call — eliminates one-frame glitch
				lt.x = targetBounds.x;
				lt.y = targetBounds.y;
				size.cx = targetBounds.width;
				size.cy = targetBounds.height;
			} else {
				// Repaint in place using current window rect
				final RECT windowRect = new RECT();
				User32.INSTANCE.GetWindowRect(hWnd, windowRect);
				lt.x = windowRect.left;
				lt.y = windowRect.top;
				size.cx = windowRect.Width();
				size.cy = windowRect.Height();
			}

			final POINT zero = new POINT();
			User32.INSTANCE.UpdateLayeredWindow(
					hWnd, Pointer.NULL,
					lt, size,
					memDC, zero, 0, bf, User32.ULW_ALPHA);

			Gdi32.INSTANCE.SelectObject(memDC, oldBmp);
			Gdi32.INSTANCE.DeleteDC(memDC);
		}
	}

	/**
	 * Image to display.
	 */
	private WindowsNativeImage image;

	/**
	 * The concentration shown. 0 = not at all, 255 = full display.
	 */
	private int alpha = 255;

	@Override
	public String toString() {
		return "LayeredWindow[hashCode="+hashCode()+",bounds="+getBounds()+"]";
	}
	
	@Override
	public void paint(final Graphics g) {
		if (getImage() != null) {
			// JNI with drawing images using the a value.
			paint(getImage().getHandle(), getAlpha(), null);
		}
	}

	private WindowsNativeImage getImage() {
		return this.image;
	}

	public void setImage(final NativeImage image) {
		this.image = (WindowsNativeImage)image;
	}

	public int getAlpha() {
		return this.alpha;
	}

	public void setAlpha(final int alpha) {
		this.alpha = alpha;
	}

	// Shared pool for the native GDI blit (UpdateLayeredWindow etc). Small and
	// bounded — this work is syscall-bound, not CPU-bound, so a handful of
	// threads covers even a large mascot population without oversubscribing.
	// Sized off the cores actually reserved for this process (see
	// ProcessPriorityUtil), not the system-wide core count — same reasoning
	// as TINT_POOL in Mascot.java. Both pools share the reserved budget, so
	// each gets a smaller slice to avoid the two pools alone saturating it.
	private static int paintPoolSize() {
		final int reserved = com.group_finity.mascot.environment.ProcessPriorityUtil.reservedCoreCount();
		if ( reserved <= 0 )
			return Math.max( 2, Math.min( 4, Runtime.getRuntime().availableProcessors() / 2 ) );
		return Math.max( 1, reserved - 1 );
	}

	private static final ExecutorService PAINT_POOL = Executors.newFixedThreadPool(
		paintPoolSize(),
		r -> { Thread t = new Thread( r, "mascot-paint" ); t.setDaemon( true ); return t; }
	);

	private static final class PaintRequest {
		final Pointer handle; final int alpha; final Rectangle bounds;
		PaintRequest( Pointer h, int a, Rectangle b ) { handle = h; alpha = a; bounds = b; }
	}

	private final AtomicBoolean paintInFlight = new AtomicBoolean( false );
	// Only the newest request matters — if the paint thread falls behind, older
	// in-between frames are dropped rather than queued, so a slow blit can't
	// build a backlog. Mirrors the dirty-check idea already used in Mascot.apply().
	private final AtomicReference<PaintRequest> pendingPaint = new AtomicReference<>();

        @Override

	public void updateImage( final java.awt.Rectangle bounds ) {
		if (getImage() == null) return;
		pendingPaint.set( new PaintRequest( getImage().getHandle(), getAlpha(), bounds ) );
		if ( paintInFlight.compareAndSet( false, true ) ) {
			PAINT_POOL.submit( this::drainPaintQueue );
		}
	}

	private void drainPaintQueue() {
		try {
			PaintRequest req;
			while ( ( req = pendingPaint.getAndSet( null ) ) != null ) {
				paint( req.handle, req.alpha, req.bounds );
			}
		} finally {
			paintInFlight.set( false );
			// Catch the race where a new request landed after the loop's last
			// check but before paintInFlight cleared.
			if ( pendingPaint.get() != null && paintInFlight.compareAndSet( false, true ) ) {
				PAINT_POOL.submit( this::drainPaintQueue );
			}
		}
	}

	public void updateImage() {
		repaint();
	}
}