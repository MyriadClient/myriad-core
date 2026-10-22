package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.MathUtil;
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
			h.onTick(() -> a[0] = Myriad.placement().place(this, TARGET, 0, Placement.Options.forServer())).tick();
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
}
