package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server-side slot: holds between modules, silent swaps, and the slot changes the server sees. */
class InventoryHarnessTest {
	private static List<Integer> slotsSent(Harness h) {
		return h.sent(ServerboundSetCarriedItemPacket.class).stream().map(ServerboundSetCarriedItemPacket::getSlot).toList();
	}

	private static Harness withItems() {
		Harness h = Harness.start();
		h.level.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
		h.standAt(0.5, 1, 0.5, 0, 0);
		for (int i = 0; i < 9; i++) h.give(i, new ItemStack(Items.STONE, i + 1));
		return h;
	}

	@Test
	void oneModuleHoldsAtATimeAndTheOneRefusedTakesOverWhenItLetsGo() {
		try (Harness h = withItems()) {
			boolean[] got = new boolean[2];
			h.onTick(() -> {
				got[0] = Myriad.inventory().hold("a", 2, 20);
				got[1] = Myriad.inventory().hold("b", 5, 20);
			}).tick();
			assertTrue(got[0]);
			assertFalse(got[1], "a second module took a strong hold");
			assertEquals(2, Myriad.inventory().serverSlot());
			// Refused a tick ago: the hold is handed over rather than dropped back to the visible slot and taken again.
			h.onTick(() -> Myriad.inventory().release("a")).tick();
			assertEquals(List.of(2, 5), slotsSent(h));
			h.onTick(() -> Myriad.inventory().release("b")).tick();
			assertEquals(List.of(2, 5, 0), slotsSent(h));
			assertEquals(0, h.player.getInventory().getSelectedSlot(), "the visible slot moved");
		}
	}

	@Test
	void aRefusalFromLongerAgoIsNotHandedTheHold() {
		try (Harness h = withItems()) {
			h.onTick(() -> {
				Myriad.inventory().hold("a", 2, 20);
				Myriad.inventory().hold("b", 5, 20);
			}).ticks(3);
			h.onTick(() -> Myriad.inventory().release("a")).tick();
			assertEquals(List.of(2, 0), slotsSent(h));
		}
	}

	@Test
	void aWeakHoldYieldsToAStrongOne() {
		try (Harness h = withItems()) {
			h.onTick(() -> Myriad.inventory().holdWeakly("charging", 2, 20)).tick();
			boolean[] got = {false};
			h.onTick(() -> got[0] = Myriad.inventory().hold("mining", 5, 20)).tick();
			assertTrue(got[0]);
			assertEquals(5, Myriad.inventory().serverSlot());
			assertEquals(List.of(2, 5), slotsSent(h));
		}
	}

	@Test
	void aSilentSwapGoesBackFirstThingNextTick() {
		try (Harness h = withItems()) {
			int[] inHand = {-1};
			h.onTick(() -> Myriad.inventory().silentSwap(4, () -> inHand[0] = h.player.getMainHandItem().getCount())).tick();
			assertEquals(5, inHand[0], "the action didn't run with slot 4 in hand");
			assertEquals(List.of("SetCarriedItem"), h.kinds(1));
			h.tick();
			assertEquals(List.of("SetCarriedItem"), h.kinds(2));
			assertEquals(List.of(4, 0), slotsSent(h));
		}
	}
}
