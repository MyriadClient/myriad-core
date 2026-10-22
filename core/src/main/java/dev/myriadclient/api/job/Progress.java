package dev.myriadclient.api.job;

import org.jetbrains.annotations.Nullable;

/**
 * How a {@link Step.Run} stands after a tick. Four states, and the difference between the middle two is the point:
 * <ul>
 * <li>{@link #running}: working, nothing to say.</li>
 * <li>{@link #stuck}: can't go on right now, for a reason that may clear (no cobblestone, out of reach, the chunk
 * hasn't loaded). The step keeps being ticked and may unstick itself; a {@code whenStuck} above it can run a remedy;
 * the Jobs window shows the reason. Not a failure.</li>
 * <li>{@link #done}: finished, with the result.</li>
 * <li>{@link #failed}: can't ever finish from here (the server refused the block, Baritone gave up, no elytra left).
 * It goes up until a {@code recover} or {@code retry} takes it.</li>
 * </ul>
 * Core makes these; a {@code Run} returns them. {@link #running()} is one shared instance, so returning it every tick
 * costs nothing.
 */
public final class Progress<T> {
	private enum Kind {
		RUNNING, STUCK, DONE, FAILED
	}

	private static final Progress<?> RUNNING = new Progress<>(Kind.RUNNING, null, null);
	/** Running because the job is paused: wrappers that count ticks (timeouts) don't count these. */
	private static final Progress<?> PAUSED = new Progress<>(Kind.RUNNING, null, null);
	private static final Progress<Void> DONE = new Progress<>(Kind.DONE, null, null);

	private final Kind kind;
	private final @Nullable Why why;
	private final @Nullable T value;

	private Progress(Kind kind, @Nullable Why why, @Nullable T value) {
		this.kind = kind;
		this.why = why;
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public static <T> Progress<T> running() {
		return (Progress<T>) RUNNING;
	}

	public static <T> Progress<T> stuck(Why why) {
		return new Progress<>(Kind.STUCK, java.util.Objects.requireNonNull(why), null);
	}

	public static <T> Progress<T> done(T value) {
		return new Progress<>(Kind.DONE, null, value);
	}

	/** {@code done(null)} for a {@code Run<Void>}. */
	public static Progress<Void> done() {
		return DONE;
	}

	public static <T> Progress<T> failed(Why why) {
		return new Progress<>(Kind.FAILED, java.util.Objects.requireNonNull(why), null);
	}

	@SuppressWarnings("unchecked")
	static <T> Progress<T> paused() {
		return (Progress<T>) PAUSED;
	}

	boolean isPaused() {
		return this == PAUSED;
	}

	public boolean isRunning() {
		return kind == Kind.RUNNING;
	}

	public boolean isStuck() {
		return kind == Kind.STUCK;
	}

	public boolean isDone() {
		return kind == Kind.DONE;
	}

	public boolean isFailed() {
		return kind == Kind.FAILED;
	}

	/** Done or failed: the step is over. */
	boolean isOver() {
		return kind == Kind.DONE || kind == Kind.FAILED;
	}

	/** The reason, when stuck or failed. */
	public @Nullable Why why() {
		return why;
	}

	/** The result, when done (null for {@code Void}). */
	public T value() {
		if (kind != Kind.DONE) throw new IllegalStateException("Not done: " + this);
		return value;
	}

	@SuppressWarnings("unchecked")
	<R> Progress<R> cast() {
		if (kind == Kind.DONE) throw new IllegalStateException("A done progress carries its own type");
		return (Progress<R>) this;
	}

	@Override
	public String toString() {
		return switch (kind) {
			case RUNNING -> "running";
			case STUCK -> "stuck: " + why;
			case DONE -> "done" + (value == null ? "" : ": " + value);
			case FAILED -> "failed: " + why;
		};
	}
}
