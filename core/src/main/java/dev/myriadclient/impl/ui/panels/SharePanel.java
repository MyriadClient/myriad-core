package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.share.ConfigShare;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.share.ShareManager;
import dev.myriadclient.impl.wheel.WheelImpl;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared configs, in three parts: put a share together (whole menu windows or single modules, control panels, wheels,
 * the theme) and publish it for a code; take a code in, after seeing what's in it, as your settings or as a control
 * panel to try; and the shares you published, to copy or delete. Each part folds, with what it holds in its header, so
 * the window reads as a few lines until opened. Settings an addon protects never go in.
 */
final class SharePanel extends WidgetPanel {
	private final Set<Module> modules = new LinkedHashSet<>();
	private final Set<ControlPanel> panels = new LinkedHashSet<>();
	private final Set<WheelImpl> wheels = new LinkedHashSet<>();
	private final Set<String> unfolded = new HashSet<>();
	/** Which sections are open (all but Modules start folded). */
	private final Map<String, Boolean> open = new HashMap<>(Map.of("modules", true, "mine", true));
	private boolean theme, binds;
	private String title = "", code = "";
	/** What the last action said (published code, an error). */
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

		VBox groups = new VBox(3, 0);
		for (ModuleGroup g : MenuGroups.all()) groups.add(new GroupCard(g));
		content.add(section("modules", "Modules", groups, () -> modules.isEmpty() ? "" : String.valueOf(modules.size())));
		List<ControlPanel> allPanels = Myriad.controls().all();
		if (!allPanels.isEmpty()) {
			VBox body = new VBox(3, 0);
			for (ControlPanel p : allPanels) body.add(row(p.name(), () -> panels.contains(p), on -> toggle(panels, p, on)));
			content.add(section("panels", "Control panels", body, () -> panels.isEmpty() ? "" : String.valueOf(panels.size())));
		}
		List<WheelImpl> allWheels = MyriadImpl.get().wheelManager().all();
		if (!allWheels.isEmpty()) {
			VBox body = new VBox(3, 0);
			for (WheelImpl w : allWheels) body.add(row(w.name(), () -> wheels.contains(w), on -> toggle(wheels, w, on)));
			content.add(section("wheels", "Wheels", body, () -> wheels.isEmpty() ? "" : String.valueOf(wheels.size())));
		}
		VBox also = new VBox(3, 0);
		also.add(row("Theme", () -> theme, on -> theme = on));
		also.add(row("Keybinds", () -> binds, on -> binds = on));
		content.add(section("also", "Also", also, this::alsoHint));

		HBox share = new HBox(4);
		share.addWeighted(new TextField(() -> title).placeholder("Title").onChange(s -> title = s), 1);
		share.addFixed(new Button("Share", this::publish).accent(), 44);
		content.add(share);
		content.add(new Label(() -> status).color(() -> Myriad.ui().theme().accent.argb()).visible(() -> !status.isEmpty()));

		content.add(Spacer.divider());
		content.add(new Label("Take one in").bold());
		HBox take = new HBox(4);
		take.addWeighted(new TextField(() -> code).placeholder("myriad_…  or its link").onChange(s -> code = s).onSubmit(s -> fetch()), 1);
		take.addFixed(new Button("Look", this::fetch), 36);
		content.add(take);
		if (fetched != null) {
			content.add(new Label(fetched.title()).bold());
			content.add(new Label(contents(fetched)).dim());
			String needs = needs(fetched);
			if (!needs.isEmpty()) content.add(new Label("Needs " + needs + " for some of it.").color(() -> Myriad.ui().theme().yellow.argb()));
			HBox apply = new HBox(4);
			if (fetched.modules().isEmpty()) {
				// Nothing to try out: its control panels, wheels or theme are only ever added.
				apply.addWeighted(new Button("Add it", () -> apply(ConfigShares.Apply.REPLACE)).accent(), 1);
			} else {
				apply.addWeighted(new Button("Use these settings", () -> apply(ConfigShares.Apply.REPLACE)), 1);
				apply.addWeighted(new Button("Try as control panel", () -> apply(ConfigShares.Apply.AS_CONTROL_PANEL)).accent(), 1);
			}
			content.add(apply);
		}

		List<ConfigShares.Published> mine = Myriad.shares().published();
		if (!mine.isEmpty()) {
			content.add(Spacer.divider());
			VBox body = new VBox(3, 0);
			for (ConfigShares.Published p : mine) {
				HBox r = new HBox(4);
				r.addWeighted(new Label(p.title()), 1);
				r.addFixed(new Label(p.code()).dim(), 70);
				r.addFixed(new Button("Copy", () -> copy(p.code())), 34);
				r.addFixed(new Button("Delete", () -> Myriad.shares().unpublish(p.code())
					.whenComplete((v, t) -> later(() -> status = t != null ? "Couldn't delete it: " + t.getMessage() : "Deleted " + p.code()))), 40);
				body.add(r);
			}
			content.add(section("mine", "Your shares", body, () -> String.valueOf(mine.size())));
		}
		content.add(new Label("Values an addon marks private (webhooks, coordinates, passwords) never go in.").dim());
	}

	/** A part of the window that folds, with {@code hint} (what's picked in it) beside its name while folded. */
	private Collapsible section(String key, String name, VBox body, Supplier<String> hint) {
		return new Collapsible(() -> name, body, () -> open.getOrDefault(key, false), v -> open.put(key, v)).indent(10)
			.hint(() -> open.getOrDefault(key, false) ? "" : hint.get());
	}

	/** The extras picked, for the Also section's header: "theme, keybinds". */
	private String alsoHint() {
		List<String> on = new ArrayList<>();
		if (theme) on.add("theme");
		if (binds) on.add("keybinds");
		return String.join(", ", on);
	}

	/** What a share holds, in a line: "12 modules · 1 control panel · 2 wheels · a theme". */
	private static String contents(ConfigShare s) {
		List<String> parts = new ArrayList<>();
		if (!s.modules().isEmpty()) parts.add(count(s.modules().size(), "module"));
		if (!s.controlPanels().isEmpty()) parts.add(count(s.controlPanels().size(), "control panel"));
		int wheelCount = s.json().has("wheels") && s.json().get("wheels").isJsonArray() ? s.json().getAsJsonArray("wheels").size() : 0;
		if (wheelCount > 0) parts.add(count(wheelCount, "wheel"));
		if (s.hasTheme()) parts.add("a theme");
		return String.join(" · ", parts);
	}

	private static String count(int n, String what) {
		return n + " " + what + (n == 1 ? "" : "s");
	}

	/** The addons a share's modules come from that aren't installed here, or "". */
	private static String needs(ConfigShare s) {
		return String.join(", ", s.addons().stream().filter(a -> Myriad.addons().stream().noneMatch(x -> x.id().equals(a))).toList());
	}

	private void publish() {
		ShareManager.ShareBuilder b = (ShareManager.ShareBuilder) Myriad.shares().builder().modules(modules).binds(binds);
		if (!title.isBlank()) b.title(title.trim());
		panels.forEach(b::controlPanel);
		wheels.forEach(b::wheel);
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

	/** One of the menu's windows (a group): click shares all of it, unfold to pick modules. */
	private final class GroupCard extends CardEntry {
		private final ModuleGroup group;

		GroupCard(ModuleGroup group) {
			super(false, unfolded.contains(group.id().toString()));
			this.group = group;
		}

		private boolean all() {
			return modules.containsAll(MenuGroups.modules(group));
		}

		@Override
		protected String title() {
			return group.isAddonWindow() ? group.name() : group.name() + " · " + AddonNames.of(group.addon());
		}

		@Override
		protected boolean isOn() {
			return MenuGroups.modules(group).stream().anyMatch(modules::contains);
		}

		@Override
		protected int accent() {
			return theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(group.id().toString());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(group.id().toString());
			else unfolded.remove(group.id().toString());
		}

		@Override
		protected void primary() {
			boolean all = all();
			for (Module m : MenuGroups.modules(group)) toggle(modules, m, !all);
		}

		@Override
		protected void shiftSecondary() {
			setExpanded(!isExpanded());
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			long n = MenuGroups.modules(group).stream().filter(modules::contains).count();
			return badge(c, n + "/" + MenuGroups.modules(group).size(), right, cy, theme().textDim.argb(), false) - 4;
		}

		@Override
		protected String tooltipText() {
			return "Click to share the whole window; right-click to pick modules";
		}

		@Override
		protected void buildBody(VBox body) {
			for (Module m : MenuGroups.modules(group)) body.add(row(m.name(), () -> modules.contains(m), on -> toggle(modules, m, on)));
		}
	}
}
