package dev.myriadclient.api.job;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Policies: steps that wrap another and run in the same job, so they show as pills on it rather than as jobs. A
 * {@code retry} or {@code recover} starts work over inside that one job, so whoever holds the job sees it through.
 */
final class Policies {
	private Policies() {
	}

	/** A step around {@code inner}: same name, same job. */
	private abstract static class Wrap<T> extends Step<T> {
		final Step<T> inner;

		Wrap(Step<T> inner) {
			this.inner = inner;
		}

		@Override
		public String name() {
			return inner.name();
		}

		/** How it reads on the tree, or null for nothing. */
		@Nullable String pill() {
			return null;
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			String p = pill();
			List<String> all = pills;
			if (p != null) {
				all = new ArrayList<>(pills);
				all.add(0, p);
			}
			inner.describe(out, depth, name, all);
		}

		@Override
		boolean handlesFailure() {
			return inner.handlesFailure();
		}
	}

	/** An exec that hands everything to the one it wraps; subclasses look at what comes back. */
	private abstract static class Around<T> extends Exec<T> {
		Exec<T> inner;

		Around(Exec<T> inner) {
			this.inner = inner;
		}

		@Override
		Progress<T> step() {
			return inner.tick();
		}

		@Override
		void stop(Why why) {
			inner.cancel(why);
		}

		@Override
		void pause() {
			inner.pause();
		}

		@Override
		void resume() {
			inner.resume();
		}
	}

	static final class Named<T> extends Wrap<T> {
		private final @Nullable String name;
		private final @Nullable Supplier<String> live;

		Named(Step<T> inner, @Nullable String name, @Nullable Supplier<String> live) {
			super(inner);
			this.name = name;
			this.live = live;
		}

		@Override
		public String name() {
			if (name != null) return name;
			try {
				String s = live.get();
				return s == null ? inner.name() : s;
			} catch (RuntimeException e) {
				return inner.name();
			}
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			inner.describe(out, depth, name != null ? name : name(), pills);
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			if (live != null) node.rename(live);
			return inner.open(node);
		}
	}

	static final class Timeout<T> extends Wrap<T> {
		private final int ticks;

		Timeout(Step<T> inner, int ticks) {
			super(inner);
			this.ticks = ticks;
		}

		@Override
		String pill() {
			return "timeout " + ticks;
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			node.pill(this::pill);
			return new Around<>(inner.open(node)) {
				private int count;

				@Override
				Progress<T> step() {
					Progress<T> p = inner.tick();
					if (p.isOver() || p.isPaused() || ++count <= ticks) return p;
					Why why = Why.of(Why.TIMEOUT, "after " + ticks + " ticks");
					inner.cancel(why);
					return Progress.failed(why);
				}
			};
		}
	}

	static final class StuckFor<T> extends Wrap<T> {
		private final int ticks;

		StuckFor(Step<T> inner, int ticks) {
			super(inner);
			this.ticks = ticks;
		}

		@Override
		String pill() {
			return "gives up stuck after " + ticks;
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Around<>(inner.open(node)) {
				private int stuck;

				@Override
				Progress<T> step() {
					Progress<T> p = inner.tick();
					if (p.isPaused()) return p;
					if (!p.isStuck()) {
						stuck = 0;
						return p;
					}
					if (++stuck <= ticks) return p;
					inner.cancel(p.why());
					return Progress.failed(p.why());
				}
			};
		}
	}

	static final class WhenStuck<T> extends Wrap<T> {
		private final @Nullable Why.Code code;
		private final int ticks;
		private final Function<Why, ? extends Step<?>> remedy;

		WhenStuck(Step<T> inner, @Nullable Why.Code code, int ticks, Function<Why, ? extends Step<?>> remedy) {
			super(inner);
			this.code = code;
			this.ticks = Math.max(0, ticks);
			this.remedy = remedy;
		}

		@Override
		String pill() {
			return code == null ? "when stuck" : "when stuck: " + code.describe();
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			node.pill(this::pill);
			return new Around<>(inner.open(node)) {
				private @Nullable Slot<?> fixing;
				private int stuck;

				@Override
				Progress<T> step() {
					if (fixing != null) {
						Progress<?> r = fixing.tick();
						if (r.isFailed()) {
							inner.cancel(r.why());
							return Progress.failed(r.why());
						}
						if (!r.isDone()) return r.isStuck() ? Progress.stuck(r.why()) : Progress.running();
						// Done: the step carries on, ticked again this tick.
						fixing = null;
						stuck = 0;
						inner.resume();
					}
					Progress<T> p = inner.tick();
					if (p.isPaused()) return p;
					if (!p.isStuck() || code != null && !p.why().is(code)) {
						stuck = 0;
						return p;
					}
					if (stuck++ < ticks) return p;
					inner.pause();
					fixing = Slot.child(node, remedy.apply(p.why()));
					// Its first tick now; if it's done at once, the step carries on next tick (one remedy a tick at most).
					Progress<?> r = fixing.tick();
					if (r.isFailed()) {
						inner.cancel(r.why());
						return Progress.failed(r.why());
					}
					if (r.isDone()) {
						fixing = null;
						stuck = 0;
						inner.resume();
						return Progress.running();
					}
					return r.isStuck() ? Progress.stuck(r.why()) : Progress.running();
				}

				@Override
				void stop(Why why) {
					if (fixing != null) fixing.cancel(why);
					inner.cancel(why);
				}

				@Override
				void pause() {
					if (fixing != null) fixing.pause();
					else inner.pause();
				}

				@Override
				void resume() {
					if (fixing != null) fixing.resume();
					else inner.resume();
				}
			};
		}
	}

	static final class Recover<T> extends Wrap<T> {
		private final @Nullable Predicate<Why> when;
		private final Function<Why, ? extends Step<T>> alternative;

		Recover(Step<T> inner, @Nullable Predicate<Why> when, Function<Why, ? extends Step<T>> alternative) {
			super(inner);
			this.when = when;
			this.alternative = alternative;
		}

		@Override
		String pill() {
			return "recover";
		}

		@Override
		boolean handlesFailure() {
			return when == null || inner.handlesFailure();
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Around<>(inner.open(node)) {
				private boolean recovered;

				@Override
				Progress<T> step() {
					Progress<T> p = inner.tick();
					if (recovered || !p.isFailed() || p.why().isCancellation() || when != null && !when.test(p.why())) return p;
					Step<T> alt = alternative.apply(p.why());
					recovered = true;
					node.restart();
					node.rename(alt.name());
					inner = alt.open(node);
					return inner.tick();
				}
			};
		}
	}

	static final class Retry<T> extends Wrap<T> {
		private final int times;
		private final @Nullable Predicate<Why> when;

		Retry(Step<T> inner, int times, @Nullable Predicate<Why> when) {
			super(inner);
			this.times = Math.max(0, times);
			this.when = when;
		}

		@Override
		String pill() {
			return "retry " + times;
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			Attempts e = new Attempts(node);
			node.pill(() -> "retry " + e.attempts + "/" + times);
			return e;
		}

		private final class Attempts extends Around<T> {
			private final JobNode<T> node;
			int attempts;
			private boolean again;

			Attempts(JobNode<T> node) {
				super(Retry.this.inner.open(node));
				this.node = node;
			}

			@Override
			Progress<T> step() {
				if (again) {
					again = false;
					node.restart();
					inner = Retry.this.inner.open(node);
				}
				Progress<T> p = inner.tick();
				if (!p.isFailed() || attempts >= times || p.why().isCancellation() || when != null && !when.test(p.why())) return p;
				attempts++;
				again = true;
				return Progress.running();
			}
		}
	}

	static final class Require<T> extends Wrap<T> {
		private final BooleanSupplier condition;
		private final Why otherwise;

		Require(Step<T> inner, BooleanSupplier condition, Why otherwise) {
			super(inner);
			this.condition = condition;
			this.otherwise = otherwise;
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return condition.getAsBoolean() ? inner.open(node) : Exec.over(Progress.failed(otherwise));
		}
	}

	static final class Suspending<T> extends Wrap<T> {
		private final List<MyriadId> ids;
		private final List<Class<? extends Module>> types;

		Suspending(Step<T> inner, List<MyriadId> ids, List<Class<? extends Module>> types) {
			super(inner);
			this.ids = ids;
			this.types = types;
		}

		@Override
		String pill() {
			List<String> names = new ArrayList<>();
			for (MyriadId id : ids) names.add(id.path().replace('_', ' '));
			for (Class<? extends Module> t : types) names.add(t.getSimpleName());
			return "holds off " + String.join(", ", names);
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			// Held for the job, so its end (whatever way, after a retry too) lets them go.
			node.suspends = true;
			for (MyriadId id : ids) Modules.suspend(node, id);
			for (Class<? extends Module> t : types) Modules.suspend(node, t);
			return inner.open(node);
		}
	}

	/** Runs something once when the wrapped step ends, whatever way. */
	private abstract static class AtEnd<T> extends Wrap<T> {
		AtEnd(Step<T> inner) {
			super(inner);
		}

		/** The step ended with {@code p} (failed with the reason when it was cancelled). */
		abstract void ended(Progress<T> p, boolean cancelled);

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Around<>(inner.open(node)) {
				@Override
				Progress<T> step() {
					Progress<T> p = inner.tick();
					if (p.isOver()) run(p, false);
					return p;
				}

				@Override
				void stop(Why why) {
					inner.cancel(why);
					run(Progress.failed(why), true);
				}
			};
		}

		private void run(Progress<T> p, boolean cancelled) {
			try {
				ended(p, cancelled);
			} catch (Throwable t) {
				JobEngine.LOG.error("A step's end action threw", t);
			}
		}
	}

	static final class Finally<T> extends AtEnd<T> {
		private final Runnable action;

		Finally(Step<T> inner, Runnable action) {
			super(inner);
			this.action = action;
		}

		@Override
		String pill() {
			return "finally";
		}

		@Override
		void ended(Progress<T> p, boolean cancelled) {
			action.run();
		}
	}

	static final class OnDone<T> extends AtEnd<T> {
		private final Consumer<? super T> action;

		OnDone(Step<T> inner, Consumer<? super T> action) {
			super(inner);
			this.action = action;
		}

		@Override
		void ended(Progress<T> p, boolean cancelled) {
			if (p.isDone()) action.accept(p.value());
		}
	}

	static final class OnFailed<T> extends AtEnd<T> {
		private final Consumer<Why> action;

		OnFailed(Step<T> inner, Consumer<Why> action) {
			super(inner);
			this.action = action;
		}

		@Override
		boolean handlesFailure() {
			return true;
		}

		@Override
		void ended(Progress<T> p, boolean cancelled) {
			if (!cancelled && p.isFailed() && !p.why().isCancellation()) action.accept(p.why());
		}
	}

	static final class Peek<T> extends Wrap<T> {
		private final Consumer<? super T> action;

		Peek(Step<T> inner, Consumer<? super T> action) {
			super(inner);
			this.action = action;
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Around<>(inner.open(node)) {
				@Override
				Progress<T> step() {
					Progress<T> p = inner.tick();
					if (p.isDone()) action.accept(p.value());
					return p;
				}
			};
		}
	}
}
