package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobEngine;
import dev.myriadclient.api.job.JobEvent;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.event.Profiler;
import dev.myriadclient.impl.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ties the job engine to the game: ticks it in {@code TickEvent.Pre} at {@link Priority#JOBS} (after held actions go
 * out, before the services act, so what a step asks for goes out this tick), ends every job on leaving the world, and
 * when a job ends has the services let go of what it asked for. It tells the player about a root job that fails with
 * nothing to take the failure, or stays stuck for {@link #STUCK_NOTICE_TICKS} (once, until it gets going again; not
 * for being out of reach, which moving puts right and a printer waits on all the time), and keeps the roots that ended
 * in the last few seconds for the Jobs window.
 */
public final class JobRunner implements JobEngine.Host {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Jobs");
	/** Five seconds stuck before the player is told: shorter waits (a container opening, a block to break) are normal. */
	static final int STUCK_NOTICE_TICKS = 100;
	/** How long an ended root stays listed in the Jobs window. */
	public static final long ENDED_MS = 10_000;
	private static final int ENDED_MAX = 20;

	/** A root job that ended, and when. */
	public record Ended(Job<?> job, long atMs) {
	}

	private final JobEngine engine = new JobEngine(this);
	/** Per stuck root: its tick count when it got stuck, negative once the player was told. */
	private final Map<Job<?>, Integer> stuckSince = new HashMap<>();
	private final Deque<Ended> ended = new ArrayDeque<>();

	public JobEngine engine() {
		return engine;
	}

	@Subscribe(priority = Priority.JOBS, inGame = true)
	private void onTick(TickEvent.Pre e) {
		engine.tick();
		noticeStuck();
	}

	/** A root stuck for five seconds is named once in a notice that replaces itself, not one per tick. */
	private void noticeStuck() {
		List<Job<?>> running = engine.running();
		stuckSince.keySet().retainAll(running);
		for (Job<?> root : running) {
			Job<?> stuck = root.state() == Job.State.PAUSED ? null : JobText.stuck(root);
			if (stuck == null || stuck.why() == null || stuck.why().is(Why.OUT_OF_REACH)) {
				stuckSince.remove(root);
				continue;
			}
			Integer since = stuckSince.putIfAbsent(root, root.ticks());
			if (since == null || since < 0 || root.ticks() - since < STUCK_NOTICE_TICKS) continue;
			stuckSince.put(root, -1);
			Myriad.notifications().send(JobText.owner(root), root.name() + " stuck: " + stuck.why(), Notifications.Level.WARNING, 6000,
				"myriad:job_stuck_" + root.id());
		}
	}

	/** Roots that ended in the last ten seconds, newest first. */
	public List<Ended> ended() {
		long now = Clock.millis();
		ended.removeIf(x -> now - x.atMs() > ENDED_MS);
		return List.copyOf(ended);
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		engine.cancelAll(Why.of(Why.LEFT_WORLD, ""));
	}

	@Override
	public void release(Job<?> job) {
		Myriad.placement().cancel(job);
		Myriad.breaking().cancel(job);
		Myriad.building().stop(job);
		Myriad.inventory().release(job);
		Myriad.inventory().giveBack(job);
		Myriad.inventory().unmask(job);
		Myriad.tickSpeed().clear(job);
	}

	@Override
	public <E> Subscription listen(Class<E> type, Consumer<? super E> handler) {
		return Myriad.events().listen(type, handler);
	}

	@Override
	public void post(Object event) {
		// Every root that fails or is cancelled says why in the log: notices are toasts, gone a moment later.
		if (event instanceof JobEvent.Finished f && f.isRoot()) {
			if (f.job().state() != Job.State.DONE) LOG.info("{} ended: {}", f.job(), f.job().why());
			ended.addFirst(new Ended(f.job(), Clock.millis()));
			while (ended.size() > ENDED_MAX) ended.removeLast();
		}
		if (Myriad.events().hasListeners(event.getClass())) Myriad.events().post(event);
	}

	@Override
	public void failed(Job<?> root, Why why) {
		String owner = root.owner() instanceof Module m ? m.name() : String.valueOf(root.owner());
		Myriad.notifications().warn(owner, root.name() + " failed: " + why);
	}

	@Override
	public void log(Job<?> job, String message) {
		LOG.info("{}: {}", job, message);
	}

	@Override
	public void ticked(Job<?> root, long nanos, long budgetNanos) {
		Profiler profiler = ((MyriadEventBus) Myriad.events()).profiler();
		if (profiler == null) return;
		String addon = root.owner() instanceof Identified i ? i.id().namespace() : "myriad";
		profiler.recordJob(root, addon, nanos, budgetNanos);
	}
}
