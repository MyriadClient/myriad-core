package dev.myriadclient.impl.render;

import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * GPU time spent in Myriad's highlight passes, from timestamps written into the command stream around each pass and
 * read back a few frames later (the GPU runs behind). Off unless something measures ({@code .bench render}): while off,
 * {@link #begin} and {@link #end} are one field read.
 */
public final class GpuTimings {
	/** What's timed: the outline pass drawing highlighted silhouettes into the masks, spreading them, drawing them. */
	public enum Pass {
		MASKS, SPREAD, GLOW, COMPOSITE, HAND
	}

	/** Frames in flight: a frame's timestamps are read this many frames later. */
	private static final int FRAMES = 4;
	/** Brackets a frame can hold (a pass runs once per layer that has highlights). */
	private static final int SLOTS = 32;

	private static boolean on;
	private static GpuQueryPool[] pools;
	/** The pass each bracket of each frame in the ring timed, and how many brackets each frame has. */
	private static Pass[][] slots;
	private static int[] used;
	private static int open = -1;
	private static int frame;
	private static final long[] totals = new long[Pass.values().length];
	private static long cpuTotal;
	private static int frames;
	private static long cpuStart;
	/** A measurement over a set number of frames: when it's done, and what to tell. */
	private static int target;
	private static long wallStart;
	private static @Nullable Consumer<String> done;

	private GpuTimings() {
	}

	/** Switches highlights to the way they were drawn before (or back); true if they're the old way now. */
	public static boolean toggleLegacy() {
		return HighlightPasses.legacy = !HighlightPasses.legacy;
	}

	/** Measures {@code frameCount} frames the way the passes were before (HighlightPasses.legacy), then as many as they are now. */
	public static void compare(int frameCount, Consumer<String> report) {
		HighlightPasses.legacy = true;
		measure(frameCount, before -> {
			HighlightPasses.legacy = false;
			report.accept("Before: " + before);
			measure(frameCount, after -> report.accept("Now: " + after));
		});
	}

	/** Measures the next {@code frameCount} frames, then hands a one-line report to {@code report}. */
	public static void measure(int frameCount, Consumer<String> report) {
		start();
		target = frameCount;
		done = report;
	}

	/** Starts measuring from the next frame, from zero. */
	public static void start() {
		if (pools == null) {
			pools = new GpuQueryPool[FRAMES];
			for (int i = 0; i < FRAMES; i++) pools[i] = RenderSystem.getDevice().createTimestampQueryPool(SLOTS * 2);
			slots = new Pass[FRAMES][SLOTS];
			used = new int[FRAMES];
		}
		Arrays.fill(used, 0);
		Arrays.fill(totals, 0);
		cpuTotal = 0;
		frames = -FRAMES;
		on = true;
	}

	public static void stop() {
		on = false;
	}

	/** A new frame: the oldest frame in the ring is read and its slots reused. */
	public static void frame() {
		if (!on) return;
		frame = (frame + 1) % FRAMES;
		if (frames >= 0) {
			GpuQueryPool pool = pools[frame];
			for (int i = 0; i < used[frame]; i++) {
				OptionalLong a = pool.getValue(i * 2), b = pool.getValue(i * 2 + 1);
				if (a.isPresent() && b.isPresent()) totals[slots[frame][i].ordinal()] += b.getAsLong() - a.getAsLong();
			}
		}
		used[frame] = 0;
		frames++;
		if (frames == 0) wallStart = System.nanoTime();
		if (done != null && frames >= target) {
			Consumer<String> report = done;
			done = null;
			on = false;
			report.accept(report());
		}
	}

	private static String report() {
		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		double frameMs = (System.nanoTime() - wallStart) / 1e6 / frames;
		double sum = 0;
		StringBuilder sb = new StringBuilder();
		for (Pass p : Pass.values()) {
			sum += gpuMs(p);
			sb.append(String.format(Locale.ROOT, ", %s %.3f", p.name().toLowerCase(Locale.ROOT), gpuMs(p)));
		}
		return String.format(Locale.ROOT, "Highlights over %d frames at %dx%d: GPU %.3f ms a frame (%s), CPU %.3f ms; frame %.2f ms (%.0f fps)",
			frames, main.width, main.height, sum, sb.substring(2), cpuMs(), frameMs, 1000 / frameMs);
	}

	public static void begin(Pass pass) {
		if (!on || used[frame] >= SLOTS) return;
		open = used[frame];
		slots[frame][open] = pass;
		RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pools[frame], open * 2);
		cpuStart = System.nanoTime();
	}

	public static void end(Pass pass) {
		if (!on || open < 0 || slots[frame][open] != pass) return;
		cpuTotal += System.nanoTime() - cpuStart;
		RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pools[frame], open * 2 + 1);
		used[frame] = open + 1;
		open = -1;
	}

	/** Mean GPU milliseconds a frame in {@code pass} (frames it didn't run in count as zero). */
	public static double gpuMs(Pass pass) {
		return frames <= 0 ? 0 : totals[pass.ordinal()] / 1e6 / frames;
	}

	/** Mean CPU milliseconds a frame spent issuing the passes. */
	public static double cpuMs() {
		return frames <= 0 ? 0 : cpuTotal / 1e6 / Math.max(1, frames + FRAMES);
	}
}
