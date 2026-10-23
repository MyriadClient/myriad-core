package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.addon.AddonState;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.addon.AddonLoader;
import dev.myriadclient.impl.update.UpdateManager;
import net.minecraft.util.Util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every addon Myriad found, as cards: what it adds, who made it, where to find it, and why it failed if it did. */
public final class AddonsPanel extends WidgetPanel {
	private final Set<String> unfolded = new HashSet<>();
	private final UpdateManager updates = MyriadImpl.get().updates();
	/** In the title bar: checking at start, checking now, and installing every update. */
	private final List<TitleControl> titleControls = List.of(
		TitleControl.toggle(updates::checkEnabled, updates::setCheckEnabled,
			() -> "Check GitHub for new releases when the game starts: " + (updates.checkEnabled() ? "on" : "off")),
		TitleControl.button(() -> updates.isChecking() ? "\uf110" : "\uf021", () -> updates.check(true),
			() -> updates.isChecking() ? "Checking…" : "Check for updates now"),
		TitleControl.button(() -> "\uf019", updates::updateAll, () -> "Update all " + updates.updatable().size() + " (they install when you quit)")
			.visible(() -> updates.updatable().size() > 1));

	@Override
	public List<TitleControl> titleControls() {
		return titleControls;
	}

	/** How many, and how many failed. */
	@Override
	public String subtitle() {
		long failed = Myriad.addons().stream().filter(a -> a.state() == AddonState.FAILED).count();
		return Myriad.addons().size() + " installed" + (failed > 0 ? ", " + failed + " failed" : "");
	}

	@Override
	public String title() {
		return "Addons";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	protected void build(VBox content) {
		for (Addon a : Myriad.addons()) content.add(new AddonEntry(a));
	}

	private final class AddonEntry extends CardEntry {
		private final Addon addon;

		AddonEntry(Addon addon) {
			super(addon.state() == AddonState.LOADED, false);
			this.addon = addon;
		}

		private boolean failed() {
			return addon.state() == AddonState.FAILED;
		}

		@Override
		protected String title() {
			return addon.name();
		}

		@Override
		protected boolean isOn() {
			return addon.state() == AddonState.LOADED;
		}

		@Override
		protected int accent() {
			return failed() ? theme().red.argb() : theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(addon.id());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(addon.id());
			else unfolded.remove(addon.id());
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			if (failed()) right = badge(c, " Failed", right, cy, theme().red.argb(), true) - 4;
			right = badge(c, addon.version(), right, cy, theme().textDim.argb(), false) - 4;
			UpdateManager.State u = updates.state(addon.id());
			if (u == null) return right;
			return switch (u.kind()) {
				case AVAILABLE -> badge(c, " " + u.version(), right, cy, theme().accent.argb(), true) - 4;
				case DOWNLOADING -> badge(c, " " + u.version(), right, cy, theme().textDim.argb(), false) - 4;
				case STAGED -> badge(c, " Restart to apply", right, cy, theme().green.argb(), true) - 4;
				case FAILED -> badge(c, " Update failed", right, cy, theme().red.argb(), true) - 4;
				default -> right;
			};
		}

		@Override
		protected String tooltipText() {
			String d = addon.description();
			String update = updateLine(updates.state(addon.id()));
			if (update == null) return d == null || d.isEmpty() ? null : d;
			return d == null || d.isEmpty() ? update : d + "\n" + update;
		}

		/** What the header's update pill means, for its tooltip. */
		private static String updateLine(UpdateManager.State u) {
			if (u == null) return null;
			return switch (u.kind()) {
				case AVAILABLE -> "Version " + u.version() + " is available. Open the card to update.";
				case DOWNLOADING -> "Downloading " + u.version() + "…";
				case STAGED -> u.version() + " installs when you quit.";
				case FAILED -> "Updating to " + u.version() + " failed: " + u.message();
				default -> null;
			};
		}

		@Override
		protected Object bodyKey() {
			return updates.state(addon.id());
		}

		@Override
		protected void buildBody(VBox body) {
			if (failed()) body.add(new Label("Failed to load: " + addon.failure()).color(() -> theme().red.argb()));
			if (addon.description() != null && !addon.description().isEmpty()) body.add(new Label(addon.description()).dim());
			if (!addon.authors().isEmpty()) body.add(new Label("By " + String.join(", ", addon.authors())).dim());
			String id = addon.id();
			body.add(new Label(() -> counts(id)));
			AddonLoader.Startup started = MyriadImpl.get().addonLoader().startup(id);
			if (started != null) body.add(new Label(started.describe(failed())).dim());
			// Links from the addon's fabric.mod.json "contact" block.
			HBox links = new HBox(4);
			addon.homepage().ifPresent(url -> links.add(new Button(" Website", () -> Util.getPlatform().openUri(url))).tooltip(url));
			addon.sources().ifPresent(url -> links.add(new Button(" Source", () -> Util.getPlatform().openUri(url))).tooltip(url));
			addon.issues().ifPresent(url -> links.add(new Button(" Issues", () -> Util.getPlatform().openUri(url))).tooltip(url));
			if (!links.children().isEmpty()) body.add(links);
			buildUpdate(body, updates.state(id));
			for (AddonSettings s : Myriad.addonSettings().ownedBy(id)) {
				body.add(new Label(s.name()).bold());
				body.add(SettingsView.build(s.settings()));
			}
		}

		private void buildUpdate(VBox body, UpdateManager.State u) {
			if (u == null) return;
			String id = addon.id();
			switch (u.kind()) {
				case AVAILABLE, FAILED -> {
					if (u.kind() == UpdateManager.Kind.FAILED) body.add(new Label("Updating to " + u.version() + " failed: " + u.message()).color(() -> theme().red.argb()));
					HBox row = new HBox(4);
					row.add(new Button(" Update to " + u.version(), () -> updates.update(id)).accent()).tooltip("Downloads it now; it installs when you quit");
					if (u.releaseUrl() != null) row.add(new Button(" Release notes", () -> Util.getPlatform().openUri(u.releaseUrl()))).tooltip(u.releaseUrl());
					body.add(row);
				}
				case CHECKING -> body.add(new Label("Checking for updates…").dim());
				case DOWNLOADING -> body.add(new Label("Downloading " + u.version() + "…").dim());
				case STAGED -> body.add(new Label(u.version() + " is downloaded and installs when you quit."
					+ (u.message() != null ? " " + u.message() + "." : "")).color(() -> theme().green.argb()));
				case UP_TO_DATE -> body.add(new Label("Up to date.").dim());
				case UNKNOWN, DEV_BUILD, NO_SOURCE -> body.add(new Label(u.message() + ".").dim());
			}
		}

		/** "12 modules · 3 HUD elements · 1 command", leaving out what the addon doesn't add. */
		private static String counts(String id) {
			StringBuilder sb = new StringBuilder();
			count(sb, Myriad.modules().ownedBy(id).size(), "module");
			count(sb, (int) Myriad.panels().ownedBy(id).stream().filter(p -> p.isHud()).count(), "HUD element");
			count(sb, (int) Myriad.panels().ownedBy(id).stream().filter(p -> !p.isHud()).count(), "window");
			count(sb, Myriad.commands().ownedBy(id).size(), "command");
			count(sb, Myriad.themes().ownedBy(id).size(), "theme");
			count(sb, Myriad.barWidgets().ownedBy(id).size(), "bar widget");
			count(sb, Myriad.keyActions().ownedBy(id).size(), "key action");
			return sb.isEmpty() ? "Adds nothing visible yet." : sb.toString();
		}

		private static void count(StringBuilder sb, int n, String what) {
			if (n == 0) return;
			if (!sb.isEmpty()) sb.append("  ·  ");
			sb.append(n).append(' ').append(what).append(n == 1 ? "" : "s");
		}
	}
}
