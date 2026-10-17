package dev.myriadclient.api.render;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Highlights that fade in when they appear and fade out when they go, keyed by whatever you highlight (block
 * positions, entity ids). Tell it what should show; it hands back each key with its current opacity.
 *
 * <pre>{@code
 * private final FadeMap<BlockPos> placing = new FadeMap<>(150, 300);
 *
 * // each tick: the positions this module wants to show
 * placing.retain(plannedPositions);
 *
 * // each frame, in a Render3DEvent handler
 * placing.forEach((pos, alpha) -> Renderer3D.blockShape(pos, ColorUtil.fade(fill, alpha), ColorUtil.fade(line, alpha), ShapeMode.BOTH, false));
 * }</pre>
 */
public final class FadeMap<K> {
	private final long fadeInMs, fadeOutMs;
	private final Map<K, Entry> entries = new LinkedHashMap<>();

	private static final class Entry {
		boolean on = true;
		float alpha;
		long last = System.currentTimeMillis();
	}

	public FadeMap(long fadeInMs, long fadeOutMs) {
		this.fadeInMs = Math.max(1, fadeInMs);
		this.fadeOutMs = Math.max(1, fadeOutMs);
	}

	/** Shows {@code key} (fading in if it's new or was fading out). */
	public void show(K key) {
		Entry e = entries.computeIfAbsent(key, k -> new Entry());
		e.on = true;
	}

	/** Starts fading {@code key} out; it's dropped once invisible. */
	public void hide(K key) {
		Entry e = entries.get(key);
		if (e != null) e.on = false;
	}

	/** Shows exactly {@code keys}: new ones fade in, missing ones fade out. */
	public void retain(Collection<? extends K> keys) {
		Set<K> keep = new HashSet<>(keys);
		for (var e : entries.entrySet()) if (!keep.contains(e.getKey())) e.getValue().on = false;
		for (K k : keys) show(k);
	}

	/** Fades everything out. */
	public void hideAll() {
		for (Entry e : entries.values()) e.on = false;
	}

	/** Removes everything at once, without fading. */
	public void clear() {
		entries.clear();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** Calls {@code draw} with each key and its opacity (0..1), advancing the fades. */
	public void forEach(BiConsumer<K, Float> draw) {
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<K, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
			var me = it.next();
			Entry e = me.getValue();
			long dt = now - e.last;
			e.last = now;
			e.alpha = e.on ? Math.min(1, e.alpha + dt / (float) fadeInMs) : Math.max(0, e.alpha - dt / (float) fadeOutMs);
			if (!e.on && e.alpha <= 0) {
				it.remove();
				continue;
			}
			draw.accept(me.getKey(), e.alpha);
		}
	}
}
