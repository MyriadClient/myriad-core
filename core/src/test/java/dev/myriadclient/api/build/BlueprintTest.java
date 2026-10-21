package dev.myriadclient.api.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintTest {
	private static Set<BlockPos> near(Blueprint bp, Vec3 center, double range) {
		Set<BlockPos> out = new HashSet<>();
		bp.near(center, range, (pos, target) -> assertTrue(out.add(pos.immutable()), "reported twice: " + pos));
		return out;
	}

	/** Brute force: every position whose centre is within range. */
	private static Set<BlockPos> expected(Iterable<BlockPos> all, Vec3 center, double range) {
		Set<BlockPos> out = new HashSet<>();
		for (BlockPos p : all) if (Vec3.atCenterOf(p).distanceToSqr(center) <= range * range) out.add(p.immutable());
		return out;
	}

	@Test
	void boxOnlyVisitsWhatsInRangeOfAHugeBox() {
		Blueprint tunnel = Blueprint.box(new BlockPos(0, 64, 0), new BlockPos(2, 66, 1_000_000), Target.air());
		Vec3 eyes = new Vec3(1.5, 65.6, 5000.5);
		Set<BlockPos> got = near(tunnel, eyes, 4.5);
		assertEquals(expected(BlockPos.betweenClosed(new BlockPos(0, 64, 4990), new BlockPos(2, 66, 5010)), eyes, 4.5), got);
		assertFalse(got.isEmpty());
	}

	@Test
	void indexedBlueprintMatchesBruteForceAcrossSectionEdges() {
		Map<BlockPos, Target> m = new HashMap<>();
		for (int x = -20; x <= 20; x += 3) for (int y = 60; y <= 70; y++) for (int z = -20; z <= 20; z += 2) m.put(new BlockPos(x, y, z), Target.solid());
		Blueprint bp = Blueprint.of(m);
		for (Vec3 eyes : new Vec3[]{new Vec3(0.5, 65, 0.5), new Vec3(15.9, 63.2, -16.1), new Vec3(-31.5, 64, 0)}) {
			assertEquals(expected(m.keySet(), eyes, 5), near(bp, eyes, 5));
		}
		int[] count = {0};
		bp.forEach((pos, t) -> count[0]++);
		assertEquals(m.size(), count[0]);
	}

	@Test
	void indexedBlueprintMatchesBruteForceForScatteredPositions() {
		Random r = new Random(42);
		Map<BlockPos, Target> m = new HashMap<>();
		// Scattered over many sections, negative coordinates included; each with a target of its own.
		for (int i = 0; i < 5000; i++) m.put(new BlockPos(r.nextInt(80) - 40, r.nextInt(60) - 30, r.nextInt(80) - 40), Target.anyOf());
		Blueprint bp = Blueprint.of(m);
		for (int i = 0; i < 50; i++) {
			Vec3 eyes = new Vec3(r.nextDouble() * 80 - 40, r.nextDouble() * 60 - 30, r.nextDouble() * 80 - 40);
			double range = 1 + r.nextDouble() * 9;
			assertEquals(expected(m.keySet(), eyes, range), near(bp, eyes, range));
			bp.near(eyes, range, (pos, target) -> assertSame(m.get(pos), target));
		}
	}

	@Test
	void laterBlueprintsWinWhereTheyOverlap() {
		BlockPos p = new BlockPos(1, 64, 1);
		Blueprint merged = Blueprint.box(p, p, Target.air()).and(Blueprint.of(Map.of(p, Target.solid())));
		Map<BlockPos, Target> got = new HashMap<>();
		merged.near(Vec3.atCenterOf(p), 2, got::put);
		assertSame(Target.solid(), got.get(p));
		assertEquals(1, got.size());
	}
}
