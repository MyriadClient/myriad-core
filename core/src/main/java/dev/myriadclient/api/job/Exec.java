package dev.myriadclient.api.job;

import org.jetbrains.annotations.Nullable;

/**
 * A step running: made by {@link Step#open} for one start, ticked by the {@link Slot} it runs in. Once {@link #step()}
 * returns done or failed it's over and has let go of what it held; {@link #cancel} ends it from outside, children first.
 */
abstract class Exec<T> {
	private @Nullable Progress<T> ended;

	/** One tick; a throw fails the step with {@link Why#error} after cancelling what's under it. */
	final Progress<T> tick() {
		if (ended != null) return ended;
		Progress<T> p;
		try {
			p = step();
		} catch (Throwable t) {
			Why why = Why.of(t);
			cancel(why);
			return ended;
		}
		if (p.isOver()) ended = p;
		return p;
	}

	/** Ends it from outside: what runs under it is cancelled with {@code why} and lets go. */
	final void cancel(Why why) {
		if (ended != null) return;
		ended = Progress.failed(why);
		try {
			stop(why);
		} catch (Throwable t) {
			JobEngine.LOG.error("A step failed to stop cleanly", t);
		}
	}

	final boolean isOver() {
		return ended != null;
	}

	/** One tick of work; called only while it isn't over. Returns done or failed once, having let go of what it held. */
	abstract Progress<T> step();

	/** Cancelled: cancel what runs under it, then let go of what it holds. */
	abstract void stop(Why why);

	/** The job was paused (or a parent of it): tell the running steps under it. */
	void pause() {
	}

	void resume() {
	}

	/** An exec that is already over, for steps that end the moment they're reached. */
	static <T> Exec<T> over(Progress<T> result) {
		return new Exec<>() {
			@Override
			Progress<T> step() {
				return result;
			}

			@Override
			void stop(Why why) {
			}
		};
	}
}
