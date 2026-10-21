package dev.myriadclient.impl.service;

/**
 * Work on an open container's menu, one tick at a time ({@link ContainerTransfer}, {@link PlannerJob}): queued per
 * container and run in turn, so their clicks never interleave.
 */
interface MenuJob {
	/** One tick's work; true once it's over (its result is complete). */
	boolean tick(MenuSync sync);

	/** The container closed (or the world was left) before it finished. */
	void abort(String why);
}
