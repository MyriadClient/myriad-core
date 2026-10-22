package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.event.Profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the time goes: every module, service, listener and running job with the milliseconds it spent in tick, render
 * and other handlers over the last second, and hints where the numbers point at one of core's tools. Timing is only on
 * while this window is open (or {@code .profiler on}).
 */
public final class ProfilerPanel extends WidgetPanel {
	private static final int ROWS = 25;
	private int ticks;
	private boolean ownsProfiler;

	@Override
	public String title() {
		return "Profiler";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void onOpen() {
		MyriadEventBus bus = (MyriadEventBus) Myriad.events();
		if (bus.profiler() == null) {
			bus.setProfiler(new Profiler());
			ownsProfiler = true;
		}
	}

	@Override
	public void onClose() {
		if (ownsProfiler) ((MyriadEventBus) Myriad.events()).setProfiler(null);
	}

	@Override
	public void tick() {
		if (++ticks % 20 == 0) rebuild();
	}

	@Override
	protected void build(VBox content) {
		Profiler profiler = ((MyriadEventBus) Myriad.events()).profiler();
		content.add(new Label("Milliseconds per second in event handlers, by who runs them. Tick handlers compete with the game's 50 ms "
			+ "tick; render handlers with the frame.").dim());
		if (profiler == null) {
			content.add(new Label("Not profiling."));
			return;
		}
		List<Profiler.Entry> entries = profiler.snapshot();
		HBox head = new HBox(4);
		head.addWeighted(new Label("Owner").dim(), 1);
		head.addFixed(new Label("tick").dim(), 44);
		head.addFixed(new Label("render").dim(), 44);
		head.addFixed(new Label("other").dim(), 44);
		head.addFixed(new Label("calls").dim(), 44);
		content.add(head);
		content.add(Spacer.divider());
		double total = 0;
		int shown = 0;
		List<Profiler.Entry> hinted = new ArrayList<>();
		for (Profiler.Entry e : entries) {
			if (!e.isJob()) total += e.totalMs();
			if (e.hint() != null) hinted.add(e);
			if (shown++ >= ROWS) continue;
			HBox row = new HBox(4);
			String hint = e.hint();
			row.addWeighted(new Label(hint != null ? e.name() + " *" : e.name()).tooltip(hint != null ? e.addon() + ": " + hint : e.addon()), 1);
			row.addFixed(new Label(ms(e.tickMs())), 44);
			row.addFixed(new Label(ms(e.renderMs())), 44);
			row.addFixed(new Label(ms(e.otherMs())), 44);
			row.addFixed(new Label(String.valueOf(e.calls())).dim(), 44);
			content.add(row);
		}
		content.add(Spacer.divider());
		content.add(new Label(String.format(Locale.ROOT, "%d owners, %.2f ms/s in all", entries.size(), total)).dim());
		for (Profiler.Entry e : hinted) content.add(new Label("* " + e.name() + ": " + e.hint()));
	}

	private static String ms(double v) {
		return v < 0.005 ? "-" : String.format(Locale.ROOT, "%.2f", v);
	}
}
