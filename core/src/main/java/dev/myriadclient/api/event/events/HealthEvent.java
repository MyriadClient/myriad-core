package dev.myriadclient.api.event.events;

import org.jetbrains.annotations.ApiStatus;

/**
 * The server set your health (and food): posted on the render thread with the values before and after, every time the
 * health packet arrives, whether or not anything changed. {@link #damage()} is how much you lost this time, for
 * modules that react to being hit (auto log, pop chams, damage indicators) without reading packets. Only while
 * something listens. Not cancellable.
 */
public final class HealthEvent {
	private final float oldHealth, newHealth;
	private final int oldFood, newFood;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public HealthEvent(float oldHealth, float newHealth, int oldFood, int newFood) {
		this.oldHealth = oldHealth;
		this.newHealth = newHealth;
		this.oldFood = oldFood;
		this.newFood = newFood;
	}

	/** Your health before the packet, in half hearts (20 is full, absorption not counted). */
	public float oldHealth() {
		return oldHealth;
	}

	/** Your health as the server set it, in half hearts. */
	public float newHealth() {
		return newHealth;
	}

	/** Your food level before the packet (0 to 20). */
	public int oldFood() {
		return oldFood;
	}

	/** Your food level as the server set it (0 to 20). */
	public int newFood() {
		return newFood;
	}

	/** Health lost (0 if none). */
	public float damage() {
		return Math.max(0, oldHealth - newHealth);
	}

	/** Whether you lost health this time. */
	public boolean wasHurt() {
		return newHealth < oldHealth;
	}
}
