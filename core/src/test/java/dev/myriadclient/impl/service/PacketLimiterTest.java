package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.PacketLimits.Kind;
import dev.myriadclient.impl.util.Clock;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PacketLimiterTest {
	private long now = 1_000_000;

	/** A limiter on a clock the test moves. */
	private PacketLimiter limiter() {
		Clock.use(() -> now);
		return new PacketLimiter();
	}

	@AfterEach
	void wallClock() {
		Clock.use(null);
	}

	private static ServerboundContainerClickPacket click() {
		return new ServerboundContainerClickPacket(0, 0, (short) 9, (byte) 0, ContainerInput.PICKUP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY);
	}

	@Test
	void theBudgetRunsOutPerKind() {
		PacketLimiter l = limiter();
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
	void interactionsKeepUnderPapersSpamLimiter() {
		// Paper drops the 10th use or place packet within 300 ms: a burst of 9 goes, the 10th waits for the window and
		// a margin for packets bunching up on their way.
		PacketLimiter l = limiter();
		assertEquals(9, l.limit(Kind.INTERACT));
		assertEquals(380, l.windowMs(Kind.INTERACT));
		for (int i = 0; i < 9; i++) {
			assertTrue(l.canSend(Kind.INTERACT, 1));
			l.record(Kind.INTERACT);
		}
		assertFalse(l.canSend(Kind.INTERACT, 1));
		now += 379;
		assertFalse(l.canSend(Kind.INTERACT, 1), "not a millisecond early");
		now += 1;
		assertTrue(l.canSend(Kind.INTERACT, 9));
	}

	@Test
	void interactionsDropOutOneAtATime() {
		PacketLimiter l = limiter();
		for (int i = 0; i < 9; i++) {
			l.record(Kind.INTERACT);
			now += 30;
		}
		// The first went 270 ms ago: it frees its place 110 ms on, the second 30 ms after that.
		assertFalse(l.canSend(Kind.INTERACT, 1));
		now += 110;
		assertTrue(l.canSend(Kind.INTERACT, 1));
		assertFalse(l.canSend(Kind.INTERACT, 2));
	}

	@Test
	void papersWindowNeverSeesTenWithJitter() {
		// Sending whenever the budget lets you, any 10 packets in a row span at least 380 ms, so even with the first
		// arriving 80 ms later than the tenth (relative to when they went), the server sees them 300 ms apart.
		PacketLimiter l = limiter();
		Random r = new Random(7);
		List<Long> sentAt = new ArrayList<>();
		for (int t = 0; t < 20_000; t += 1 + r.nextInt(25)) {
			now = 1_000_000 + t;
			int burst = 1 + r.nextInt(4);
			for (int b = 0; b < burst && l.canSend(Kind.INTERACT, 1); b++) {
				l.record(Kind.INTERACT);
				sentAt.add(now);
			}
		}
		for (int i = 0; i + 9 < sentAt.size(); i++) {
			long span = sentAt.get(i + 9) - sentAt.get(i);
			assertTrue(span >= 380, "10 within " + span + " ms");
		}
	}

	@Test
	void clicksBurstUnder2b2tsLimit() {
		// 2b2t drops you on the 80th click within about 4 s: a whole sort goes at once, over a longer window than 2b2t's.
		PacketLimiter l = limiter();
		assertEquals(76, l.limit(Kind.INVENTORY));
		assertEquals(4500, l.windowMs(Kind.INVENTORY));
		assertTrue(l.canSend(Kind.INVENTORY, l.limit(Kind.INVENTORY)));
	}

	@Test
	void clicksDropOutExactlyAWindowLater() {
		PacketLimiter l = limiter();
		for (int i = 0; i < 76; i++) l.record(Kind.INVENTORY);
		assertFalse(l.canSend(Kind.INVENTORY, 1));
		now += 4499;
		assertFalse(l.canSend(Kind.INVENTORY, 1));
		now += 1;
		assertTrue(l.canSend(Kind.INVENTORY, 76));
	}

	@Test
	void noStretchOf45SecondsEverHolds80Clicks() {
		// Clicking whenever the budget lets you, urgent clicks too: never 80 within 4.5 s, and about 17 a second.
		PacketLimiter l = limiter();
		List<Long> sentAt = new ArrayList<>();
		Runnable clickAll = () -> {
			while (l.canSend(Kind.INVENTORY, 1)) {
				l.record(Kind.INVENTORY);
				sentAt.add(now);
			}
		};
		for (int t = 0; t < 60_000; t += 50) {
			now = 1_000_000 + t;
			clickAll.run();
			if (t % 1000 == 0) l.urgent(clickAll);
		}
		for (int i = 0; i + 79 < sentAt.size(); i++) assertTrue(sentAt.get(i + 79) - sentAt.get(i) >= 4500);
		double perSecond = sentAt.size() / 60.0;
		assertTrue(perSecond > 16.5, perSecond + " a second");
	}

	@Test
	void urgentClicksStopShortOfADisconnect() {
		PacketLimiter l = limiter();
		for (int i = 0; i < 79; i++) l.record(Kind.INVENTORY);
		AtomicBoolean allowed = new AtomicBoolean(true);
		l.urgent(() -> allowed.set(l.canSend(Kind.INVENTORY, 1)));
		assertFalse(allowed.get());
	}

	@Test
	void urgentClicksHaveTheLastFew() {
		PacketLimiter l = limiter();
		for (int i = 0; i < l.limit(Kind.INVENTORY); i++) l.record(Kind.INVENTORY);
		assertFalse(l.canSend(Kind.INVENTORY, 1));
		AtomicBoolean allowed = new AtomicBoolean();
		l.urgent(() -> allowed.set(l.canSend(Kind.INVENTORY, 3)));
		assertTrue(allowed.get());
		l.urgent(() -> allowed.set(l.canSend(Kind.INVENTORY, 4)));
		assertFalse(allowed.get());
	}

	@Test
	void urgentActionsIgnoreTheBudgetButStillCount() {
		PacketLimiter l = limiter();
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

	@Test
	void everyClickPacketCountsOnce() {
		PacketLimiter l = limiter();
		// Made through the game: counted as it's made, not again as its packet goes out.
		l.recordClick();
		l.record(click());
		l.clickDone();
		assertEquals(1, l.used(Kind.INVENTORY));
		// Sent raw: counted as it goes out.
		l.record(click());
		assertEquals(2, l.used(Kind.INVENTORY));
		// Made through the game but sent past the connection (ViaFabricPlus does): a raw one after it still counts.
		l.recordClick();
		l.clickDone();
		l.record(click());
		assertEquals(4, l.used(Kind.INVENTORY));
	}
}
