package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobEngine;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Ties the job engine to the game: ticks it in {@code TickEvent.Pre} at {@link Priority#JOBS} (after held actions go
 * out, before the services act, so what a step asks for goes out this tick), ends every job on leaving the world, and
 * when a job ends has the services let go of what it asked for.
 */
public final class JobRunner implements JobEngine.Host {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Jobs");
	private final JobEngine engine = new JobEngine(this);

	public JobEngine engine() {
		return engine;
	}

	@Subscribe(priority = Priority.JOBS, inGame = true)
	private void onTick(TickEvent.Pre e) {
		engine.tick();
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
}
