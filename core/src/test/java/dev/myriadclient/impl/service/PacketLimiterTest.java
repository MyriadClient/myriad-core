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
