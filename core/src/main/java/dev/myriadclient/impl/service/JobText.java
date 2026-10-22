package dev.myriadclient.impl.service;

import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.module.Module;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a job reads in the Jobs window, the HUD, the launcher and {@code .jobs}: the same words everywhere. A job's line
 * is its name and then what's happening under it: the step running now, its live status, and why it's waiting when
 * it's stuck.
 */
public final class JobText {
	private JobText() {
	}

	/** Who started it: a module's name, or the owner as it prints. */
	public static String owner(Job<?> job) {
		return job.owner() instanceof Module m ? m.name() : String.valueOf(job.owner());
	}

	/** "Building wall · Placing · 142 left · stuck: no item: cobblestone". */
	public static String line(Job<?> job) {
		String detail = detail(job);
		return detail.isEmpty() ? job.name() : job.name() + " · " + detail;
	}

	/** What's happening under {@code job}: the step running now (if it isn't {@code job}), its status, and why it waits. */
	public static String detail(Job<?> job) {
		List<String> parts = new ArrayList<>();
		Job<?> now = current(job);
		if (now != job) parts.add(now.name());
		String status = now.status() != null ? now.status() : job.status();
		if (status != null && !status.isEmpty()) parts.add(status);
		if (job.state().isActive() && !Double.isNaN(job.progress())) parts.add(Math.round(job.progress() * 100) + "%");
		Job<?> stuck = stuck(job);
		if (job.state() == Job.State.PAUSED) parts.add("paused");
		else if (stuck != null && stuck.why() != null) parts.add("stuck: " + stuck.why());
		else if (!job.state().isActive() && job.state() != Job.State.DONE) parts.add(state(job.state()) + reason(job.why()));
		return String.join(" · ", parts);
	}

	private static String reason(@Nullable Why why) {
		return why == null || why.is(Why.CANCELLED) && why.detail().isEmpty() ? "" : ": " + why;
	}

	/** The deepest job running under {@code job}, following the first child: the step whose status says what's going on. */
	public static Job<?> current(Job<?> job) {
		Job<?> j = job;
		for (int depth = 0; depth < 32; depth++) {
			List<Job<?>> children = j.children();
			if (children.isEmpty()) break;
			j = children.getFirst();
		}
		return j;
	}

	/** The first stuck job in {@code job}'s tree, itself included, or null. */
	public static @Nullable Job<?> stuck(Job<?> job) {
		if (job.state() == Job.State.STUCK) return job;
		for (Job<?> c : job.children()) {
			Job<?> s = stuck(c);
			if (s != null) return s;
		}
		return null;
	}

	/** "running", "stuck", "done"... */
	public static String state(Job.State state) {
		return state.name().toLowerCase(Locale.ROOT);
	}

	/** Ticks as minutes and seconds: "0:42", "12:05". */
	public static String elapsed(int ticks) {
		int s = ticks / 20;
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}
}
