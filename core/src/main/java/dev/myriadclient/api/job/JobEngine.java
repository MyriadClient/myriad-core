package dev.myriadclient.api.job;

import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.util.Threads;
import dev.myriadclient.api.util.TickBudget;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The job runner's engine: the tree of jobs, ticked once per tick by core, with no game behind it so the semantics are
 * tested on their own. Core wires it to the game through a {@link Host} (the bus, the services, notices) and ticks it
 * in {@code TickEvent.Pre} at {@code Priority.JOBS}. Addons use {@code Myriad.jobs()}; nothing here is theirs to call.
 */
@ApiStatus.Internal
public final class JobEngine implements Jobs {
	static final Logger LOG = LoggerFactory.getLogger("Myriad/Jobs");
	/** How long each root job may work per tick before its steps are told to carry on next tick. */
	static final long BUDGET_NANOS = 2_000_000;
	/** How many steps a sequence may move through in one tick while they finish at once. */
	static final int HOPS = 64;

	/** What the engine needs from the game. */
	@ApiStatus.Internal
	public interface Host {
		/** The services let go of what {@code job} asked for (placements, breaks, holds, borrows). */
		void release(Job<?> job);

		<E> Subscription listen(Class<E> type, Consumer<? super E> handler);

		void post(Object event);

		/** A root failed and nothing took the failure: tell the player. */
		void failed(Job<?> root, Why why);

		void log(Job<?> job, String message);

		/** A root job's tick took {@code nanos}, against {@code budgetNanos} (for the profiler). */
		default void ticked(Job<?> root, long nanos, long budgetNanos) {
		}
	}

	private final Host host;
	private final List<Slot<?>> roots = new ArrayList<>();
	private final List<Slot<?>> starting = new ArrayList<>();
	private final Map<Integer, JobNode<?>> active = new HashMap<>();
	private final List<JobNode<?>> changed = new ArrayList<>();
	private final Map<Class<?>, Channel> channels = new HashMap<>();
	private final TickBudget budget = TickBudget.of(BUDGET_NANOS);
	private int nextId = 1;
	private boolean ticking;

	public JobEngine(Host host) {
		this.host = host;
	}

	// ---- Jobs -----------------------------------------------------------------------------------------------------

	@Override
	public <T> Job<T> start(Object owner, Step<T> step) {
		Threads.assertRender();
		Slot<T> slot = Slot.open(this, null, owner, step);
		(ticking ? starting : roots).add(slot);
		return slot.node;
	}

	@Override
	public List<Job<?>> running() {
		List<Job<?>> out = new ArrayList<>();
		for (Slot<?> s : roots) if (!s.isOver()) out.add(s.node);
		for (Slot<?> s : starting) if (!s.isOver()) out.add(s.node);
		return out;
	}

	@Override
	public List<Job<?>> of(Object owner) {
		List<Job<?>> out = new ArrayList<>();
		for (Job<?> j : running()) if (j.owner() == owner) out.add(j);
		return out;
	}

	@Override
	public Optional<Job<?>> byId(int id) {
		return Optional.ofNullable(active.get(id));
	}

	@Override
	public void cancel(Object owner) {
		// A module turning off is why its jobs end, as far as anyone watching is concerned.
		Why why = owner instanceof Module m && !m.isEnabled() ? Why.of(Why.OWNER_OFF, m.name() + " turned off") : Why.of(Why.CANCELLED, "");
		for (Slot<?> s : List.copyOf(allRoots())) if (s.node.owner == owner) s.cancel(why);
	}

	@Override
	public boolean isBusy(Object owner) {
		return !of(owner).isEmpty();
	}

	@Override
	public Job<Void> later(Object owner, int ticks, Runnable action) {
		return start(owner, Step.delay(Math.max(0, ticks)).then(Step.run("Run", action)).named("Later"));
	}

	@Override
	public Job<Void> every(Object owner, int intervalTicks, Runnable action) {
		int interval = Math.max(1, intervalTicks);
		return start(owner, Step.sequence("Every " + interval + " ticks", Step.run("Run", action), Step.delay(interval)).repeatWhile(() -> true));
	}

	// ---- driven by core ---------------------------------------------------------------------------------------------

	/** One tick of every root job, oldest first, then the change events. */
	public void tick() {
		ticking = true;
		try {
			for (int i = 0; i < roots.size(); i++) {
				Slot<?> s = roots.get(i);
				if (s.isOver()) continue;
				budget.reset();
				long t0 = System.nanoTime();
				s.tick();
				s.node.lastNanos = System.nanoTime() - t0;
				host.ticked(s.node, s.node.lastNanos, BUDGET_NANOS);
				if (s.node.state() == Job.State.FAILED) unhandled(s.node);
			}
		} finally {
			ticking = false;
			roots.removeIf(Slot::isOver);
			roots.addAll(starting);
			starting.clear();
			flushChanges();
		}
	}

	/** Ends every job: leaving the world. */
	public void cancelAll(Why why) {
		for (Slot<?> s : List.copyOf(allRoots())) s.cancel(why);
		roots.removeIf(Slot::isOver);
		starting.removeIf(Slot::isOver);
	}

	private List<Slot<?>> allRoots() {
		List<Slot<?>> all = new ArrayList<>(roots.size() + starting.size());
		all.addAll(roots);
		all.addAll(starting);
		return all;
	}

	private void unhandled(JobNode<?> root) {
		Why why = root.why();
		if (why == null || why.isCancellation() || root.step().handlesFailure() || root.watched) return;
		try {
			host.failed(root, why);
		} catch (Throwable t) {
			LOG.error("Couldn't report {} failing", root, t);
		}
	}

	private void flushChanges() {
		if (changed.isEmpty()) return;
		List<JobNode<?>> now = List.copyOf(changed);
		changed.clear();
		for (JobNode<?> n : now) {
			Job.State before = n.takeChange();
			if (before != null) post(new JobEvent.Changed(n, before));
		}
	}

	// ---- for nodes and steps ----------------------------------------------------------------------------------------

	int nextId() {
		return nextId++;
	}

	TickBudget budget() {
		return budget;
	}

	void started(JobNode<?> node) {
		active.put(node.id(), node);
		post(new JobEvent.Started(node));
	}

	void changed(JobNode<?> node) {
		changed.add(node);
	}

	void finished(JobNode<?> node) {
		active.remove(node.id());
		if (node.suspends) Modules.resume(node);
		try {
			host.release(node);
		} catch (Throwable t) {
			LOG.error("Services failed to let go of {}", node, t);
		}
		post(new JobEvent.Finished(node));
	}

	void log(Job<?> job, String message) {
		host.log(job, message);
	}

	private void post(Object event) {
		try {
			host.post(event);
		} catch (Throwable t) {
			LOG.error("A job event handler threw", t);
		}
	}

	/**
	 * Hears {@code type} for one step: one bus listener per event class, shared by every step that wants it, queues
	 * each event (on whatever thread fired it) for the step to read on its tick.
	 */
	Sink listen(Class<?> type, Consumer<Object> handler) {
		Channel c = channels.computeIfAbsent(type, t -> {
			Channel made = new Channel();
			made.bus = host.listen(t, e -> {
				for (Sink s : made.sinks) s.queue.add(e);
			});
			return made;
		});
		Sink s = new Sink(handler);
		c.sinks.add(s);
		s.release = () -> {
			c.sinks.remove(s);
			if (c.sinks.isEmpty() && channels.get(type) == c) {
				channels.remove(type);
				c.bus.unsubscribe();
			}
		};
		return s;
	}

	private static final class Channel {
		final List<Sink> sinks = new CopyOnWriteArrayList<>();
		Subscription bus;
	}

	/** One step's queue of one event class. */
	static final class Sink implements Subscription {
		final Queue<Object> queue = new ConcurrentLinkedQueue<>();
		final Consumer<Object> handler;
		Runnable release;
		private boolean released;

		Sink(Consumer<Object> handler) {
			this.handler = handler;
		}

		void deliver() {
			Object e;
			while (!released && (e = queue.poll()) != null) handler.accept(e);
		}

		@Override
		public void unsubscribe() {
			if (released) return;
			released = true;
			queue.clear();
			release.run();
		}
	}
}
