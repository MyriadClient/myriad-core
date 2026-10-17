package dev.myriadclient.api.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MergedBoxesTest {
	/** A chest's hitbox within its block. */
	private static final AABB CHEST = new AABB(1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 14 / 16.0, 15 / 16.0);

	private static final class Recorder extends ShapeBuilder {
		final List<float[]> quads = new ArrayList<>(), lines = new ArrayList<>();

		@Override
		protected void emitQuad(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int color) {
			quads.add(new float[]{ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz});
		}

		@Override
		protected void emitLine(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz, int fromColor, int toColor, float width) {
			lines.add(new float[]{ax, ay, az, bx, by, bz});
		}
	}

	private static Recorder draw(Set<BlockPos> cells, AABB shape) {
		Recorder r = new Recorder();
		MergedBoxes.Lookup lookup = new MergedBoxes.Lookup() {
			@Override
			public int group(BlockPos pos) {
				return cells.contains(pos) ? 0xFFFFFFFF : 0;
			}

			@Override
			public AABB shape(BlockPos pos) {
				return shape;
			}
		};
		MergedBoxes.draw(r, cells, lookup, g -> 0x40FFFFFF, g -> g, false);
		return r;
	}

	/** Lines lying entirely in the plane x = {@code x}. */
	private static long linesInPlaneX(Recorder r, float x) {
		return r.lines.stream().filter(l -> Math.abs(l[0] - x) < 1e-5 && Math.abs(l[3] - x) < 1e-5).count();
	}

	@Test
	void aLoneBlockIsAPlainBox() {
		Recorder r = draw(Set.of(BlockPos.ZERO), null);
		assertEquals(6, r.quads.size());
		assertEquals(12, r.lines.size());
	}

	@Test
	void touchingBlocksShareNoFaceAndNoLineBetweenThem() {
		Recorder r = draw(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), null);
		// A 2x1x1 box: the two inner faces are gone; each long face is two quads.
		assertEquals(10, r.quads.size());
		// The four long edges in two pieces each, plus four short edges at each end; nothing across the middle.
		assertEquals(16, r.lines.size());
		assertEquals(0, linesInPlaneX(r, 1));
	}

	@Test
	void chestsMergeAtTheirHitboxNotTheBlock() {
		Recorder r = draw(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), CHEST);
		assertEquals(10, r.quads.size());
		assertEquals(0, linesInPlaneX(r, 1));
		for (float[] q : r.quads) {
			for (int i = 0; i < 12; i += 3) {
				// Inset on the outside: x from 1/16 to 31/16, z from 1/16 to 15/16, up to 14/16.
				assertTrue(q[i] >= 1 / 16f - 1e-5 && q[i] <= 31 / 16f + 1e-5);
				assertTrue(q[i + 1] >= -1e-5 && q[i + 1] <= 14 / 16f + 1e-5);
				assertTrue(q[i + 2] >= 1 / 16f - 1e-5 && q[i + 2] <= 15 / 16f + 1e-5);
			}
		}
	}

	@Test
	void aColumnRisingAboveItsNeighbourShowsTheStep() {
		// Two chests stacked at x=0, one chest at x=1: the lower x=0 chest reaches up to its neighbour above,
		// so the strip of its side between 14/16 and 1 shows above the x=1 chest.
		Recorder r = draw(Set.of(BlockPos.ZERO, new BlockPos(0, 1, 0), new BlockPos(1, 0, 0)), CHEST);
		boolean strip = r.quads.stream().anyMatch(q -> {
			float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
			for (int i = 0; i < 12; i += 3) {
				if (Math.abs(q[i] - 1) > 1e-5) return false;
				minY = Math.min(minY, q[i + 1]);
				maxY = Math.max(maxY, q[i + 1]);
			}
			return Math.abs(minY - 14 / 16f) < 1e-5 && Math.abs(maxY - 1) < 1e-5;
		});
		assertTrue(strip, "the side of the taller column above the shorter one is drawn");
	}

	@Test
	void anLShapeKeepsItsInsideCorner() {
		Recorder r = draw(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(0, 0, 1)), null);
		// The inside corner at x=1, z=1 runs the full height.
		long corner = r.lines.stream().filter(l -> Math.abs(l[0] - 1) < 1e-5 && Math.abs(l[2] - 1) < 1e-5
			&& Math.abs(l[3] - 1) < 1e-5 && Math.abs(l[5] - 1) < 1e-5 && Math.abs(l[1] - l[4]) > 0.5).count();
		assertEquals(1, corner);
	}
}
