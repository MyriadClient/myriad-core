package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.util.Mining;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Tools for {@link BreakManager}: which slot mines a block, how fast as the server and Grim credit it (Efficiency only
 * once the server has held the tool a while), fetching a better tool from the inventory, and doing a step's packets with
 * the tool held at the server.
 */
final class BreakTools {
	/** Most ticks a break waits for its tool to come in before starting with what's in the hotbar. */
	private static final int TOOL_WAIT_TICKS = 10;

	private final Minecraft mc = Minecraft.getInstance();
	/** Who borrows tools and holds slots: the breaking service. */
	private final Object owner;
	/** Efficiency of the item the server held, sampled each tick: {ms, level}, oldest first. */
	private final ArrayDeque<long[]> heldEfficiency = new ArrayDeque<>();
	/** A waiting break whose better tool is being brought in from the inventory: it doesn't start until it arrives. */
	private @Nullable BreakJob toolComing, toolWaitJob;
	private int toolWait;
	/** A tool was swapped in this step: it goes back at the end of the step. */
	private boolean swapped;
	/** Holding a slot at the server between packets (see {@link #updateHold}). */
	private boolean holding;

	BreakTools(Object owner) {
		this.owner = owner;
	}

	/**
	 * Progress per tick with {@code slot} held, as the server and Grim credit it: Efficiency counts only as far as
	 * the items the server held lately had it (see {@link #creditedEfficiency(int)}).
	 */
	float rate(BlockState state, BlockPos pos, int slot) {
		return Mining.delta(state, pos, slot, creditedEfficiency(slot));
	}

	/**
	 * The Efficiency the server and Grim count for {@code slot}: its own, but no more than any item the server held
	 * in about the time the attribute takes to reach Grim (it follows the item held at a server tick, and Grim gets it
	 * a round trip later): one and a half pings plus a margin. A tool swapped in just now mines at its base speed there.
	 */
	private int creditedEfficiency(int slot) {
		int level = slot >= 0 ? Mining.efficiency(mc.player.getInventory().getItem(slot)) : 0;
		if (level == 0) return 0;
		level = Math.min(level, Mining.efficiency(mc.player.getInventory().getItem(Myriad.inventory().serverSlot())));
		long since = Clock.millis() - efficiencyWindowMs();
		for (long[] sample : heldEfficiency) if (sample[0] >= since) level = Math.min(level, (int) sample[1]);
		return level;
	}

	boolean hasEfficiency(int slot) {
		return slot >= 0 && Mining.efficiency(mc.player.getInventory().getItem(slot)) > 0;
	}

	static long efficiencyWindowMs() {
		return Myriad.server().ping() * 3L / 2 + 200;
	}

	/** Notes the Efficiency of what the server holds this tick, keeping a few seconds of it. */
	void sampleEfficiency() {
		long now = Clock.millis();
		heldEfficiency.addLast(new long[]{now, Mining.efficiency(mc.player.getInventory().getItem(Myriad.inventory().serverSlot()))});
		while (heldEfficiency.size() > 1 && now - heldEfficiency.peekFirst()[0] > 5000) heldEfficiency.pollFirst();
	}

	int slotFor(Breaking.Options o, BlockState state, BlockPos pos) {
		int selected = mc.player.getInventory().getSelectedSlot();
		if (!o.autoTool()) return selected;
		int best = Mining.fastestSlot(state, 0, 9);
		return best < 0 || Mining.delta(state, pos, selected) >= Mining.delta(state, pos, best) ? selected : best;
	}

	/** The break waiting for its better tool to come in (it doesn't start until then), or null. */
	@Nullable BreakJob toolComing() {
		return toolComing;
	}

	/**
	 * Auto tool from the inventory: when the best tool for the block being mined, or the next one, is outside the
	 * hotbar, it's borrowed in (see {@link Inventory#borrow}, which waits while you move, since Grim cancels clicks then,
	 * and puts it back once no break needs it); until then the best hotbar tool does. A packet-mode break already started
	 * is timed by the tool it started with, so then it's fetched for the next block instead.
	 */
	void fetch(List<BreakJob> jobs, @Nullable BreakJob primary) {
		// Borrowed tools stay while any break still needs them.
		for (BreakJob q : jobs) {
			if (!q.options.autoTool()) continue;
			BlockState state = mc.level.getBlockState(q.pos);
			Myriad.inventory().borrow(owner, slotFor(q.options, state, q.pos), null);
		}
		BreakJob j = primary != null && primary.phase == BreakJob.Phase.MINING && (!primary.packet() || primary.fast()) ? primary : null;
		if (j == null) for (BreakJob q : jobs) if (q.phase == BreakJob.Phase.QUEUED) {
			j = q;
			break;
		}
		toolComing = null;
		if (j == null || !j.options.autoTool()) return;
		BlockState state = mc.level.getBlockState(j.pos);
		int from = Mining.fastestSlot(state, 9, 36);
		if (from < 0 || Mining.delta(state, j.pos, from) <= Mining.delta(state, j.pos, slotFor(j.options, state, j.pos))) return;
		// A tool that's worse for this block makes room for it. While you move it takes a tick longer (your keys are
		// released first); a break that hasn't started waits for it rather than start with a worse tool, as packet
		// breaks are timed by the tool they start with.
		if (Myriad.inventory().borrow(owner, from, s -> s.getDestroySpeed(state) > 1) < 0 && j.phase == BreakJob.Phase.QUEUED) {
			toolWait = j == toolWaitJob ? toolWait + 1 : 0;
			toolWaitJob = j;
			if (toolWait < TOOL_WAIT_TICKS) toolComing = j;
		}
	}

	/**
	 * Runs {@code action} (break packets) with {@code slot} held at the server, swapping only if it isn't already. The
	 * swap goes back at the end of this step, in the same tick: Grim's PacketOrderE flags a slot change after an attack
	 * or a use in the same tick, not after a dig.
	 */
	void withTool(int slot, Runnable action) {
		if (Myriad.inventory().serverSlot() == slot) {
			action.run();
			return;
		}
		Myriad.inventory().silentSwap(slot, action);
		swapped = true;
	}

	/** Whether a tool was swapped in since {@link #startStep()}: the step sends it back at its end. */
	boolean swappedThisStep() {
		return swapped;
	}

	void startStep() {
		swapped = false;
	}

	boolean holding() {
		return holding;
	}

	/**
	 * Holds the tool a break needs at the server between packets: the vanilla-mode block being mined, the
	 * double-broken block around when the server finishes it (it checks the held tool every tick), and a packet-mode
	 * block for the last stretch before it's done, so the tool's Efficiency counts by the finish (it only does once
	 * the server has ticked with the tool held, and Grim has had it back). Not while you use an item in your main hand.
	 */
	void updateHold(@Nullable BreakJob primary, @Nullable BreakJob delayed, List<BreakJob> jobs, int tick) {
		int slot = -1;
		int lead = (int) Math.ceil(efficiencyWindowMs() / 50.0) + 2;
		if (delayed != null && tick >= delayed.due - lead) {
			BlockState state = mc.level.getBlockState(delayed.pos);
			slot = slotFor(delayed.options, state, delayed.pos);
		} else if (primary != null && primary.phase == BreakJob.Phase.MINING) {
			BreakJob p = primary;
			BlockState state = mc.level.getBlockState(p.pos);
			int tool = slotFor(p.options, state, p.pos);
			// With the tool's Efficiency, the block would be done within the time it takes to count: hold it from now.
			float full = Mining.delta(state, p.pos, tool);
			if (!p.packet() || hasEfficiency(tool) && full * (tick - p.started + 1 + lead) >= BreakManager.PACKET_THRESHOLD) slot = tool;
		} else if (primary == null) {
			// Between blocks: keep (or bring) the next block's tool, if its Efficiency would make a difference, so it
			// counts by the time that block starts.
			for (BreakJob j : jobs) {
				if (j.phase != BreakJob.Phase.QUEUED || !j.packet()) continue;
				BlockState state = mc.level.getBlockState(j.pos);
				int tool = slotFor(j.options, state, j.pos);
				if (hasEfficiency(tool)) slot = tool;
				break;
			}
		}
		if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) slot = -1;
		if (slot >= 0 && slot != mc.player.getInventory().getSelectedSlot()) {
			holding = Myriad.inventory().hold(owner, slot, 3);
		} else if (holding) {
			Myriad.inventory().release(owner);
			holding = false;
		}
	}

	void clear() {
		heldEfficiency.clear();
		holding = false;
	}
}
