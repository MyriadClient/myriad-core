package dev.myriadclient.api.job;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A description of work that produces a {@code T}, spread over game ticks: open a chest and take what's needed, build
 * a wall, fly a route. A step is a value, like {@code Placement.Options} or a {@code Blueprint}: immutable, reusable,
 * printable, and nothing happens until {@link Jobs#start Myriad.jobs().start(owner, step)} runs it as a {@link Job}.
 * <p>
 * Steps come from three places: the leaves here ({@link #run}, {@link #delay}, {@link #until}, {@link #await}...),
 * {@link Steps} (the services as steps: place, break, open, take, build) and your own {@link Run} ({@link #of}). They
 * compose with {@link #then}, {@link #sequence}, {@link #all}, {@link #race} and {@link #forEach}, and take policies
 * ({@link #timeout}, {@link #retry}, {@link #whenStuck}, {@link #recover}, {@link #suspending}, {@link #finallyDo})
 * that apply to the step and everything under it. In the Jobs window a sequence shows its current step, and policies
 * show as pills on the job they wrap, never as jobs of their own.
 *
 * <pre>{@code
 * Step<Integer> restock = Steps.openContainer(chest, 20, Placement.Options.forServer())
 *     .then(view -> Steps.take(view, ItemInfo::isFood, Containers.Transfer.upTo(32)))
 *     .then(moved -> Steps.closeContainer().then(Step.supply("Count", () -> moved)))
 *     .named("Restock food")
 *     .timeout(20 * 30)
 *     .retry(2);
 * Myriad.jobs().start(this, restock).result().thenAccept(moved -> info("Took " + moved));
 * }</pre>
 *
 * A step is finite, goal-directed work. A module is a continuous controller (Kill Aura, a bounce loop). Modules start
 * jobs; jobs may {@linkplain #suspending hold modules off} while they run. Steps run on the render thread only.
 *
 * @param <T> what the step produces ({@code Void} for "it's done")
 */
@ApiStatus.NonExtendable
public abstract class Step<T> {
	/** Core makes steps; addons compose them and write {@link Run}s. */
	Step() {
	}

	// ---- leaves -------------------------------------------------------------------------------------------------

	/** Runs {@code action} on one tick, then is done. */
	public static Step<Void> run(String name, Runnable action) {
		Objects.requireNonNull(action);
		return of(name, () -> ctx -> {
			action.run();
			return Progress.done();
		});
	}

	/** Produces {@code value.get()} on one tick. */
	public static <T> Step<T> supply(String name, Supplier<T> value) {
		Objects.requireNonNull(value);
		return of(name, () -> ctx -> Progress.done(value.get()));
	}

	/** Waits {@code ticks} ticks (0: done at once). (Not {@code wait}: that's {@link Object#wait}.) */
	public static Step<Void> delay(int ticks) {
		return of("Wait " + ticks + (ticks == 1 ? " tick" : " ticks"), () -> ctx -> ctx.ticks() > ticks ? Progress.done() : Progress.running());
	}

	/** Waits until {@code condition} holds, checked once a tick; fails with {@link Why#TIMEOUT} after {@code timeoutTicks}. */
	public static Step<Void> until(String name, BooleanSupplier condition, int timeoutTicks) {
		Objects.requireNonNull(condition);
		return of(name, () -> ctx -> condition.getAsBoolean() ? Progress.done()
			: ctx.ticks() > timeoutTicks ? Progress.failed(Why.of(Why.TIMEOUT, name + ": after " + timeoutTicks + " ticks")) : Progress.running());
	}

	/**
	 * Starts {@code future} when reached and waits for it: a service call that answers later ({@code Containers.open},
	 * a placement's {@code result()}). Fails with the future's failure ({@link Why#of(Throwable)}), or
	 * {@link Why#TIMEOUT} after {@code timeoutTicks}.
	 */
	public static <T> Step<T> await(String name, Supplier<? extends CompletableFuture<T>> future, int timeoutTicks) {
		Objects.requireNonNull(future);
		return of(name, () -> new Run<>() {
			private CompletableFuture<T> f;

			@Override
			public void start(JobContext ctx) {
				f = future.get();
			}

			@Override
			public Progress<T> tick(JobContext ctx) {
				if (f.isDone()) {
					try {
						return Progress.done(f.join());
					} catch (RuntimeException e) {
						return Progress.failed(Why.of(e));
					}
				}
				return ctx.ticks() > timeoutTicks ? Progress.failed(Why.of(Why.TIMEOUT, name + ": after " + timeoutTicks + " ticks")) : Progress.running();
			}
		});
	}

	/**
	 * Your own step: {@code run} makes a fresh {@link Run} each time the step starts (so one step value can run twice,
	 * even at once), and the runner polls it once a tick.
	 */
	public static <T> Step<T> of(String name, Supplier<? extends Run<T>> run) {
		return new RunStep<>(Objects.requireNonNull(name), Objects.requireNonNull(run));
	}

	/** Fails at once with {@code why}: the "else" of a {@link #choose} or {@link #recover}. */
	public static <T> Step<T> fail(Why why) {
		Objects.requireNonNull(why);
		return of("Fail: " + why, () -> ctx -> Progress.failed(why));
	}

	/** Does nothing, successfully. */
	public static Step<Void> nothing() {
		return of("Nothing", () -> ctx -> Progress.done());
	}

	// ---- composition --------------------------------------------------------------------------------------------

	/** The steps in order, each starting the tick the one before finishes (or the same tick, for instant ones). */
	public static Step<Void> sequence(String name, Step<?>... steps) {
		return Composites.sequence(name, List.of(steps));
	}

	/** All at once; done when every one is, with their results in order; the first failure fails it and cancels the rest. */
	public static <T> Step<List<T>> all(String name, List<? extends Step<T>> steps) {
		return new Composites.All<>(name, List.copyOf(steps));
	}

	/** All at once; the first to finish wins and the rest are cancelled. Fails only when every one has failed. */
	public static <T> Step<T> race(String name, List<? extends Step<T>> steps) {
		return new Composites.Race<>(name, List.copyOf(steps));
	}

	/** {@code body} for each item, in order ({@code items} is read when the step starts). */
	public static <X> Step<Void> forEach(String name, Supplier<? extends Iterable<X>> items, Function<? super X, ? extends Step<?>> body) {
		return new Composites.ForEach<>(name, items, body);
	}

	/** {@code step} if {@code condition} holds when reached, else nothing. Shows as {@code step} itself. */
	public static Step<Void> when(BooleanSupplier condition, Step<?> step) {
		return new Composites.When(condition, step);
	}

	/** The step {@code choice} picks when reached: for decisions that depend on the world then, not when the step was built. */
	public static <T> Step<T> choose(String name, Supplier<? extends Step<T>> choice) {
		return new Composites.Choose<>(name, choice);
	}

	/** After this, {@code next}. */
	public <R> Step<R> then(Step<R> next) {
		return Composites.then(this, Objects.requireNonNull(next));
	}

	/** After this, the step {@code next} makes from this one's result. */
	public <R> Step<R> then(Function<? super T, ? extends Step<R>> next) {
		return Composites.then(this, Objects.requireNonNull(next));
	}

	/** After this, {@code action} with the result, on the same tick, then done with the same result. A throw fails the step. */
	public Step<T> peek(Consumer<? super T> action) {
		return new Policies.Peek<>(this, action);
	}

	/** This step again, {@code times} in all, each after the one before. */
	public Step<Void> repeat(int times) {
		return new Composites.Repeat<>(this, times, null);
	}

	/** This step again while {@code condition} holds (checked before each run, the first too). */
	public Step<Void> repeatWhile(BooleanSupplier condition) {
		return new Composites.Repeat<>(this, -1, Objects.requireNonNull(condition));
	}

	// ---- policies: each returns a new step; the policy covers this step and everything under it -------------------

	/** The name shown in the Jobs window and the tree (default: what the factory gave it). */
	public Step<T> named(String name) {
		return new Policies.Named<>(this, Objects.requireNonNull(name), null);
	}

	/** A name that changes as it runs ("Visiting chest 3 of 8"). */
	public Step<T> named(Supplier<String> live) {
		return new Policies.Named<>(this, null, Objects.requireNonNull(live));
	}

	/** Fails with {@link Why#TIMEOUT} if still running or stuck after {@code ticks} (paused ticks don't count). */
	public Step<T> timeout(int ticks) {
		return new Policies.Timeout<>(this, ticks);
	}

	/**
	 * If stuck on {@code code} for {@code ticks} (0: at once), runs {@code remedy} in its place and then carries on with
	 * this step: "no cobblestone → fetch some from the chest, then carry on building". The step is paused while the
	 * remedy runs, which shows as its child. If the remedy fails, so does this step; if it's still stuck on the same code
	 * after, the remedy runs again after another {@code ticks}. A remedy that gets stuck itself is left to a
	 * {@code whenStuck} further out.
	 */
	public Step<T> whenStuck(Why.Code code, int ticks, Function<Why, ? extends Step<?>> remedy) {
		return new Policies.WhenStuck<>(this, Objects.requireNonNull(code), ticks, remedy);
	}

	/** {@link #whenStuck(Why.Code, int, Function)} for any reason. */
	public Step<T> whenStuck(int ticks, Function<Why, ? extends Step<?>> remedy) {
		return new Policies.WhenStuck<>(this, null, ticks, remedy);
	}

	/** If stuck for {@code ticks} in a row, gives up with the stuck reason as the failure (a stuck step otherwise waits forever). */
	public Step<T> stuckFor(int ticks) {
		return new Policies.StuckFor<>(this, ticks);
	}

	/**
	 * If this fails, {@code alternative} runs instead and its result stands. Not for cancellations (the player, a lost
	 * race, the owner turning off, leaving the world).
	 */
	public Step<T> recover(Function<Why, ? extends Step<T>> alternative) {
		return new Policies.Recover<>(this, null, alternative);
	}

	/** Like {@link #recover(Function)}, only for failures {@code when} accepts. */
	public Step<T> recover(Predicate<Why> when, Function<Why, ? extends Step<T>> alternative) {
		return new Policies.Recover<>(this, Objects.requireNonNull(when), alternative);
	}

	/** If this fails, starts it over the next tick, up to {@code times} more times (after its {@link #finallyDo} ran). Not for cancellations. */
	public Step<T> retry(int times) {
		return new Policies.Retry<>(this, times, null);
	}

	/** Like {@link #retry(int)}, only for failures {@code when} accepts. */
	public Step<T> retry(int times, Predicate<Why> when) {
		return new Policies.Retry<>(this, times, Objects.requireNonNull(when));
	}

	/** Checked when reached: fails with {@code otherwise} unless {@code condition} holds. */
	public Step<T> require(BooleanSupplier condition, Why otherwise) {
		return new Policies.Require<>(this, Objects.requireNonNull(condition), Objects.requireNonNull(otherwise));
	}

	/**
	 * Holds these modules off while this runs ({@code Modules.suspend}, by id: another addon's needn't be a dependency);
	 * they come back when it ends, whatever way, if the player has them on.
	 */
	public Step<T> suspending(MyriadId... modules) {
		return new Policies.Suspending<>(this, List.of(modules), List.of());
	}

	@SafeVarargs
	public final Step<T> suspending(Class<? extends Module>... modules) {
		return new Policies.Suspending<>(this, List.of(), List.of(modules));
	}

	/** Runs when this ends: done, failed or cancelled. For letting go of what the step took. */
	public Step<T> finallyDo(Runnable action) {
		return new Policies.Finally<>(this, Objects.requireNonNull(action));
	}

	/** Runs with the result when this is done (a callback, not a step: for notifications and bookkeeping). */
	public Step<T> onDone(Consumer<? super T> action) {
		return new Policies.OnDone<>(this, Objects.requireNonNull(action));
	}

	/**
	 * Runs with the reason when this fails for good (after any {@link #retry} and {@link #recover} inside it), not when
	 * it's cancelled. A root step with this (or a {@code recover}) shows no failure notice.
	 */
	public Step<T> onFailed(Consumer<Why> action) {
		return new Policies.OnFailed<>(this, Objects.requireNonNull(action));
	}

	// ---- about the step -----------------------------------------------------------------------------------------

	public abstract String name();

	/** The tree, one line per step, policies as pills: what the Jobs window shows for a job that hasn't started. */
	@Override
	public final String toString() {
		StringBuilder out = new StringBuilder();
		describe(out, 0, null, new ArrayList<>());
		return out.toString().stripTrailing();
	}

	/** Runs this step in {@code node}'s job: the work for one start. */
	abstract Exec<T> open(JobNode<T> node);

	/** Whether a failure of this step is taken care of ({@code onFailed}, an unconditional {@code recover}). */
	boolean handlesFailure() {
		return false;
	}

	/** Appends this step's lines: {@code name} overrides its own, {@code pills} come from policies around it. */
	abstract void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills);

	static void line(StringBuilder out, int depth, String name, List<String> pills) {
		out.append("  ".repeat(depth)).append(name);
		if (!pills.isEmpty()) out.append("  [").append(String.join(", ", pills)).append(']');
		out.append('\n');
	}

	/**
	 * What a step of your own does while it runs. One instance per start, polled once a tick by the runner at
	 * {@code Priority.JOBS}, before the services send this tick's actions, so what you ask them for goes out in this
	 * tick. Pass {@code ctx.job()} as the owner to every service, so cancelling the job cancels what it asked for.
	 * Never subscribe to the bus yourself: {@link JobContext#on} subscribes for the life of the step and delivers on
	 * your tick.
	 *
	 * <pre>{@code
	 * final class FlyLeg implements Step.Run<Void> {
	 *     public Progress<Void> tick(JobContext ctx) {
	 *         if (flight.state() == Flight.State.STUCK) return Progress.stuck(flight.why());
	 *         if (arrived()) return Progress.done();
	 *         flight.heading(headingToCorner());
	 *         ctx.progress(flown(), legLength());
	 *         return Progress.running();
	 *     }
	 *     public void cancel(JobContext ctx) { flight.land(); }
	 * }
	 * }</pre>
	 * A throw from any of these fails the step ({@link Why#ERROR}, or the reason of a thrown {@link Why#asException()}).
	 * Only gains {@code default} methods in later versions.
	 */
	public interface Run<T> {
		/** The step started (this tick, before the first {@link #tick}). */
		default void start(JobContext ctx) {
		}

		/** One tick of work: what to tell the services, and how it stands. */
		Progress<T> tick(JobContext ctx);

		/** The player (or a parent) paused the job: stop asking the services for things; hold what you hold. */
		default void pause(JobContext ctx) {
		}

		default void resume(JobContext ctx) {
		}

		/** The step is being cancelled (by the player, a failure above, a race lost, the owner turning off): let go. */
		default void cancel(JobContext ctx) {
		}

		/** A live detail for the Jobs window ("leg 3 of 12", "waiting for the chest to load"), or null. */
		default @Nullable String status() {
			return null;
		}
	}
}
