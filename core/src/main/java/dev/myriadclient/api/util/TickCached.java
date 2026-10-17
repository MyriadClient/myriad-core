package dev.myriadclient.api.util;

import java.util.function.Supplier;

/**
 * A value worked out at most once per client tick, however often it's read. For anything a render handler needs from
 * the world that only changes per tick: which entities to draw and in what colour, the nearest target, what's
 * dangerous. Reading it from a render handler at 200 frames a second then costs the work once, not 200 times.
 *
 * <pre>{@code
 * private final TickCached<List<Entity>> targets = TickCached.of(() -> Targets.query().range(64).list());
 *
 * @Subscribe
 * private void onRender(Render3DEvent e) {
 *     for (Entity t : targets.get()) ...
 * }
 * }</pre>
 *
 * Not thread-safe beyond the render thread; a read from another thread may compute the value a second time.
 */
public final class TickCached<T> {
	private final Supplier<T> compute;
	private T value;
	private long tick = -1;

	private TickCached(Supplier<T> compute) {
		this.compute = compute;
	}

	public static <T> TickCached<T> of(Supplier<T> compute) {
		return new TickCached<>(compute);
	}

	/** The value for this tick, computed on the first read of the tick. */
	public T get() {
		long now = Ticks.current();
		if (now != tick) {
			value = compute.get();
			tick = now;
		}
		return value;
	}

	/** Forgets the value, so the next read computes it again this tick. */
	public void invalidate() {
		tick = -1;
	}
}
