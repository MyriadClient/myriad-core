package dev.myriadclient.api.job;

import org.jetbrains.annotations.ApiStatus;

/**
 * Jobs starting, changing and finishing, on the bus: for UIs, and for addons that react to another addon's work (a
 * Discord addon posting when a search lands). Fired on the render thread, on the job's tick.
 */
public abstract class JobEvent {
	private final Job<?> job;

	JobEvent(Job<?> job) {
		this.job = job;
	}

	public Job<?> job() {
		return job;
	}

	/** Whether this is a root job (one {@link Jobs#start} made) rather than a step under one. */
	public boolean isRoot() {
		return job.parent() == null;
	}

	/** A job (root or child) started. */
	public static final class Started extends JobEvent {
		@ApiStatus.Internal
		public Started(Job<?> job) {
			super(job);
		}
	}

	/** Its state, reason, status or progress changed. At most once per job per tick. */
	public static final class Changed extends JobEvent {
		private final Job.State before;

		@ApiStatus.Internal
		public Changed(Job<?> job, Job.State before) {
			super(job);
			this.before = before;
		}

		/** The state at the start of the tick it changed in. */
		public Job.State before() {
			return before;
		}
	}

	/** Done, failed or cancelled; {@link Job#state()} and {@link Job#why()} say which. */
	public static final class Finished extends JobEvent {
		@ApiStatus.Internal
		public Finished(Job<?> job) {
			super(job);
		}
	}
}
