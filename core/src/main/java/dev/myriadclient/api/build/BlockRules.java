package dev.myriadclient.api.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a block state is made of, for building it: which properties the placement itself decides (checked by
 * simulating the click), which grow with more of the same item (candles, sea pickles, layers, double slabs), which a
 * bare-hand click cycles afterwards (repeater delay, comparator mode, note, open, lever), and which the game keeps
 * changing on its own (connections, power, age), which a build can't hold and ignores. Core's building and
 * {@code Placement.solve} work from these.
 */
@ApiStatus.Internal
public final class BlockRules {
	/** Properties the click sets: the face clicked, where on it, and which way you look. */
	public static final List<Property<?>> PLACED = List.of(
		BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
		BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.ORIENTATION,
		BlockStateProperties.ATTACH_FACE, BlockStateProperties.BELL_ATTACHMENT, BlockStateProperties.HALF,
		BlockStateProperties.ROTATION_16, BlockStateProperties.DOOR_HINGE, BlockStateProperties.VERTICAL_DIRECTION,
		BlockStateProperties.HANGING, BlockStateProperties.CHEST_TYPE
	);

	/** Properties that count up as the same item is used on the block again. A double slab is the slab's second half. */
	public static final List<IntegerProperty> COUNTED = List.of(
		BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS, BlockStateProperties.LAYERS,
		BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT
	);

	/** Each block's {@link Profile}, worked out the first time it's asked for. */
	private static final Map<Block, Profile> PROFILES = new ConcurrentHashMap<>();

	/**
	 * The properties of one block that a build cares about, picked out once, so comparing two of its states only reads
	 * those (a stair compares two values, not a list of every property any block might have).
	 */
	private record Profile(Property<?>[] placed, @Nullable IntegerProperty counted, boolean slab, Property<?>[] settings) {
		static Profile of(Block block) {
			var all = block.getStateDefinition().getProperties();
			Property<?>[] placed = PLACED.stream().filter(all::contains).toArray(Property<?>[]::new);
			IntegerProperty counted = COUNTED.stream().filter(all::contains).findFirst().orElse(null);
			boolean slab = all.contains(BlockStateProperties.SLAB_TYPE);
			List<Property<?>> settings = new ArrayList<>();
			if (all.contains(BlockStateProperties.DELAY)) settings.add(BlockStateProperties.DELAY);
			if (all.contains(BlockStateProperties.NOTE)) settings.add(BlockStateProperties.NOTE);
			if (all.contains(BlockStateProperties.MODE_COMPARATOR)) settings.add(BlockStateProperties.MODE_COMPARATOR);
			if (all.contains(BlockStateProperties.INVERTED) && block instanceof DaylightDetectorBlock) settings.add(BlockStateProperties.INVERTED);
			if (all.contains(BlockStateProperties.POWERED) && block instanceof LeverBlock) settings.add(BlockStateProperties.POWERED);
			if (all.contains(BlockStateProperties.OPEN)) settings.add(BlockStateProperties.OPEN);
			return new Profile(placed, counted, slab, settings.toArray(Property<?>[]::new));
		}
	}

	private BlockRules() {
	}

	private static Profile profile(Block block) {
		return PROFILES.computeIfAbsent(block, Profile::of);
	}

	/**
	 * Whether {@code current} already is {@code target} as far as a build can make it: the same block, placed the same
	 * way, with as many of it, and (with {@code settings}) clicked into the same setting.
	 */
	public static boolean matches(BlockState current, BlockState target, boolean settings) {
		// States are shared objects: the same one is the commonest answer, and the cheapest.
		if (current == target) return true;
		if (target.isAir()) return current.isAir() || current.getBlock() instanceof LiquidBlock && current.canBeReplaced();
		Block block = target.getBlock();
		if (current.getBlock() != block) return false;
		Profile p = profile(block);
		if (!placedMatches(p, current, target)) return false;
		if (count(p, current) != count(p, target)) return false;
		return !settings || clicksToMatch(p, current, target) == 0;
	}

	/** Whether every placed property both states have agrees (a double slab's halves agree with either). */
	public static boolean placedMatches(BlockState a, BlockState b) {
		if (a.getBlock() == b.getBlock()) return placedMatches(profile(a.getBlock()), a, b);
		for (Property<?> p : PLACED) {
			if (a.hasProperty(p) && b.hasProperty(p) && !a.getValue(p).equals(b.getValue(p))) return false;
		}
		return true;
	}

	private static boolean placedMatches(Profile p, BlockState a, BlockState b) {
		for (Property<?> prop : p.placed) if (!a.getValue(prop).equals(b.getValue(prop))) return false;
		if (p.slab) {
			SlabType x = a.getValue(BlockStateProperties.SLAB_TYPE), y = b.getValue(BlockStateProperties.SLAB_TYPE);
			return x == y || x == SlabType.DOUBLE || y == SlabType.DOUBLE;
		}
		return true;
	}

	/**
	 * Whether how {@code state} comes out depends on the click (which face, where on it, which way you look): stairs,
	 * slabs, logs, repeaters, doors. Blocks that don't (stone, glass, planks) can go anywhere they touch, so a build
	 * places them first and the others get more faces to be placed against.
	 */
	public static boolean oriented(BlockState state) {
		Profile p = profile(state.getBlock());
		return p.placed.length > 0 || p.slab;
	}

	/** How many of the block the state holds: the counted property's value, 2 for a double slab, else 1. */
	public static int count(BlockState state) {
		return count(profile(state.getBlock()), state);
	}

	private static int count(Profile p, BlockState state) {
		if (p.counted != null) return state.getValue(p.counted);
		if (p.slab) return state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
		return 1;
	}

	/**
	 * Bare-hand clicks that turn {@code current} into {@code target}'s setting: repeater delay, comparator mode, note,
	 * open or closed, a lever's position and a daylight detector's inversion each cycle by one per click. 0 when they
	 * already agree, -1 when clicking can't get there (an iron door doesn't open by hand).
	 */
	public static int clicksToMatch(BlockState current, BlockState target) {
		if (current.getBlock() != target.getBlock()) return -1;
		return clicksToMatch(profile(target.getBlock()), current, target);
	}

	private static int clicksToMatch(Profile p, BlockState current, BlockState target) {
		int clicks = 0;
		for (Property<?> prop : p.settings) {
			if (current.getValue(prop).equals(target.getValue(prop))) continue;
			if (prop == BlockStateProperties.DELAY) clicks += Math.floorMod(target.getValue(BlockStateProperties.DELAY) - current.getValue(BlockStateProperties.DELAY), 4);
			else if (prop == BlockStateProperties.NOTE) clicks += Math.floorMod(target.getValue(BlockStateProperties.NOTE) - current.getValue(BlockStateProperties.NOTE), 25);
			else if (prop == BlockStateProperties.OPEN && !opensByHand(current.getBlock())) return -1;
			else clicks++;
		}
		return clicks;
	}

	/**
	 * Whether a hand click on {@code current} by a player facing {@code yaw} keeps its facing. Opening a fence gate
	 * from in front swings it away from the player, turning it round; every other setting leaves the facing be.
	 */
	public static boolean clickKeepsFacing(BlockState current, float yaw) {
		if (!(current.getBlock() instanceof FenceGateBlock) || current.getValue(BlockStateProperties.OPEN)) return true;
		return Direction.fromYRot(yaw) != current.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
	}

	private static boolean opensByHand(Block block) {
		if (block instanceof DoorBlock door) return door.type().canOpenByHand();
		// Of the trapdoors only iron's needs redstone (its set type isn't readable from outside).
		if (block instanceof TrapDoorBlock) return block != Blocks.IRON_TRAPDOOR;
		return block instanceof FenceGateBlock;
	}

	/**
	 * Whether a position holding {@code target} is placed as part of another one, so there's nothing to do there
	 * itself: the top half of a door or tall plant, the head of a bed.
	 */
	public static boolean placedWithNeighbour(BlockState target) {
		if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return target.getBlock() instanceof BedBlock && target.getValue(BedBlock.PART) == BedPart.HEAD;
	}

	/**
	 * Whether a build can make {@code target} at all: blocks that only appear through other blocks (portals, fire,
	 * piston heads, fluids flowing) and blocks with no item can't be placed.
	 */
	public static boolean placeable(BlockState target) {
		Block b = target.getBlock();
		if (b instanceof LiquidBlock || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.END_GATEWAY || b == Blocks.FIRE
			|| b == Blocks.SOUL_FIRE || b == Blocks.PISTON_HEAD || b == Blocks.MOVING_PISTON || b == Blocks.BUBBLE_COLUMN) return false;
		return item(target) != Items.AIR;
	}

	/** The item that places {@code target}: a torch for a wall torch, redstone for redstone wire, string for tripwire. */
	public static Item item(BlockState target) {
		return target.getBlock().asItem();
	}

	/** Whether {@code state} needs something solid under it to stay (sand, gravel, concrete powder, anvils) and has nothing. */
	public static boolean falls(BlockGetter level, BlockPos pos, BlockState state) {
		return state.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()));
	}
}
