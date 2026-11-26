package dev.myriadclient.impl.ui.panels;

import com.mojang.blaze3d.Blaze3D;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.addon.AddonState;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Note;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.addon.AddonLoader;
import dev.myriadclient.impl.update.LinkInstall;
import dev.myriadclient.impl.update.UpdateManager;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Every addon Myriad found, as cards: what it adds, who made it, where to find it, and why it failed if it did. */
public final class AddonsPanel extends WidgetPanel {
	private final Set<String> unfolded = new HashSet<>();
	/** Addons' settings blocks (and core's own sections) folded away; all open at first. */
	private final Set<String> folded = new HashSet<>();
	private final UpdateManager updates = MyriadImpl.get().updates();
	/** The "add from link" form: open, what's typed, and what the link offered (or why it can't be installed). */
	private boolean adding, looking;
	private String link = "", linkToken = "";
	private LinkInstall.Offer offer;
	private String linkProblem, installed;
	/** Private-source tokens being typed, per addon. */
	private final Map<String, String> tokenDrafts = new HashMap<>();
	/** In the title bar: checking at start, checking now, and installing every update. */
	private final List<TitleControl> titleControls = List.of(
		TitleControl.toggle(updates::checkEnabled, updates::setCheckEnabled,
			() -> "Check for new releases when the game starts: " + (updates.checkEnabled() ? "on" : "off")),
		TitleControl.button(() -> updates.isChecking() ? "\uf110" : "\uf021", () -> updates.check(true),
			() -> updates.isChecking() ? "Checking…" : "Check for updates now"),
		TitleControl.button(() -> "\uf0c1", () -> {
			adding = !adding;
			rebuild();
		}, () -> adding ? "Close adding an addon from a link" : "Add an addon from a link"),
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
		if (adding) content.add(addForm());
		for (Addon a : Myriad.addons()) content.add(new AddonEntry(a));
	}

	/** Adding an addon from a link: look it up (downloaded and checked, nothing written), see what it is, then install. */
	private Widget addForm() {
		VBox form = new VBox(4, 0);
		form.add(new Label("Add an addon from a link").bold());
		form.add(new Label("A GitHub repo (owner/repo, or its address) or an addon's https feed. It's downloaded and checked first; nothing is installed "
			+ "until you say so.").dim());
		form.add(new TextField(() -> link).onChange(s -> link = s).onSubmit(s -> lookUp()).placeholder("github.com/owner/repo, or https://…/feed.json"));
		form.add(new TextField(() -> linkToken).onChange(s -> linkToken = s).masked().placeholder("Access token, for a private repo or feed (optional)"));
		HBox buttons = new HBox(4);
		buttons.add(new Button(() -> looking ? "Looking…" : "Look up", this::lookUp).accent());
		buttons.add(new Button("Cancel", this::closeForm).quiet());
		form.add(buttons);
		if (linkProblem != null) form.add(new Note("Can't install it: " + linkProblem + ".").error());
		if (installed != null) form.add(new Note(installed).color(() -> Myriad.ui().theme().green.argb()));
		LinkInstall.Offer o = offer;
		if (o != null) {
			form.add(new Label(o.name() + " " + o.version()).bold());
			form.add(new Label(o.id() + "  ·  from " + o.from() + "  ·  " + (o.jar().length / 1024) + " KB").dim());
			if (o.signed()) {
				form.add(new Note("Signed with key " + o.keyId() + ". If its developer publishes their key, check it's this one: every update will "
					+ "have to be signed with it."));
			} else {
				form.add(new Note("Unsigned: whoever controls " + o.from() + " decides what you get, now and in every update. Install it only if you "
					+ "trust them.").error());
			}
			HBox act = new HBox(4);
			Button install = new Button("Install " + o.name(), () -> install(o));
			act.add(o.signed() ? install.accent() : install.danger());
			act.add(new Button("Don't install", () -> {
				offer = null;
				rebuild();
			}).quiet());
			form.add(act);
		}
		return form;
	}

	private void lookUp() {
		if (looking || link.isBlank()) return;
		looking = true;
		offer = null;
		linkProblem = installed = null;
		rebuild();
		updates.lookUp(link, linkToken).whenComplete((o, err) -> Async.onRenderThread(() -> {
			looking = false;
			if (err != null) linkProblem = UpdateManager.reasonOf(err);
			else offer = o;
			rebuild();
		}));
	}

	private void install(LinkInstall.Offer o) {
		try {
			updates.install(o);
			installed = o.name() + " " + o.version() + " is in your mods folder and loads when you restart the game.";
			link = linkToken = "";
		} catch (java.io.IOException e) {
			linkProblem = e.getMessage();
		}
		offer = null;
		rebuild();
	}

	private void closeForm() {
		adding = looking = false;
		offer = null;
		link = linkToken = "";
		linkProblem = installed = null;
		rebuild();
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
			UpdateManager.State u = updates.state(addon.id());
			if (u != null) right = switch (u.kind()) {
				case AVAILABLE -> badge(c, " " + u.version(), right, cy, theme().accent.argb(), true) - 4;
				case DOWNLOADING -> badge(c, " " + u.version(), right, cy, theme().textDim.argb(), false) - 4;
				case STAGED -> badge(c, " Restart to apply", right, cy, theme().green.argb(), true) - 4;
				case FAILED -> badge(c, " Update failed", right, cy, theme().red.argb(), true) - 4;
				default -> right;
			};
			right = badge(c, addon.version(), right, cy, theme().textDim.argb(), false) - 4;
			return right;
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
			if (failed()) body.add(new Note("Failed to load: " + addon.failure()).error());
			if (addon.description() != null && !addon.description().isEmpty()) body.add(new Label(addon.description()).dim());
			if (!addon.authors().isEmpty()) body.add(new Label("By " + String.join(", ", addon.authors())).dim());
			String id = addon.id();
			body.add(new Label(() -> counts(id)));
			AddonLoader.Startup started = MyriadImpl.get().addonLoader().startup(id);
			if (started != null) body.add(new Label(started.describe(failed())).dim());
			// Links from the addon's fabric.mod.json "contact" block, and its update, in one row.
			Toolbar links = new Toolbar();
			addon.homepage().ifPresent(url -> links.add("\uf0ac", "Website", url, () -> Blaze3D.openUri(URI.create(url))));
			addon.sources().ifPresent(url -> links.add("\uf121", "Source", url, () -> Blaze3D.openUri(URI.create(url))));
			addon.issues().ifPresent(url -> links.add("\uf188", "Issues", url, () -> Blaze3D.openUri(URI.create(url))));
			UpdateManager.State u = updates.state(id);
			if (u != null && (u.kind() == UpdateManager.Kind.AVAILABLE || u.kind() == UpdateManager.Kind.FAILED)) {
				links.gap();
				if (u.releaseUrl() != null) links.add("\uf15c", "Notes", u.releaseUrl(), () -> Blaze3D.openUri(URI.create(u.releaseUrl())));
				links.add("\uf019", "Update to " + u.version(), "Downloads it now; it installs when you quit.", () -> updates.update(id), false, true, () -> true);
			}
			if (!links.isEmpty()) body.add(links);
			buildUpdate(body, u);
			buildSigning(body);
			// Core's own settings: profiles and the anti-cheat.
			if (id.equals("myriad")) ProfilesSection.build(body, AddonsPanel.this::rebuild, folded);
			// Each block of settings folds like a module's group: its rows straight in when it has one group, its groups
			// inside when it has more.
			for (AddonSettings s : Myriad.addonSettings().ownedBy(id)) {
				var groups = SettingsView.displayOrder(s.settings()).stream().filter(g -> !g.settings().isEmpty()).toList();
				Widget inner;
				if (groups.size() == 1) {
					VBox rows = new VBox(3, 0);
					for (var setting : groups.getFirst().settings()) {
						Widget row = Myriad.ui().settingWidgets().create(setting);
						if (row != null) rows.add(row);
					}
					inner = rows;
				} else inner = SettingsView.build(s.settings());
				String key = id + "/" + s.name();
				body.add(new Collapsible(s::name, inner, () -> !folded.contains(key), open -> {
					if (open) folded.remove(key);
					else folded.add(key);
				}).indent(10).hint(() -> folded.contains(key) ? String.valueOf(s.settings().all().stream().filter(dev.myriadclient.api.setting.Setting::isVisible).count()) : ""));
			}
		}

		/** Whether its updates are signed, where they come from, and a token for a private source. */
		private void buildSigning(VBox body) {
			String source = UpdateManager.sourceOf(addon);
			if (source == null) return;
			List<String> keys = UpdateManager.signingKeys(addon);
			String id = addon.id();
			body.add(new Label(keys.isEmpty() ? "Updates from " + source + ", unsigned."
				: "Updates from " + source + ", signed: only updates signed with key " + String.join(" or ", keys) + " are taken.").dim()
				.tooltip(keys.isEmpty() ? "Its developer doesn't sign its releases, so an update is only as safe as " + source + "."
					: "Each update is checked against its developer's signature before it's installed."));
			HBox token = new HBox(4);
			token.addWeighted(new TextField(() -> tokenDrafts.getOrDefault(id, "")).onChange(t -> tokenDrafts.put(id, t)).masked()
				.placeholder(updates.hasToken(id) ? "Access token saved; paste another to replace it" : "Access token, if its source is private"), 1);
			token.addFixed(new Button("Save", () -> {
				if (updates.setToken(id, tokenDrafts.getOrDefault(id, ""))) tokenDrafts.remove(id);
				rebuild();
			}).quiet(), 40);
			if (updates.hasToken(id)) token.addFixed(new Button("Forget", () -> {
				updates.setToken(id, null);
				rebuild();
			}).quiet(), 46);
			String key = id + "/private-source";
			body.add(new Collapsible(() -> "Private source", token, () -> unfolded.contains(key), open -> {
				if (open) unfolded.add(key);
				else unfolded.remove(key);
			}).indent(10).hint(() -> updates.hasToken(id) ? "token saved" : ""));
		}

		private void buildUpdate(VBox body, UpdateManager.State u) {
			if (u == null) return;
			String id = addon.id();
			switch (u.kind()) {
				// The update button is in the links' row (buildBody).
				case AVAILABLE -> {
				}
				case FAILED -> body.add(new Note("Updating to " + u.version() + " failed: " + u.message()).error());
				case CHECKING -> body.add(new Label("Checking for updates…").dim());
				case DOWNLOADING -> body.add(new Label("Downloading " + u.version() + "…").dim());
				case STAGED -> body.add(new Note(u.version() + " is downloaded and installs when you quit."
					+ (u.message() != null ? " " + u.message() + "." : "")).color(() -> theme().green.argb()));
				case UP_TO_DATE -> body.add(new Label(u.message() == null ? "Up to date." : "Up to date. " + u.message() + ".").dim());
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
