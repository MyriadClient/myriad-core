package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.share.ConfigShare;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.Async;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared configs, in three parts: put a share together (whole menu windows, single modules, control panels, the
 * theme) and publish it for a code; take a code in, after seeing what's in it, as your settings or as a control panel
 * to try; and the shares you published, to copy or delete. Settings an addon protects never go in.
 */
final class SharePanel extends WidgetPanel {
	private final Set<Module> modules = new LinkedHashSet<>();
	private final Set<ControlPanel> panels = new LinkedHashSet<>();
	private final Set<String> unfolded = new HashSet<>();
	private boolean theme, binds;
	private String title = "", code = "";
	/** What the last action said (published code, an error, a preview). */
	private String status = "";
	private ConfigShare fetched;

	@Override
	public String title() {
		return "Share";
	}

	@Override
	public String icon() {
		return "\uf1e0";
	}

	@Override
	protected void build(VBox content) {
		content.add(new Label("Share").bold());
		content.add(new Label("Pick what goes in. Values an addon marks private (webhooks, coordinates, passwords) never do.").dim());
		for (ModuleGroup g : ModuleGroup.all()) content.add(new GroupCard(g));
		for (ControlPanel p : Myriad.controls().all()) content.add(row("Control panel: " + p.name(), () -> panels.contains(p), on -> toggle(panels, p, on)));
		content.add(row("Theme (colours and style)", () -> theme, on -> theme = on));
		content.add(row("Keybinds", () -> binds, on -> binds = on));
		HBox share = new HBox(4);
		share.addWeighted(new TextField(() -> title).placeholder("Title").onChange(s -> title = s), 1);
		share.addFixed(new Button("Share", this::publish).accent(), 44);
		content.add(share);

		content.add(new Label("Take one in").bold());
		HBox take = new HBox(4);
		take.addWeighted(new TextField(() -> code).placeholder("myriad_…").onChange(s -> code = s).onSubmit(s -> fetch()), 1);
		take.addFixed(new Button("Look", this::fetch), 36);
		content.add(take);
		if (fetched != null) {
			content.add(new Label(() -> summary(fetched)).dim());
			HBox apply = new HBox(4);
			apply.addWeighted(new Button("Use these settings", () -> apply(ConfigShares.Apply.REPLACE)), 1);
			apply.addWeighted(new Button("Try as control panel", () -> apply(ConfigShares.Apply.AS_CONTROL_PANEL)).accent(), 1);
			content.add(apply);
		}
		content.add(new Label(() -> status).color(() -> Myriad.ui().theme().accent.argb()));

		List<ConfigShares.Published> mine = Myriad.shares().published();
		if (!mine.isEmpty()) content.add(new Label("Your shares").bold());
		for (ConfigShares.Published p : mine) {
			HBox r = new HBox(4);
			r.addWeighted(new Label(p.title() + "  " + p.code()), 1);
			r.addFixed(new Button("Copy", () -> copy(p.code())), 34);
			r.addFixed(new Button("Delete", () -> Myriad.shares().unpublish(p.code())
				.whenComplete((v, t) -> later(() -> status = t != null ? "Couldn't delete it: " + t.getMessage() : "Deleted " + p.code()))), 40);
			content.add(r);
		}
	}

	private static String summary(ConfigShare s) {
		StringBuilder b = new StringBuilder(s.title()).append(": ").append(s.modules().size()).append(" modules");
		if (!s.controlPanels().isEmpty()) b.append(", ").append(s.controlPanels().size()).append(" control panels");
		if (s.hasTheme()) b.append(", a theme");
		List<String> missing = s.addons().stream().filter(a -> Myriad.addons().stream().noneMatch(x -> x.id().equals(a))).toList();
		if (!missing.isEmpty()) b.append(". Needs ").append(String.join(", ", missing)).append(" for some of it");
		return b.toString();
	}

	private void publish() {
		ConfigShares.Builder b = Myriad.shares().builder().modules(modules).binds(binds);
		if (!title.isBlank()) b.title(title.trim());
		panels.forEach(b::controlPanel);
		if (theme) b.theme();
		ConfigShare share = b.build();
		if (share.isEmpty()) {
			status = "Pick something to share first.";
			return;
		}
		status = "Sharing…";
		Myriad.shares().publish(share).whenComplete((p, t) -> later(() -> {
			if (t != null) {
				status = "Couldn't share it: " + cause(t);
				return;
			}
			copy(p.code());
			status = "Shared as " + p.code() + " (copied)";
		}));
	}

	private void fetch() {
		status = "Looking…";
		Myriad.shares().fetch(code).whenComplete((s, t) -> later(() -> {
			fetched = t == null ? s : null;
			status = t != null ? "Couldn't get it: " + cause(t) : "";
		}));
	}

	private void apply(ConfigShares.Apply how) {
		if (fetched == null) return;
		var result = Myriad.shares().apply(fetched, how);
		status = (how == ConfigShares.Apply.AS_CONTROL_PANEL ? "Added as a control panel: " : "Took in: ") + String.join(", ", result.applied())
			+ (result.missing().isEmpty() ? "" : ". Not installed: " + String.join(", ", result.missing()));
		fetched = null;
	}

	/** Back on the render thread, then shows what changed. */
	private void later(Runnable r) {
		Async.onRenderThread(() -> {
			r.run();
			rebuild();
		});
	}

	private static String cause(Throwable t) {
		while (t.getCause() != null) t = t.getCause();
		return t.getMessage();
	}

	private static void copy(String text) {
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
	}

	private static <T> void toggle(Set<T> set, T item, boolean on) {
		if (on) set.add(item);
		else set.remove(item);
	}

	private HBox row(String label, Supplier<Boolean> on, Consumer<Boolean> set) {
		HBox r = new HBox(4);
		r.addWeighted(new Label(label), 1);
		r.addFixed(new Toggle(on, set), 20);
		return r;
	}

	/** One of the menu's windows (an addon's category): click shares all of it, unfold to pick modules. */
	private final class GroupCard extends CardEntry {
		private final ModuleGroup group;

		GroupCard(ModuleGroup group) {
			super(false, unfolded.contains(group.key()));
			this.group = group;
		}

		private boolean all() {
			return modules.containsAll(group.modules());
		}

		@Override
		protected String title() {
			return group.category().name() + " · " + group.addonName();
		}

		@Override
		protected boolean isOn() {
			return group.modules().stream().anyMatch(modules::contains);
		}

		@Override
		protected int accent() {
			return group.category().color();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(group.key());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(group.key());
			else unfolded.remove(group.key());
		}

		@Override
		protected void primary() {
			boolean all = all();
			for (Module m : group.modules()) toggle(modules, m, !all);
		}

		@Override
		protected void shiftSecondary() {
			setExpanded(!isExpanded());
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			long n = group.modules().stream().filter(modules::contains).count();
			return badge(c, n + "/" + group.modules().size(), right, cy, theme().textDim.argb(), false) - 4;
		}

		@Override
		protected String tooltipText() {
			return "Click to share the whole window; right-click to pick modules";
		}

		@Override
		protected void buildBody(VBox body) {
			for (Module m : group.modules()) body.add(row(m.name(), () -> modules.contains(m), on -> toggle(modules, m, on)));
		}
	}
}
