package dev.myriadclient.api.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HelpersTest {
	@Test
	void anglesFollowMinecraftConventions() {
		Vec3 o = Vec3.ZERO;
		assertEquals(0, MathUtil.anglesTo(o, new Vec3(0, 0, 5))[0], 1e-3);    // south
		assertEquals(90, MathUtil.anglesTo(o, new Vec3(-5, 0, 0))[0], 1e-3);  // west
		assertEquals(-90, MathUtil.anglesTo(o, new Vec3(5, 0, 0))[0], 1e-3);  // east
		assertEquals(90, MathUtil.anglesTo(o, new Vec3(0, -5, 0))[1], 1e-3);  // straight down
		Vec3 south = MathUtil.horizontalDirection(0);
		assertEquals(1, south.z, 1e-6);
		assertEquals(-1, MathUtil.horizontalDirection(90).x, 1e-6);
		assertEquals(-20, MathUtil.angleDifference(170, 150), 1e-3);
		assertEquals(20, MathUtil.angleDifference(170, -170), 1e-3);
		assertEquals(0, MathUtil.angleTo(o, 0, 0, new Vec3(0, 0, 10)), 1e-3);
	}

	@Test
	void closestPointOnABox() {
		AABB b = new AABB(0, 0, 0, 1, 2, 1);
		assertEquals(new Vec3(1, 1, 0.5), MathUtil.closestPoint(b, new Vec3(3, 1, 0.5)));
		assertEquals(new Vec3(0.5, 1, 0.5), MathUtil.closestPoint(b, new Vec3(0.5, 1, 0.5)));
		assertEquals(2, MathUtil.distanceTo(b, new Vec3(3, 1, 0.5)), 1e-9);
		assertEquals(45, MathUtil.snap(50, 45), 1e-9);
	}

	@Test
	void formatting() {
		assertEquals("350m", Format.distance(350));
		assertEquals("1.4km", Format.distance(1400));
		assertEquals("45s", Format.duration(45_000));
		assertEquals("3m 05s", Format.duration(185_000));
		assertEquals("2h 14m", Format.duration(8_040_000));
		assertEquals("just now", Format.ago(500));
		assertEquals("3m ago", Format.ago(200_000));
		assertEquals("999", Format.compact(999));
		assertEquals("1.2k", Format.compact(1234));
		assertEquals("45k", Format.compact(45_000));
		assertEquals("2k", Format.compact(2000));
		assertEquals("3.1M", Format.compact(3_100_000));
		assertEquals("64%", Format.percent(0.64));
	}

	@Test
	void slotNumbering() {
		assertEquals(36, Slots.playerScreen(0));
		assertEquals(44, Slots.playerScreen(8));
		assertEquals(9, Slots.playerScreen(9));
		assertEquals(35, Slots.playerScreen(35));
		assertEquals(8, Slots.playerScreen(Slots.FEET));
		assertEquals(5, Slots.playerScreen(Slots.HEAD));
		assertEquals(45, Slots.playerScreen(Slots.OFF_HAND));
		// A double chest has 54 slots, then the main inventory, then the hotbar.
		assertEquals(54, Slots.containerScreen(54, 9));
		assertEquals(80, Slots.containerScreen(54, 35));
		assertEquals(81, Slots.containerScreen(54, 0));
		assertEquals(89, Slots.containerScreen(54, 8));
	}

	@Test
	void spheresAreNearestFirstAndRound() {
		Vec3 center = new Vec3(0.5, 0.5, 0.5);
		List<BlockPos> sphere = Positions.sphere(center, 1);
		assertEquals(BlockPos.ZERO, sphere.getFirst());
		assertEquals(7, sphere.size()); // the centre and its six neighbours
		assertEquals(125, Positions.cube(BlockPos.ZERO, 2).size());
		assertEquals(BlockPos.ZERO, Positions.cube(BlockPos.ZERO, 2).getFirst());
	}

	@Test
	void timerExpiresAndResets() {
		Timer t = new Timer();
		assertTrue(t.passed(1_000));
		t.reset();
		assertFalse(t.passed(10_000));
		t.expire();
		assertTrue(t.tick(10_000));
		assertFalse(t.passed(10_000));
	}
}
