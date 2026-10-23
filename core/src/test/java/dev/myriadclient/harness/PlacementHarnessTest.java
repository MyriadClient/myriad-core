package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.MathUtil;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placements as the server sees them: when they go out against the tick's movement packet. */
class PlacementHarnessTest {
	private static final BlockPos FLOOR = new BlockPos(1, 0, 0), TARGET = new BlockPos(1, 1, 0);

	private static Harness standingBesideAFloorBlock() {
		Harness h = Harness.start();
		h.level.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
		h.level.set(FLOOR, Blocks.STONE.defaultBlockState());
		h.standAt(0.5, 1, 0.5, -90, 45).give(0, new ItemStack(Items.STONE, 64));
		return h;
	}

	@Test
	void anUnturnedPlacementGoesOutInItsOwnTickBeforeTheMovementPacket() {
		try (Harness h = standingBesideAFloorBlock()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			Placement.Attempt[] a = new Placement.Attempt[1];
			// Turned this tick too, so there is a movement packet to come after the placement.
			h.onTick(() -> {
				a[0] = Myriad.placement().place(this, TARGET, 0, Placement.Options.forServer());
				h.player.setYRot(-80);
			}).tick();
			assertTrue(a[0].sent(), "not sent: " + a[0].check());
			List<String> kinds = h.kinds(1);
			assertTrue(kinds.indexOf("UseItemOn") >= 0 && kinds.indexOf("UseItemOn") < kinds.indexOf("MovePlayer"), kinds.toString());
		}
	}

	@Test
	void whereLooksAreCheckedTheTurnGoesFirstAndThePlacementNextTickBeforeItsMovement() {
		try (Harness h = standingBesideAFloorBlock()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			Placement.Attempt[] a = new Placement.Attempt[1];
			h.onTick(() -> a[0] = Myriad.placement().place(this, TARGET, 0, Placement.Options.forServer())).ticks(3);
			assertTrue(a[0].sent(), "not sent: " + a[0].check());
			assertEquals(List.of("MovePlayer"), h.kinds(1), "tick 1 should only turn");
			List<String> two = h.kinds(2);
			assertTrue(two.indexOf("UseItemOn") >= 0 && (two.indexOf("MovePlayer") < 0 || two.indexOf("UseItemOn") < two.indexOf("MovePlayer")), two.toString());
			// The look the turn sent is on the clicked face.
			float[] look = h.looks().getFirst();
			BlockHitResult hit = h.sent(ServerboundUseItemOnPacket.class).getFirst().getHitResult();
			Vec3 eyes = h.player.getEyePosition();
			assertTrue(new AABB(hit.getBlockPos()).clip(eyes, eyes.add(MathUtil.direction(look[0], look[1]).scale(6))).isPresent(), "the turn didn't face the click");
		}
	}

	@Test
	void theResultSettlesFromTheServersAnswerAndAPendingPlacementIsntRepeated() {
		try (Harness h = standingBesideAFloorBlock()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			Placement.Attempt[] a = new Placement.Attempt[2];
			h.onTick(() -> a[0] = Myriad.placement().place(this, TARGET, 0, Placement.Options.forServer())).tick();
			h.onTick(() -> a[1] = Myriad.placement().place(this, TARGET, 0, Placement.Options.forServer())).tick();
			assertEquals(Placement.Check.PENDING, a[1].check());
			assertFalse(a[0].result().isDone());
			// Refused: the server puts air back, then acknowledges.
			int seq = h.sent(ServerboundUseItemOnPacket.class).getFirst().getSequence();
			h.serverBlock(TARGET, Blocks.AIR.defaultBlockState()).ack(seq);
			assertEquals(Boolean.FALSE, a[0].result().getNow(null));
			assertTrue(h.level.getBlockState(TARGET).isAir(), "the client kept its guess");
		}
	}

	@Test
	void nineInteractionsGoOutInAWindowAndTheRestAreHeldBack() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
			h.standAt(0.5, 1, 0.5, 0, 90).give(0, new ItemStack(Items.STONE, 64));
			List<Placement.Attempt> attempts = new ArrayList<>();
			h.onTick(() -> {
				for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
					if (x == 0 && z == 0) continue;
					attempts.add(Myriad.placement().place(this, new BlockPos(x, 1, z), 0, Placement.Options.forServer()));
				}
			}).tick();
			assertEquals(9, h.sent(ServerboundUseItemOnPacket.class).size());
			assertEquals(9, attempts.stream().filter(Placement.Attempt::sent).count());
			assertTrue(attempts.stream().filter(x -> !x.sent()).allMatch(x -> x.check() == Placement.Check.RATE_LIMITED),
				attempts.stream().map(Placement.Attempt::check).toList().toString());
		}
	}

	@Test
	void anAirPlacementsSwapBackLandsOnTheBlocksOwnSlotEvenWhenTheServersSlotMovedMeanwhile() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			h.level.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
			h.standAt(0.5, 1, 0.5, -90, 0);
			h.give(3, new ItemStack(Items.OBSIDIAN, 16)).give(5, new ItemStack(Items.DIAMOND_SWORD));
			h.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			ServerInventory server = ServerInventory.of(h.player);
			Placement.Attempt[] a = new Placement.Attempt[1];
			h.onTick(() -> {
				// Nothing beside it: an air placement, from the off hand.
				a[0] = Myriad.placement().place(this, new BlockPos(2, 2, 0), 3, Placement.Options.forServer().withAirPlace(true));
				// Another module moves the server's slot in the same tick.
				Myriad.inventory().hold("weapon", 5, 20);
			}).ticks(3);
			assertTrue(a[0].sent(), "not sent: " + a[0].check());
			long swaps = h.sent(ServerboundPlayerActionPacket.class).stream().filter(p -> p.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND).count();
			assertEquals(2, swaps, "not through the off hand and back");
			server.apply(h.sent());
			assertTrue(server.hotbar(3).is(Items.OBSIDIAN), "the block went back to slot " + server.held() + ", not its own: slot 3 has " + server.hotbar(3));
			assertTrue(server.offHand().is(Items.TOTEM_OF_UNDYING), "the off hand kept " + server.offHand());
			assertTrue(server.hotbar(5).is(Items.DIAMOND_SWORD));
			assertEquals(5, server.held(), "the hold's slot isn't the server's at the end");
		}
	}

	/** Obsidian in slot 3, a totem in the off hand; the server's copy of that. */
	private static ServerInventory obsidianAndTotem(Harness h) {
		Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
		h.level.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
		h.standAt(0.5, 1, 0.5, -90, 0);
		h.give(3, new ItemStack(Items.OBSIDIAN, 16));
		h.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
		return ServerInventory.of(h.player);
	}

	/** Air places from slot 3 and ticks until the off hand check asks the server for the inventory; what was sent. */
	private static List<Harness.Sent> airPlaceAndWaitForTheCheck(Harness h) {
		Placement.Attempt[] a = new Placement.Attempt[1];
		h.onTick(() -> a[0] = Myriad.placement().place("test", new BlockPos(2, 2, 0), 3, Placement.Options.forServer().withAirPlace(true))).ticks(2);
		assertTrue(a[0].sent(), "not sent: " + a[0].check());
		for (int t = 0; t < 20 && !asked(h); t++) h.tick();
		assertTrue(asked(h), "the off hand was never checked");
		return h.sent();
	}

	private static boolean asked(Harness h) {
		return h.sent(ServerboundContainerClickPacket.class).stream().anyMatch(p -> p.slotNum() == -999);
	}

	private static boolean swapsTheOffHand(Harness h) {
		return h.sent(ServerboundContainerClickPacket.class).stream().anyMatch(p -> p.buttonNum() == 40);
	}

	@Test
	void anAirPlacementsBlockShownInTheOffHandByALateUpdateIsCheckedAndLeftAlone() {
		try (Harness h = Harness.start()) {
			ServerInventory server = obsidianAndTotem(h);
			server.apply(airPlaceAndWaitForTheCheck(h));
			assertTrue(server.offHand().is(Items.TOTEM_OF_UNDYING), "the server's off hand has " + server.offHand());
			// The server's answer to the swap and placement, late, after the swap back: the block in the off hand again.
			h.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.OBSIDIAN, 15));
			h.player.getInventory().setItem(3, new ItemStack(Items.TOTEM_OF_UNDYING));
			assertTrue(Myriad.inventory().shown(dev.myriadclient.api.util.Slots.OFF_HAND).is(Items.TOTEM_OF_UNDYING), "the late update shows");
			server.sendAll(h.player);
			h.ticks(3);
			assertTrue(h.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "the off hand has " + h.player.getOffhandItem());
			assertFalse(swapsTheOffHand(h), "swapped an off hand that was right");
		}
	}

	@Test
	void anAirPlacementsDroppedSwapBackIsFoundAndPutRight() {
		try (Harness h = Harness.start()) {
			ServerInventory server = obsidianAndTotem(h);
			List<Harness.Sent> sent = new ArrayList<>(airPlaceAndWaitForTheCheck(h));
			// An anti-cheat drops the swap back: the server keeps the block in the off hand, the client shows the totem.
			Harness.Sent back = sent.stream().filter(s -> s.packet() instanceof ServerboundPlayerActionPacket p
				&& p.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND).skip(1).findFirst().orElseThrow();
			sent.remove(back);
			server.apply(sent);
			assertTrue(server.offHand().is(Items.OBSIDIAN), "the server's off hand has " + server.offHand());
			assertTrue(h.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING));
			server.sendAll(h.player);
			int before = h.sent().size();
			h.ticks(3);
			assertTrue(swapsTheOffHand(h), "the block was left in the off hand");
			server.apply(h.sent().subList(before, h.sent().size()));
			assertTrue(server.offHand().is(Items.TOTEM_OF_UNDYING), "the server's off hand has " + server.offHand());
			assertTrue(server.hotbar(3).is(Items.OBSIDIAN), "slot 3 has " + server.hotbar(3));
		}
	}
}
