package dev.myriadclient.impl.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTimingTest {
	@Test
	void anAimedPlacementHoldsTheRestOfItsTick() {
		ActionTiming timing = ActionTiming.get();
		timing.reset();
		assertFalse(timing.isLate());
		timing.holdRestOfTick();
		// Breaking and inventory clicks read this to stay out of the tick; actions sent meanwhile go out next tick.
		assertTrue(timing.isLate());
		timing.reset();
		assertFalse(timing.isLate());
	}
}
