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
import dev.myriadclient.api.ui.widget.Heading;
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

import org.jetbrains.annotations.Nullable;

/**
 * Addons in one list: what's installed, then what else the Myriad Library offers, both found by one search. A card says
 * what the addon is in a line or two; what's new, what it adds and who made it fold away in the same places on every card,
 * and what can be done with it sits along its foot: install, update, auto update, remove.
 */
public final class AddonsPanel extends WidgetPanel {
	private final Set<String> unfolded = new HashSet<>();
	/** Addons' settings blocks (and core's own sections) folded away; all open at first. */
	private final Set<String> folded = new HashSet<>();
	private final UpdateManager updates = MyriadImpl.get().updates();
	/** Private-source tokens being typed, per addon. */
	private final Map<String, String> tokenDrafts = new HashMap<>();
	/** The search typed (both lists); whether the Library was read since this window opened; one install at a time. */
	private String query = "";
	private boolean libraryAsked;
	private String libraryLooking;
	private final Map<String, String> libraryNotes = new HashMap<>();
	/** {@link #libraryNotes}' key for the account footer's own sign-in. */
	private static final String ACCOUNT = " account";
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
		long failed = Myriad.addons().stream().filter(a -> a.state() == AddonState.FAILED).count();
		long offered = libraryOnly().size();
		return Myriad.addons().size() + " installed" + (failed > 0 ? ", " + failed + " failed" : "") + (offered > 0 ? " · " + offered + " more in the Library" : "");
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
		// The Library (and what the account may install) is read once per window, when it's first shown.
		if (!libraryAsked) {
			libraryAsked = true;
			updates.refreshLibrary().whenComplete((v, e) -> Async.onRenderThread(this::rebuild));
		}
		HBox top = new HBox(4);
		top.addWeighted(new TextField(() -> query).onChange(q -> query = q).placeholder("Search addons and modules").search(), 1);
		top.addFixed(new Button("\uf0c1 Add from link", () -> AddAddonPanel.open(here())).quiet().tooltip("Install an addon from a GitHub repo or its feed"), 84);
		content.add(top);

		List<Addon> installed = Myriad.addons();
		content.add(new Heading("Installed").hint(() -> hint(installed.stream().filter(this::matches).count(), installed.size())));
		content.add(new Button(() -> "Update all (" + updates.updatable().size() + ")", updates::updateAll).accent()
			.tooltip("Downloads every update now; they install when you quit.").visible(() -> updates.updatable().size() > 1));
		for (Addon a : installed) content.add(new AddonEntry(a).visible(() -> matches(a)));
		content.add(new Label(() -> "No installed addon matches \"" + query.strip() + "\".").dim()
			.visible(() -> !query.isBlank() && installed.stream().noneMatch(this::matches)));

		List<UpdateManager.LibraryAddon> library = libraryOnly();
		content.add(new Heading("Myriad Library").hint(() -> library.isEmpty() ? "" : hint(library.stream().filter(this::matches).count(), library.size())));
		if (updates.libraryLoading() && library.isEmpty()) content.add(new Label("Reading the Library…").dim());
		String problem = updates.libraryProblem();
		if (problem != null) content.add(updates.libraryAddons().isEmpty() ? new Note("Can't show the Library: " + problem + ".").error()
			: new Note("Showing the Library as it was last read: " + problem + "."));
		if (library.isEmpty() && problem == null && !updates.libraryLoading()) {
			content.add(new Label(updates.libraryAddons().isEmpty() ? "Nothing in the Library yet." : "You have everything the Library offers.").dim());
		}
		for (UpdateManager.LibraryAddon a : library) content.add(new LibraryCard(a).visible(() -> matches(a)));
		content.add(new Label(() -> "Nothing in the Library matches \"" + query.strip() + "\".").dim()
			.visible(() -> !query.isBlank() && !library.isEmpty() && library.stream().noneMatch(this::matches)));

		// The key to the badges and who's signed in sit at the window's foot, below both lists.
		content.add(Spacer.fill());
		content.add(new Label("Verified: built by Myriad from its public source. Reviewed: closed source, read by Myriad before each release. "
			+ "Neither means safe: install only what you trust.").dim());
		accountFooter(content);
	}

	/** "3 of 12" while searching, else "12". */
	private String hint(long shown, int all) {
		return query.isBlank() ? String.valueOf(all) : shown + " of " + all;
	}

	/** The Library's listings that aren't loaded addons here (installed ones are in the list above, with their badge). */
	private List<UpdateManager.LibraryAddon> libraryOnly() {
		Set<String> loaded = new HashSet<>();
		for (Addon a : Myriad.addons()) loaded.add(a.id());
		return updates.libraryAddons().stream().filter(a -> !loaded.contains(a.id())).toList();
	}

	/**
	 * Who's signed in, at the window's foot on both views: the one place the Myriad account lives. Signing in is only
	 * for invite-only addons; nothing else needs it.
	 */
	private void accountFooter(VBox content) {
		var account = updates.account();
		Toolbar who = new Toolbar();
		if (account.signedIn()) {
			who.add("\uf007", "Signed in as " + account.name(), "Your Myriad session, for invite-only addons", () -> {
			});
			who.gap();
			who.add("\uf08b", "Sign out", "Ends the session here and on Myriad's API", () -> account.signOut().whenComplete((confirmed, e) -> Async.onRenderThread(() -> {
				if (!Boolean.TRUE.equals(confirmed)) Myriad.notifications().toast("Signed out here", "Myriad's API couldn't be reached; the session lapses there after 30 days unused").show();
				rebuild();
			})));
		} else {
			who.add("\uf007", account.busy() ? "Signing in…" : "Sign in with Minecraft",
				"Only for invite-only addons. Proves which Minecraft account you are, the way joining a server does: your login goes only to Mojang, never to Myriad.",
				() -> signIn(ACCOUNT, null));
			String note = libraryNotes.get(ACCOUNT);
			if (note != null) content.add(new Note(note).error());
		}
		content.add(who);
	}

	/** The workspace this window is on: its popups open there. */
	private int here() {
		return window() != null ? window().workspace() : Myriad.ui().activeWorkspace();
	}

	/** Whether an installed addon matches the search: its name, id, description, authors or a module's name. */
	private boolean matches(Addon a) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return true;
		StringBuilder text = new StringBuilder(a.name() + " " + a.id() + " " + java.util.Objects.toString(a.description(), "") + " " + String.join(" ", a.authors()));
		for (var m : Myriad.modules().ownedBy(a.id())) text.append(' ').append(m.name());
		return text.toString().toLowerCase(Locale.ROOT).contains(q);
	}

	private static boolean moduleMatches(UpdateManager.LibraryModule m, String q) {
		return m.name().toLowerCase(Locale.ROOT).contains(q) || m.description().toLowerCase(Locale.ROOT).contains(q);
	}

	/** Whether a Library listing matches the search (nothing typed leaves the game). */
	private boolean matches(UpdateManager.LibraryAddon a) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return true;
		StringBuilder text = new StringBuilder(a.name() + " " + a.id() + " " + a.description() + " " + a.author());
		UpdateManager.LibraryDetails d = updates.libraryDetails(a.id(), a.detailsSha(), () -> {
		});
		if (d != null) for (UpdateManager.LibraryModule m : d.modules()) text.append(' ').append(m.name()).append(' ').append(m.description());
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

	/**
	 * "What's new in 2.9.0", folded: the same place on every card, installed or not. Headings stand out, list items read
	 * as text, anything else is dim.
	 */
	private void whatsNew(VBox body, String key, UpdateManager.Changelog log) {
		if (log == null || log.text().isBlank()) return;
		// Markdown's blocks: a heading, a list item or a paragraph, each running on over its wrapped lines until a blank
		// line or the next block. Emphasis and code marks go; list items get a bullet.
		List<String[]> blocks = new java.util.ArrayList<>(); // {kind, text}: h, li or p
		boolean open = false;
		for (String l : log.text().lines().toList()) {
			String t = l.replace("**", "").replace("`", "").strip();
			if (t.isEmpty()) {
				open = false;
			} else if (t.startsWith("#")) {
				blocks.add(new String[]{"h", t.replaceFirst("^#+\\s*", "")});
				open = false;
			} else if (t.startsWith("- ") || t.startsWith("* ")) {
				blocks.add(new String[]{"li", t.substring(2)});
				open = true;
			} else if (open) {
				String[] last = blocks.getLast();
				last[1] = last[1] + " " + t;
			} else {
				blocks.add(new String[]{"p", t});
				open = true;
			}
		}
		VBox all = new VBox(2, 0);
		int items = 0;
		for (String[] b : blocks) {
			switch (b[0]) {
				case "h" -> all.add(new Label(b[1]).bold());
				case "li" -> {
					all.add(new Label("\u2022 " + b[1]));
					items++;
				}
				default -> all.add(new Label(b[1]).dim());
			}
		}
		int n = items;
		body.add(fold(key + "/changelog", () -> "What's new in " + log.version(), all, () -> n == 0 ? "" : n + (n == 1 ? " change" : " changes")));
	}

	/**
	 * One row of a card's details: what it is, dim, in a short column on the left; the value beside it. Kept to a few
	 * short rows so a narrow window still fits them side by side.
	 */
	private static void fact(VBox details, String name, Widget value) {
		HBox row = new HBox(6);
		row.addFixed(new Label(name).dim().oneLine(), 52);
		row.addWeighted(value, 1);
		details.add(row);
	}

	private static void fact(VBox details, String name, String value) {
		fact(details, name, new Label(value));
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
			// Open, the card shows its description itself.
			return (a.description().isEmpty() || isExpanded() ? "" : a.description() + "\n") + (a.verified()
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
			if (adds != null) whatsNew(body, key, adds.changelog());
			if (adds != null && !adds.modules().isEmpty()) {
				// Each module: its name and tag on one line, what it does under it.
				VBox mods = new VBox(4, 0);
				for (UpdateManager.LibraryModule m : adds.modules()) {
					VBox one = new VBox(1, 0);
					HBox head = new HBox(6);
					head.addWeighted(new Label(m.name()), 1);
					if (!m.tag().isEmpty()) head.addFixed(new Label(m.tag()).dim().oneLine(), 64);
					one.add(head);
					if (!m.description().isEmpty()) one.add(new Label(m.description()).dim());
					// Searching for a module: only the ones that match, unless the search matched the addon itself.
					mods.add(one).visible(() -> {
						String q = query.strip().toLowerCase(Locale.ROOT);
						return q.isEmpty() || adds.modules().stream().noneMatch(x -> moduleMatches(x, q)) || moduleMatches(m, q);
					});
				}
				int n = adds.modules().size();
				body.add(fold(key + "/modules", () -> "What it adds", mods, () -> n + (n == 1 ? " module" : " modules")));
			}
			// What the header, badges and foot don't already say.
			VBox details = new VBox(3, 0);
			if (!a.author().isEmpty()) fact(details, "Author", a.author());
			fact(details, "ID", a.id());
			if (a.keyId() != null) fact(details, "Key", new Label(a.keyId()).tooltip("The developer key its releases are signed with; each is checked before it's installed."));
			if (a.version() == null) fact(details, "Release", "none for Minecraft " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("") + (a.newestElsewhere() == null ? "" : " (newest: " + a.newestElsewhere() + ")"));
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
		signIn(id, null);
	}

	/** Signs in with Minecraft; {@code then} runs once signed in. */
	private void signIn(String id, @Nullable Runnable then) {
		var account = updates.account();
		if (account.busy()) return;
		libraryNotes.remove(id);
		account.link().whenComplete((v, err) -> Async.onRenderThread(() -> {
			if (err != null) libraryNotes.put(id, "Can't sign in: " + UpdateManager.reasonOf(err) + ".");
			else {
				Myriad.notifications().toast("Signed in", "Myriad knows you as " + account.name()).show();
				if (then != null) then.run();
			}
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
				libraryNotes.put(id, "That code was for " + name + ", not this addon: you can install " + name + " from the Library below.");
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
			String d = isExpanded() ? null : addon.description(); // open, the card shows it itself
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
			buildUpdate(body, u);
			// What's new: in the update waiting for it, else in the version it runs (from its Library listing).
			boolean newer = u != null && (u.kind() == UpdateManager.Kind.AVAILABLE || u.kind() == UpdateManager.Kind.STAGED || u.kind() == UpdateManager.Kind.FAILED);
			whatsNew(body, id, updates.changelog(id, newer ? u.version() : addon.version(), () -> changelogIn = true));
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

		/**
		 * What the header, badges and foot don't already say: who made it, its id, how long it took to start, and the
		 * key its updates are checked against. (Its running cost is in the Profiler.)
		 */
		private VBox details() {
			String id = addon.id();
			VBox d = new VBox(3, 0);
			if (!addon.authors().isEmpty()) fact(d, "Author", String.join(", ", addon.authors()));
			fact(d, "ID", id);
			AddonLoader.Startup started = MyriadImpl.get().addonLoader().startup(id);
			if (started != null) {
				String full = started.describe(failed());
				int split = full.indexOf(" (");
				fact(d, "Startup", new Label((split < 0 ? full : full.substring(0, split)).replace("Started in ", "")).tooltip(full));
			}
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

		/**
		 * The key its updates are checked against (where they come from is its Source button), whether it's pinned to
		 * the Library, and a token for a private source.
		 */
		private void buildSigning(VBox body) {
			String id = addon.id();
			String source = UpdateManager.sourceOf(addon);
			List<String> keys = UpdateManager.signingKeys(addon);
			if (source != null || !keys.isEmpty()) {
				fact(body, "Key", new Label(keys.isEmpty() ? "none, unsigned" : String.join(" or ", keys))
					.tooltip(keys.isEmpty() ? "Its developer doesn't sign its releases, so an update is only as safe as " + source + "."
						: "Each update is checked against its developer's signature before it's installed."));
			}
			if (updates.pinnedToLibrary(id)) {
				HBox row = new HBox(4);
				row.addWeighted(new Label("Library only").tooltip("Updates only through the Myriad Library, which checks each release first."), 1);
				row.addFixed(new Button("Unpin", () -> {
					updates.unpinFromLibrary(id);
					rebuild();
				}).quiet().tooltip("Update from its own source instead, without the Library's checks (its developer's signature is still needed)."), 46);
				fact(body, "Updates", row);
				return;
			}
			if (source == null) return;
			// A token only matters for a private repo or feed: shown once one is saved (to replace or forget it), or when
			// the source couldn't be read (a private repo looks like one with no releases). Add from link takes one too.
			UpdateManager.State s = updates.state(id);
			boolean unreachable = s != null && s.kind() == UpdateManager.Kind.UNKNOWN && s.version() == null;
			if (!updates.hasToken(id) && !unreachable) return;
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
			body.add(fold(id + "/private-source", () -> "Access token", token, () -> updates.hasToken(id) ? "saved" : "for a private source"));
		}

		private void buildUpdate(VBox body, UpdateManager.State u) {
			if (u == null || updates.removing(addon.id())) return;
			String id = addon.id();
			if ((u.kind() == UpdateManager.Kind.AVAILABLE || u.kind() == UpdateManager.Kind.FAILED) && updates.needsSignIn(id)) {
				// Invite-only: its updates come with the account's session. Signing in goes straight on to the update.
				String note = libraryNotes.get(id);
				body.add(note != null ? new Note(note).error() : new Note(u.version() + " is ready. It's invite-only: sign in with Minecraft to update it.").icon("\uf023"));
				body.add(new Button(() -> updates.account().busy() ? "Signing in…" : "Sign in and update", () -> signIn(id, () -> updates.update(id))).accent()
					.tooltip("Proves which Minecraft account you are, the way joining a server does: your login goes only to Mojang, never to Myriad."));
				return;
			}
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
	}
}
