package dev.myriadclient.impl.service;

import dev.myriadclient.impl.service.SlotLedger.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlotLedgerTest {
	private static final String E = "";
	private SlotLedger<String> ledger;
	private long tick;

	@BeforeEach
	void setUp() {
		ledger = new SlotLedger<>(String::equals, E);
		ledger.reset(List.of("shulker", E, "stone", E), E);
	}

	/** One click: each changed slot before and after. */
	private void click(Object... beforeAfter) {
		ledger.nextChange();
		for (int i = 0; i < beforeAfter.length; i += 3) {
			ledger.predict((Integer) beforeAfter[i], (String) beforeAfter[i + 1], (String) beforeAfter[i + 2], tick);
		}
		tick++;
	}

	private static List<String> menu(String... slots) {
		return Arrays.asList(slots);
	}

	@Test
	void plainClickUnanswered() {
		// A shift-click the server made as predicted: no reply, the steps stay and the logical view is the click's.
		click(2, "stone", E, 3, E, "stone");
		assertEquals(E, ledger.logical(2));
		assertEquals("stone", ledger.logical(3));
		assertEquals("stone", ledger.confirmed(2));
		assertEquals(2, ledger.pendingSteps());
	}

	@Test
	void destinationEcho() {
		// 2b2t re-sends a moved shulker's new slot: the click was taken, so the emptied slot (not answered: as predicted)
		// is done too.
		click(0, "shulker", E, 1, E, "shulker");
		assertEquals(Verdict.ECHO, ledger.onSlot(1, "shulker"));
		assertEquals(0, ledger.pendingSteps());
		assertEquals(E, ledger.confirmed(0));
		assertEquals(1, ledger.passed());
	}

	@Test
	void staleWholeMenuKeepsLaterClicks() {
		// Three quick clicks; the first one's whole-menu reply shows the others not made yet.
		click(0, "shulker", E, 1, E, "shulker");
		click(2, "stone", E, 3, E, "stone");
		Verdict[] v = ledger.onContents(menu(E, "shulker", "stone", E), E);
		assertEquals(Verdict.ECHO, v[0]);
		assertEquals(Verdict.ECHO, v[1]);
		assertEquals(Verdict.STALE, v[2]);
		assertEquals(Verdict.STALE, v[3]);
		assertEquals(E, ledger.logical(2), "the second click still shows");
		assertEquals("stone", ledger.logical(3));
		v = ledger.onContents(menu(E, "shulker", E, "stone"), E);
		assertEquals(Verdict.ECHO, v[2]);
		assertEquals(0, ledger.pendingSteps());
	}

	@Test
	void backAndForth() {
		// A to B and back: a whole menu showing A could be after both or before either, so it settles nothing (a check
		// does); it isn't taken as progress either.
		click(0, "shulker", E, 1, E, "shulker");
		click(1, "shulker", E, 0, E, "shulker");
		Verdict[] v = ledger.onContents(menu("shulker", E, "stone", E), E);
		assertEquals(Verdict.ECHO, v[0], "it agrees with what the client shows either way");
		assertEquals(Verdict.ECHO, v[1]);
		assertEquals(0, ledger.passed());
		assertEquals(4, ledger.pendingSteps());
		assertEquals("shulker", ledger.logical(0));
		ledger.settle(0, "shulker", 2);
		ledger.settle(1, E, 2);
		assertEquals(0, ledger.pendingSteps());
		assertFalse(ledger.anyNeedsCheck());
	}

	@Test
	void thereAndBackAgreesWithTheServer() {
		// The Printer swapping a bucket in and out of a glass slot: the server saying glass agrees with the client whether
		// it took the swaps or not, so nothing is overruled or doubted.
		ledger.reset(List.of("glass", "bucket", E, E), E);
		click(0, "glass", "bucket", 1, "bucket", "glass");
		click(0, "bucket", "glass", 1, "glass", "bucket");
		assertEquals(Verdict.ECHO, ledger.onSlot(0, "glass"));
		assertEquals("glass", ledger.logical(0));
		assertTrue(ledger.isPending(0));
		assertEquals(0, ledger.passed());
	}

	@Test
	void youngStepCrossedByOneSlot() {
		// A slot sent just before the server got a click, arriving after it: the click is younger than a round trip, so
		// its step stays shown, and a check is wanted.
		tick = 10;
		click(2, "stone", E, 3, E, "stone");
		assertEquals(Verdict.STALE, ledger.onSlot(2, "stone", 12, 4));
		assertEquals(E, ledger.logical(2));
		assertTrue(ledger.needsCheck(2));
		// The same packet two round trips later is the server's word.
		assertEquals(Verdict.CONTRADICTS, ledger.onSlot(2, "stone", 20, 4));
		assertEquals("stone", ledger.logical(2));
	}

	@Test
	void oneSlotIsTheServersWord() {
		// A step the server never answered (made as predicted), then a server command puts the old value back: one slot
		// is sent after every click it had, so it wins, and the slot is left for a check to vouch for.
		click(2, "stone", E, 3, E, "stone");
		assertEquals(Verdict.CONTRADICTS, ledger.onSlot(2, "stone"));
		assertEquals("stone", ledger.logical(2));
		assertTrue(ledger.needsCheck(2));
		ledger.settle(2, "stone", 1);
		assertFalse(ledger.needsCheck(2));
	}

	@Test
	void sameStackAgainIsNotProgress() {
		// Sorting moves look-alike stacks: a value two steps lead to counts as the earlier one, never overstating.
		ledger.reset(List.of("stone", E, E, E), E);
		click(0, "stone", E, 1, E, "stone");
		click(1, "stone", E, 2, E, "stone");
		click(2, "stone", E, 1, E, "stone");
		assertEquals(Verdict.ECHO, ledger.onSlot(1, "stone"));
		assertEquals(1, ledger.passed());
	}

	@Test
	void backAndForthHalfway() {
		click(0, "shulker", E, 1, E, "shulker");
		click(1, "shulker", E, 0, E, "shulker");
		// The first click's reply: slot 1 shows the shulker, the second step on it still waits.
		assertEquals(Verdict.ECHO, ledger.onSlot(1, "shulker"));
		assertEquals(E, ledger.logical(1));
		assertEquals(1, ledger.steps(1).size());
	}

	@Test
	void refusedClick() {
		// The server put something else there: its word wins and the click's step on that slot goes.
		click(2, "stone", E, 3, E, "stone");
		assertEquals(Verdict.CONTRADICTS, ledger.onSlot(3, "dirt"));
		assertEquals("dirt", ledger.logical(3));
		assertTrue(ledger.isPending(2), "only the slot the server spoke of");
	}

	@Test
	void wholeMenuUndoesDroppedClick() {
		// A click an anti-cheat dropped, then a whole menu after a later click: the dropped one's slots read as they
		// were, which only looks stale; settle() is what tells it apart, once a check came back after it.
		click(2, "stone", E, 3, E, "stone");
		long dropped = 1;
		Verdict[] v = ledger.onContents(menu("shulker", E, "stone", E), E);
		assertEquals(Verdict.STALE, v[2]);
		ledger.settle(2, "stone", dropped);
		ledger.settle(3, E, dropped);
		assertEquals("stone", ledger.logical(2));
		assertEquals(E, ledger.logical(3));
		assertEquals(0, ledger.pendingSteps());
	}

	@Test
	void settleKeepsLaterSteps() {
		click(2, "stone", E, 3, E, "stone");
		long first = 1;
		click(3, "stone", E, 1, E, "stone");
		ledger.settle(3, "stone", first);
		assertEquals(E, ledger.logical(3), "the second click's step stays");
		assertEquals(1, ledger.steps(3).size());
	}

	@Test
	void cursor() {
		click(2, "stone", E, SlotLedger.CURSOR, E, "stone");
		assertEquals("stone", ledger.logical(SlotLedger.CURSOR));
		assertEquals(Verdict.ECHO, ledger.onCursor("stone"));
		assertEquals(0, ledger.pendingSteps(), "the slot it came from is done with it");
		Verdict[] v = ledger.onContents(menu("shulker", E, E, E), "stone");
		assertEquals(Verdict.SERVER, v[2]);
		assertEquals(Verdict.SERVER, v[4]);
	}

	@Test
	void dragEndCrossedByCursorAnswers() {
		// A shulker put down by a one-slot drag (start, slot, end: only the end changes anything). 2b2t answers the start
		// and the slot click with the cursor still holding it; those arrive after the end: the box stays put down, not
		// back on the cursor as well.
		ledger.reset(List.of(E, E, E, E), "shulker");
		tick = 10;
		click(1, E, "shulker", SlotLedger.CURSOR, "shulker", E);
		assertEquals(Verdict.STALE, ledger.onCursor("shulker", 12, 3));
		assertEquals(Verdict.STALE, ledger.onCursor("shulker", 12, 3));
		assertEquals(E, ledger.logical(SlotLedger.CURSOR));
		// The end's own answer: the slot holds it, so the click was taken, cursor and all.
		assertEquals(Verdict.ECHO, ledger.onSlot(1, "shulker", 12, 3));
		assertEquals(E, ledger.logical(SlotLedger.CURSOR));
		assertEquals(E, ledger.confirmed(SlotLedger.CURSOR));
		assertEquals("shulker", ledger.logical(1));
		// Long after the click, the cursor is the server's word.
		click(1, "shulker", E, SlotLedger.CURSOR, E, "shulker");
		assertEquals(Verdict.CONTRADICTS, ledger.onCursor(E, 30, 3));
		assertEquals(E, ledger.logical(SlotLedger.CURSOR));
	}

	@Test
	void identicalStacks() {
		// Two slots holding the same thing are told apart by slot, so a reply for one can't settle the other.
		ledger.reset(List.of("stone", "stone", E, E), E);
		click(0, "stone", E, 2, E, "stone");
		click(1, "stone", E, 3, E, "stone");
		assertEquals(Verdict.ECHO, ledger.onSlot(2, "stone"));
		assertTrue(ledger.isPending(3));
	}

	@Test
	void laterEchoConfirmsSilentClicks() {
		// Stone moved (never answered), then a shulker (answered): the server took both, so both are done.
		click(2, "stone", E, 3, E, "stone");
		click(0, "shulker", E, 1, E, "shulker");
		assertEquals(Verdict.ECHO, ledger.onSlot(1, "shulker"));
		assertEquals(0, ledger.pendingSteps());
		assertEquals("stone", ledger.confirmed(3));
	}

	@Test
	void refusalSeenInLaterReply() {
		// A whole menu that shows a later click's result but an earlier click's slots as they were: refused, not stale.
		click(2, "stone", E, 3, E, "stone");
		click(0, "shulker", E, 1, E, "shulker");
		Verdict[] v = ledger.onContents(menu(E, "shulker", "stone", E), E);
		assertEquals(Verdict.CONTRADICTS, v[2]);
		assertEquals(Verdict.CONTRADICTS, v[3]);
		assertEquals("stone", ledger.logical(2));
		assertEquals(E, ledger.logical(3));
	}

	@Test
	void noStepForNoChange() {
		click(0, "shulker", "shulker");
		assertEquals(0, ledger.pendingSteps());
	}

	@Test
	void stepsCapped() {
		List<String> values = new ArrayList<>(List.of("a", E, E, E));
		ledger.reset(values, E);
		String now = "a";
		for (int i = 0; i < SlotLedger.MAX_STEPS + 5; i++) {
			String next = now.equals("a") ? "b" : "a";
			click(0, now, next);
			now = next;
		}
		assertEquals(SlotLedger.MAX_STEPS, ledger.steps(0).size());
		assertEquals(now, ledger.logical(0));
	}

	@Test
	void oldestPending() {
		assertEquals(-1, ledger.oldestPendingTick());
		tick = 7;
		click(2, "stone", E);
		click(3, E, "stone");
		assertEquals(7, ledger.oldestPendingTick());
	}
}
