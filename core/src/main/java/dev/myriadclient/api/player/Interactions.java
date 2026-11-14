package dev.myriadclient.api.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.util.Ticks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

/**
 * The player's actions, done the way vanilla does them (cooldowns reset, sequence numbers kept, events posted), plus
 * the timers vanilla keeps for them. Use these instead of building the packets yourself. Outside a world the actions
 * do nothing and return {@link InteractionResult#FAIL} or false.
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

	/**
	 * Attacks {@code target} as a left click would, posting {@code AttackEvent}, and swings the main hand if
	 * {@code swing}. Reach and attack charge aren't checked: see {@link Reach#canReach(Entity)} and
	 * {@link #attackCharge()}.
	 */
	public static void attack(Entity target, boolean swing) {
		if (!ready()) return;
		mc().gameMode.attack(mc().player, target);
		if (swing) swing(InteractionHand.MAIN_HAND, mc().player.getMainHandItem().getAttackAnimation());
	}

	/**
	 * Right-clicks a block face (placing, opening, pressing) with the item in {@code hand}, swinging if {@code swing}
	 * and the click did something. Returns what happened, as the client predicts it.
	 */
	public static InteractionResult interactBlock(BlockHitResult hit, InteractionHand hand, boolean swing) {
		if (!ready()) return InteractionResult.FAIL;
		InteractionResult result = mc().gameMode.useItemOn(mc().player, hand, hit);
		if (swing && result.consumesAction()) swing(hand);
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

	/**
	 * Continues breaking {@code pos}, as holding left click does each tick (starting over if it's another block than
	 * the one being broken). False if nothing happened.
	 */
	public static boolean continueBreaking(BlockPos pos, Direction side) {
		return ready() && mc().gameMode.continueDestroyBlock(pos, side);
	}

	/** Gives up the block being broken, as letting go of left click does; the server is told it was aborted. */
	public static void stopBreaking() {
		if (ready()) mc().gameMode.stopDestroyBlock();
	}

	/** Whether your hand is breaking a block right now (holding attack on one, or a module breaking through vanilla's way). */
	public static boolean isMining() {
		return ready() && mc().gameMode.isDestroying();
	}

	/**
	 * How far your own hand has got breaking the block at {@code pos}, 0 to 1; 0 if it isn't breaking that block. A
	 * break a module makes through the breaking service is {@code Myriad.breaking().progress(pos)}.
	 */
	@ApiStatus.AvailableSince("2.0")
	public static float handBreakProgress(BlockPos pos) {
		if (!ready() || !mc().gameMode.isDestroying() || !pos.equals(mc().gameMode.destroyBlockPos)) return 0;
		return mc().gameMode.destroyProgress;
	}

	/** Whether you're eating or drinking right now (using a food or drink item); false outside a world. */
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

	/** Swings the hand: the server sees it, and the client animates it. Nothing outside a world. */
	public static void swing(InteractionHand hand) {
		if (mc().player != null) swing(hand, mc().player.getItemInHand(hand).getInteractAnimation());
	}

	private static void swing(InteractionHand hand, SwingAnimation animation) {
		mc().player.swing(hand, animation, false);
		mc().player.connection.send(ServerboundPunchPacket.INSTANCE);
	}

	/**
	 * Attack charge, 0..1 (1 = a full-strength hit), of the item the server holds in your hand (a weapon a module holds
	 * silently counts, not the one you see): see {@link Inventory#attackCharge()}.
	 */
	public static float attackCharge() {
		return Myriad.inventory().attackCharge();
	}

	/** Ticks since you last swung to attack (the counter attack charge comes from); 0 outside a world. */
	public static int ticksSinceAttack() {
		return mc().player == null ? 0 : mc().player.attackStrengthTicker;
	}

	/** Ticks until right-click can be used again (vanilla waits 4 between uses). */
	public static int itemUseCooldown() {
		return mc().rightClickDelay;
	}

	/** Sets the right-click cooldown in ticks (negative counts as 0), e.g. 0 to use or place every tick. */
	public static void setItemUseCooldown(int ticks) {
		mc().rightClickDelay = Math.max(0, ticks);
	}

	/** Sets the delay before the player can jump again (vanilla waits 10 ticks between held jumps). */
	public static void setJumpCooldown(int ticks) {
		if (mc().player != null) mc().player.noJumpDelay = Math.max(0, ticks);
	}
}
