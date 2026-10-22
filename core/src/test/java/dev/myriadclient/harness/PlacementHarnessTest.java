package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
			h.onTick(() -> a[0] = Myriad.placement().place(this, TARGET, 0, Placement.Options.forServer())).tick();
			assertTrue(a[0].sent(), "not sent: " + a[0].check());
			List<String> kinds = h.kinds(1);
			assertTrue(kinds.indexOf("UseItemOn") >= 0 && kinds.indexOf("UseItemOn") < kinds.indexOf("MovePlayer"), kinds.toString());
		}
	}
}
