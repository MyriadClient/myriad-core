package dev.myriadclient.impl.render.title;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What you stand in on the title screen, one picked each time it's made: a corner of somewhere, cut out of the dark. You
 * stand at the origin, on the floor block at y 0; the side away from the buttons has a post in the foreground.
 */
public enum TitleScene {
	TAVERN {
		@Override
		void build(Builder b) {
			b.floor(Blocks.SPRUCE_PLANKS);
			BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
			b.put(barrel, -2, 1, -2);
			b.put(barrel, -1, 1, -2);
			b.put(barrel, -2, 2, -2);
			b.put(Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.SOUTH), -2, 1, -1);
			b.put(Blocks.LANTERN.defaultBlockState(), -2, 3, -2);
			// A rail along the side, its end post going up past the top of the picture.
			BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
			b.put(fence.setValue(FenceBlock.SOUTH, true), 2, 1, -1);
			b.put(fence.setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true), 2, 1, 0);
			b.put(fence.setValue(FenceBlock.NORTH, true), 2, 1, 1);
			for (int y = 2; y <= 7; y++) b.put(fence, 2, y, 1);
		}
	},
	CAMPFIRE {
		@Override
		void build(Builder b) {
			b.tintedFloor(Blocks.GRASS_BLOCK, Blocks.PODZOL);
			b.put(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), -2, 1, 0);
			BlockState seat = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
			b.put(seat, -2, 1, -2);
			b.put(seat, -1, 1, -2);
			b.tinted(Blocks.FERN.defaultBlockState(), 1, 1, -1);
			// A post with a lantern on it.
			b.put(Blocks.SPRUCE_FENCE.defaultBlockState(), 2, 1, 1);
			b.put(Blocks.SPRUCE_FENCE.defaultBlockState(), 2, 2, 1);
			b.put(Blocks.LANTERN.defaultBlockState(), 2, 3, 1);
		}
	},
	MINE {
		@Override
		void build(Builder b) {
			b.floor(Blocks.COBBLESTONE, Blocks.STONE, Blocks.TUFF);
			// A timber frame behind you, with a lantern hung from it.
			BlockState post = Blocks.OAK_LOG.defaultBlockState();
			BlockState beam = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
			for (int y = 1; y <= 3; y++) {
				b.put(post, -2, y, -2);
				b.put(post, 2, y, -2);
			}
			for (int x = -2; x <= 2; x++) b.put(beam, x, 4, -2);
			b.put(Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 1, 3, -2);
			b.put(Blocks.IRON_ORE.defaultBlockState(), -2, 1, -1);
			b.put(Blocks.RAIL.defaultBlockState(), -1, 1, -1);
			b.put(Blocks.RAIL.defaultBlockState(), -1, 1, 0);
			b.put(Blocks.RAIL.defaultBlockState(), -1, 1, 1);
			// A prop, going up past the top of the picture.
			for (int y = 1; y <= 7; y++) b.put(Blocks.OAK_FENCE.defaultBlockState(), 2, y, 1);
		}
	};

	/** One block; {@code tinted} that it takes the theme's green. */
	record Placed(BlockState state, int x, int y, int z, boolean tinted) {
	}

	private static TitleScene last;

	private final List<Placed> blocks;

	TitleScene() {
		Builder b = new Builder();
		build(b);
		blocks = List.copyOf(b.out);
	}

	abstract void build(Builder b);

	List<Placed> blocks() {
		return blocks;
	}

	/** Any scene but the one shown last. */
	public static TitleScene pick() {
		TitleScene[] all = values();
		int i = ThreadLocalRandom.current().nextInt(last == null ? all.length : all.length - 1);
		if (last != null && i >= last.ordinal()) i++;
		return last = all[i];
	}

	static final class Builder {
		/** The floor's rows from the back: how far each runs to either side. */
		private static final int[][] FLOOR = {{-2, 1}, {-2, 2}, {-2, 2}, {-1, 2}};

		private final List<Placed> out = new ArrayList<>();

		void put(BlockState state, int x, int y, int z) {
			out.add(new Placed(state, x, y, z, false));
		}

		void tinted(BlockState state, int x, int y, int z) {
			out.add(new Placed(state, x, y, z, true));
		}

		/** A floor of {@code blocks} in turn, by where each is so it stays the same. */
		void floor(Block... blocks) {
			forFloor((x, z) -> put(blocks[Math.floorMod(x * 3 + z * 5, blocks.length)].defaultBlockState(), x, 0, z));
		}

		/** A floor mostly of {@code main}, tinted, with patches of {@code patch}. */
		void tintedFloor(Block main, Block patch) {
			forFloor((x, z) -> {
				if (Math.floorMod(x * 3 + z * 5, 4) == 0) put(patch.defaultBlockState(), x, 0, z);
				else tinted(main.defaultBlockState(), x, 0, z);
			});
		}

		private void forFloor(Tile tile) {
			for (int row = 0; row < FLOOR.length; row++) {
				for (int x = FLOOR[row][0]; x <= FLOOR[row][1]; x++) tile.at(x, row - 2);
			}
		}

		private interface Tile {
			void at(int x, int z);
		}
	}
}
