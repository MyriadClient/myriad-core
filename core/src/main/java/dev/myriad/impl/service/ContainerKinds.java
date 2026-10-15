package dev.myriad.impl.service;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Which menus vanilla's blocks open, to tell the container asked for from something else that opened. */
final class ContainerKinds {
	private ContainerKinds() {
	}

	/** Whether clicking {@code state} could have opened {@code menu}; true for blocks this doesn't know (a mod's). */
	static boolean opens(BlockState state, AbstractContainerMenu menu) {
		MenuType<?> type;
		try {
			type = menu.getType();
		} catch (UnsupportedOperationException e) {
			return false;
		}
		Block b = state.getBlock();
		// A chest is a double one when its neighbour joins it; the menu says which.
		if (b instanceof ChestBlock) return type == MenuType.GENERIC_9x3 || type == MenuType.GENERIC_9x6;
		if (b instanceof EnderChestBlock || b instanceof BarrelBlock) return type == MenuType.GENERIC_9x3;
		if (b instanceof ShulkerBoxBlock) return type == MenuType.SHULKER_BOX;
		if (b instanceof HopperBlock) return type == MenuType.HOPPER;
		// Droppers too.
		if (b instanceof DispenserBlock) return type == MenuType.GENERIC_3x3;
		if (b instanceof CrafterBlock) return type == MenuType.CRAFTER_3x3;
		if (b instanceof FurnaceBlock) return type == MenuType.FURNACE;
		if (b instanceof BlastFurnaceBlock) return type == MenuType.BLAST_FURNACE;
		if (b instanceof SmokerBlock) return type == MenuType.SMOKER;
		if (b instanceof BrewingStandBlock) return type == MenuType.BREWING_STAND;
		if (b instanceof SmithingTableBlock) return type == MenuType.SMITHING;
		if (b instanceof CraftingTableBlock) return type == MenuType.CRAFTING;
		if (b instanceof AnvilBlock) return type == MenuType.ANVIL;
		if (b instanceof GrindstoneBlock) return type == MenuType.GRINDSTONE;
		if (b instanceof StonecutterBlock) return type == MenuType.STONECUTTER;
		if (b instanceof LoomBlock) return type == MenuType.LOOM;
		if (b instanceof CartographyTableBlock) return type == MenuType.CARTOGRAPHY_TABLE;
		if (b instanceof EnchantingTableBlock) return type == MenuType.ENCHANTMENT;
		if (b instanceof BeaconBlock) return type == MenuType.BEACON;
		return true;
	}
}
