package dev.myriadclient.api.build;

import dev.myriadclient.api.util.BlockInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one position of a {@link Blueprint} should be: empty, a certain block, a block in a certain orientation, or
 * any solid block. It decides whether the position is done and which items can fill it.
 *
 * <pre>{@code
 * Target.air()                                  // dig it out
 * Target.solid()                                // any full block (bridges, walls, surround with whatever you have)
 * Target.anyOf(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN) // the first one you have
 * Target.state(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST)) // oriented
 * }</pre>
 */
public interface Target {
	/** Whether {@code state} at {@code pos} already is what this target wants. */
	boolean matches(BlockGetter level, BlockPos pos, BlockState state);

	/** Whether this target wants the position empty. */
	default boolean wantsAir() {
		return false;
	}

	/**
	 * How good {@code stack} is for filling this target: -1 if it can't, otherwise lower is better (0 = first
	 * choice). Never called for {@link #air()}.
	 */
	int preference(ItemStack stack);

	/**
	 * The exact state to place when the orientation matters (facing, axis, slab or stair half, ...), or null when
	 * any way the block comes out is fine. With a state, the planner looks for a click and a rotation that place it
	 * that way, and rotates for the placement.
	 */
	default @Nullable BlockState orientedState() {
		return null;
	}

	/** A short description for debugging and plan overlays. */
	String describe();

	/**
	 * The properties that set how a block was placed, as opposed to ones the game keeps updating from its
	 * neighbours (stair shapes, fence connections, waterlogging, power). {@link #state} compares only these.
	 */
	final class Orientation {
		public static final List<Property<?>> PROPERTIES = List.of(
			BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
			BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.ORIENTATION,
			BlockStateProperties.ATTACH_FACE, BlockStateProperties.HALF, BlockStateProperties.SLAB_TYPE,
			BlockStateProperties.ROTATION_16, BlockStateProperties.DOOR_HINGE, BlockStateProperties.VERTICAL_DIRECTION,
			BlockStateProperties.HANGING
		);

		private Orientation() {
		}
	}

	/** The position should be empty: anything there is broken (fluids can't be, and count as done). */
	static Target air() {
		return Air.INSTANCE;
	}

	/** Any state of {@code block}. */
	static Target block(Block block) {
		return anyOf(block);
	}

	/** Any of these blocks; when placing, the first one listed that you have. */
	static Target anyOf(Block... blocks) {
		List<Block> list = List.of(blocks);
		return new Target() {
			@Override
			public boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
				return list.contains(state.getBlock());
			}

			@Override
			public int preference(ItemStack stack) {
				return stack.getItem() instanceof BlockItem item ? list.indexOf(item.getBlock()) : -1;
			}

			@Override
			public String describe() {
				return list.size() == 1 ? list.getFirst().getName().getString() : "any of " + list.size() + " blocks";
			}
		};
	}

	/**
	 * {@code state}'s block, placed the way {@code state} faces (the {@link Orientation#PROPERTIES} it has); other
	 * properties are left to the game.
	 */
	static Target state(BlockState state) {
		List<Property<?>> props = Orientation.PROPERTIES.stream().filter(state::hasProperty).toList();
		return new Target() {
			@Override
			public boolean matches(BlockGetter level, BlockPos pos, BlockState current) {
				if (!current.is(state.getBlock())) return false;
				for (Property<?> p : props) if (!current.getValue(p).equals(state.getValue(p))) return false;
				return true;
			}

			@Override
			public int preference(ItemStack stack) {
				return stack.is(state.getBlock().asItem()) ? 0 : -1;
			}

			@Override
			public @Nullable BlockState orientedState() {
				return props.isEmpty() ? null : state;
			}

			@Override
			public String describe() {
				return state.toString();
			}
		};
	}

	/**
	 * {@code state} as near exactly as a build can make it: its block, placed the way it faces (facing, half, axis,
	 * hinge, attachment, rotation), as many of it (candles, sea pickles, layers, a double slab), and clicked into its
	 * setting by hand (a repeater's delay, a comparator's mode, a note, open or closed, a lever) unless the build's
	 * options leave settings be ({@code Building.Options.withClickSettings}). What the game keeps changing on its own
	 * (connections, power, waterlogging) is left to it. The top half of a door and the head of a bed come with the
	 * other half. For printing a schematic. One target per state, so asking for the same state twice is cheap.
	 */
	static Target exact(BlockState state) {
		return Exact.of(state);
	}

	/**
	 * Any full, solid block. Placing uses ordinary building blocks from your inventory: full cubes that don't fall and
	 * don't open a screen when clicked (no chests, no sand).
	 */
	static Target solid() {
		return Solid.INSTANCE;
	}

	/** {@link #exact}. */
	final class Exact implements Target {
		private static final Map<BlockState, Exact> ALL = new ConcurrentHashMap<>();
		private final BlockState state;

		private Exact(BlockState state) {
			this.state = state;
		}

		static Exact of(BlockState state) {
			return ALL.computeIfAbsent(state, Exact::new);
		}

		/** The state wanted. */
		public BlockState state() {
			return state;
		}

		@Override
		public boolean matches(BlockGetter level, BlockPos pos, BlockState current) {
			if (BlockRules.placedWithNeighbour(state)) return current.is(state.getBlock()) || current.canBeReplaced();
			return BlockRules.matches(current, state, true);
		}

		@Override
		public boolean wantsAir() {
			return state.isAir();
		}

		@Override
		public int preference(ItemStack stack) {
			return !stack.isEmpty() && stack.is(BlockRules.item(state)) ? 0 : -1;
		}

		@Override
		public BlockState orientedState() {
			return state;
		}

		@Override
		public String describe() {
			return state.getBlock().getName().getString();
		}
	}

	final class Air implements Target {
		static final Air INSTANCE = new Air();

		@Override
		public boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
			return state.isAir() || !state.getFluidState().isEmpty() && state.canBeReplaced();
		}

		@Override
		public boolean wantsAir() {
			return true;
		}

		@Override
		public int preference(ItemStack stack) {
			return -1;
		}

		@Override
		public String describe() {
			return "air";
		}
	}

	final class Solid implements Target {
		static final Solid INSTANCE = new Solid();

		@Override
		public boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
			return !state.isAir() && Block.isShapeFullBlock(state.getCollisionShape(level, pos));
		}

		@Override
		public int preference(ItemStack stack) {
			if (!(stack.getItem() instanceof BlockItem item)) return -1;
			Block block = item.getBlock();
			BlockState state = block.defaultBlockState();
			if (block instanceof FallingBlock || BlockInfo.isClickable(state) || state.hasBlockEntity()) return -1;
			return Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) ? 0 : -1;
		}

		@Override
		public String describe() {
			return "any solid block";
		}
	}
}
