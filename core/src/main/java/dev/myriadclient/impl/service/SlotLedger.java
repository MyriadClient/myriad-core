package dev.myriadclient.impl.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * What one menu's slots hold, as the server last said ("confirmed") and with the client's own changes it hasn't
 * answered yet on top ("logical"). Every local change is a pending step (what the slot held before, what it holds
 * after) queued per slot in the order made; the cursor is slot {@link #CURSOR}.
 * <p>
 * The server's packets are weighed against those steps by value: one showing a step's result is the server catching
 * up (the steps up to it are done); a whole-menu packet showing a slot as it was before its first pending step was
 * made before the server got to that step (the step stays); anything else is the server's own word, and the slot's
 * steps are dropped.
 * <p>
 * The server takes clicks in order and answers each with the slots that came out differently from what the client
 * said. So once it shows the result of change {@code m}, every earlier change was taken too, and those it said nothing
 * about came out as predicted: they're done ({@link #passed}), and a whole menu showing a slot as it was before one of
 * them is the server refusing it, not a packet sent before it got there. A click an anti-cheat drops without a word
 * looks the same as one taken, so only a check of the whole menu tells those apart ({@link #settle}). Nothing here
 * expires with time. Pure, so it can be tested without a game.
 *
 * @param <T> what a slot holds
 */
public final class SlotLedger<T> {
	public static final int CURSOR = -1;
	/** Steps kept per slot. */
	static final int MAX_STEPS = 32;

	/** How a packet's value for a slot was taken. */
	public enum Verdict {
		/** No step pending: the server's value, the same as before or new. */
		SERVER,
		/** The result of a pending step: those up to it are done. */
		ECHO,
		/** A whole menu showing the slot as before its first pending step (sent before the server got to it, or there and back): the steps stay. */
		STALE,
		/** Neither: the server holds something the steps didn't lead to, and they're dropped. */
		CONTRADICTS
	}

	/** A local change not yet answered: {@code seq} numbers changes in the order they were made. */
	public record Step<T>(T before, T after, long seq, long tick) {}

	private final BiPredicate<T, T> same;
	private final T empty;
	private final Map<Integer, T> confirmed = new HashMap<>();
	private final Map<Integer, ArrayDeque<Step<T>>> pending = new HashMap<>();
	/**
	 * Slots whose steps the server's word overruled: right nearly always, but a packet sent just before the server got
	 * a click looks the same, so a check should vouch for them.
	 */
	private final java.util.Set<Integer> doubtful = new java.util.HashSet<>();
	private long seq;
	/** The latest change the server is known to have taken. */
	private long passed;

	/** {@code same} says whether two values are alike; {@code empty} is what a slot nobody mentioned holds. */
	public SlotLedger(BiPredicate<T, T> same, T empty) {
		this.same = same;
		this.empty = empty;
	}

	/** Forgets everything; the slots hold what {@code values} say (index = slot), the cursor {@code cursor}. */
	public void reset(List<T> values, T cursor) {
		confirmed.clear();
		pending.clear();
		doubtful.clear();
		passed = seq;
		for (int i = 0; i < values.size(); i++) confirmed.put(i, values.get(i));
		confirmed.put(CURSOR, cursor);
	}

	/** Starts a new change: the steps {@link #predict} records until the next call share its number. */
	public long nextChange() {
		return ++seq;
	}

	/** The slot went from {@code before} to {@code after} by a local change ({@link #nextChange}'s number). */
	public void predict(int slot, T before, T after, long tick) {
		if (same.test(before, after)) return;
		ArrayDeque<Step<T>> q = pending.computeIfAbsent(slot, k -> new ArrayDeque<>());
		// A server says nothing to a change it made the same way, so on a slot changed over and over the oldest steps
		// are taken as made rather than kept for ever.
		if (q.size() >= MAX_STEPS) confirmed.put(slot, q.removeFirst().after());
		q.addLast(new Step<>(before, after, seq, tick));
	}

	/** What the server last said the slot holds. */
	public T confirmed(int slot) {
		return confirmed.getOrDefault(slot, empty);
	}

	/** What the slot holds once the server has taken every pending change. */
	public T logical(int slot) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		return q == null || q.isEmpty() ? confirmed(slot) : q.peekLast().after();
	}

	/** Whether a check should vouch for the slot: changes pending, or overruled ones (see {@link #doubtful}). */
	public boolean needsCheck(int slot) {
		return isPending(slot) || doubtful.contains(slot);
	}

	/** Whether any slot needs a check. */
	public boolean anyNeedsCheck() {
		return !doubtful.isEmpty() || pendingSteps() > 0;
	}

	public boolean isPending(int slot) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		return q != null && !q.isEmpty();
	}

	/** Steps waiting on the server, every slot. */
	public int pendingSteps() {
		int n = 0;
		for (ArrayDeque<Step<T>> q : pending.values()) n += q.size();
		return n;
	}

	/** The tick of the oldest pending step, or -1 for none. */
	public long oldestPendingTick() {
		long oldest = -1;
		for (ArrayDeque<Step<T>> q : pending.values()) {
			if (!q.isEmpty() && (oldest < 0 || q.peekFirst().tick() < oldest)) oldest = q.peekFirst().tick();
		}
		return oldest;
	}

	/** The slots with steps pending. */
	public Iterable<Integer> pendingSlots() {
		return pending.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
	}

	/** The pending steps of one slot, oldest first. */
	public List<Step<T>> steps(int slot) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		return q == null ? List.of() : List.copyOf(q);
	}

	/** The latest change the server is known to have taken (its result shown); every one up to it is done. */
	public long passed() {
		return passed;
	}

	/** The server set one slot (it had taken every click it got before sending this). */
	public Verdict onSlot(int slot, T value) {
		return onSlot(slot, value, 0, -1);
	}

	/**
	 * The server set one slot, at tick {@code now}. One showing the slot as before a step made within {@code crossing}
	 * ticks was most likely sent just before the server got that click (they crossed on the way): the step stays, and
	 * the slot is left for a check to settle. Older, it's the server's word.
	 */
	public Verdict onSlot(int slot, T value, long now, long crossing) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		if (q != null && !q.isEmpty() && q.peekFirst().seq() > passed && now - q.peekFirst().tick() <= crossing && same.test(q.peekFirst().before(), value)) {
			confirmed.put(slot, value);
			doubtful.add(slot);
			return Verdict.STALE;
		}
		Verdict v = weigh(slot, value, false);
		retire();
		return v;
	}

	/** The server sent the whole menu: {@code values} by slot, and the cursor. Returns each slot's verdict, cursor last. */
	public Verdict[] onContents(List<T> values, T cursor) {
		// The changes it shows the result of come first: they say how far the server got, which every slot is judged by.
		for (int i = 0; i <= values.size(); i++) {
			int slot = i == values.size() ? CURSOR : i;
			pass(echoed(slot, slot == CURSOR ? cursor : values.get(i)));
		}
		Verdict[] verdicts = new Verdict[values.size() + 1];
		for (int i = 0; i < values.size(); i++) verdicts[i] = weigh(i, values.get(i), true);
		verdicts[values.size()] = weigh(CURSOR, cursor, true);
		retire();
		return verdicts;
	}

	/** The server set the cursor. */
	public Verdict onCursor(T value) {
		Verdict v = weigh(CURSOR, value, false);
		retire();
		return v;
	}

	/**
	 * The slot holds {@code value} once the server took every change up to {@code upTo} (a check that came back after
	 * them): those steps are done whatever they said, later ones stay.
	 */
	public void settle(int slot, T value, long upTo) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		if (q != null) q.removeIf(s -> s.seq() <= upTo);
		confirmed.put(slot, value);
		doubtful.remove(slot);
	}

	/**
	 * The change whose result {@code value} is in the slot, or 0 if that can't be told. A value the slot held before its
	 * first pending step says nothing (it went A to B to A: both taken, or neither), and of several steps leading to it
	 * the earliest is taken, so how far the server got is never overstated.
	 */
	private long echoed(int slot, T value) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		if (q == null || q.isEmpty() || same.test(q.peekFirst().before(), value)) return 0;
		for (Step<T> s : q) if (same.test(s.after(), value)) return s.seq();
		return 0;
	}

	private void pass(long upTo) {
		if (upTo > passed) passed = upTo;
	}

	/** Steps up to {@link #passed} came out as predicted (a wrong one would have been answered): done. */
	private void retire() {
		for (Map.Entry<Integer, ArrayDeque<Step<T>>> e : pending.entrySet()) {
			ArrayDeque<Step<T>> q = e.getValue();
			while (!q.isEmpty() && q.peekFirst().seq() <= passed) confirmed.put(e.getKey(), q.removeFirst().after());
		}
	}

	private Verdict weigh(int slot, T value, boolean whole) {
		ArrayDeque<Step<T>> q = pending.get(slot);
		confirmed.put(slot, value);
		if (q == null || q.isEmpty()) return Verdict.SERVER;
		// What the slot will hold once every step is taken: whether the server took none of them yet (A to B and back) or
		// all, it shows what the client does. Nothing to correct; the steps stay for a later answer or check.
		if (same.test(q.peekLast().after(), value) && same.test(q.peekFirst().before(), value)) return Verdict.ECHO;
		long match = echoed(slot, value);
		if (match > 0) {
			pass(match);
			q.removeIf(s -> s.seq() <= match);
			return Verdict.ECHO;
		}
		// A whole menu as it was before the first pending step: sent before the server got to it (a whole menu answers a
		// click that came with an old state id, as each one in a quick run does), or it went there and back. Unless it's
		// known to have taken that step, in which case it refused it. One slot is only ever sent after the server took
		// every click it had, so it's the server's word (one sent just before a click arrived aside: a check settles that).
		if (whole && q.peekFirst().seq() > passed && same.test(q.peekFirst().before(), value)) return Verdict.STALE;
		q.clear();
		doubtful.add(slot);
		return Verdict.CONTRADICTS;
	}
}
