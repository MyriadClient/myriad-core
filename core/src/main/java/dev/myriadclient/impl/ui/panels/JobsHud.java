package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;
import dev.myriadclient.api.util.TickCached;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.service.JobRunner;
import dev.myriadclient.impl.service.JobText;

import java.util.ArrayList;
import java.util.List;

/** The running jobs, a line each: "Printer · Building wall · 142 left · stuck: no item: cobblestone". */
final class JobsHud extends TextHudPanel {
	private final IntSetting lines = sgGeneral.integer("Lines").description("The most jobs listed at once.").defaultValue(3).range(1, 10).build();
	private final BoolSetting showEnded = sgGeneral.bool("Show Ended").description("Jobs that ended keep their line for a few seconds, with how they ended.")
		.build();
	/** Worked out once a tick: the frames in between draw the same lines. */
	private final TickCached<List<String[]>> rows = TickCached.of(this::rows);

	JobsHud() {
		super("Jobs", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		if (preview()) {
			out.add("Printer", "Building wall · Placing · 142 left · stuck: no item: cobblestone");
			return;
		}
		for (String[] row : rows.get()) out.add(row[0], row[1]);
	}

	private List<String[]> rows() {
		List<String[]> out = new ArrayList<>();
		for (Job<?> j : Myriad.jobs().running()) {
			if (out.size() >= lines.get()) return out;
			out.add(new String[]{JobText.owner(j), JobText.line(j)});
		}
		if (!showEnded.get()) return out;
		for (JobRunner.Ended e : MyriadImpl.get().jobRunner().ended()) {
			if (out.size() >= lines.get()) return out;
			Job<?> j = e.job();
			out.add(new String[]{JobText.owner(j), JobText.line(j) + (j.state() == Job.State.DONE ? " · done" : "")});
		}
		return out;
	}
}
