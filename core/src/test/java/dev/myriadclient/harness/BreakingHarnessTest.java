package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Breaking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	/**
	 * Buds hanging overhead, as in a geode: reach counts to a bud's own shape (much smaller than its block space), and
	 * every dig packet names a face pointing at the eyes. Grim refuses one pointing away (a top face from below), which
	 * is what a bud out of reach by its shape but not its block space used to get.
	 */
	@Test
	void budsOverheadAreReachedByTheirShapeAndHitOnAFaceTowardsTheEyes() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			BlockState bud = Blocks.SMALL_AMETHYST_BUD.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.DOWN);
			for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
			// Within reach: 2 to 3 blocks up and out. Out of reach by shape only: its block space 4.23 from the eyes, the bud 4.92.
			List<BlockPos> near = List.of(new BlockPos(2, 4, 0), new BlockPos(0, 4, 2), new BlockPos(-2, 4, 0), new BlockPos(1, 4, -2), new BlockPos(0, 4, 0));
			BlockPos far = new BlockPos(4, 5, 0);
			for (BlockPos p : near) {
				h.level.set(p.above(), Blocks.STONE.defaultBlockState());
				h.level.set(p, bud);
			}
			h.level.set(far.above(), Blocks.STONE.defaultBlockState());
			h.level.set(far, bud);
			h.standAt(0.5, 1, 0.5, 0, 0).give(0, new ItemStack(Items.DIAMOND_PICKAXE));
			Vec3 eyes = h.player.getEyePosition();
			assertTrue(new AABB(far).distanceToSqr(eyes) <= 4.5 * 4.5, "the far bud's block space should be in reach");

			assertEquals(Breaking.Check.OUT_OF_RANGE, Myriad.breaking().check(far, Breaking.Options.forServer()));
			h.onTick(() -> {
				for (BlockPos p : near) Myriad.breaking().breakBlock(this, p, 0, Breaking.Options.forServer());
				Myriad.breaking().breakBlock(this, far, 0, Breaking.Options.forServer());
			});
			int broken = 0;
			for (int t = 0; t < 300 && broken < near.size(); t++) {
				h.tick();
				for (Harness.Sent s : h.sent()) {
					if (s.tick() != h.currentTick() || !(s.packet() instanceof ServerboundPlayerActionPacket p)) continue;
					if (p.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) continue;
					h.serverBlock(p.getPos(), Blocks.AIR.defaultBlockState()).ack(p.getSequence());
					broken++;
				}
			}
			assertEquals(near.size(), broken, "not every bud in reach was broken");
			for (Harness.Sent s : h.sent()) {
				if (!(s.packet() instanceof ServerboundPlayerActionPacket p) || p.getPos().getY() > 300) continue;
				if (p.getAction() != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && p.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) continue;
				assertFalse(p.getPos().equals(far), "a dig packet went to the bud out of reach");
				AABB shape = bud.getShape(h.level, p.getPos()).bounds().move(p.getPos());
				boolean towards = switch (p.getDirection()) {
					case UP -> eyes.y > shape.maxY;
					case DOWN -> eyes.y < shape.minY;
					case EAST -> eyes.x > shape.maxX;
					case WEST -> eyes.x < shape.minX;
					case SOUTH -> eyes.z > shape.maxZ;
					case NORTH -> eyes.z < shape.minZ;
				};
				assertTrue(towards, p.getAction() + " at " + p.getPos() + " on its " + p.getDirection() + " face, which points away from the eyes");
			}
		}
	}

	/**
	 * Digging straight down on Grim, the block under you each time, landing on the next as it's started. Replayed
	 * against Grim 2.3.74's FastBreak as its source has it: damage taken at the start and on each movement and punch
	 * packet, but with the on-ground flag of the movement packet before (it samples before applying the packet), so a
	 * landing counts a packet late; past a balance of 1000 the finish is cancelled and the block sent back. And as Grim
	 * takes a finished block away at once, the client has to as well, or it stands on a block Grim thinks is gone.
	 */
	@Test
	void diggingDownPassesGrimsTimingAndTakesTheBlockAwayAsGrimDoes() {
		// As Packet Mine and Nuker ask: hand free, the block kept on screen till the server answers.
		digDown(Breaking.Options.forServer().withSwing(false).withWaitForServer(true));
		digDown(Breaking.Options.forServer().withMode(Breaking.Mode.FAST_GRIM).withDoubleBreak(true).withSwing(false).withWaitForServer(true));
	}

	private void digDown(Breaking.Options options) {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			int top = 12;
			for (int y = 0; y <= top; y++) h.level.set(new BlockPos(0, y, 0), Blocks.STONE.defaultBlockState());
			h.standAt(0.5, top + 1, 0.5, 0, 90).give(0, new ItemStack(Items.DIAMOND_PICKAXE));
			double ground = Blocks.STONE.defaultBlockState().getDestroyProgress(h.player, h.level, new BlockPos(0, top, 0));
			int target = 8;
			h.everyTick(() -> {
				BlockPos below = BlockPos.containing(h.player.getX(), h.player.getY() - 0.5, h.player.getZ());
				if (below.getY() > top - target && !h.level.getBlockState(below).isAir()) {
					Myriad.breaking().breakBlock(this, below, 0, options);
				}
			});

			// Grim's FastBreak and its view of the ground, and the vanilla server's break state.
			boolean grimGround = true;
			double maxDamage = 0, breakBalance = 0, worst = 0;
			long startMs = 0;
			boolean timing = false;
			int serverStart = 0, refusals = 0, shownLate = 0, broken = 0;
			int t = 0;
			for (; t < 400 && broken < target; t++) {
				h.tick();
				for (Harness.Sent s : h.sent()) {
					if (s.tick() != h.currentTick()) continue;
					long now = s.tick() * Harness.TICK_MS;
					double damage = grimGround ? ground : ground / 5;
					switch (s.packet()) {
						case ServerboundPlayerActionPacket p when p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && p.getPos().getY() <= top -> {
							startMs = now - (timing ? 0 : 50);
							timing = true;
							maxDamage = damage;
							serverStart = s.tick();
						}
						case ServerboundPlayerActionPacket p when p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK -> {
							// A decoy above the build limit: air, so its time is nothing.
							startMs = now;
							timing = true;
							maxDamage = Double.POSITIVE_INFINITY;
						}
						case ServerboundPlayerActionPacket p when p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK -> {
							double diff = Math.ceil(1 / maxDamage) * 50 - (now - startMs);
							breakBalance = Math.clamp(breakBalance, -1000, 1000);
							if (diff < 25) breakBalance *= 0.9;
							else breakBalance += diff;
							worst = Math.max(worst, breakBalance);
							startMs = now;
							BlockState was = Blocks.STONE.defaultBlockState();
							if (h.level.getBlockState(p.getPos()).isAir()) {
								// Taken away on the client as it went, as Grim does.
							} else if (p.getPos().getY() == Math.floor(h.player.getY() - 0.5)) {
								shownLate++;
							}
							if (breakBalance > 1000) {
								refusals++;
								h.serverBlock(p.getPos(), was).ack(p.getSequence());
							} else if (damage * (s.tick() - serverStart + 1) >= 0.7) {
								h.serverBlock(p.getPos(), Blocks.AIR.defaultBlockState()).ack(p.getSequence());
								broken++;
							} else {
								h.ack(p.getSequence());
							}
						}
						case ServerboundMovePlayerPacket m -> {
							if (timing) maxDamage = Math.max(maxDamage, damage);
							grimGround = m.isOnGround();
						}
						case ServerboundPunchPacket ignored -> {
							if (timing) maxDamage = Math.max(maxDamage, damage);
						}
						default -> {
						}
					}
				}
			}
			System.out.println("[dig down " + options.mode() + "] " + broken + " blocks in " + t + " ticks, " + refusals + " refused, " + shownLate
				+ " left in place, highest balance " + Math.round(worst));
			assertEquals(target, broken, "dug " + broken + " of " + target + " blocks in " + t + " ticks");
			assertEquals(0, refusals, "Grim cancelled " + refusals + " finishes (highest balance " + Math.round(worst) + ")");
			assertEquals(0, shownLate, shownLate + " finishes left the block you stand on in place till the server answered");
		}
	}

	/**
	 * A finish the server sends the block back on (Grim cancelled it) is refused, not one the server goes on to finish
	 * by itself (that comes with no update): the block is finished again as soon as Grim's timing lets it, not after
	 * waiting out the time the server would have taken plus its grace (about 2 seconds).
	 */
	@Test
	void aFinishSentBackIsRefusedAndFinishedAgainSoon() {
		for (Breaking.Mode mode : List.of(Breaking.Mode.PACKET, Breaking.Mode.FAST_GRIM)) {
			try (Harness h = Harness.start()) {
				Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
				for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
				BlockPos block = new BlockPos(1, 1, 0);
				h.level.set(block, Blocks.STONE.defaultBlockState());
				h.standAt(0.5, 1, 0.5, 0, 0).give(0, new ItemStack(Items.DIAMOND_PICKAXE));
				Breaking.Options options = Breaking.Options.forServer().withMode(mode).withSwing(false).withWaitForServer(true);
				h.everyTick(() -> {
					if (!h.level.getBlockState(block).isAir()) Myriad.breaking().breakBlock(this, block, 0, options);
				});
				int refusedAt = -1, brokenAt = -1;
				for (int t = 0; t < 200 && brokenAt < 0; t++) {
					h.tick();
					for (Harness.Sent s : h.sent()) {
						if (s.tick() != h.currentTick() || !(s.packet() instanceof ServerboundPlayerActionPacket p) || !p.getPos().equals(block)) continue;
						if (p.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) continue;
						if (refusedAt < 0) {
							refusedAt = s.tick();
							h.serverBlock(block, Blocks.STONE.defaultBlockState()).ack(p.getSequence());
						} else {
							brokenAt = s.tick();
							h.serverBlock(block, Blocks.AIR.defaultBlockState()).ack(p.getSequence());
						}
					}
				}
				assertTrue(refusedAt >= 0 && brokenAt >= 0, mode + ": never finished again after the refusal");
				// Grim's own time for the block (diamond on stone, about 6 ticks) is the most it should wait.
				assertTrue(brokenAt - refusedAt <= 12, mode + ": finished again " + (brokenAt - refusedAt) + " ticks after the refusal");
			}
		}
	}

	/**
	 * A start the server sends the block back on (Grim cancelled it, a wrong face say) leaves the server with no start
	 * there, and vanilla ignores a finish for a block it has none for. So the next try starts again rather than sending
	 * finishes alone that go nowhere (as many as one every 4 ticks, for seconds, were seen on 2b2t).
	 */
	@Test
	void aStartSentBackIsStartedAgainNotFinishedAlone() {
		for (Breaking.Mode mode : List.of(Breaking.Mode.PACKET, Breaking.Mode.FAST_GRIM)) {
			try (Harness h = Harness.start()) {
				Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
				for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
				BlockPos block = new BlockPos(1, 1, 0);
				h.level.set(block, Blocks.STONE.defaultBlockState());
				h.standAt(0.5, 1, 0.5, 0, 0).give(0, new ItemStack(Items.DIAMOND_PICKAXE));
				Breaking.Options options = Breaking.Options.forServer().withMode(mode).withSwing(false).withWaitForServer(true);
				h.everyTick(() -> {
					if (!h.level.getBlockState(block).isAir()) Myriad.breaking().breakBlock(this, block, 0, options);
				});
				double damage = Blocks.STONE.defaultBlockState().getDestroyProgress(h.player, h.level, block);
				BlockPos serverStart = null;
				int startedAt = 0, starts = 0, ignored = 0, brokenAt = -1;
				for (int t = 0; t < 200 && brokenAt < 0; t++) {
					h.tick();
					for (Harness.Sent s : h.sent()) {
						if (s.tick() != h.currentTick() || !(s.packet() instanceof ServerboundPlayerActionPacket p)) continue;
						if (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && p.getPos().equals(block)) {
							if (starts++ == 0) {
								h.serverBlock(block, Blocks.STONE.defaultBlockState()).ack(p.getSequence());
							} else {
								serverStart = block;
								startedAt = s.tick();
								h.ack(p.getSequence());
							}
						} else if (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
							// A decoy above the build limit: the server ignores it ("too high") and keeps its start.
							h.ack(p.getSequence());
						} else if (p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
							if (block.equals(serverStart) && damage * (s.tick() - startedAt + 1) >= 0.7) {
								h.serverBlock(block, Blocks.AIR.defaultBlockState()).ack(p.getSequence());
								brokenAt = s.tick();
							} else {
								if (serverStart == null) ignored++;
								h.ack(p.getSequence());
							}
						}
					}
				}
				assertTrue(brokenAt >= 0, mode + ": never broken (" + starts + " starts, " + ignored + " finishes with no start on the server)");
				assertTrue(ignored <= 1, mode + ": " + ignored + " finishes sent with no start on the server");
				assertTrue(brokenAt <= 30, mode + ": broken only at tick " + brokenAt);
			}
		}
	}

	/**
	 * Instant breaks in a burst just after a timed finish, as in a geode: Grim judges every start against the last
	 * finish, so each one within 275 ms of it adds {@code 300 - gap} to its start balance, and past 1000 the rest are
	 * cancelled (24 of 28 clusters came back on 2b2t). Only as many go as the balance allows, the rest once the gap has
	 * passed, and every block still goes.
	 */
	@Test
	void instantBurstAfterAFinishStaysUnderGrimsStartBalance() {
		for (Breaking.Mode mode : List.of(Breaking.Mode.PACKET, Breaking.Mode.FAST_GRIM)) {
			try (Harness h = Harness.start()) {
				Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
				for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
				BlockPos timed = new BlockPos(1, 1, 0);
				h.level.set(timed, Blocks.STONE.defaultBlockState());
				List<BlockPos> instant = new ArrayList<>();
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) {
						BlockPos p = new BlockPos(x, 1, z);
						if (p.equals(timed) || x == 0 && z == 0 || instant.size() >= 20) continue;
						h.level.set(p, Blocks.SHORT_GRASS.defaultBlockState());
						instant.add(p);
					}
				}
				h.standAt(0.5, 1, 0.5, 0, 0).give(0, new ItemStack(Items.DIAMOND_PICKAXE));
				Breaking.Options options = Breaking.Options.forServer().withMode(mode).withSwing(false).withWaitForServer(true);
				// The timed block first, then the burst.
				h.everyTick(() -> {
					if (!h.level.getBlockState(timed).isAir()) Myriad.breaking().breakBlock(this, timed, 10, options);
					else for (BlockPos p : instant) if (!h.level.getBlockState(p).isAir()) Myriad.breaking().breakBlock(this, p, 0, options);
				});
				double delayBalance = 0, worst = 0;
				long lastFinishMs = Long.MIN_VALUE / 2;
				int broken = 0, refused = 0, t = 0;
				for (; t < 200 && broken < instant.size() + 1; t++) {
					h.tick();
					for (Harness.Sent s : h.sent()) {
						if (s.tick() != h.currentTick() || !(s.packet() instanceof ServerboundPlayerActionPacket p)) continue;
						long now = s.tick() * Harness.TICK_MS;
						if (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
							long gap = now - lastFinishMs;
							if (gap >= 275) delayBalance *= 0.9;
							else delayBalance += 300 - gap;
							worst = Math.max(worst, delayBalance);
							boolean cancel = delayBalance > 1000;
							delayBalance = Math.clamp(delayBalance, -1000, 1000);
							if (p.getPos().getY() > 300) {
								h.ack(p.getSequence());
							} else if (cancel) {
								refused++;
								h.serverBlock(p.getPos(), h.level.getBlockState(p.getPos())).ack(p.getSequence());
							} else if (instant.contains(p.getPos())) {
								h.serverBlock(p.getPos(), Blocks.AIR.defaultBlockState()).ack(p.getSequence());
								broken++;
							} else {
								h.ack(p.getSequence());
							}
						} else if (p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
							lastFinishMs = now;
							h.serverBlock(p.getPos(), Blocks.AIR.defaultBlockState()).ack(p.getSequence());
							broken++;
						}
					}
				}
				assertEquals(0, refused, mode + ": Grim would have cancelled " + refused + " starts (highest start balance " + Math.round(worst) + ")");
				assertEquals(instant.size() + 1, broken, mode + ": broke " + broken + " of " + (instant.size() + 1) + " in " + t + " ticks");
				System.out.println("[burst " + mode + "] " + broken + " blocks in " + t + " ticks, highest start balance " + Math.round(worst));
			}
		}
	}
}
