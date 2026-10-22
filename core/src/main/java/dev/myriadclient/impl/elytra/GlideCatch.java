package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.elytra.NoDurability.Owner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A safety net: the glide stopping in mid-air with nothing a player would stop it for (no ground, water, ladder or
 * vehicle) means the server's stop got through to the client (a setback, a lost hold, chunks that came in late), and
 * a fall from there could cost every block the server counted while it wasn't gliding you. So the elytra is opened
 * again at once, as a player would: on if a chestplate is worn, then jump released for a tick and pressed. Not when
 * you'd land without hurting (a foot above the ground): opened there, the glide would skim the ground for the next 30
 * blocks, wearing the elytra, with No Durability's swapping kept off by the ground.
 */
final class GlideCatch {
	/** How long to keep trying to open the elytra again after the glide drops in mid-air. */
	private static final int CATCH_TICKS = 20;

	private final Minecraft mc = Minecraft.getInstance();
	private final Owner fly;
	private boolean wasGliding, catchNow, lastCatchPress;
	/** Ticks left to catch a dropped glide (0: nothing to catch). */
	private int catching;

	GlideCatch(Owner fly) {
		this.fly = fly;
	}

	/** Whether a dropped glide is being caught: jump is only the press that opens the elytra ({@link #press()}). */
	boolean catching() {
		return catching > 0;
	}

	/** Whether jump is pressed this tick for the catch. */
	boolean press() {
		return catchNow;
	}

	void stop() {
		catching = 0;
	}

	/** Each tick, with {@code descent} the fall the server counts since you last rose. */
	void tick(LocalPlayer p, double descent) {
		boolean gliding = p.isFallFlying();
		boolean free = !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.onClimbable() && !p.getAbilities().flying;
		if (wasGliding && !gliding && free && !fly.letsGlideDrop() && !softLanding(p, descent)) {
			catching = CATCH_TICKS;
			fly.info("Glide dropped in mid-air: opening the elytra again");
		}
		wasGliding = gliding;
		catchNow = false;
		if (catching == 0) return;
		if (gliding || !free) {
			catching = 0;
			return;
		}
		catching--;
		if (!ChestSwapper.elytraWorn()) {
			ChestSwapper.restoreElytra();
			return;
		}
		// Released last tick (the press opens it, as vanilla does), pressed this one.
		catchNow = !lastCatchPress;
		lastCatchPress = catchNow;
	}

	/**
	 * Whether you'd come down from here without fall damage: ground within the fall the server lets go (counting what
	 * it has already, and the rise still to come), under you and under where you're carried while you fall.
	 */
	private boolean softLanding(LocalPlayer p, double descent) {
		Vec3 v = p.getDeltaMovement();
		double rise = v.y > 0 ? v.y * v.y / (2 * 0.08) : 0;
		double drop = NoDurabilityEngine.SAFE_DESCENT - 0.5 - descent - rise;
		if (drop <= 0) return false;
		AABB box = p.getBoundingBox();
		// Where you'll be over the next half second, taken at full speed (you slow down off the elytra, so that's farther).
		for (int t = 0; t <= 10; t += 2) {
			if (mc.level.noBlockCollision(p, box.move(v.x * t, 0, v.z * t).expandTowards(0, -drop, 0))) return false;
		}
		return true;
	}
}
