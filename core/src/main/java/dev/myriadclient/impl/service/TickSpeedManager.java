package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.TickSpeed;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game's tick speed as every module asking for one wants it: each owner's multiplier, multiplied together, read
 * once a tick by the client's tick loop (a mixin). An owner asking for 1 (or nonsense) is forgotten.
 */
public final class TickSpeedManager implements TickSpeed {
	private final Map<Object, Float> multipliers = new LinkedHashMap<>();
	private volatile float combined = 1f;

	@Override
	public synchronized void set(Object owner, float multiplier) {
		if (multiplier == 1f || !(multiplier >= 0.05f)) multipliers.remove(owner);
		else multipliers.put(owner, multiplier);
		recompute();
	}

	@Override
	public synchronized void clear(Object owner) {
		if (multipliers.remove(owner) != null) recompute();
	}

	private void recompute() {
		float m = 1f;
		for (float v : multipliers.values()) m *= v;
		combined = m;
	}

	@Override
	public float multiplier() {
		return combined;
	}
}
