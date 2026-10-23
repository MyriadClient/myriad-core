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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@Test
	void aBuildPutsBackWhatItBroughtIntoTheHotbarAndNeverUsesTheSelectedSlot() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			// The selected slot (0) and slot 5 empty, the rest sticks; glass and cobblestone in the inventory.
			h.give(0, ItemStack.EMPTY);
			for (int i = 1; i < 9; i++) if (i != 5) h.give(i, new ItemStack(Items.STICK));
			h.give(20, new ItemStack(Items.GLASS, 16)).give(21, new ItemStack(Items.COBBLESTONE, 16));
			Map<BlockPos, Target> blocks = new HashMap<>();
			blocks.put(new BlockPos(1, 1, 0), Target.exact(Blocks.GLASS.defaultBlockState()));
			blocks.put(new BlockPos(1, 1, 1), Target.exact(Blocks.COBBLESTONE.defaultBlockState()));
			Job<Void> job = Myriad.jobs().start("builder", Steps.build(Blueprint.of(blocks), Building.Options.forServer()));
			boolean sawGlass = false, sawCobble = false;
			for (int t = 0; t < 10; t++) {
				h.tick();
				sawGlass |= h.player.getInventory().getItem(5).is(Items.GLASS);
				sawCobble |= h.player.getInventory().getItem(5).is(Items.COBBLESTONE);
				assertTrue(h.player.getInventory().getItem(0).isEmpty(), "the selected slot took a block at tick " + t);
			}
			assertTrue(sawGlass && sawCobble, "glass then cobblestone into slot 5: " + sawGlass + ", " + sawCobble);
			job.cancel();
			h.ticks(10);
			var inv = h.player.getInventory();
			assertTrue(inv.getItem(5).isEmpty(), "slot 5 holds " + inv.getItem(5));
			assertTrue(inv.getItem(20).is(Items.GLASS), "slot 20 holds " + inv.getItem(20));
			assertTrue(inv.getItem(21).is(Items.COBBLESTONE), "slot 21 holds " + inv.getItem(21));
			assertEquals(0, inv.getSelectedSlot());
		}
	}

	@Test
	void aBuildBorrowsASlotOfAFullHotbarWithoutShowingItAndPutsItBack() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			// Every hotbar slot holds something of yours; the glass is only in the inventory.
			h.give(0, new ItemStack(Items.DIAMOND_SWORD));
			for (int i = 1; i < 9; i++) h.give(i, new ItemStack(Items.STICK, i));
			h.give(20, new ItemStack(Items.GLASS, 16));
			Job<Void> job = Myriad.jobs().start("builder", Steps.build(Blueprint.of(Map.of(new BlockPos(1, 1, 0), Target.exact(Blocks.GLASS.defaultBlockState()))),
				Building.Options.forServer()));
			int borrowed = -1;
			for (int t = 0; t < 10 && borrowed < 0; t++) {
				h.tick();
				for (int i = 1; i < 9; i++) if (h.player.getInventory().getItem(i).is(Items.GLASS)) borrowed = i;
			}
			assertTrue(borrowed > 0, "no hotbar slot took the glass");
			assertTrue(Myriad.inventory().shownInHotbar(borrowed).is(Items.STICK), "slot " + borrowed + " shows " + Myriad.inventory().shownInHotbar(borrowed));
			assertEquals(borrowed, Myriad.inventory().shownInHotbar(borrowed).getCount(), "slot " + borrowed + " shows another slot's sticks");
			job.cancel();
			h.ticks(10);
			var inv = h.player.getInventory();
			for (int i = 1; i < 9; i++) assertTrue(inv.getItem(i).is(Items.STICK) && inv.getItem(i).getCount() == i, "slot " + i + " holds " + inv.getItem(i));
			assertTrue(inv.getItem(20).is(Items.GLASS), "slot 20 holds " + inv.getItem(20));
			assertTrue(inv.getItem(0).is(Items.DIAMOND_SWORD));
		}
	}

	@Test
	void aSlotAClickJustChangedIsntPlacedFromUntilTheServerCouldAnswer() {
		try (Harness h = standing()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			h.give(20, new ItemStack(Items.GLASS, 16));
			boolean[] right = new boolean[2];
			h.onTick(() -> {
				assertTrue(Myriad.inventory().moveToHotbar(20, 5), "the click didn't go");
				right[0] = Myriad.placement().canPlaceWith(5);
				right[1] = Myriad.placement().canPlaceWith(0);
			}).tick();
			assertFalse(right[0], "placed from slot 5 in the tick its click went");
			assertTrue(right[1], "a slot the click didn't touch waited too");
			h.tick();
			assertTrue(Myriad.placement().canPlaceWith(5), "still waiting a tick later, with no lag");
		}
	}
}
