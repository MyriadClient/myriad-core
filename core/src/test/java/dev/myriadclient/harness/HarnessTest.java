package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The harness itself: a tick sends the movement packet, and what a tick handler sends goes first. */
class HarnessTest {
	@Test
	void aTickSendsTheMovementPacket() {
		try (Harness h = Harness.start()) {
			h.tick();
			assertEquals(List.of("MovePlayer"), h.kinds(1));
		}
	}

	@Test
	void aSlotChangeFromATickHandlerGoesBeforeTheMovementPacket() {
		try (Harness h = Harness.start()) {
			h.onTick(() -> Myriad.inventory().select(3)).tick();
			assertEquals(List.of("SetCarriedItem", "MovePlayer"), h.kinds(1));
		}
	}
}
