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
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Note;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.addon.AddonLoader;
import dev.myriadclient.impl.event.Profiler;
import dev.myriadclient.impl.ui.AddonIcons;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.update.LinkInstall;
import dev.myriadclient.impl.update.UpdateManager;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Every addon Myriad found, as cards, and the Myriad Library to browse. A card says what the addon is in a line or two;
 * the rest (who made it, where it updates from, what it costs) folds away under Details, and what can be done with it
 * sits along its foot: update, auto update, remove.
 */
public final class AddonsPanel extends WidgetPanel {
	private final Set<String> unfolded = new HashSet<>();
	/** Addons' settings blocks (and core's own sections) folded away; all open at first. */
	private final Set<String> folded = new HashSet<>();
	private final UpdateManager updates = MyriadImpl.get().updates();
	/** Private-source tokens being typed, per addon. */
	private final Map<String, String> tokenDrafts = new HashMap<>();
	/** Browse: the Myriad Library instead of what's installed; the search typed; one listing's offer at a time. */
	private boolean browsing;
	private String query = "";
	private String libraryLooking;
	private final Map<String, String> libraryNotes = new HashMap<>();
	/** Invite codes being typed, per invite-only listing. */
	private final Map<String, String> codeDrafts = new HashMap<>();
	/** Shortcuts in the title bar: checking at start and checking now. */
	private final List<TitleControl> titleControls = List.of(
		TitleControl.toggle(updates::checkEnabled, updates::setCheckEnabled,
			() -> "Check for new releases when the game starts: " + (updates.checkEnabled() ? "on" : "off")),
		TitleControl.button(() -> updates.isChecking() ? "" : "", () -> updates.check(true),
			() -> updates.isChecking() ? "Checking…" : "Check for updates now"));

	@Override
	public List<TitleControl> titleControls() {
		return titleControls;
	}

	@Override
	public String subtitle() {
		if (browsing) {
			int n = updates.libraryAddons().size();
			return "Myriad Library · " + n + (n == 1 ? " addon" : " addons");
		}
		long failed = Myriad.addons().stream().filter(a -> a.state() == AddonState.FAILED).count();
		return Myriad.addons().size() + " installed" + (failed > 0 ? ", " + failed + " failed" : "");
	}

	@Override
	public String title() {
		return "Addons";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	protected void build(VBox content) {
		// Installed or the Library, as two buttons up top: the one showing is lit.
		// Installed or the Library, in the menu's own row of small buttons: the one showing is tinted.
		Toolbar views = new Toolbar()
			.add("\uf0ae", "Installed (" + Myriad.addons().size() + ")", "The addons in your mods folder", () -> show(false), false, !browsing, () -> true)
			.add("\uf02d", "Browse the Library", "Addons Myriad has checked, to install in a click", () -> show(true), false, browsing, () -> true)
			.gap()
			.add("\uf0c1", "Add from link", "Install an addon from a GitHub repo or its feed", () -> AddAddonPanel.open(here()));
		content.add(views);
		if (browsing) {
			browse(content);
			return;
		}
		content.add(new Button(() -> "Update all (" + updates.updatable().size() + ")", updates::updateAll).accent()
			.tooltip("Downloads every update now; they install when you quit.").visible(() -> updates.updatable().size() > 1));
		for (Addon a : Myriad.addons()) content.add(new AddonEntry(a));
	}

	/** The workspace this window is on: its popups open there. */
	private int here() {
		return window() != null ? window().workspace() : Myriad.ui().activeWorkspace();
	}

	private void show(boolean library) {
		if (browsing == library) return;
		browsing = library;
		if (browsing) updates.refreshLibrary().whenComplete((v, e) -> Async.onRenderThread(this::rebuild));
		rebuild();
	}

	// ---- the Library ------------------------------------------------------------------------------------------------

	/**
	 * The Myriad Library: addons Myriad has checked, searched here (nothing typed leaves the game). Verified ones are built
	 * from their public source; Reviewed ones are closed source, read by Myriad. Neither badge means safe.
	 */
	private void browse(VBox content) {
		content.add(new TextField(() -> query).onChange(q -> query = q).placeholder("Search the Library").search());
		if (updates.libraryLoading() && updates.libraryAddons().isEmpty()) content.add(new Label("Reading the Library…").dim());
		String problem = updates.libraryProblem();
		if (problem != null) content.add(updates.libraryAddons().isEmpty() ? new Note("Can't show the Library: " + problem + ".").error()
			: new Note("Showing the Library as it was last read: " + problem + "."));
		List<UpdateManager.LibraryAddon> list = updates.libraryAddons();
		if (list.isEmpty() && problem == null && !updates.libraryLoading()) content.add(new Label("Nothing in the Library yet.").dim());
		for (UpdateManager.LibraryAddon a : list) content.add(new LibraryCard(a).visible(() -> matches(a)));
		// The key and who's signed in sit at the window's foot, below the list.
		content.add(Spacer.fill());
		content.add(new Label("Verified: built by Myriad from its public source. Reviewed: closed source, read by Myriad before each release. "
			+ "Neither means safe: install only what you trust.").dim());
		var account = updates.account();
		if (account.signedIn()) {
			Toolbar who = new Toolbar();
			who.add("\uf007", "Signed in as " + account.name(), "Your Myriad session, for invite-only addons", () -> {
			});
			who.gap();
			who.add("\uf08b", "Sign out", "Ends the session here and on Myriad's API", () -> account.signOut().whenComplete((confirmed, e) -> Async.onRenderThread(() -> {
				if (!Boolean.TRUE.equals(confirmed)) Myriad.notifications().toast("Signed out here", "Myriad's API couldn't be reached; the session lapses there after 30 days unused").show();
				rebuild();
			})));
			content.add(who);
		}
	}

	private boolean matches(UpdateManager.LibraryAddon a) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return true;
		StringBuilder text = new StringBuilder(a.name() + " " + a.id() + " " + a.description() + " " + a.author());
		UpdateManager.LibraryDetails d = updates.libraryDetails(a.id(), a.detailsSha(), () -> {
		});
		if (d != null) for (UpdateManager.LibraryModule m : d.modules()) text.append(' ').append(m.name());
		return text.toString().toLowerCase(Locale.ROOT).contains(q);
	}

	/**
	 * Installs a Library addon in one go: downloaded, then every check (the Library's signed index, its version
	 * signature, the developer's, size and hash, the jar itself) before anything is written. The card already says what
	 * it is and who vouched for it, so there's no second confirmation (Add from link, with no Library behind it, keeps one).
	 */
	private void lookUpLibrary(String id) {
		if (libraryLooking != null) return;
		libraryLooking = id;
		libraryNotes.remove(id);
		rebuild();
		updates.lookUpLibrary(id).whenComplete((o, err) -> Async.onRenderThread(() -> {
			libraryLooking = null;
			if (err != null) libraryNotes.put(id, "Can't install it: " + UpdateManager.reasonOf(err) + ".");
			else {
				try {
					updates.install(o);
					libraryNotes.put(id, o.name() + " " + o.version() + " loads when you restart the game.");
				} catch (java.io.IOException e) {
					libraryNotes.put(id, "Can't install it: " + e.getMessage() + ".");
				}
			}
			rebuild();
		}));
	}

	/** A fold with a few things in it, closed at first, remembered while the window is open. */
	private Collapsible fold(String key, Supplier<String> title, Widget body, Supplier<String> hint) {
		return new Collapsible(title, body, () -> unfolded.contains(key), open -> {
			if (open) unfolded.add(key);
			else unfolded.remove(key);
		}).indent(10).hint(hint);
	}

	/** "What's new in 2.9.0": the first few lines, the rest in a fold. */
	private void whatsNew(VBox body, String key, UpdateManager.Changelog log) {
		if (log == null || log.text().isBlank()) return;
		List<String> lines = log.text().lines().filter(l -> !l.isBlank()).toList();
		VBox all = new VBox(2, 0);
		for (String l : lines) {
			// Markdown's emphasis and code marks go; list items get a bullet.
			String t = l.replace("**", "").replace("`", "").strip();
			if (t.startsWith("- ") || t.startsWith("* ")) t = "\u2022 " + t.substring(2);
			else if (t.startsWith("#")) t = t.replaceFirst("^#+\\s*", "");
			all.add(new Label(t).dim());
		}
		body.add(fold(key + "/changelog", () -> "What's new in " + log.version(), all, () -> lines.size() + (lines.size() == 1 ? " line" : " lines")));
	}

	/** Adds "3 modules" to a "  ·  " list, or nothing when there are none. */
	private static void count(StringBuilder sb, int n, String what) {
		if (n == 0) return;
		if (!sb.isEmpty()) sb.append("  ·  ");
		sb.append(n).append(' ').append(what).append(n == 1 ? "" : "s");
	}

	/** One Library listing: what it is, what it adds, what's new, and Install. */
	private final class LibraryCard extends CardEntry {
		private final UpdateManager.LibraryAddon a;
		/** Its details came in: the body is built again to show them. */
		private boolean detailsIn;

		LibraryCard(UpdateManager.LibraryAddon a) {
			// As it was: a rebuild (every click that changes something) makes new cards, which mustn't fold and unfold again.
			super(a.installed(), unfolded.contains("library/" + a.id()));
			this.a = a;
		}

		@Override
		protected String title() {
			return a.name();
		}

		@Override
		protected Identifier iconTexture(int px) {
			return AddonIcons.library(a.id(), a.iconSha(), () -> updates.libraryIcon(a.id(), a.iconSha()), px);
		}

		@Override
		protected String subtitle() {
			StringBuilder sb = new StringBuilder(a.author().isEmpty() ? "" : "By " + a.author());
			count(sb, a.moduleCount(), "module");
			return sb.isEmpty() ? a.id() : sb.toString();
		}

		@Override
		protected boolean isOn() {
			return a.installed();
		}

		@Override
		protected int accent() {
			return a.verified() ? theme().green.argb() : theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains("library/" + a.id());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add("library/" + a.id());
			else unfolded.remove("library/" + a.id());
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			if (a.installed()) right = badge(c, " Installed", right, cy, theme().green.argb(), false) - 4;
			if (a.invite()) right = badge(c, a.granted() ? " Invited" : " Invite only", right, cy, theme().accent.argb(), !a.granted()) - 4;
			right = badge(c, a.verified() ? " Verified" : " Reviewed", right, cy, a.verified() ? theme().green.argb() : theme().accent.argb(), true) - 4;
			if (a.version() != null) right = badge(c, a.version(), right, cy, theme().textDim.argb(), false) - 4;
			return right;
		}

		@Override
		protected String tooltipText() {
			return (a.description().isEmpty() ? "" : a.description() + "\n") + (a.verified()
				? "Verified: Myriad built it from its public source and checked the release is exactly that build."
				: "Reviewed: closed source; Myriad read each release before listing it.");
		}

		@Override
		protected Object bodyKey() {
			return List.of(a, String.valueOf(libraryLooking), libraryNotes.getOrDefault(a.id(), ""), detailsIn,
				updates.account().signedIn(), updates.account().busy());
		}

		@Override
		protected void buildBody(VBox body) {
			String key = "library/" + a.id();
			if (!a.description().isEmpty()) body.add(new Label(a.description()));
			// What it adds, before it's installed: fetched when the card opens, checked against the Library's hash.
			UpdateManager.LibraryDetails adds = updates.libraryDetails(a.id(), a.detailsSha(), () -> detailsIn = true);
			if (adds == null && a.detailsSha() != null) body.add(new Label("Reading what it adds…").dim());
			if (adds != null && !adds.modules().isEmpty()) {
				VBox mods = new VBox(1, 0);
				for (UpdateManager.LibraryModule m : adds.modules()) {
					Label l = new Label("• " + m.name() + (m.tag().isEmpty() ? "" : "  ·  " + m.tag()));
					mods.add(m.description().isEmpty() ? l.dim() : l.dim().tooltip(m.description()));
				}
				int n = adds.modules().size();
				body.add(fold(key + "/modules", () -> n + (n == 1 ? " module" : " modules"), mods, () -> ""));
			}
			if (adds != null) whatsNew(body, key, adds.changelog());
			VBox details = new VBox(2, 0);
			if (!a.author().isEmpty()) details.add(new Label("By " + a.author()).dim());
			details.add(new Label(a.id() + (a.keyId() == null ? "" : "  ·  developer key " + a.keyId())).dim());
			if (a.version() == null) details.add(new Label("No release for Minecraft " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("") + (a.newestElsewhere() == null ? "" : " (newest: " + a.newestElsewhere() + ")") + ".").dim());
			body.add(fold(key + "/details", () -> "Details", details, () -> ""));

			String note = libraryNotes.get(a.id());
			if (note != null) body.add(note.startsWith("Can't") ? new Note(note).error() : new Note(note).color(() -> theme().green.argb()));
			// Invite-only and not yet granted: signing in, then the code, in place of Install.
			if (a.invite() && !a.granted()) {
				invitePrompt(body, a);
				return;
			}
			// The foot: the source on the left, Install on the right.
			Toolbar foot = new Toolbar();
			if (a.repo() != null) {
				String url = "https://github.com/" + a.repo();
				foot.add("", a.verified() ? "Source" : "Releases", url, () -> Blaze3D.openUri(URI.create(url)));
			}
			foot.gap();
			if (!a.installed() && a.version() != null && !updates.libraryStale() && !libraryNotes.getOrDefault(a.id(), "").contains("loads when you restart")) {
				foot.add("", a.id().equals(libraryLooking) ? "Installing…" : "Install " + a.version(), "Downloads it and checks every signature first; nothing is written unless they all hold.",
					() -> lookUpLibrary(a.id()), false, true, () -> true);
			}
			if (!foot.isEmpty()) body.add(foot);
		}
	}

	/** An invite-only listing the account can't install yet: sign in with Minecraft, then redeem the developer's code. */
	private void invitePrompt(VBox body, UpdateManager.LibraryAddon a) {
		var account = updates.account();
		if (!account.signedIn()) {
			body.add(new Note("Sign in with Minecraft, then enter the code its developer gave you.").icon("\uf023"));
			body.add(new Button(() -> account.busy() ? "Signing in…" : "Sign in with Minecraft", () -> signIn(a.id())).accent()
				.tooltip("Proves which Minecraft account you are, the way joining a server does: your login goes only to Mojang, never to Myriad."));
			return;
		}
		body.add(new Note("Enter the code its developer gave you.").icon("\uf023"));
		HBox row = new HBox(4);
		row.addWeighted(new TextField(() -> codeDrafts.getOrDefault(a.id(), "")).onChange(t -> codeDrafts.put(a.id(), t)).onSubmit(t -> redeem(a.id()))
			.placeholder("myr-ABCDE-FGHIJ-…"), 1);
		row.addFixed(new Button(() -> a.id().equals(libraryLooking) ? "Installing…" : "Redeem and install", () -> redeem(a.id())).accent(), 96);
		body.add(row);
	}

	private void signIn(String id) {
		var account = updates.account();
		if (account.busy()) return;
		libraryNotes.remove(id);
		account.link().whenComplete((v, err) -> Async.onRenderThread(() -> {
			if (err != null) libraryNotes.put(id, "Can't sign in: " + UpdateManager.reasonOf(err) + ".");
			else Myriad.notifications().toast("Signed in", "Myriad knows you as " + account.name()).show();
			rebuild();
		}));
		rebuild();
	}

	private void redeem(String id) {
		String code = codeDrafts.getOrDefault(id, "");
		if (code.isBlank()) return;
		libraryNotes.remove(id);
		updates.account().redeem(code).whenComplete((name, err) -> Async.onRenderThread(() -> {
			if (err != null) libraryNotes.put(id, "Can't redeem it: " + UpdateManager.reasonOf(err) + ".");
			else if (!updates.account().grants().contains(id)) {
				// A code for another addon: that one's granted, this one isn't.
				codeDrafts.remove(id);
				libraryNotes.put(id, "That code was for " + name + ", not this addon: you can install " + name + " from Browse.");
			} else {
				// Redeeming a code on an addon's card means installing it: straight on to that.
				codeDrafts.remove(id);
				rebuild();
				lookUpLibrary(id);
				return;
			}
			rebuild();
		}));
	}

	// ---- what's installed ---------------------------------------------------------------------------------------------

	private void confirmRemove(Addon addon) {
		List<String> needing = UpdateManager.dependents(addon);
		String message = "Remove " + addon.name() + "? It's taken out of your mods folder when you quit (a copy stays in myriad/updates/old)."
			+ (needing.isEmpty() ? "" : " " + String.join(", ", needing) + (needing.size() == 1 ? " needs" : " need") + " it and won't load without it.");
		((WindowManager) Myriad.ui()).confirmDanger(here(), "Remove " + addon.name(), message, "Remove", () -> {
			try {
				updates.remove(addon);
				Myriad.notifications().toast("Removing " + addon.name(), "It's removed when you quit").show();
			} catch (java.io.IOException e) {
				Myriad.notifications().toast("Can't remove " + addon.name(), e.getMessage()).level(dev.myriadclient.api.service.Notifications.Level.ERROR).show();
			}
			rebuild();
		});
	}

	private final class AddonEntry extends CardEntry {
		private final Addon addon;
		/** Its update's changelog came in: the body is built again to show it. */
		private boolean changelogIn;

		AddonEntry(Addon addon) {
			super(addon.state() == AddonState.LOADED && !updates.removing(addon.id()), unfolded.contains(addon.id()));
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
		protected Identifier iconTexture(int px) {
			return AddonIcons.of(addon, px);
		}

		@Override
		protected String subtitle() {
			String id = addon.id();
			StringBuilder sb = new StringBuilder(addon.authors().isEmpty() ? "" : "By " + String.join(", ", addon.authors()));
			count(sb, Myriad.modules().ownedBy(id).size(), "module");
			int huds = (int) Myriad.panels().ownedBy(id).stream().filter(p -> p.isHud()).count();
			if (huds > 0) sb.append(sb.isEmpty() ? "" : "  ·  ").append(huds).append(" HUD");
			count(sb, Myriad.commands().ownedBy(id).size(), "command");
			return sb.isEmpty() ? id : sb.toString();
		}

		@Override
		protected boolean isOn() {
			return addon.state() == AddonState.LOADED && !updates.removing(addon.id());
		}

		@Override
		protected int accent() {
			return failed() || updates.removing(addon.id()) ? theme().red.argb() : theme().accent.argb();
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
			if (failed()) right = badge(c, " Failed", right, cy, theme().red.argb(), true) - 4;
			if (updates.removing(addon.id())) return badge(c, " Removed when you quit", right, cy, theme().red.argb(), true) - 4;
			UpdateManager.State u = updates.state(addon.id());
			if (u != null) right = switch (u.kind()) {
				case AVAILABLE -> badge(c, " " + u.version(), right, cy, theme().accent.argb(), true) - 4;
				case DOWNLOADING -> badge(c, " " + u.version(), right, cy, theme().textDim.argb(), false) - 4;
				case STAGED -> badge(c, " Restart to apply", right, cy, theme().green.argb(), true) - 4;
				case FAILED -> badge(c, " Update failed", right, cy, theme().red.argb(), true) - 4;
				default -> right;
			};
			if (updates.autoUpdates(addon.id())) right = badge(c, "Auto", right, cy, theme().textDim.argb(), false) - 4;
			LinkInstall.Library vouched = updates.vouched(addon);
			if (vouched != null) right = badge(c, vouched == LinkInstall.Library.VERIFIED ? " Verified" : " Reviewed", right, cy,
				vouched == LinkInstall.Library.VERIFIED ? theme().green.argb() : theme().accent.argb(), false) - 4;
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
			return List.of(String.valueOf(updates.state(addon.id())), updates.autoUpdates(addon.id()), updates.removing(addon.id()), updates.pinnedToLibrary(addon.id()),
				changelogIn);
		}

		@Override
		protected void buildBody(VBox body) {
			String id = addon.id();
			if (failed()) body.add(new Note("Failed to load: " + addon.failure()).error());
			if (updates.removing(id)) body.add(new Note("Removed when you quit. Changed your mind? Keep it below.").error());
			if (addon.description() != null && !addon.description().isEmpty()) body.add(new Label(addon.description()));
			UpdateManager.State u = updates.state(id);
			if (u != null && (u.kind() == UpdateManager.Kind.AVAILABLE || u.kind() == UpdateManager.Kind.STAGED)) {
				whatsNew(body, id, updates.changelog(id, u.version(), () -> changelogIn = true));
			}
			buildUpdate(body, u);
			body.add(fold(id + "/details", () -> "Details", details(), () -> ""));
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
			body.add(foot(u));
		}

		/** Who made it, what it adds, what it costs, and where its updates come from. */
		private VBox details() {
			String id = addon.id();
			VBox d = new VBox(2, 0);
			if (!addon.authors().isEmpty()) d.add(new Label("By " + String.join(", ", addon.authors())).dim());
			d.add(new Label(id + "  ·  " + addon.version()).dim());
			d.add(new Label(() -> counts(id)).dim());
			AddonLoader.Startup started = MyriadImpl.get().addonLoader().startup(id);
			if (started != null) d.add(new Label(started.describe(failed())).dim());
			d.add(new Label(() -> cost(addon)).dim());
			buildSigning(d);
			return d;
		}

		/** The foot of the card: its links on the left, what can be done with it on the right. */
		private Toolbar foot(UpdateManager.State u) {
			String id = addon.id();
			Toolbar t = new Toolbar();
			addon.homepage().ifPresent(url -> t.add("", "Website", url, () -> Blaze3D.openUri(URI.create(url))));
			addon.sources().ifPresent(url -> t.add("", "Source", url, () -> Blaze3D.openUri(URI.create(url))));
			addon.issues().ifPresent(url -> t.add("", "Issues", url, () -> Blaze3D.openUri(URI.create(url))));
			t.gap();
			boolean removing = updates.removing(id);
			if (!removing && u != null && (u.kind() == UpdateManager.Kind.AVAILABLE || u.kind() == UpdateManager.Kind.FAILED)) {
				if (u.releaseUrl() != null) t.add("", "Notes", u.releaseUrl(), () -> Blaze3D.openUri(URI.create(u.releaseUrl())));
				t.add("", "Update to " + u.version(), "Downloads it now; it installs when you quit.", () -> updates.update(id), false, true, () -> true);
			}
			if (!removing && updates.canAutoUpdate(addon)) {
				boolean on = updates.autoUpdates(id);
				t.add(on ? "" : "", on ? "Auto update on" : "Auto update", on
						? "Signed updates download on their own and install next restart. Click to turn off."
						: "Let signed updates download on their own; each installs next restart, never mid-game.",
					() -> {
						updates.setAutoUpdates(addon, !on);
						rebuild();
					}, false, on, () -> true);
			}
			if (removing) {
				t.add("", "Keep it", "Don't remove it after all", () -> {
					updates.keep(id);
					rebuild();
				}, false, true, () -> true);
			} else if (updates.removable(addon)) {
				t.add("", "Remove", "Takes it out of your mods folder when you quit", () -> confirmRemove(addon), true, false, () -> true);
			}
			return t;
		}

		/** Whether its updates are signed, where they come from, and a token for a private source. */
		private void buildSigning(VBox body) {
			String id = addon.id();
			if (updates.pinnedToLibrary(id)) {
				HBox row = new HBox(4);
				row.addWeighted(new Label("Updates only through the Myriad Library, which checks each release first.").dim(), 1);
				row.addFixed(new Button("Unpin", () -> {
					updates.unpinFromLibrary(id);
					rebuild();
				}).quiet().tooltip("Update from its own source instead, without the Library's checks (its developer's signature is still needed)."), 46);
				body.add(row);
				return;
			}
			String source = UpdateManager.sourceOf(addon);
			if (source == null) return;
			List<String> keys = UpdateManager.signingKeys(addon);
			body.add(new Label(keys.isEmpty() ? "Updates from " + source + ", unsigned."
				: "Updates from " + source + ", signed with key " + String.join(" or ", keys) + ".").dim()
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
			body.add(fold(id + "/private-source", () -> "Private source", token, () -> updates.hasToken(id) ? "token saved" : ""));
		}

		private void buildUpdate(VBox body, UpdateManager.State u) {
			if (u == null || updates.removing(addon.id())) return;
			switch (u.kind()) {
				case FAILED -> body.add(new Note("Updating to " + u.version() + " failed: " + u.message()).error());
				case DOWNLOADING -> body.add(new Label("Downloading " + u.version() + "…").dim());
				case STAGED -> body.add(new Note(u.version() + " installs when you quit." + (u.message() != null ? " " + u.message() + "." : ""))
					.color(() -> theme().green.argb()));
				default -> {
					// Up to date, checking, or not checked: said in Details' update line, not here.
				}
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

		/** What it costs while the game runs, from the profiler when it's on. */
		private static String cost(Addon a) {
			Profiler p = MyriadImpl.get().events().profiler();
			if (p == null) return "Running cost: open the Profiler to measure it.";
			double ms = 0;
			for (Profiler.Entry e : p.snapshot()) {
				if (!e.isJob() && (a.id().equals(e.addon()) || a.name().equals(e.addon()))) ms += e.totalMs();
			}
			return String.format(Locale.ROOT, "Running cost: %.2f ms each second", ms);
		}
	}
}
