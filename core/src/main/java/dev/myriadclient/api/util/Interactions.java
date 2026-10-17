package dev.myriadclient.api.util;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Inventory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The player's actions, done the way vanilla does them (cooldowns reset, sequence numbers kept, events posted), plus
 * the timers vanilla keeps for them. Use these instead of building the packets yourself.
 */
public final class Interactions {
	private Interactions() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static boolean ready() {
		return mc().player != null && mc().gameMode != null;
	}

	/** Attacks {@code target} as a left click would, posting {@code AttackEvent}. */
	public static void attack(Entity target, boolean swing) {
		if (!ready()) return;
		mc().gameMode.attack(mc().player, target);
		if (swing) mc().player.swing(InteractionHand.MAIN_HAND);
	}

	/** Right-clicks a block face (placing, opening, pressing). Returns what happened. */
	public static InteractionResult interactBlock(BlockHitResult hit, InteractionHand hand, boolean swing) {
		if (!ready()) return InteractionResult.FAIL;
		InteractionResult result = mc().gameMode.useItemOn(mc().player, hand, hit);
		if (swing && result.consumesAction()) mc().player.swing(hand);
		return result;
	}

	/** Uses the item in {@code hand} (eating, throwing, shooting) as a right click in the air would. */
	public static InteractionResult useItem(InteractionHand hand) {
		if (!ready()) return InteractionResult.FAIL;
		return mc().gameMode.useItem(mc().player, hand);
	}

	/**
	 * Starts breaking the block at {@code pos} on {@code side} (as a left click would), breaking it at once if it's
	 * instant. Keep calling {@link #continueBreaking} each tick for slower blocks. Returns false if nothing happened.
	 */
	public static boolean startBreaking(BlockPos pos, Direction side) {
		return ready() && mc().gameMode.startDestroyBlock(pos, side);
	}

	/** Continues breaking {@code pos}, as holding left click does each tick. */
	public static boolean continueBreaking(BlockPos pos, Direction side) {
		return ready() && mc().gameMode.continueDestroyBlock(pos, side);
	}

	public static void stopBreaking() {
		if (ready()) mc().gameMode.stopDestroyBlock();
	}

	public static boolean isMining() {
		return ready() && mc().gameMode.isDestroying();
	}

	/** Eating or drinking right now. */
	public static boolean isEating() {
		var p = mc().player;
		if (p == null || !p.isUsingItem()) return false;
		var action = p.getUseItem().getUseAnimation();
		return action == ItemUseAnimation.EAT || action == ItemUseAnimation.DRINK;
	}

	/**
	 * The usual "should this feature wait?" check: true while eating or drinking (if {@code whileEating}) or mining
	 * (if {@code whileMining}). Combat features pause like this so they don't interrupt the player.
	 */
	public static boolean shouldPause(boolean whileEating, boolean whileMining) {
		return (whileEating && isEating()) || (whileMining && isMining());
	}

	/** Swings the hand: the server sees it, and the client animates it. */
	public static void swing(InteractionHand hand) {
		if (mc().player != null) mc().player.swing(hand);
	}

	/**
	 * Attack charge, 0..1 (1 = a full-strength hit), of the item the server holds in your hand (a weapon a module holds
	 * silently counts, not the one you see): see {@link Inventory#attackCharge()}.
	 */
	public static float attackCharge() {
		return Myriad.inventory().attackCharge();
	}

	/** Ticks since you last swung to attack (the counter attack charge comes from). */
	public static int ticksSinceAttack() {
		return mc().player == null ? 0 : mc().player.attackStrengthTicker;
	}

	/** Ticks until right-click can be used again (vanilla waits 4 between uses). */
	public static int itemUseCooldown() {
		return mc().rightClickDelay;
	}

	/** Sets the right-click cooldown, e.g. 0 to use or place every tick. */
	public static void setItemUseCooldown(int ticks) {
		mc().rightClickDelay = Math.max(0, ticks);
	}

	/** Sets the delay before the player can jump again (vanilla waits 10 ticks between held jumps). */
	public static void setJumpCooldown(int ticks) {
		if (mc().player != null) mc().player.noJumpDelay = Math.max(0, ticks);
	}
}
