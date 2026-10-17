package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.service.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs scheduled tasks at the end of every client tick. {@link #tick()} is separate from the event for tests. */
public final class TaskScheduler implements Tasks {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Tasks");

	private final List<Task> tasks = new ArrayList<>();
	private final List<Task> added = new ArrayList<>();
	private boolean ticking;

	@Subscribe
	private void onTick(TickEvent.Post e) {
		tick();
	}

	public void tick() {
		ticking = true;
		try {
			for (Task t : tasks) {
				if (t.done) continue;
				try {
					t.step();
				} catch (Throwable ex) {
					LOG.error("Task owned by {} failed", t.owner, ex);
					t.done = true;
				}
			}
		} finally {
			ticking = false;
		}
		tasks.removeIf(t -> t.done);
		tasks.addAll(added);
		added.clear();
	}

	private Task add(Task t) {
		(ticking ? added : tasks).add(t);
		return t;
	}

	@Override
	public Handle later(Object owner, int ticks, Runnable action) {
		return sequence(owner).wait(ticks).run(action).start();
	}

	@Override
	public Handle every(Object owner, int intervalTicks, Runnable action) {
		return add(new Repeating(owner, Math.max(1, intervalTicks), action));
	}

	@Override
	public Sequence sequence(Object owner) {
		return new SequenceImpl(owner);
	}

	@Override
	public void cancel(Object owner) {
		for (Task t : tasks) if (t.owner == owner) t.done = true;
		for (Task t : added) if (t.owner == owner) t.done = true;
	}

	@Override
	public boolean isBusy(Object owner) {
		for (Task t : tasks) if (t.owner == owner && !t.done) return true;
		for (Task t : added) if (t.owner == owner && !t.done) return true;
		return false;
	}

	private abstract static class Task implements Handle {
		final Object owner;
		boolean done;

		Task(Object owner) {
			this.owner = owner;
		}

		abstract void step();

		@Override
		public void cancel() {
			done = true;
		}

		@Override
		public boolean isDone() {
			return done;
		}
	}

	private static final class Repeating extends Task {
		private final int interval;
		private final Runnable action;
		private int wait;

		Repeating(Object owner, int interval, Runnable action) {
			super(owner);
			this.interval = interval;
			this.action = action;
			this.wait = interval;
		}

		@Override
		void step() {
			if (--wait > 0) return;
			wait = interval;
			action.run();
		}
	}

	private sealed interface Step permits Run, Wait, WaitUntil, Await, Require {
	}

	private record Run(Runnable action) implements Step {
	}

	private record Wait(int ticks) implements Step {
	}

	private record WaitUntil(BooleanSupplier condition, int timeout) implements Step {
	}

	private record Await(Supplier<? extends CompletableFuture<?>> start, int timeout) implements Step {
	}

	private record Require(BooleanSupplier condition, String message) implements Step {
	}

	private final class SequenceImpl extends Task implements Sequence {
		private final List<Step> steps = new ArrayList<>();
		private Runnable onTimeout, onFinish;
		private Consumer<Throwable> onFail;
		private int index, retries;
		/** Ticks left on the current wait; -1 when the current step hasn't started. */
		private int remaining = -1;
		/** The future the current {@link Await} step is waiting for. */
		private CompletableFuture<?> awaiting;
		/** Set when a failed attempt will start over: the next attempt begins on the next tick. */
		private boolean restartNextTick;

		SequenceImpl(Object owner) {
			super(owner);
		}

		@Override
		public Sequence run(Runnable action) {
			steps.add(new Run(action));
			return this;
		}

		@Override
		public Sequence wait(int ticks) {
			steps.add(new Wait(Math.max(0, ticks)));
			return this;
		}

		@Override
		public Sequence waitUntil(BooleanSupplier condition, int timeoutTicks) {
			steps.add(new WaitUntil(condition, Math.max(1, timeoutTicks)));
			return this;
		}

		@Override
		public Sequence await(Supplier<? extends CompletableFuture<?>> start, int timeoutTicks) {
			steps.add(new Await(start, Math.max(1, timeoutTicks)));
			return this;
		}

		@Override
		public Sequence require(BooleanSupplier condition, String message) {
			steps.add(new Require(condition, message));
			return this;
		}

		@Override
		public Sequence retry(int times) {
			retries = Math.max(0, times);
			return this;
		}

		@Override
		public Sequence onTimeout(Runnable action) {
			onTimeout = action;
			return this;
		}

		@Override
		public Sequence onFail(Consumer<Throwable> action) {
			onFail = action;
			return this;
		}

		@Override
		public Sequence onFinish(Runnable action) {
			onFinish = action;
			return this;
		}

		@Override
		public Handle start() {
			return add(this);
		}

		@Override
		public void cancel() {
			super.cancel();
			if (awaiting != null) awaiting.cancel(false);
		}

		/** Runs steps until one has to wait for a later tick. */
		@Override
		void step() {
			if (restartNextTick) {
				restartNextTick = false;
				index = 0;
				remaining = -1;
			}
			try {
				runSteps();
			} catch (Throwable t) {
				fail(t);
			}
		}

		private void runSteps() {
			while (!done && index < steps.size()) {
				Step step = steps.get(index);
				switch (step) {
					case Run r -> {
						r.action.run();
						next();
					}
					case Wait w -> {
						if (remaining == -1) remaining = w.ticks;
						else remaining--;
						if (remaining > 0) return;
						next();
					}
					case WaitUntil u -> {
						if (u.condition.getAsBoolean()) {
							next();
							continue;
						}
						if (remaining == -1) remaining = u.timeout;
						if (--remaining <= 0) {
							if (onTimeout != null && retries == 0) onTimeout.run();
							fail(new TimeoutException("Condition not met within " + u.timeout + " ticks"));
						}
						return;
					}
					case Await a -> {
						if (awaiting == null) {
							awaiting = a.start.get();
							remaining = a.timeout;
						}
						if (awaiting.isDone()) {
							CompletableFuture<?> f = awaiting;
							awaiting = null;
							Throwable error = f.handle((v, t) -> t).join();
							if (error != null) {
								fail(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
								return;
							}
							next();
							continue;
						}
						if (--remaining <= 0) {
							awaiting.cancel(false);
							awaiting = null;
							fail(new TimeoutException("Not done within " + a.timeout + " ticks"));
						}
						return;
					}
					case Require r -> {
						if (!r.condition.getAsBoolean()) {
							fail(new IllegalStateException(r.message));
							return;
						}
						next();
					}
				}
			}
			if (!done) {
				done = true;
				if (onFinish != null) onFinish.run();
			}
		}

		private void fail(Throwable reason) {
			if (done) return;
			awaiting = null;
			if (retries > 0) {
				retries--;
				restartNextTick = true;
				return;
			}
			done = true;
			if (onFail != null) onFail.accept(reason);
			else if (!(reason instanceof TimeoutException) || onTimeout == null) LOG.warn("Task owned by {} failed: {}", owner, reason.toString());
		}

		private void next() {
			index++;
			remaining = -1;
		}
	}
}
