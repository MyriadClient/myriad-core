package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.service.JobRunner;
import dev.myriadclient.impl.service.JobText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the modules are doing at length: every running job by the module that started it, with the step it's on, why
 * it's waiting, its progress and how long it has run, and the steps under it; each can be paused or cancelled. A job
 * that ended stays listed for a few seconds with how it ended, until dismissed.
 */
final class JobsPanel extends WidgetPanel {
	/** How deep the steps under a job are listed. */
	private static final int DEPTH = 6;
	/** The job the launcher opened the window for, drawn in the accent colour. */
	private static int focused = -1;
	private final Set<Integer> dismissed = new HashSet<>();
	private String built = "";

	@Override
	public String title() {
		return "Jobs";
	}

	@Override
	public String icon() {
		return "";
	}

	/** Opens the Jobs window with {@code job} picked out. */
	static void open(Job<?> job) {
		focused = job.id();
		Myriad.ui().openPanel(CorePanels.JOBS, new JsonObject());
	}

	private static JobRunner runner() {
		return MyriadImpl.get().jobRunner();
	}

	/** Changes when the jobs listed or the steps under them do (their text updates by itself). */
	private String signature() {
		StringBuilder s = new StringBuilder();
		for (Job<?> j : Myriad.jobs().running()) tree(s, j, 0);
		s.append('|');
		for (JobRunner.Ended e : runner().ended()) if (!dismissed.contains(e.job().id())) s.append(e.job().id()).append(',');
		return s.append('|').append(focused).toString();
	}

	private static void tree(StringBuilder s, Job<?> j, int depth) {
		s.append(j.id()).append(depth == 0 ? ';' : ',');
		if (depth < DEPTH) for (Job<?> c : j.children()) tree(s, c, depth + 1);
	}

	@Override
	public void tick() {
		if (!built.equals(signature())) rebuild();
	}

	@Override
	protected void build(VBox content) {
		built = signature();
		List<Job<?>> running = Myriad.jobs().running();
		HBox head = new HBox(4);
		head.addWeighted(new Label(running.isEmpty() ? "Nothing running" : running.size() + " running").dim(), 1);
		if (!running.isEmpty()) head.addFixed(new Button("Cancel all", () -> Myriad.jobs().running().forEach(Job::cancel)), 80);
		content.add(head);
		if (running.isEmpty()) {
			content.add(new Label("Modules that work at length (building, restocking, searching) list their jobs here while they run.").dim());
		}
		Map<String, List<Job<?>>> byOwner = new LinkedHashMap<>();
		for (Job<?> j : running) byOwner.computeIfAbsent(JobText.owner(j), k -> new ArrayList<>()).add(j);
		byOwner.forEach((owner, jobs) -> {
			content.add(new Label(owner).bold());
			for (Job<?> j : jobs) job(content, j);
		});
		List<JobRunner.Ended> ended = runner().ended().stream().filter(e -> !dismissed.contains(e.job().id())).toList();
		if (!ended.isEmpty()) content.add(new Label("Ended").bold());
		for (JobRunner.Ended e : ended) {
			Job<?> j = e.job();
			HBox row = new HBox(4);
			row.addWeighted(new Label(JobText.owner(j) + " · " + JobText.line(j) + (j.state() == Job.State.DONE ? " · done" : "")).dim(), 1);
			row.addFixed(new Button("×", () -> {
				dismissed.add(j.id());
				rebuild();
			}).tooltip("Dismiss"), 22);
			content.add(row);
		}
	}

	/** A running root: its name, state and time, pause and cancel, what it's doing, its progress, and its steps. */
	private void job(VBox content, Job<?> j) {
		HBox head = new HBox(4);
		Label name = new Label(() -> j.name() + "  ·  " + JobText.state(j.state()) + "  ·  " + JobText.elapsed(j.ticks()));
		if (j.id() == focused) name.color(() -> Myriad.ui().theme().accent.argb());
		head.addWeighted(name.tooltip("Job " + j.id() + " · .job cancel " + j.id()), 1);
		head.addFixed(new Button(() -> j.state() == Job.State.PAUSED ? "Resume" : "Pause", () -> {
			if (j.state() == Job.State.PAUSED) j.resume();
			else j.pause();
		}), 64);
		head.addFixed(new Button("Cancel", j::cancel), 64);
		content.add(head);
		content.add(new Label(() -> JobText.detail(j)).dim()).visible(() -> !JobText.detail(j).isEmpty());
		content.add(new Bar(j)).visible(() -> !Double.isNaN(j.progress()));
		for (Job<?> c : j.children()) step(content, c, 1);
	}

	private static void step(VBox content, Job<?> j, int depth) {
		String indent = "    ".repeat(depth);
		content.add(new Label(() -> {
			StringBuilder s = new StringBuilder(indent).append(j.name());
			if (j.state() != Job.State.RUNNING) s.append(" · ").append(JobText.state(j.state()));
			if (j.state() == Job.State.STUCK && j.why() != null) s.append(": ").append(j.why());
			else if (j.status() != null && !j.status().isEmpty()) s.append(" · ").append(j.status());
			List<String> pills = j.pills();
			if (!pills.isEmpty()) s.append("  [").append(String.join(", ", pills)).append(']');
			return s.toString();
		}).dim());
		if (depth < DEPTH) for (Job<?> c : j.children()) step(content, c, depth + 1);
	}

	/** A job's progress, as a thin bar. */
	private static final class Bar extends Widget {
		private final Job<?> job;

		Bar(Job<?> job) {
			this.job = job;
		}

		@Override
		protected float measure(Canvas canvas, float width) {
			return 4;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			double p = job.progress();
			if (Double.isNaN(p)) return;
			c.roundRect(x, y, width, height, 2, theme().surface.argb());
			c.roundRect(x, y, (float) (width * Math.clamp(p, 0, 1)), height, 2, theme().accent.argb());
		}
	}
}
