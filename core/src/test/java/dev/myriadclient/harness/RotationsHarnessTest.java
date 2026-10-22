package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-side rotations as the movement packets carry them. */
class RotationsHarnessTest {
	private static List<Float> yaws(Harness h) {
		return h.looks().stream().map(l -> l[0]).toList();
	}

	@Test
	void aTurnWithATurnSpeedReachesItsTargetAndSaysSoOnce() {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 1, 0.5, 0, 0);
			int[] sent = {0};
			Runnable afterSent = () -> sent[0]++;
			h.everyTick(() -> Myriad.rotations().request(this, 90, 0, Rotations.PRIORITY_NORMAL, Rotations.Options.INSTANT.withTurnSpeed(30), afterSent));
			h.ticks(2);
			assertEquals(List.of(30f, 60f), yaws(h));
			assertEquals(0, sent[0], "said sent before the target was");
			h.ticks(1);
			assertEquals(90f, yaws(h).get(2));
			// Runs once the packet with the target has gone: at the next tick's start.
			h.ticks(1);
			assertEquals(1, sent[0]);
		}
	}

	@Test
	void theHigherPriorityWinsAnAxisAndTheCameraStaysPut() {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 1, 0.5, 0, 0);
			h.everyTick(() -> {
				Myriad.rotations().request("low", 45, 10, Rotations.PRIORITY_LOW);
				Myriad.rotations().request("high", 120, Float.NaN, Rotations.PRIORITY_HIGH);
			});
			h.tick();
			float[] look = h.looks().getFirst();
			assertEquals(120f, look[0]);
			assertEquals(10f, look[1]);
			assertEquals(0f, h.player.getYRot(), "the camera turned");
		}
	}

	@Test
	void withMoveFixYouKeepGoingYourWayWithTheKeysReKeyedForTheSentYaw() {
		try (Harness h = Harness.start()) {
			for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) h.level.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
			// Camera south, holding forward (south); the sent yaw faces west, so south is to its left.
			h.standAt(0.5, 1, 0.5, 0, 0).keys("forward");
			h.everyTick(() -> Myriad.rotations().request(this, 90, Float.NaN, Rotations.PRIORITY_NORMAL, Rotations.Options.MOVE_FIX, null));
			h.ticks(10);
			Vec3 moved = h.player.position().subtract(0.5, 1, 0.5);
			assertTrue(moved.z > 1 && Math.abs(moved.x) < 0.3, "walked " + moved + ", not south");
			Input sent = h.sent(ServerboundPlayerInputPacket.class).getLast().input();
			assertTrue(sent.left() && !sent.forward() && !sent.backward() && !sent.right(), "keys sent: " + sent);
		}
	}
}
