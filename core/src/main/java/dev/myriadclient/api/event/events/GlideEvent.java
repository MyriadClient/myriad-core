package dev.myriadclient.api.event.events;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * The local player's glide is about to move it this tick, by vanilla's elytra physics ({@link #movement()}). Change it
 * to fly the glide yourself: {@link #setVelocity} for a flight that sets its speed (controlled flight, a boost), or
 * {@link #setMovement} for this tick's movement alone. Not posted on a tick No Durability holds you still. Posted on
 * the render thread during the player's tick, only while something listens. Not cancellable.
 */
public final class GlideEvent {
	private Vec3 movement;
	private boolean velocity, changed;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public GlideEvent(Vec3 movement) {
		this.movement = movement;
	}

	/** This tick's movement in blocks: vanilla's, or what an earlier handler set. */
	public Vec3 movement() {
		return movement;
	}

	/** Moves by {@code movement} this tick; the velocity carried into the next stays vanilla's. */
	public void setMovement(Vec3 movement) {
		this.movement = movement;
		changed = true;
	}

	/** Moves by {@code velocity} this tick and carries it into the next, as the glide's own speed. */
	public void setVelocity(Vec3 velocity) {
		this.movement = velocity;
		this.velocity = changed = true;
	}

	/** Whether a listener before you already flies this tick (a lower priority boost would keep out of it). */
	public boolean isChanged() {
		return changed;
	}

	/** Core's: whether a handler used {@link #setVelocity}. */
	@ApiStatus.Internal
	public boolean setsVelocity() {
		return velocity;
	}
}
