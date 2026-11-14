package dev.myriadclient.api.event.events;

import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * The local player is about to move by {@link #movement} this tick (after input, gravity and friction were applied,
 * before collisions). Change it to speed up, slow down or stop: speed, flight, step and no-slow features hook here.
 * Posted on the render thread for every move of the local player (more than once a tick when something pushes you),
 * only while something listens. Not cancellable: set a zero movement to stay put.
 */
public final class PlayerMoveEvent {
	private final MoverType type;
	private Vec3 movement;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public PlayerMoveEvent(MoverType type, Vec3 movement) {
		this.type = type;
		this.movement = movement;
	}

	/** {@code SELF} for normal movement; pistons, shulkers and the like push with other types. */
	public MoverType type() {
		return type;
	}

	/** The movement about to be made, in blocks: what vanilla worked out, or what an earlier handler set. */
	public Vec3 movement() {
		return movement;
	}

	/** Moves by {@code movement} instead (blocks, before collisions); the velocity the player carries is unchanged. */
	public void setMovement(Vec3 movement) {
		this.movement = movement;
	}

	/** Keeps the vertical movement and replaces the horizontal part. */
	public void setHorizontal(double x, double z) {
		this.movement = new Vec3(x, movement.y, z);
	}
}
