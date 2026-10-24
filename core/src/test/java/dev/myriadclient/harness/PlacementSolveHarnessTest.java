package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.BlockRules;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.impl.mixin.BlockItemAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placement solving over every way a set of tricky blocks can be placed (the blocks Essentials' {@code .buildtest}
 * builds in a world): a click is found for each, and that click, made with the look and sneak it asks for, places the
 * block as wanted. Both with the server checking looks (Grim) and without.
 */
class PlacementSolveHarnessTest {
	/** The blocks (made once the harness has booted the game). */
	private static List<Block> blocks() {
		return List.of(
			Blocks.OAK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.OAK_LOG, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER,
			Blocks.PISTON, Blocks.DISPENSER, Blocks.HOPPER, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LEVER, Blocks.STONE_BUTTON,
			Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.CANDLE, Blocks.SEA_PICKLE, Blocks.SNOW, Blocks.LADDER,
			Blocks.LANTERN, Blocks.END_ROD, Blocks.CHEST, Blocks.NOTE_BLOCK, Blocks.BELL, Blocks.OAK_SIGN, Blocks.GRINDSTONE,
			Blocks.DAYLIGHT_DETECTOR, Blocks.POINTED_DRIPSTONE, Blocks.CRAFTER, Blocks.REDSTONE_WIRE
		);
	}
	/**
	 * Where each case is built: a block up, so the face over it is above the player's eyes and can be seen (Grim
	 * checks), as something hung from a ceiling is built from below.
	 */
	private static final BlockPos AT = new BlockPos(0, 2, 0);

	@Test
	void everyCaseSolvesToAClickThatPlacesItWhereLooksAreChecked() {
		solveAll(AntiCheat.Profile.GRIM);
	}

	@Test
	void everyCaseSolvesToAClickThatPlacesItWhereTheyArent() {
		solveAll(AntiCheat.Profile.VANILLA);
	}

	/**
	 * Each case with a block on every side to click but the one the player stands at, two blocks off and facing it; a
	 * case passes from one of the four sides, as a build walks round to the side it needs.
	 */
	private static void solveAll(AntiCheat.Profile profile) {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(profile);
			List<BlockState> cases = cases();
			List<String> failed = new ArrayList<>();
			for (BlockState wanted : cases) {
				BlockItem item = (BlockItem) wanted.getBlock().asItem();
				List<String> tried = new ArrayList<>();
				boolean placedRight = false;
				for (Direction side : Direction.Plane.HORIZONTAL) {
					build(h, wanted, side);
					BlockPos stand = AT.relative(side, 2);
					h.standAt(stand.getX() + 0.5, 1, stand.getZ() + 0.5, side.getOpposite().toYRot(), 30).give(0, new ItemStack(item));
					Placement.Solution s = Myriad.placement().solve(AT, wanted, 0, Placement.Options.forServer());
					Placement.Click click = s.click();
					if (click == null) {
						tried.add(side + ": " + s.check());
						continue;
					}
					BlockState placed = place(h.player, item, click);
					if (placed != null && wanted.getBlock().equals(placed.getBlock()) && key(placed).equals(key(wanted))) {
						placedRight = true;
						break;
					}
					tried.add(side + ": placed " + (placed == null ? "nothing" : key(placed)) + " with " + click);
				}
				if (!placedRight) failed.add(key(wanted) + " " + tried);
			}
			assertTrue(failed.isEmpty(), failed.size() + " of " + cases.size() + " cases on " + profile + ":\n" + String.join("\n", failed));
		}
	}

	/**
	 * A floor, and a stone on every side of the case but {@code open} (the one below standing on the floor) (and above a door, which takes two blocks), with
	 * nothing else nearby.
	 */
	private static void build(Harness h, BlockState wanted, Direction open) {
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
				for (int y = 1; y <= 4; y++) h.level.set(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}
		boolean tall = wanted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
		for (Direction d : Direction.values()) {
			if (d == open || d == Direction.UP && tall) continue;
			h.level.set(AT.relative(d), Blocks.STONE.defaultBlockState());
		}
	}

	/** What {@code click} puts at its position, made as the server would see it: with its look (or the player's) and sneak. */
	private static BlockState place(LocalPlayer player, BlockItem item, Placement.Click click) {
		if (click.turns()) {
			player.setYRot(click.yaw());
			player.setXRot(click.pitch());
		}
		Input real = player.input.keyPresses;
		player.input.keyPresses = new Input(false, false, false, false, false, click.sneak(), false);
		try {
			BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(item), click.hit());
			if (!ctx.canPlace()) return null;
			BlockPlaceContext updated = item.updatePlacementContext(ctx);
			if (updated == null || !updated.getClickedPos().equals(click.pos())) return null;
			return ((BlockItemAccessor) item).myriad$placementState(updated);
		} finally {
			player.input.keyPresses = real;
		}
	}

	/** Every combination of the properties a build sets, one state each. */
	private static List<BlockState> cases() {
		List<BlockState> out = new ArrayList<>();
		for (Block block : blocks()) {
			Set<String> seen = new LinkedHashSet<>();
			for (BlockState s : block.getStateDefinition().getPossibleStates()) {
				if (BlockRules.placedWithNeighbour(s) || !BlockRules.placeable(s)) continue;
				if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)) continue;
				if (s.hasProperty(BlockStateProperties.CHEST_TYPE) && s.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) continue;
				if (s.hasProperty(BlockStateProperties.BELL_ATTACHMENT) && s.getValue(BlockStateProperties.BELL_ATTACHMENT) == BellAttachType.DOUBLE_WALL) continue;
				// More of a block (a second candle, a slab's other half) and settings clicked after placing aren't a first click.
				if (BlockRules.COUNTED.stream().anyMatch(p -> s.hasProperty(p) && !s.getValue(p).equals(block.defaultBlockState().getValue(p)))) continue;
				if (s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) continue;
				if (s.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) && s.hasProperty(BlockStateProperties.SPELEOTHEM_THICKNESS)
					&& s.getValue(BlockStateProperties.SPELEOTHEM_THICKNESS) != SpeleothemThickness.TIP) continue;
				if (seen.add(key(s))) out.add(s);
			}
		}
		return out;
	}

	/** The properties placing decides, as text. */
	private static String key(BlockState s) {
		StringBuilder b = new StringBuilder(s.getBlock().toString()).append('[');
		for (Property<?> p : s.getProperties()) {
			if (BlockRules.PLACED.contains(p) || p == BlockStateProperties.SLAB_TYPE) b.append(p.getName()).append('=').append(s.getValue(p)).append(',');
		}
		return b.append(']').toString();
	}
}
