package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.MovementPacketsEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Where {@link BreakManager}'s breaks look, for those with {@code rotate}: turned to the block being mined, or the
 * first one waiting, and a packet goes only once the rotation the server has looks at its block.
 */
final class BreakAim {
	private final Minecraft mc = Minecraft.getInstance();
	private final BreakManager breaking;

	BreakAim(BreakManager breaking) {
		this.breaking = breaking;
	}

	/** The block about to need the look: the one being mined, or the first one waiting. */
	private @Nullable BreakJob focus() {
		BreakJob primary = breaking.primary();
		if (primary != null && primary.options.rotate()) return primary;
		for (BreakJob j : breaking.jobs()) if (j.phase == BreakJob.Phase.QUEUED && j.options.rotate()) return j;
		return null;
	}

	/** Faces whatever is about to need it: the block being mined, or the first one waiting. */
	@Subscribe
	private void onTickStart(TickEvent.Pre e) {
		if (breaking.jobs().isEmpty() || mc.player == null) return;
		BreakJob focus = focus();
		if (focus == null) return;
		aim(focus);
		if (focus.aim == null) return;
		float[] r = PlacementManager.anglesFromNextPosition(focus.aim);
		Myriad.rotations().request(breaking, r[0], r[1], Rotations.PRIORITY_HIGH, PlacementManager.MOVE_FIX, null);
	}

	/** Aims again from where you are now, just before the rotation goes out (see PlacementManager). */
	@Subscribe(priority = Priority.HIGH)
	private void onBeforeRotationSent(MovementPacketsEvent e) {
		BreakJob focus = focus();
		if (focus == null || focus.aim == null || mc.player == null) return;
		float[] r = MathUtil.anglesTo(mc.player.getEyePosition(), focus.aim);
		Myriad.rotations().request(breaking, r[0], r[1], PlacementManager.REFINED_PRIORITY, PlacementManager.MOVE_FIX, null);
	}

	/**
	 * The point and face to break {@code j} at: the nearest visible point, else the nearest on a face towards the eyes,
	 * else its centre with the face the eyes are furthest past (a face pointing away is refused).
	 */
	void aim(BreakJob j) {
		BlockHitResult hit = Reach.hitFor(j.pos, false, j.options.range());
		if (hit != null) {
			j.face = hit.getDirection();
			j.aim = hit.getLocation();
		} else {
			j.aim = Vec3.atCenterOf(j.pos);
			j.face = towardsEyes(j);
		}
	}

	/** The face of {@code j}'s outline the eyes are furthest past; its current one with the eyes inside. */
	private Direction towardsEyes(BreakJob j) {
		VoxelShape shape = mc.level.getBlockState(j.pos).getShape(mc.level, j.pos);
		AABB b = (shape.isEmpty() ? Shapes.block() : shape).bounds().move(j.pos);
		Vec3 e = Reach.eyes();
		Direction face = j.face;
		double best = 0;
		for (Direction d : Direction.values()) {
			double past = switch (d) {
				case UP -> e.y - b.maxY;
				case DOWN -> b.minY - e.y;
				case EAST -> e.x - b.maxX;
				case WEST -> b.minX - e.x;
				case SOUTH -> e.z - b.maxZ;
				case NORTH -> b.minZ - e.z;
			};
			if (past > best) {
				best = past;
				face = d;
			}
		}
		return face;
	}

	/** With rotate, only once the rotation just sent looks at the block; the face it lands on is the one used. */
	boolean canAct(BreakJob j) {
		if (!j.options.rotate()) return true;
		BlockHitResult look = Reach.rayHit(Myriad.rotations().serverYaw(), Myriad.rotations().serverPitch(), j.pos, j.options.range());
		if (look == null) return false;
		j.face = look.getDirection();
		return true;
	}
}
