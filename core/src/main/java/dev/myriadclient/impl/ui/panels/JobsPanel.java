package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.EmptyState;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Heading;
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
 * What the modules are doing at length: every running job by the module that started it, as a card with its state, how
 * long it has run and its progress; unfolded, what it's doing, the steps under it, and pausing or cancelling it. A job
 * that ended stays listed for a few seconds with how it ended, until dismissed.
 */
final class JobsPanel extends WidgetPanel {
	/** How deep the steps under a job are listed. */
	private static final int DEPTH = 6;
	/** The job the launcher opened the window for, drawn in the accent colour. */
	private static int focused = -1;
	private final Set<Integer> dismissed = new HashSet<>();
	/** Jobs start unfolded, what they're doing being what this window is for. */
	private final Set<Integer> folded = new HashSet<>();
	private final List<TitleControl> titleControls = List.of(
		TitleControl.button(() -> Icons.BAN, () -> Myriad.jobs().running().forEach(Job::cancel), () -> "Cancel every job").visible(() -> !Myriad.jobs().running().isEmpty()));
	private String built = "";

	@Override
	public String title() {
		return "Jobs";
	}

	@Override
	public String subtitle() {
		int n = Myriad.jobs().running().size();
		return n == 0 ? null : n + " running";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public List<TitleControl> titleControls() {
		return titleControls;
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
		List<JobRunner.Ended> ended = runner().ended().stream().filter(e -> !dismissed.contains(e.job().id())).toList();
		if (running.isEmpty() && ended.isEmpty()) {
			content.add(new EmptyState(Icons.CHECK, "Nothing running", "Modules that work at length (building, restocking, searching) list their jobs here while they run."));
		}
		Map<String, List<Job<?>>> byOwner = new LinkedHashMap<>();
		for (Job<?> j : running) byOwner.computeIfAbsent(JobText.owner(j), k -> new ArrayList<>()).add(j);
		byOwner.forEach((owner, jobs) -> {
			content.add(new Heading(owner));
			for (Job<?> j : jobs) content.add(new JobCard(j));
		});
		if (!ended.isEmpty()) content.add(new Heading("Ended"));
		for (JobRunner.Ended e : ended) {
			Job<?> j = e.job();
			HBox row = new HBox(4);
			row.addWeighted(new Label(JobText.owner(j) + " · " + JobText.line(j) + (j.state() == Job.State.DONE ? " · done" : "")).dim(), 1);
			row.addFixed(new Button(Icons.TIMES, () -> {
				dismissed.add(j.id());
				rebuild();
			}).tooltip("Dismiss"), ROW_BUTTON);
			content.add(row);
		}
	}

	private static final float ROW_BUTTON = 14;

	/** A running root: its name, state and time; unfolded, what it's doing, its steps, pause and cancel. */
	private final class JobCard extends CardEntry {
		private final Job<?> job;

		JobCard(Job<?> job) {
			super(true, !folded.contains(job.id()));
			this.job = job;
			this.tooltip = null;
		}

		@Override
		protected String title() {
			return job.name();
		}

		@Override
		protected boolean isOn() {
			return job.state() != Job.State.PAUSED;
		}

		@Override
		protected int accent() {
			return JobsPanel.stateColor(job.state());
		}

		@Override
		protected double progress() {
			return job.progress();
		}

		@Override
		protected boolean isExpanded() {
			return !folded.contains(job.id());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) folded.remove(job.id());
			else folded.add(job.id());
		}

		@Override
		protected String tooltipText() {
			return "Job " + job.id() + " · .job cancel " + job.id();
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			if (job.state() != Job.State.RUNNING) right = badge(c, JobText.state(job.state()), right, cy, JobsPanel.stateColor(job.state()), true) - 4;
			return badge(c, JobText.elapsed(job.ticks()), right, cy, job.id() == focused ? theme().accent.argb() : theme().textDim.argb(), job.id() == focused) - 4;
		}

		@Override
		protected void buildBody(VBox body) {
			body.add(new Label(() -> JobText.detail(job)).dim()).visible(() -> !JobText.detail(job).isEmpty());
			for (Job<?> c : job.children()) step(body, c, 0);
			HBox actions = new HBox(4);
			actions.add(new Button(() -> job.state() == Job.State.PAUSED ? "Resume" : "Pause", () -> {
				if (job.state() == Job.State.PAUSED) job.resume();
				else job.pause();
			}));
			actions.add(new Button("Cancel", job::cancel));
			body.add(actions);
		}
	}

	private static int stateColor(Job.State state) {
		var theme = Myriad.ui().theme();
		return switch (state) {
			case STUCK -> theme.yellow.argb();
			case PAUSED -> theme.textDim.argb();
			case FAILED, CANCELLED -> theme.red.argb();
			default -> theme.accent.argb();
		};
	}

	private static void step(VBox body, Job<?> j, int depth) {
		body.add(new StepRow(j, depth));
		if (depth + 1 < DEPTH) for (Job<?> c : j.children()) step(body, c, depth + 1);
	}

	/** A step under a job: indented by how deep it is, a dot for its state, and its text wrapped beside that. */
	private static final class StepRow extends Widget {
		private static final float INDENT = 7, DOT = 3, TEXT = 7;
		private final Job<?> job;
		private final int depth;
		private final Label text;

		StepRow(Job<?> job, int depth) {
			this.job = job;
			this.depth = depth;
			this.text = new Label(() -> {
				StringBuilder s = new StringBuilder(job.name());
				if (job.state() != Job.State.RUNNING) s.append(" · ").append(JobText.state(job.state()));
				if (job.state() == Job.State.STUCK && job.why() != null) s.append(": ").append(job.why());
				else if (job.status() != null && !job.status().isEmpty()) s.append(" · ").append(job.status());
				List<String> pills = job.pills();
				if (!pills.isEmpty()) s.append("  [").append(String.join(", ", pills)).append(']');
				return s.toString();
			}).dim();
			attachChild(text);
		}

		/** Deep steps stop indenting once there'd be little left for their text. */
		private float indent(float width) {
			return Math.min(depth * INDENT, Math.max(0, width - 60));
		}

		@Override
		protected float measure(Canvas c, float width) {
			float left = indent(width) + TEXT;
			return text.layout(c, x + left, y, width - left);
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			float ly = y + (c.textHeight() + 1) / 2;
			float dx = x + indent(width) + DOT / 2;
			c.circle(dx, ly, DOT / 2, stateColor(job.state()));
			text.render(c, mx, my);
		}
	}
}
