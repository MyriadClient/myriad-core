package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Containers opened by code: the click, the view once its contents arrive, clicks in it, and closing. */
class ContainersHarnessTest {
	private static final BlockPos CHEST = new BlockPos(1, 1, 0);

	private static Harness besideAChest() {
		Harness h = Harness.start();
		h.level.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
		h.level.set(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		h.level.set(CHEST, Blocks.CHEST.defaultBlockState());
		h.standAt(0.5, 1, 0.5, 0, 0);
		return h;
	}

	@Test
	void openingByCodeClicksTheChestAndHandsBackItsViewOnceTheContentsArrive() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			@SuppressWarnings("unchecked") CompletableFuture<Containers.View>[] open = new CompletableFuture[1];
			h.onTick(() -> open[0] = Myriad.containers().open(this, CHEST, 20, Placement.Options.forServer())).ticks(3);
			List<ServerboundUseItemOnPacket> clicks = h.sent(ServerboundUseItemOnPacket.class);
			assertEquals(1, clicks.size(), "one click on the chest");
			assertEquals(CHEST, clicks.getFirst().getHitResult().getBlockPos());
			assertFalse(open[0].isDone());
			h.serverOpensChest(7, List.of(new ItemStack(Items.DIAMOND, 5), ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 3)));
			Containers.View view = open[0].getNow(null);
			assertSame(this, view.opener());
			assertFalse(view.byHand());
			assertEquals(8, view.count(s -> s.is(Items.DIAMOND)));
		}
	}

	@Test
	void standingStillItClicksAndClosesInTheSameTickInThatOrder() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			@SuppressWarnings("unchecked") CompletableFuture<Containers.View>[] open = new CompletableFuture[1];
			h.onTick(() -> open[0] = Myriad.containers().open(this, CHEST, 20, Placement.Options.forServer())).ticks(3);
			h.serverOpensChest(7, List.of(new ItemStack(Items.DIAMOND, 5)));
			Containers.View view = open[0].getNow(null);
			h.clearSent();
			h.onTick(() -> {
				view.quickMove(view.find(s -> s.is(Items.DIAMOND)));
				Myriad.containers().close();
			}).tick();
			assertEquals(List.of("ContainerClick", "ContainerClose"), h.kinds(h.currentTick()));
			assertEquals(7, h.sent(ServerboundContainerClickPacket.class).getFirst().containerId());
			assertFalse(view.isOpen());
		}
	}

	@Test
	void walkingOnGrimTheCloseWaitsForATickWithTheKeysUp() {
		try (Harness h = besideAChest()) {
			for (int x = -2; x <= 2; x++) for (int z = -30; z <= 2; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			@SuppressWarnings("unchecked") CompletableFuture<Containers.View>[] open = new CompletableFuture[1];
			h.onTick(() -> open[0] = Myriad.containers().open(this, CHEST, 20, Placement.Options.forServer())).ticks(3);
			h.serverOpensChest(7, List.of(new ItemStack(Items.DIAMOND, 5)));
			Containers.View view = open[0].getNow(null);
			// Walking away (north), the chest still open.
			h.keys("back").ticks(3);
			h.onTick(() -> Myriad.containers().close()).ticks(4);
			assertFalse(view.isOpen(), "never closed");
			Input last = null;
			for (Harness.Sent s : h.sent()) {
				if (s.packet() instanceof ServerboundPlayerInputPacket p) last = p.input();
				if (s.packet() instanceof ServerboundContainerClosePacket) break;
			}
			assertTrue(last != null && !last.forward() && !last.backward() && !last.left() && !last.right() && !last.jump(), "closed with keys down: " + last);
		}
	}

	/** A pickaxe borrowed from inventory slot 20 into the hotbar (full of dirt), then given back, with {@code chest} open meanwhile. */
	private static List<ServerboundContainerClickPacket> borrowAndGiveBack(Harness h, Runnable chest, Runnable closeChest) {
		for (int i = 0; i < 9; i++) h.give(i, new ItemStack(Items.DIRT));
		h.give(20, new ItemStack(Items.DIAMOND_PICKAXE));
		int[] slot = {-1};
		h.onTick(() -> slot[0] = Myriad.inventory().borrow("miner", 20, s -> s.is(Items.DIRT))).tick();
		assertTrue(slot[0] >= 0, "nothing borrowed");
		// Still wanted: asked again with the hotbar slot it went to, as the javadoc says.
		h.onTick(() -> Myriad.inventory().borrow("miner", slot[0], s -> s.is(Items.DIRT))).tick();
		assertTrue(h.player.getInventory().getItem(slot[0]).is(Items.DIAMOND_PICKAXE));
		chest.run();
		h.clearSent();
		h.onTick(() -> Myriad.inventory().giveBack("miner")).ticks(3);
		closeChest.run();
		h.ticks(3);
		return h.sent(ServerboundContainerClickPacket.class);
	}

	@Test
	void aGiveBackWaitsForAContainerAModuleOpenedToClose() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			@SuppressWarnings("unchecked") CompletableFuture<Containers.View>[] open = new CompletableFuture[1];
			int[] closedAt = {-1};
			List<ServerboundContainerClickPacket> clicks = borrowAndGiveBack(h, () -> {
				h.onTick(() -> open[0] = Myriad.containers().open(this, CHEST, 20, Placement.Options.forServer())).ticks(3);
				h.serverOpensChest(7, List.of());
				assertFalse(open[0].getNow(null).byHand());
			}, () -> {
				assertTrue(h.sent(ServerboundContainerClickPacket.class).isEmpty(), "gave back inside the module's container");
				h.onTick(() -> Myriad.containers().close()).tick();
				closedAt[0] = h.currentTick();
			});
			assertEquals(1, clicks.size(), "one click to put it back");
			assertEquals(0, clicks.getFirst().containerId(), "put back through the player's own menu once the chest closed");
			assertTrue(h.player.getInventory().getItem(20).is(Items.DIAMOND_PICKAXE), "not back in its slot");
		}
	}

	@Test
	void aGiveBackGoesThroughAContainerYouOpenedYourself() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			List<ServerboundContainerClickPacket> clicks = borrowAndGiveBack(h, () -> h.serverOpensChest(9, List.of()), () -> {
			});
			assertEquals(1, clicks.size(), "one click to put it back");
			assertEquals(9, clicks.getFirst().containerId(), "not through the open chest's menu");
			assertTrue(h.player.containerMenu.containerId == 9, "the chest was closed for it");
			assertTrue(h.player.getInventory().getItem(20).is(Items.DIAMOND_PICKAXE), "not back in its slot");
		}
	}

	@Test
	void aSortShowsItsResultWhileItRunsAndYourClickStopsIt() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			var menu = h.serverOpensChest(7, List.of(new ItemStack(Items.DIRT, 3), ItemStack.EMPTY, new ItemStack(Items.STONE, 5), new ItemStack(Items.DIRT, 2)));
			Containers.View view = Myriad.containers().current().orElseThrow();
			ItemStack[] sorted = {new ItemStack(Items.DIRT, 5), new ItemStack(Items.STONE, 5), ItemStack.EMPTY, ItemStack.EMPTY};
			CompletableFuture<Boolean> done = view.arrange(Containers.Arrangement.of(new int[]{0, 1, 2, 3}, List.of(sorted)).dumpTo(new int[]{27, 28, 29}));
			var tracker = dev.myriadclient.impl.MyriadImpl.get().containerTracker();
			assertTrue(tracker.showsFinished(menu));
			assertTrue(ItemStack.matches(sorted[0], tracker.shown(menu, 0)), "slot 0 shows " + tracker.shown(menu, 0));
			assertTrue(tracker.shown(menu, 2).isEmpty(), "slot 2 shows " + tracker.shown(menu, 2));
			assertTrue(tracker.shown(menu, 30).isEmpty(), "a slot the sort only parks things in shows what it held at the start");
			// Your click, aimed at the sorted look: the sort stops and the click is dropped.
			assertTrue(tracker.yourClick(menu));
			assertFalse(tracker.showsFinished(menu), "still showing the sort after your click");
			assertEquals(null, tracker.shown(menu, 0));
			h.tick();
			assertTrue(done.isCompletedExceptionally());
			assertFalse(tracker.yourClick(menu), "with no sort running, clicks go through");
		}
	}

	@Test
	void aSortThatBringsInItemsFromElsewhereShowsWhatTheMenuHolds() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			h.give(9, new ItemStack(Items.STONE, 5));
			var menu = h.serverOpensChest(7, List.of(ItemStack.EMPTY));
			Containers.View view = Myriad.containers().current().orElseThrow();
			// Five stone from your inventory (menu slot 27 is inventory slot 9): the end of your inventory isn't known.
			view.arrange(Containers.Arrangement.of(new int[]{0}, List.of(new ItemStack(Items.STONE, 5))).from(0, 27));
			assertFalse(dev.myriadclient.impl.MyriadImpl.get().containerTracker().showsFinished(menu));
		}
	}

	@Test
	void aSortStoppedWithSomethingOnTheCursorPutsItDownFirst() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			// Stone and dirt in turn: more clicks than the budget takes in a tick, so a tick can end with a stack on the cursor.
			List<ItemStack> contents = new java.util.ArrayList<>();
			for (int i = 0; i < 24; i++) contents.add(new ItemStack(i % 2 == 0 ? Items.STONE : Items.DIRT, 1 + i % 5));
			var menu = h.serverOpensChest(7, contents);
			int stone = 0, dirt = 0;
			for (ItemStack c : contents) if (c.is(Items.STONE)) stone += c.getCount(); else dirt += c.getCount();
			Containers.View view = Myriad.containers().current().orElseThrow();
			int[] area = new int[27];
			List<ItemStack> sorted = new java.util.ArrayList<>();
			for (int i = 0; i < 27; i++) {
				area[i] = i;
				sorted.add(i == 0 ? new ItemStack(Items.DIRT, dirt) : i == 1 ? new ItemStack(Items.STONE, stone) : ItemStack.EMPTY);
			}
			CompletableFuture<Boolean> done = view.arrange(Containers.Arrangement.of(area, sorted).dumpTo(new int[]{27, 28, 29}));
			// Ticks until a click of the sort leaves something on the cursor (it swaps stacks through it).
			for (int t = 0; t < 40 && menu.getCarried().isEmpty() && !done.isDone(); t++) h.tick();
			assertFalse(menu.getCarried().isEmpty(), "the sort never carried anything");
			int total = count(menu);
			assertTrue(dev.myriadclient.impl.MyriadImpl.get().containerTracker().yourClick(menu));
			// A round trip after its last click (at most a second), then a click to put it down.
			h.ticks(30);
			assertTrue(menu.getCarried().isEmpty(), "left on the cursor: " + menu.getCarried());
			assertTrue(done.isCompletedExceptionally(), "the sort didn't end failed: " + done);
			assertEquals(total, count(menu), "items lost or made");
		}
	}

	/** Everything in the menu's slots and on its cursor. */
	private static int count(net.minecraft.world.inventory.AbstractContainerMenu menu) {
		int n = menu.getCarried().getCount();
		for (var slot : menu.slots) n += slot.getItem().getCount();
		return n;
	}
}
