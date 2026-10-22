package dev.myriadclient.api.job;

import dev.myriadclient.api.module.Module;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** A job: the state the Jobs window shows for one {@link Slot}. Render thread only, like the rest of the runner. */
final class JobNode<T> implements Job<T> {
	final JobEngine engine;
	final Object owner;
	private final int id;
	private final @Nullable JobNode<?> parent;
	private final Step<T> step;
	Slot<T> slot;

	private String name;
	private @Nullable Supplier<String> liveName;
	private final List<JobNode<?>> children = new ArrayList<>(1);
	private State state = State.QUEUED;
	private @Nullable Why why;
	private boolean paused;
	private int ticks;
	private double progress = Double.NaN;
	private @Nullable String status;
	private @Nullable Supplier<String> runStatus;
	private final CompletableFuture<T> result = new CompletableFuture<>();
	private List<Supplier<String>> pills = List.of();
	private List<Runnable> keepers = List.of();
	private @Nullable Progress<T> ending;
	/** Whether it holds modules off ({@code suspending}), so ending it lets them go. */
	boolean suspends;
	/** Whether anything waited on {@link #result()} when it ended (read before completing it runs and drops them). */
	boolean watched;
	/** Nanoseconds its last tick took (roots), for the profiler and the Jobs window. */
	long lastNanos;
	private boolean changed;
	private State before = State.QUEUED;

	JobNode(JobEngine engine, @Nullable JobNode<?> parent, Object owner, Step<T> step) {
		this.engine = engine;
		this.parent = parent;
		this.owner = owner;
		this.step = step;
		this.id = engine.nextId();
		this.name = step.name();
		if (parent != null) parent.children.add(this);
	}

	// ---- Job ----------------------------------------------------------------------------------------------------

	@Override
	public int id() {
		return id;
	}

	@Override
	public String name() {
		Supplier<String> live = liveName;
		if (live == null) return name;
		try {
			String s = live.get();
			return s == null ? name : s;
		} catch (RuntimeException e) {
			return name;
		}
	}

	@Override
	public Object owner() {
		return owner;
	}

	@Override
	public @Nullable Job<?> parent() {
		return parent;
	}

	@Override
	public List<Job<?>> children() {
		return List.copyOf(children);
	}

	@Override
	public Step<T> step() {
		return step;
	}

	@Override
	public State state() {
		return state;
	}

	@Override
	public @Nullable Why why() {
		return why;
	}

	@Override
	public @Nullable String status() {
		if (status != null) return status;
		Supplier<String> s = runStatus;
		if (s == null) return null;
		try {
			return s.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public double progress() {
		return progress;
	}

	@Override
	public int ticks() {
		return ticks;
	}

	@Override
	public List<String> pills() {
		if (pills.isEmpty()) return List.of();
		List<String> out = new ArrayList<>(pills.size());
		for (Supplier<String> p : pills) {
			String s = p.get();
			if (s != null && !s.isEmpty()) out.add(s);
		}
		return out;
	}

	@Override
	public CompletableFuture<T> result() {
		return result;
	}

	@Override
	public void pause() {
		if (!state.isActive() || paused) return;
		boolean told = ancestorPaused();
		paused = true;
		if (!told) slot.pauseOwn();
		set(State.PAUSED, why);
	}

	@Override
	public void resume() {
		if (!paused) return;
		paused = false;
		if (!ancestorPaused()) slot.resumeOwn();
		set(State.RUNNING, null);
	}

	@Override
	public void cancel() {
		slot.cancel(Why.of(Why.CANCELLED, ""));
	}

	// ---- for the runner and the steps ---------------------------------------------------------------------------

	boolean isPaused() {
		return paused;
	}

	private boolean ancestorPaused() {
		for (JobNode<?> p = parent; p != null; p = p.parent) if (p.paused) return true;
		return false;
	}

	void rename(String name) {
		this.name = Objects.requireNonNull(name);
		liveName = null;
		touch();
	}

	void rename(Supplier<String> live) {
		liveName = live;
		touch();
	}

	/** A policy's pill. Policies open outermost first, so each goes in front: the pills read in the order they were written. */
	void pill(Supplier<String> text) {
		if (pills.isEmpty()) pills = new ArrayList<>(2);
		pills.add(0, text);
	}

	/** Runs {@code action} at the start of each of this job's ticks while it runs (a borrow kept for the steps under it). */
	void keep(Runnable action) {
		if (keepers.isEmpty()) keepers = new ArrayList<>(1);
		keepers.add(action);
	}

	void setProgress(double fraction) {
		if (Double.compare(fraction, progress) == 0) return;
		progress = fraction;
		touch();
	}

	void setStatus(@Nullable String text) {
		if (Objects.equals(text, status)) return;
		status = text;
		touch();
	}

	void setRunStatus(@Nullable Supplier<String> status) {
		runStatus = status;
	}

	/** A new start in the same job (a retry): what the last attempt said about itself goes. */
	void restart() {
		status = null;
		runStatus = null;
		progress = Double.NaN;
		touch();
	}

	void beforeTick() {
		ticks++;
		for (int i = 0; i < keepers.size(); i++) keepers.get(i).run();
	}

	void report(Progress<T> p) {
		if (p.isDone()) finish(State.DONE, p.value(), null);
		else if (p.isFailed()) finish(State.FAILED, null, p.why());
		else if (p.isStuck()) set(State.STUCK, p.why());
		else set(State.RUNNING, null);
	}

	private void set(State s, @Nullable Why w) {
		if (s == state && Objects.equals(w, why)) return;
		touch();
		state = s;
		why = w;
	}

	void touch() {
		if (changed || !state.isActive()) return;
		changed = true;
		before = state;
		engine.changed(this);
	}

	/** Called once per tick for a node that {@link #touch}ed: whether it still has a change to report, and from what. */
	@Nullable State takeChange() {
		if (!changed) return null;
		changed = false;
		return state.isActive() ? before : null;
	}

	/** Over: the state is final, the future completes, the parent forgets it, and the services let go of what it asked for. */
	void finish(State s, @Nullable T value, @Nullable Why w) {
		if (!state.isActive()) return;
		state = s;
		why = w;
		paused = false;
		ending = s == State.DONE ? Progress.done(value) : Progress.failed(w == null ? Why.of(Why.CANCELLED, "") : w);
		if (parent != null) parent.children.remove(this);
		watched = result.getNumberOfDependents() > 0;
		engine.finished(this);
		if (s == State.DONE) result.complete(value);
		else result.completeExceptionally(new JobException(ending.why()));
	}

	Progress<T> ending() {
		return ending;
	}

	/** "Printer › Build wall › Placing": the owner, then each job from the root down (a root named like its owner once). */
	@Override
	public String toString() {
		String o = ownerName(owner);
		StringBuilder path = new StringBuilder();
		for (JobNode<?> n = this; n != null; n = n.parent) {
			String nm = n.name();
			if (n.parent == null && nm.equals(o)) break;
			path.insert(0, path.isEmpty() ? nm : nm + " › ");
		}
		return path.isEmpty() ? o : o + " › " + path;
	}

	static String ownerName(Object owner) {
		return owner instanceof Module m ? m.name() : String.valueOf(owner);
	}
}
