package dev.myriadclient.impl.render;

import java.util.ArrayDeque;

/**
 * GPU objects freed a couple of frames late. The UI is recorded before it's drawn, so a texture released while
 * recording (an evicted font page) may still be referenced by this frame's draws.
 */
public final class GpuGarbage {
	private static final int DELAY_FRAMES = 3;
	private static final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private static long frame;

	private record Entry(long frame, AutoCloseable resource) {
	}

	private GpuGarbage() {
	}

	public static void close(AutoCloseable resource) {
		if (resource != null) queue.add(new Entry(frame, resource));
	}

	/** Advances a frame and frees what's old enough. Render thread only. */
	public static void tick() {
		frame++;
		while (!queue.isEmpty() && frame - queue.peek().frame() >= DELAY_FRAMES) {
			try {
				queue.poll().resource().close();
			} catch (Exception ignored) {
			}
		}
	}
}
