package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.Steps;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jobs driving the real services: what their steps report, and that they let go of what they started. */
class JobsHarnessTest {
	private static Harness standing() {
		Harness h = Harness.start();
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
		h.standAt(0.5, 1, 0.5, -90, 30).give(0, new ItemStack(Items.OBSIDIAN, 64));
		return h;
	}

	private static Job<Void> place(Harness h, BlockPos pos, Placement.Options options) {
		Job<Void> job = Myriad.jobs().start("builder", Steps.place(pos, 0, options));
		h.tick();
		return job;
	}

	@Test
	void aPlacementJobIsDoneOnceTheServerKeepsTheBlock() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			BlockPos pos = new BlockPos(1, 1, 0);
			Job<Void> job = place(h, pos, Placement.Options.forServer());
			assertEquals(1, h.sent(ServerboundUseItemOnPacket.class).size());
			assertTrue(job.state().isActive(), "done before the server answered");
			h.serverBlock(pos, Blocks.OBSIDIAN.defaultBlockState()).ack(h.sent(ServerboundUseItemOnPacket.class).getFirst().getSequence()).tick();
			assertEquals(Job.State.DONE, job.state(), "why: " + job.why());
		}
	}

	@Test
	void aPlacementTheServerRefusesFailsTheJobAsRefused() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			BlockPos pos = new BlockPos(1, 1, 0);
			Job<Void> job = place(h, pos, Placement.Options.forServer());
			h.serverBlock(pos, Blocks.AIR.defaultBlockState()).ack(h.sent(ServerboundUseItemOnPacket.class).getFirst().getSequence()).tick();
			assertEquals(Job.State.FAILED, job.state());
			assertEquals(Why.SERVER_REFUSED, job.why().code());
		}
	}

	@Test
	void whatTheServiceRefusesShowsAsTheJobsReason() {
		Map<String, Why.Code> seen = new HashMap<>();
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			h.level.set(new BlockPos(-1, 1, 0), Blocks.STONE.defaultBlockState());
			seen.put("far", place(h, new BlockPos(9, 1, 0), Placement.Options.forServer()).why().code());
			seen.put("nothing beside", place(h, new BlockPos(1, 3, 2), Placement.Options.forServer()).why().code());
			seen.put("where you stand", place(h, new BlockPos(0, 1, 0), Placement.Options.forServer()).why().code());
			seen.put("something there", place(h, new BlockPos(-1, 1, 0), Placement.Options.forServer()).why().code());
			Job<Void> already = Myriad.jobs().start("builder", Steps.place(new BlockPos(1, 0, 1), 0, Placement.Options.forServer()));
			h.level.set(new BlockPos(1, 0, 1), Blocks.OBSIDIAN.defaultBlockState());
			h.tick();
			assertEquals(Job.State.DONE, already.state(), "the block was already there");
		}
		assertEquals(Map.of("far", Why.OUT_OF_REACH, "nothing beside", Why.NO_SUPPORT, "where you stand", Why.ENTITY_IN_WAY,
			"something there", Why.BLOCKED), seen);
	}

	@Test
	void cancellingAJobDropsTheTurnedPlacementItQueued() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			// Facing away, where looks are checked the placement waits a tick for its turn: cancelled meanwhile, it never goes.
			h.standAt(0.5, 1, 0.5, 90, 0);
			Job<Void> job = place(h, new BlockPos(1, 1, 0), Placement.Options.forServer());
			assertEquals(0, h.sent(ServerboundUseItemOnPacket.class).size(), "placed without turning first");
			job.cancel();
			h.ticks(3);
			assertEquals(Job.State.CANCELLED, job.state());
			assertEquals(0, h.sent(ServerboundUseItemOnPacket.class).size(), "placed after the job was cancelled");
		}
	}

	@Test
	void aPausedJobDoesNothingUntilItsResumed() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			Job<Void> job = Myriad.jobs().start("builder", Steps.place(new BlockPos(1, 1, 0), 0, Placement.Options.forServer()));
			job.pause();
			h.ticks(3);
			assertEquals(0, h.sent(ServerboundUseItemOnPacket.class).size(), "placed while paused");
			job.resume();
			h.tick();
			assertEquals(1, h.sent(ServerboundUseItemOnPacket.class).size());
		}
	}

	@Test
	void aBuildWithNothingToBuildWithIsStuckOnNoItemWithTheCount() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			Map<BlockPos, Target> wall = new HashMap<>();
			for (int z = -1; z <= 1; z++) wall.put(new BlockPos(2, 1, z), Target.state(Blocks.GLASS.defaultBlockState()));
			Job<Void> job = Myriad.jobs().start("builder", Steps.build(Blueprint.of(wall), Building.Options.forServer()));
			h.ticks(3);
			assertEquals(Job.State.STUCK, job.state());
			assertEquals(Why.NO_ITEM, job.why().code());
			assertTrue(job.why().detail().contains("3"), "no count in: " + job.why().detail());
		}
	}
}
