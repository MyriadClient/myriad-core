package dev.myriadclient.api.util;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PumpkinBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * What kind of block something is, with one answer shared by every addon: can it be broken, does it survive
 * explosions, does it hold items, does right-clicking it open it. Position overloads read the client world.
 */
public final class BlockInfo {
	/** Obsidian's blast resistance; anything at or above it survives crystal and bed explosions. */
	private static final float BLAST_RESISTANT = 600;

	private BlockInfo() {
	}

	private static BlockState state(BlockPos pos) {
		var world = Minecraft.getInstance().level;
		return world == null ? Blocks.AIR.defaultBlockState() : world.getBlockState(pos);
	}

	/** Bedrock, barriers, end portal frames and the like: nothing a survival player can mine. */
	public static boolean isUnbreakable(BlockState state) {
		return state.getBlock().defaultDestroyTime() < 0;
	}

	public static boolean isUnbreakable(BlockPos pos) {
		return isUnbreakable(state(pos));
	}

	/** Not air or fluid, and minable. */
	public static boolean canBreak(BlockPos pos) {
		BlockState s = state(pos);
		return !s.isAir() && s.getFluidState().isEmpty() && !isUnbreakable(s);
	}

	/** Whether the held item (or the best hotbar tool) breaks it in a single tick. */
	public static boolean canInstaBreak(BlockPos pos, int toolSlot) {
		return canBreak(pos) && Mining.delta(state(pos), pos, toolSlot) >= 1;
	}

	/** Survives explosions: obsidian, crying obsidian, ender chests, anvils, netherite blocks, bedrock… */
	public static boolean isBlastResistant(BlockState state) {
		return state.getBlock().getExplosionResistance() >= BLAST_RESISTANT;
	}

	public static boolean isBlastResistant(BlockPos pos) {
		return isBlastResistant(state(pos));
	}

	/** Holds items players put in and take out: chests, barrels, shulkers, ender chests, hoppers, furnaces… */
	public static boolean isStorage(BlockState state) {
		Block b = state.getBlock();
		return b instanceof AbstractChestBlock<?> || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock || b instanceof HopperBlock
			|| b instanceof DispenserBlock || b instanceof AbstractFurnaceBlock || b instanceof BrewingStandBlock || b instanceof CrafterBlock;
	}

	public static boolean isStorage(BlockPos pos) {
		return isStorage(state(pos));
	}

	/**
	 * Right-clicking it does something (opens, toggles, cycles, eats), so clicking it to place a block against it would
	 * interact instead unless the player sneaks. Besides the common cases, any block whose class handles a click itself
	 * counts (repeaters, redstone dust, cakes, composters, candles, modded blocks), told from its code once per class.
	 */
	public static boolean isClickable(BlockState state) {
		Block b = state.getBlock();
		return b instanceof BaseEntityBlock || b instanceof AbstractChestBlock<?> || b instanceof DoorBlock || b instanceof TrapDoorBlock
			|| b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
			|| b instanceof AnvilBlock || b instanceof BedBlock || b instanceof NoteBlock || HANDLES_CLICKS.get(b.getClass());
	}

	/**
	 * Vanilla blocks that handle some clicks but let a held block through to be placed: fences (leads), redstone ore
	 * (lights up), TNT (flint and steel) and pumpkins (shears).
	 */
	private static final Set<Class<?>> PASSES_BLOCKS = Set.of(FenceBlock.class, RedStoneOreBlock.class, TntBlock.class, PumpkinBlock.class);

	/** Per block class: whether it, or a class it extends, overrides {@code useWithoutItem} or {@code useItemOn}. */
	private static final ClassValue<Boolean> HANDLES_CLICKS = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			for (Class<?> c = type; c != null && c != Block.class && c != BlockBehaviour.class; c = c.getSuperclass()) {
				if (PASSES_BLOCKS.contains(c)) return false;
				if (declares(c, "useWithoutItem", BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class)
					|| declares(c, "useItemOn", ItemStack.class, BlockState.class, Level.class, BlockPos.class, Player.class, InteractionHand.class, BlockHitResult.class))
					return true;
			}
			return false;
		}
	};

	private static boolean declares(Class<?> c, String name, Class<?>... params) {
		try {
			c.getDeclaredMethod(name, params);
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	/** Air, water, grass and anything else a placed block replaces. */
	public static boolean isReplaceable(BlockPos pos) {
		return state(pos).canBeReplaced();
	}
}
