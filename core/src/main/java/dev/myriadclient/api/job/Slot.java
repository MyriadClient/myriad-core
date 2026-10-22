package dev.myriadclient.api.job;

/**
 * A step in its place under a parent (or as a root): its {@link JobNode}, what the Jobs window shows, and the
 * {@link Exec} that does the work. The slot turns each tick's {@link Progress} into the node's state, so policies and
 * {@code retry} that restart the step inside it keep one job across attempts.
 */
final class Slot<T> {
	final JobNode<T> node;
	private final Exec<T> exec;

	private Slot(JobNode<T> node, Exec<T> exec) {
		this.node = node;
		this.exec = exec;
		node.slot = this;
	}

	/** {@code step} as a new job under {@code parent} (null: a root of {@code engine}'s). */
	static <T> Slot<T> open(JobEngine engine, JobNode<?> parent, Object owner, Step<T> step) {
		JobNode<T> node = new JobNode<>(engine, parent, owner, step);
		Exec<T> exec;
		try {
			exec = step.open(node);
		} catch (Throwable t) {
			exec = Exec.over(Progress.failed(Why.of(t)));
		}
		Slot<T> slot = new Slot<>(node, exec);
		engine.started(node);
		return slot;
	}

	/** {@code step} as a child of {@code parent}'s job. */
	static <T> Slot<T> child(JobNode<?> parent, Step<T> step) {
		return open(parent.engine, parent, parent.owner, step);
	}

	Progress<T> tick() {
		if (!node.state().isActive()) return node.ending();
		if (node.isPaused()) return Progress.paused();
		node.beforeTick();
		Progress<T> p = exec.tick();
		node.report(p);
		return p;
	}

	boolean isOver() {
		return !node.state().isActive();
	}

	void cancel(Why why) {
		if (isOver()) return;
		exec.cancel(why);
		node.finish(Job.State.CANCELLED, null, why);
	}

	/** A parent paused: tell the steps under this one, unless it's paused itself (then they were told already). */
	void pause() {
		if (!isOver() && !node.isPaused()) exec.pause();
	}

	void resume() {
		if (!isOver() && !node.isPaused()) exec.resume();
	}

	/** The node's own pause, from the player or the API. */
	void pauseOwn() {
		exec.pause();
	}

	void resumeOwn() {
		exec.resume();
	}
}
