package dev.myriadclient.api.event.events;

import org.jetbrains.annotations.ApiStatus;

/**
 * The server set your health (and food): posted on the render thread with the values before and after, every time the
 * health packet arrives, whether or not anything changed. {@link #damage()} is how much you lost this time, for
 * modules that react to being hit (auto log, pop chams, damage indicators) without reading packets.
 */
public final class HealthEvent {
	private final float oldHealth, newHealth;
	private final int oldFood, newFood;

	@ApiStatus.Internal
	public HealthEvent(float oldHealth, float newHealth, int oldFood, int newFood) {
		this.oldHealth = oldHealth;
		this.newHealth = newHealth;
		this.oldFood = oldFood;
		this.newFood = newFood;
	}

	public float oldHealth() {
		return oldHealth;
	}

	public float newHealth() {
		return newHealth;
	}

	public int oldFood() {
		return oldFood;
	}

	public int newFood() {
		return newFood;
	}

	/** Health lost (0 if none). */
	public float damage() {
		return Math.max(0, oldHealth - newHealth);
	}

	public boolean wasHurt() {
		return newHealth < oldHealth;
	}
}
