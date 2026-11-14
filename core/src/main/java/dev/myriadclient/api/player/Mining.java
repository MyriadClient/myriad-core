package dev.myriadclient.api.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.item.ItemInfo;
import dev.myriadclient.api.service.Inventory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block breaking maths that mirrors the server, for mining with packets. Slots are inventory indexes (0-8 the hotbar,
 * see {@link dev.myriadclient.api.item.Slots}), -1 for bare hands. Everything but {@link #efficiency} reads the local
 * player, so call it in game only.
 */
public final class Mining {
	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private Mining() {
	}

	/** The Efficiency level on {@code stack}, 0 without it. */
	public static int efficiency(ItemStack stack) {
		return ItemInfo.enchantmentLevel(stack, Enchantments.EFFICIENCY);
	}

	/**
	 * The inventory slot in [from, to) whose item mines {@code state} fastest (by the item's speed and Efficiency, ties
	 * to the first), or -1 if the range is empty or every item in it is spared. Items a module
	 * {@link Inventory#spare spares} (gear about to break) don't count. An empty slot counts as bare hands.
	 */
	public static int fastestSlot(BlockState state, int from, int to) {
		double best = -1;
		int slot = -1;
		for (int i = from; i < to; i++) {
			ItemStack stack = mc().player.getInventory().getItem(i);
			if (Myriad.inventory().isSpared(stack)) continue;
			double score = stack.getDestroySpeed(state);
			if (score > 1) {
				int eff = efficiency(stack);
				if (eff > 0) score += eff * eff + 1;
			}
			if (score > best) {
				best = score;
				slot = i;
			}
		}
		return slot;
	}

	/**
	 * Whether breaking {@code state} with the item in {@code toolSlot} drops it (and breaks at the faster rate): always
	 * for blocks that need no tool, else only with a correct one.
	 */
	public static boolean canHarvest(BlockState state, int toolSlot) {
		if (!state.requiresCorrectToolForDrops()) return true;
		return toolSlot >= 0 && mc().player.getInventory().getItem(toolSlot).isCorrectToolForDrops(state);
	}

	/**
	 * Mining speed with the tool in {@code toolSlot} (or bare hands for -1), including Haste, Mining Fatigue, being
	 * under water and being off the ground (a fifth), as the server works it out. 1 is bare hands on the ground.
	 */
	public static float speed(BlockState state, int toolSlot) {
		return speed(state, toolSlot, toolSlot >= 0 ? efficiency(mc().player.getInventory().getItem(toolSlot)) : 0);
	}

	/**
	 * {@link #speed(BlockState, int)} with Efficiency at {@code efficiency} instead of the tool's own. The server (and
	 * Grim) add Efficiency through an attribute that follows the item held at its last tick, not the item held now: a
	 * tool swapped in moments ago mines at its base speed there until the server has ticked with it (and Grim has had
	 * the attribute back), so code timing a break by the server should pass what it has had.
	 */
	public static float speed(BlockState state, int toolSlot, int efficiency) {
		float speed = 1f;
		if (toolSlot >= 0) {
			ItemStack stack = mc().player.getInventory().getItem(toolSlot);
			speed = stack.getDestroySpeed(state);
			if (speed > 1f && efficiency > 0 && !stack.isEmpty()) speed += efficiency * efficiency + 1;
		}
		if (MobEffectUtil.hasDigSpeed(mc().player)) speed *= 1f + (MobEffectUtil.getDigSpeedAmplification(mc().player) + 1) * 0.2f;
		var fatigue = mc().player.getEffect(MobEffects.MINING_FATIGUE);
		if (fatigue != null) {
			speed *= switch (fatigue.getAmplifier()) {
				case 0 -> 0.3f;
				case 1 -> 0.09f;
				case 2 -> 0.0027f;
				default -> 8.1e-4f;
			};
		}
		if (mc().player.isEyeInFluid(FluidTags.WATER)) speed *= (float) mc().player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
		if (!mc().player.onGround()) speed /= 5f;
		return speed;
	}

	/**
	 * Fraction of the block at {@code pos} broken per tick with the tool in {@code toolSlot}; 0 for unbreakable blocks.
	 * A block breaks when the sum reaches 1, so {@code Math.ceil(1 / delta)} is its break time in ticks (1 or more
	 * breaks at once).
	 */
	public static float delta(BlockState state, BlockPos pos, int toolSlot) {
		return delta(state, pos, toolSlot, toolSlot >= 0 ? efficiency(mc().player.getInventory().getItem(toolSlot)) : 0);
	}

	/** {@link #delta(BlockState, BlockPos, int)} with Efficiency at {@code efficiency} (see {@link #speed(BlockState, int, int)}). */
	public static float delta(BlockState state, BlockPos pos, int toolSlot, int efficiency) {
		float hardness = state.getDestroySpeed(mc().level, pos);
		if (hardness == -1f) return 0f;
		int divisor = canHarvest(state, toolSlot) ? 30 : 100;
		return speed(state, toolSlot, efficiency) / hardness / divisor;
	}
}
