package dev.myriadclient.api.job;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** The steps that run other steps, each child as a job of its own under them; and the transparent ones, which don't. */
final class Composites {
	private Composites() {
	}

	static Step<Void> sequence(String name, List<? extends Step<?>> steps) {
		return new Chain<>(name, List.copyOf(steps), false, true);
	}

	/** {@code first}, then {@code link} (a step, or a function of the result): one chain however long it gets. */
	@SuppressWarnings("unchecked")
	static <R> Step<R> then(Step<?> first, Object link) {
		if (first instanceof Chain<?> c && c.fromThen) {
			List<Object> links = new ArrayList<>(c.links);
			links.add(link);
			return new Chain<>(c.name, links, true, false);
		}
		return new Chain<>(first.name(), List.of(first, link), true, false);
	}

	/** Steps one after another; a link may be a function of the result before it. Done with the last result. */
	static final class Chain<T> extends Step<T> {
		final String name;
		final List<Object> links;
		/** Made by {@code then}: another {@code then} extends it rather than nesting it. */
		final boolean fromThen;
		private final boolean voidResult;

		Chain(String name, List<Object> links, boolean fromThen, boolean voidResult) {
			this.name = name;
			this.links = links;
			this.fromThen = fromThen;
			this.voidResult = voidResult;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			line(out, depth, name != null ? name : this.name, pills);
			for (Object l : links) {
				if (l instanceof Step<?> s) s.describe(out, depth + 1, null, new ArrayList<>());
				else line(out, depth + 1, "(the next step, from the result)", List.of());
			}
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Exec<>() {
				private int index;
				private @Nullable Object last;
				private @Nullable Slot<?> current;

				@Override
				@SuppressWarnings("unchecked")
				Progress<T> step() {
					for (int hops = 0; hops < JobEngine.HOPS; hops++) {
						if (current == null) {
							if (index >= links.size()) return Progress.done(voidResult ? null : (T) last);
							Object l = links.get(index);
							Step<?> next = l instanceof Step<?> s ? s : ((Function<Object, Step<?>>) l).apply(last);
							node.setProgress((double) index / links.size());
							current = Slot.child(node, next);
						}
						Progress<?> p = current.tick();
						if (!p.isDone()) return p.cast();
						last = p.value();
						current = null;
						index++;
					}
					return Progress.running();
				}

				@Override
				void stop(Why why) {
					if (current != null) current.cancel(why);
				}

				@Override
				void pause() {
					if (current != null) current.pause();
				}

				@Override
				void resume() {
					if (current != null) current.resume();
				}
			};
		}
	}

	static final class ForEach<X> extends Step<Void> {
		private final String name;
		private final Supplier<? extends Iterable<X>> items;
		private final Function<? super X, ? extends Step<?>> body;

		ForEach(String name, Supplier<? extends Iterable<X>> items, Function<? super X, ? extends Step<?>> body) {
			this.name = name;
			this.items = items;
			this.body = body;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			line(out, depth, name != null ? name : this.name, pills);
			line(out, depth + 1, "(a step for each item)", List.of());
		}

		@Override
		Exec<Void> open(JobNode<Void> node) {
			return new Exec<>() {
				private @Nullable Iterator<X> it;
				private int index, size = -1;
				private @Nullable Slot<?> current;

				@Override
				Progress<Void> step() {
					if (it == null) {
						Iterable<X> all = items.get();
						if (all instanceof Collection<?> c) size = c.size();
						it = all.iterator();
					}
					for (int hops = 0; hops < JobEngine.HOPS; hops++) {
						if (current == null) {
							if (!it.hasNext()) return Progress.done();
							if (size > 0) node.setProgress((double) index / size);
							current = Slot.child(node, body.apply(it.next()));
						}
						Progress<?> p = current.tick();
						if (!p.isDone()) return p.cast();
						current = null;
						index++;
					}
					return Progress.running();
				}

				@Override
				void stop(Why why) {
					if (current != null) current.cancel(why);
				}

				@Override
				void pause() {
					if (current != null) current.pause();
				}

				@Override
				void resume() {
					if (current != null) current.resume();
				}
			};
		}
	}

	/** Several at once: {@code all} waits for every one, a {@link Race} for the first. */
	private abstract static class Several<T, R> extends Step<R> {
		final String name;
		final List<? extends Step<T>> steps;

		Several(String name, List<? extends Step<T>> steps) {
			this.name = name;
			this.steps = steps;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			line(out, depth, name != null ? name : this.name, pills);
			for (Step<T> s : steps) s.describe(out, depth + 1, null, new ArrayList<>());
		}

		abstract class Run extends Exec<R> {
			final JobNode<R> node;
			@Nullable List<Slot<T>> slots;

			Run(JobNode<R> node) {
				this.node = node;
			}

			List<Slot<T>> slots() {
				if (slots == null) {
					slots = new ArrayList<>(steps.size());
					for (Step<T> s : steps) slots.add(Slot.child(node, s));
				}
				return slots;
			}

			void cancelRest(Why why) {
				if (slots != null) for (Slot<T> s : slots) s.cancel(why);
			}

			@Override
			void stop(Why why) {
				cancelRest(why);
			}

			@Override
			void pause() {
				if (slots != null) for (Slot<T> s : slots) s.pause();
			}

			@Override
			void resume() {
				if (slots != null) for (Slot<T> s : slots) s.resume();
			}
		}
	}

	static final class All<T> extends Several<T, List<T>> {
		All(String name, List<? extends Step<T>> steps) {
			super(name, steps);
		}

		@Override
		Exec<List<T>> open(JobNode<List<T>> node) {
			return new Run(node) {
				private final List<T> results = new ArrayList<>();

				@Override
				Progress<List<T>> step() {
					List<Slot<T>> all = slots();
					int done = 0;
					Why stuck = null;
					for (Slot<T> s : all) {
						Progress<T> p = s.tick();
						if (p.isDone()) done++;
						else if (p.isFailed()) {
							cancelRest(Why.of(Why.CANCELLED, "another step of " + name + " failed"));
							return Progress.failed(p.why());
						} else if (p.isStuck() && stuck == null) stuck = p.why();
					}
					node.setProgress(all.isEmpty() ? 1 : (double) done / all.size());
					if (done < all.size()) return stuck != null ? Progress.stuck(stuck) : Progress.running();
					results.clear();
					for (Slot<T> s : all) results.add(s.node.ending().value());
					return Progress.done(List.copyOf(results));
				}
			};
		}
	}

	static final class Race<T> extends Several<T, T> {
		Race(String name, List<? extends Step<T>> steps) {
			super(name, steps);
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Run(node) {
				@Override
				Progress<T> step() {
					List<Slot<T>> all = slots();
					Why lastFailure = null, stuck = null;
					int failed = 0;
					for (Slot<T> s : all) {
						Progress<T> p = s.tick();
						if (p.isDone()) {
							cancelRest(Why.of(Why.CANCELLED, "lost the race to " + s.node.name()));
							return p;
						}
						if (p.isFailed()) {
							failed++;
							lastFailure = p.why();
						} else if (p.isStuck() && stuck == null) stuck = p.why();
					}
					if (all.isEmpty()) return Progress.failed(Why.of(Why.UNSUPPORTED, name + " has nothing to race"));
					if (failed == all.size()) return Progress.failed(lastFailure);
					// Stuck only when every runner still in it is.
					return stuck != null && failed + countStuck(all) == all.size() ? Progress.stuck(stuck) : Progress.running();
				}

				private int countStuck(List<Slot<T>> all) {
					int n = 0;
					for (Slot<T> s : all) if (s.node.state() == Job.State.STUCK) n++;
					return n;
				}
			};
		}
	}

	/** Transparent: runs {@code step} in the job it's reached in, or is done at once. */
	static final class When extends Step<Void> {
		private final BooleanSupplier condition;
		private final Step<?> step;

		When(BooleanSupplier condition, Step<?> step) {
			this.condition = condition;
			this.step = step;
		}

		@Override
		public String name() {
			return step.name();
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			List<String> p = new ArrayList<>(pills);
			p.add(0, "if");
			step.describe(out, depth, name, p);
		}

		@Override
		Exec<Void> open(JobNode<Void> node) {
			return condition.getAsBoolean() ? asVoid(step, node) : Exec.over(Progress.done());
		}
	}

	static final class Choose<T> extends Step<T> {
		private final String name;
		private final Supplier<? extends Step<T>> choice;

		Choose(String name, Supplier<? extends Step<T>> choice) {
			this.name = name;
			this.choice = choice;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			line(out, depth, name != null ? name : this.name, pills);
			line(out, depth + 1, "(chosen when reached)", List.of());
		}

		@Override
		Exec<T> open(JobNode<T> node) {
			return new Exec<>() {
				private @Nullable Slot<T> chosen;

				@Override
				Progress<T> step() {
					if (chosen == null) chosen = Slot.child(node, choice.get());
					return chosen.tick();
				}

				@Override
				void stop(Why why) {
					if (chosen != null) chosen.cancel(why);
				}

				@Override
				void pause() {
					if (chosen != null) chosen.pause();
				}

				@Override
				void resume() {
					if (chosen != null) chosen.resume();
				}
			};
		}
	}

	/** Transparent: runs {@code step} again and again in the same job, a round counted on its pill. */
	static final class Repeat<T> extends Step<Void> {
		private final Step<T> step;
		private final int times;
		private final @Nullable BooleanSupplier condition;

		Repeat(Step<T> step, int times, @Nullable BooleanSupplier condition) {
			this.step = step;
			this.times = times;
			this.condition = condition;
		}

		@Override
		public String name() {
			return step.name();
		}

		@Override
		void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
			List<String> p = new ArrayList<>(pills);
			p.add(0, condition == null ? "×" + times : "repeats");
			step.describe(out, depth, name, p);
		}

		@Override
		Exec<Void> open(JobNode<Void> node) {
			Rounds e = new Rounds(node);
			node.pill(() -> condition == null ? "round " + Math.max(1, e.round) + "/" + times : "round " + Math.max(1, e.round));
			return e;
		}

		private final class Rounds extends Exec<Void> {
			private final JobNode<Void> node;
			int round;
			private @Nullable Exec<Void> current;

			Rounds(JobNode<Void> node) {
				this.node = node;
			}

			@Override
			Progress<Void> step() {
				for (int hops = 0; hops < JobEngine.HOPS; hops++) {
					if (current == null) {
						if (condition != null ? !condition.getAsBoolean() : round >= times) return Progress.done();
						round++;
						if (round > 1) node.restart();
						current = asVoid(step, node);
					}
					Progress<Void> p = current.tick();
					if (!p.isDone()) return p;
					current = null;
				}
				return Progress.running();
			}

			@Override
			void stop(Why why) {
				if (current != null) current.cancel(why);
			}

			@Override
			void pause() {
				if (current != null) current.pause();
			}

			@Override
			void resume() {
				if (current != null) current.resume();
			}
		}
	}

	/** {@code step} run in {@code node}'s job, its result dropped: for the transparent steps, which produce nothing. */
	@SuppressWarnings("unchecked")
	static <X> Exec<Void> asVoid(Step<X> step, JobNode<Void> node) {
		Exec<X> inner = step.open((JobNode<X>) (JobNode<?>) node);
		return new Exec<>() {
			@Override
			Progress<Void> step() {
				Progress<X> p = inner.tick();
				return p.isDone() ? Progress.done() : p.cast();
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
		};
	}
}
