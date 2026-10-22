package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Breaking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Breaking as the server sees it: dig packets and their timing, against Grim's FastBreak check and vanilla's 70% rule. */
class BreakingHarnessTest {
	/** Grim's limit on each balance; past it, the packet is refused. */
	private static final double GRIM_LIMIT = 1000;

	@Test
	void packetBreaksInARowNeverPassGrimsLimitsAndFinishEarly() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			List<BlockPos> blocks = new ArrayList<>();
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
					if ((x != 0 || z != 0) && blocks.size() < 12) blocks.add(new BlockPos(x, 1, z));
				}
			}
			for (BlockPos p : blocks) h.level.set(p, Blocks.STONE.defaultBlockState());
			h.standAt(0.5, 1, 0.5, 0, 45).give(0, new ItemStack(Items.DIAMOND_PICKAXE));
			// What each block was before it went: for the damage Grim and the server credit.
			Map<BlockPos, BlockState> was = new HashMap<>();
			for (BlockPos p : blocks) was.put(p, h.level.getBlockState(p));

			List<Breaking.Attempt> attempts = new ArrayList<>();
			h.onTick(() -> {
				for (BlockPos p : blocks) attempts.add(Myriad.breaking().breakBlock(this, p, 0, Breaking.Options.forServer()));
			});
			int answered = 0;
			for (int t = 0; t < 200 && answered < blocks.size(); t++) {
				h.tick();
				// The server breaks each finished block and says so, as it would by the next tick.
				for (Harness.Sent s : h.sent()) {
					if (s.tick() != h.currentTick() || !(s.packet() instanceof ServerboundPlayerActionPacket p)) continue;
					if (p.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) continue;
					h.serverBlock(p.getPos(), Blocks.AIR.defaultBlockState()).ack(p.getSequence());
					answered++;
				}
			}
			assertEquals(blocks.size(), answered, "not every block was finished");
			assertTrue(attempts.stream().allMatch(Breaking.Attempt::accepted));

			// Replay the dig packets through Grim's FastBreak (as Grim 2.3.74's source has it) and the vanilla server's 70% rule.
			double breakBalance = 0, delayBalance = 0, worstDelay = 0, worstBreak = 0;
			// No finish yet: the first start is as spaced out as can be.
			long startMs = 0, lastFinishMs = Long.MIN_VALUE / 2;
			int startTick = 0, earlyFinishes = 0, ticksTaken = 0, vanillaTicks = 0;
			boolean first = true;
			double damage = 0;
			for (Harness.Sent s : h.sent()) {
				if (!(s.packet() instanceof ServerboundPlayerActionPacket p)) continue;
				long now = s.tick() * Harness.TICK_MS;
				if (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
					long gap = now - lastFinishMs;
					if (gap >= 275) delayBalance *= 0.9;
					else delayBalance += 300 - gap;
					worstDelay = Math.max(worstDelay, delayBalance);
					assertTrue(delayBalance <= GRIM_LIMIT, "Grim refuses a start at tick " + s.tick() + " (start balance " + delayBalance + ")");
					// Grim counts its first start as 50 ms earlier than it came.
					startMs = now - (first ? 50 : 0);
					first = false;
					startTick = s.tick();
					damage = was.get(p.getPos()).getDestroyProgress(h.player, h.level, p.getPos());
				} else if (p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
					double expectedMs = Math.ceil(1 / damage) * 50;
					double shortfall = expectedMs - (now - startMs);
					if (shortfall < 25) breakBalance *= 0.9;
					else {
						breakBalance += shortfall;
						earlyFinishes++;
					}
					worstBreak = Math.max(worstBreak, breakBalance);
					assertTrue(breakBalance <= GRIM_LIMIT, "Grim refuses a finish at tick " + s.tick() + " (break balance " + breakBalance + ")");
					int ticks = s.tick() - startTick;
					assertTrue(damage * (ticks + 1) >= 0.7, "the server refuses a finish after " + ticks + " ticks (under 70%)");
					ticksTaken += ticks;
					vanillaTicks += (int) Math.ceil(1 / damage);
					lastFinishMs = now;
				}
			}
			assertTrue(earlyFinishes > 0, "never used Grim's allowance for early finishes");
			// Core keeps a margin under Grim's limit (800 for finishes, 700 for starts): the replay should agree.
			assertTrue(worstBreak <= 800 && worstDelay <= 700, "past core's own margin: " + worstBreak + " / " + worstDelay);
			assertTrue(ticksTaken < vanillaTicks, ticksTaken + " ticks mining, no faster than vanilla's " + vanillaTicks);
			System.out.println("[break] " + blocks.size() + " blocks: " + ticksTaken + " ticks mining (vanilla " + vanillaTicks + "), "
				+ earlyFinishes + " early, highest balances " + Math.round(worstBreak) + " (finishes) / " + Math.round(worstDelay) + " (starts)");
		}
	}
}
