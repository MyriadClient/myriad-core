package com.example.myriadaddon.modules;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.BlockBreakEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.util.Mining;
import dev.myriadclient.api.util.MyriadId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Switches to the best tool when you start mining, and back afterwards. A better tool in your inventory is borrowed
 * into the hotbar, and goes back to the inventory once you stop.
 *
 * <p>Shows:
 * <ul>
 *   <li>using a shared service ({@code Myriad.inventory()}) instead of poking the inventory directly, so slot changes
 *       stay in sync with other features;</li>
 *   <li>{@code borrow}, which brings an item in without disturbing what you use and puts it back once you stop asking
 *       for it. Grim (2b2t) cancels inventory clicks sent while you move, so while you do it first releases your keys
 *       for a tick ({@code prepareClick}) and the item comes in on the next call;</li>
 *   <li>using core helpers ({@link Mining}) instead of re-deriving vanilla maths;</li>
 *   <li>soft integration with another addon by id, without compiling against it: if Myriad Essentials' Packet Mine is
 *       on, it mines (and picks tools) itself and this module stays out of its way.</li>
 * </ul>
 */
public final class AutoTool extends Module {
	/** Another addon's module, referenced by id so this addon still works when that one isn't installed. */
	private static final MyriadId PACKET_MINE = MyriadId.of("myriad-essentials", "packet_mine");

	private final BoolSetting switchBack = sgGeneral.bool("Switch Back").description("Return to the previous slot when you stop mining.").defaultValue(true).build();
	private final IntSetting keepDurability = sgGeneral.intSetting("Keep Durability").description("Skip tools with this much durability or less, so they don't break.")
		.defaultValue(5).range(0, 100).build();

	private int previousSlot = -1;

	public AutoTool() {
		super(Categories.PLAYER, "Auto Tool", "Switches to the best tool for the block you're mining.");
	}

	@Override
	protected void onDisable() {
		restore();
	}

	@Subscribe
	private void onStartBreaking(BlockBreakEvent.Start e) {
		if (!inGame() || packetMineActive()) return;
		BlockState state = mc.level.getBlockState(e.pos());
		int best = bestSlot(state);
		if (best >= 9) {
			// In the main inventory: borrow it, over a worse tool for this block if the hotbar is full. -1 means not
			// now (you're moving), and the best hotbar tool does meanwhile.
			best = Myriad.inventory().borrow(this, best, s -> s.getDestroySpeed(state) > 1);
			if (best == -1) best = bestSlot(state, 9);
		}
		int current = mc.player.getInventory().getSelectedSlot();
		if (best == -1 || best == current) return;
		if (previousSlot == -1) previousSlot = current;
		Myriad.inventory().select(best);
	}

	/**
	 * Actions (switching slots, clicking, attacking) belong at the start of a tick, before the player moves: that's when
	 * vanilla sends them, and Grim flags them after the movement packet. Myriad holds late ones back to the next tick,
	 * but acting in {@code TickEvent.Pre} means they go out when you meant them to.
	 */
	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (previousSlot == -1 || !inGame()) return;
		// Still mining: keep the tool if it's borrowed. Once you stop, it goes back on its own.
		if (mc.gameMode.isDestroying()) Myriad.inventory().borrow(this, mc.player.getInventory().getSelectedSlot(), null);
		else restore();
	}

	private void restore() {
		if (previousSlot != -1 && switchBack.get() && inGame()) Myriad.inventory().select(previousSlot);
		previousSlot = -1;
	}

	/** The slot (hotbar or main inventory) that mines {@code state} fastest, or -1 if nothing beats what you're holding. */
	private int bestSlot(BlockState state) {
		return bestSlot(state, 36);
	}

	/** The slot below {@code end} that mines {@code state} fastest, or -1 if nothing beats what you're holding. */
	private int bestSlot(BlockState state, int end) {
		int best = -1;
		float bestSpeed = Mining.speed(state, mc.player.getInventory().getSelectedSlot());
		for (int i = 0; i < end; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= keepDurability.get()) continue;
			float speed = Mining.speed(state, i);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		return best;
	}

	private static boolean packetMineActive() {
		return Myriad.modules().get(PACKET_MINE).map(Module::isEnabled).orElse(false);
	}
}
