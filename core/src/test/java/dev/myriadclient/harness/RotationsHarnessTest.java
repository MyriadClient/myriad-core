package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Rotations;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
