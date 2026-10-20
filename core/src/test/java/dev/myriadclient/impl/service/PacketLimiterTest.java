package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.PacketLimits.Kind;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PacketLimiterTest {
	@Test
	void theBudgetRunsOutPerKind() {
		PacketLimiter l = new PacketLimiter();
		for (int i = 0; i < l.limit(Kind.INVENTORY); i++) {
			assertTrue(l.canSend(Kind.INVENTORY, 1));
			l.record(Kind.INVENTORY);
		}
		assertFalse(l.canSend(Kind.INVENTORY, 1));
		assertEquals(l.limit(Kind.INVENTORY), l.used(Kind.INVENTORY));
		// Other kinds have their own budget.
		assertTrue(l.canSend(Kind.INTERACT, 1));
	}

	@Test
	void interactionsKeepUnderPapersSpamLimiter() throws InterruptedException {
		// Paper drops the 10th use or place packet within 300 ms: a burst of 9 goes, the 10th waits for the window.
		PacketLimiter l = new PacketLimiter();
		assertEquals(9, l.limit(Kind.INTERACT));
		assertTrue(l.windowMs(Kind.INTERACT) > 300);
		for (int i = 0; i < 9; i++) {
			assertTrue(l.canSend(Kind.INTERACT, 1));
			l.record(Kind.INTERACT);
		}
		assertFalse(l.canSend(Kind.INTERACT, 1));
		Thread.sleep(l.windowMs(Kind.INTERACT) + 40);
		assertTrue(l.canSend(Kind.INTERACT, 9));
	}

	@Test
	void urgentActionsIgnoreTheBudgetButStillCount() {
		PacketLimiter l = new PacketLimiter();
		for (int i = 0; i < l.limit(Kind.INVENTORY); i++) l.record(Kind.INVENTORY);
		AtomicBoolean allowed = new AtomicBoolean();
		l.urgent(() -> {
			allowed.set(l.canSend(Kind.INVENTORY, 1));
			l.record(Kind.INVENTORY);
		});
		assertTrue(allowed.get());
		assertFalse(l.canSend(Kind.INVENTORY, 1));
		assertEquals(l.limit(Kind.INVENTORY) + 1, l.used(Kind.INVENTORY));
	}
}
