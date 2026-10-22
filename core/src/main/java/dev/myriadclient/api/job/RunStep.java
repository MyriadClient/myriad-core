package dev.myriadclient.api.job;

import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.util.TickBudget;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A leaf: a {@link Step.Run} made fresh for each start and polled once a tick. */
final class RunStep<T> extends Step<T> {
	private final String name;
	private final Supplier<? extends Run<T>> factory;

	RunStep(String name, Supplier<? extends Run<T>> factory) {
		this.name = name;
		this.factory = factory;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	Exec<T> open(JobNode<T> node) {
		return new RunExec<>(node, factory.get());
	}

	@Override
	void describe(StringBuilder out, int depth, @Nullable String name, List<String> pills) {
		line(out, depth, name != null ? name : this.name, pills);
	}

	private static final class RunExec<T> extends Exec<T> implements JobContext {
		private final JobNode<T> node;
		private final Run<T> run;
		private boolean started;
		private int ticks;
		private List<JobEngine.Sink> sinks = List.of();

		RunExec(JobNode<T> node, Run<T> run) {
			this.node = node;
			this.run = run;
			node.setRunStatus(run::status);
		}

		@Override
		Progress<T> step() {
			ticks++;
			for (int i = 0; i < sinks.size(); i++) sinks.get(i).deliver();
			if (!started) {
				started = true;
				run.start(this);
			}
			Progress<T> p = run.tick(this);
			if (p == null) p = Progress.running();
			if (p.isOver()) release();
			return p;
		}

		@Override
		void stop(Why why) {
			try {
				if (started) run.cancel(this);
			} finally {
				release();
			}
		}

		@Override
		void pause() {
			if (started) run.pause(this);
		}

		@Override
		void resume() {
			if (started) run.resume(this);
		}

		private void release() {
			for (JobEngine.Sink s : List.copyOf(sinks)) s.unsubscribe();
			sinks = List.of();
		}

		// ---- JobContext -------------------------------------------------------------------------------------

		@Override
		public Job<?> job() {
			return node;
		}

		@Override
		public Object owner() {
			return node.owner;
		}

		@Override
		public int ticks() {
			return ticks;
		}

		@Override
		public TickBudget budget() {
			return node.engine.budget();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <E> Subscription on(Class<E> event, Consumer<? super E> handler) {
			JobEngine.Sink s = node.engine.listen(event, e -> ((Consumer<Object>) handler).accept(e));
			if (sinks.isEmpty()) sinks = new ArrayList<>(1);
			sinks.add(s);
			return s;
		}

		@Override
		public void progress(int done, int total) {
			node.setProgress(total <= 0 ? Double.NaN : Math.clamp((double) done / total, 0, 1));
		}

		@Override
		public void progress(double fraction) {
			node.setProgress(Double.isNaN(fraction) ? fraction : Math.clamp(fraction, 0, 1));
		}

		@Override
		public void status(String text) {
			node.setStatus(text);
		}

		@Override
		public boolean paused() {
			return node.isPaused();
		}

		@Override
		public void log(String message) {
			node.engine.log(node, message);
		}
	}
}
