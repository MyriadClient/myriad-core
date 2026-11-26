package dev.myriadclient.impl.update;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.event.events.GameReadyEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.impl.ui.UiScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks for newer releases of Myriad and its addons, downloads the ones the user picks and installs them when the game
 * exits (see {@link UpdateInstaller}). An addon is checked when its {@code fabric.mod.json} names a GitHub repo or an
 * update feed (see {@link UpdateSource}) and it runs from a jar.
 * <p>
 * An addon whose installed jar lists signing keys ({@link DevKey}) takes only updates signed with one of them; core
 * takes only updates signed with Myriad's root keys ({@link RootKeys}). An addon that lists none updates as before,
 * marked unsigned. Every download is also checked against its size and SHA-256 and, before it's staged, read as the
 * loader will read it ({@link JarCheck}).
 */
public final class UpdateManager {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Updates");
	private static final String TOAST_KEY = "myriad:updates";

	public enum Kind {
		CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING,
		/** Downloaded; installs when the game exits. */
		STAGED,
		/** The download or its checks failed; {@code message} says why. It can be tried again. */
		FAILED,
		/** The check itself failed (offline, no releases yet). */
		UNKNOWN,
		/** Running from a folder (a dev build) or a nested jar, so there's no jar to replace. */
		DEV_BUILD,
		/** Not checked: its {@code fabric.mod.json} names no GitHub repository or feed (or turns updates off). */
		NO_SOURCE
	}

	/** An addon's update state; {@code version} is the release it's about, when there is one. */
	public record State(Kind kind, @Nullable String version, @Nullable String releaseUrl, @Nullable String message) {
	}

	private final UpdateInstaller installer;
	private final FeedTokens tokens;
	private final LibraryClient library;
	/** Addons whose release in {@link #releases} came from the Library (they're held to its key rule too). */
	private final Set<String> fromLibrary = ConcurrentHashMap.newKeySet();
	/** SHA-256 of installed jars, by path, size and modification time: for badges, worked out once. */
	private final Map<String, String> jarHashes = new ConcurrentHashMap<>();
	private final Map<String, State> states = new ConcurrentHashMap<>();
	private final Map<String, Releases.Release> releases = new ConcurrentHashMap<>();
	/** Installed versions to pretend, for testing: {@code -Dmyriad.updates.pretend=myriad=0.1.0,myriad-essentials=0.0.9}. */
	private final Map<String, String> pretend = parsePretend(System.getProperty("myriad.updates.pretend", ""));
	/** Toasts wait until something draws them (the menu or the HUD); render thread only. */
	private final List<Runnable> queuedToasts = new ArrayList<>();
	private volatile boolean checkEnabled = true;
	/** Newer releases for another Minecraft already said ("id message"), kept in the config so each is said once. */
	private final Set<String> noted = ConcurrentHashMap.newKeySet();
	private volatile boolean checking;

	public UpdateManager(Path root) {
		this.installer = new UpdateInstaller(root.resolve("updates"));
		this.tokens = new FeedTokens(root.resolve("updates"));
		this.library = new LibraryClient(root.resolve("updates"));
	}

	/** The ids of the keys {@code a}'s updates must be signed with; empty when its updates aren't signed. */
	public static List<String> signingKeys(Addon a) {
		return trustedKeys(a).stream().map(DevKey::id).toList();
	}

	/** Where {@code a}'s updates come from, for the user; null when it names nowhere. */
	public static @Nullable String sourceOf(Addon a) {
		return UpdateSource.of(a.metadata()).map(UpdateSource::describe).orElse(null);
	}

	/** Whether {@code id} has a token for a private source. */
	public boolean hasToken(String id) {
		return tokens.has(id);
	}

	/** Sets {@code id}'s private-source token (blank removes it); false when it isn't a valid token. */
	public boolean setToken(String id, @Nullable String token) {
		return tokens.set(id, token);
	}

	/** The keys {@code a}'s updates must be signed with: Myriad's root keys for core, else those its jar lists. */
	static List<DevKey> trustedKeys(Addon a) {
		if (a.isCore() && !RootKeys.ALL.isEmpty()) return RootKeys.ALL;
		return DevKey.of(a.metadata());
	}

	/** The notes about other Minecraft versions' releases already shown (see {@link #announce}), for the config. */
	public Set<String> noted() {
		return Set.copyOf(noted);
	}

	public void setNoted(Collection<String> said) {
		noted.clear();
		noted.addAll(said);
	}

	public boolean checkEnabled() {
		return checkEnabled;
	}

	public void setCheckEnabled(boolean enabled) {
		if (checkEnabled == enabled) return;
		checkEnabled = enabled;
		if (Myriad.isReady()) Myriad.config().markDirty();
	}

	/** Called once addons are loaded: reports how last session's updates went and checks once the game is up. */
	public void start(EventBus events) {
		Map<String, UpdateInstaller.Installed> installed = new HashMap<>();
		for (Addon a : Myriad.addons()) {
			Path jar = jarOf(a);
			if (jar != null) installed.put(a.id(), new UpdateInstaller.Installed(a.version(), jar));
		}
		UpdateInstaller.Outcome o = installer.reconcile(installed);
		if (!o.installed().isEmpty()) {
			String names = String.join(", ", o.installed().stream().map(p -> name(p.id()) + " " + p.version()).toList());
			toast("Updated", names, Notifications.Level.SUCCESS);
		}
		if (!o.added().isEmpty()) {
			String names = String.join(", ", o.added().stream().map(p -> name(p.id()) + " " + p.version()).toList());
			toast("Installed", names, Notifications.Level.SUCCESS);
		}
		for (UpdateInstaller.Pending p : o.stillWaiting()) {
			states.put(p.id(), new State(Kind.STAGED, p.version(), null, "Couldn't be installed last time; trying again when you quit"));
			toast("Update not installed", name(p.id()) + " " + p.version() + " couldn't be installed. Trying again when you quit; "
				+ "or move it from myriad/updates into mods yourself.", Notifications.Level.WARNING);
		}
		events.listen(GameReadyEvent.class, e -> {
			if (checkEnabled) check(false);
		});
		events.listen(ScreenOpenEvent.class, e -> {
			if (e.screen() instanceof UiScreen) flushToasts();
		});
		events.listen(WorldEvent.Join.class, e -> flushToasts());
	}

	public @Nullable State state(String id) {
		return states.get(id);
	}

	public boolean isChecking() {
		return checking;
	}

	/** Addons with an update ready to download (or whose download failed and can be retried). */
	public List<String> updatable() {
		List<String> out = new ArrayList<>();
		for (Addon a : Myriad.addons()) {
			State s = states.get(a.id());
			if (s != null && (s.kind() == Kind.AVAILABLE || s.kind() == Kind.FAILED) && releases.containsKey(a.id())) out.add(a.id());
		}
		return out;
	}

	/**
	 * Checks every addon that names a repo or a feed. {@code manual} (the user asked) always reports the result; the
	 * automatic check only speaks up when there's something to install.
	 */
	public void check(boolean manual) {
		if (checking) return;
		checking = true;
		// The Library first: addons pinned to it update only through it, and an installed jar it lists gets pinned.
		library.refresh().handle((index, err) -> {
			for (Addon a : Myriad.addons()) {
				String sha = jarHash(a);
				if (sha != null && library.vouching(a.id(), sha) != null) library.pin(a.id());
			}
			Async.onRenderThread(() -> {
				checking = false;
				checkNow(manual);
			});
			return null;
		});
	}

	private void checkNow(boolean manual) {
		if (checking) return;
		Map<Addon, Optional<UpdateSource>> todo = new LinkedHashMap<>();
		for (Addon a : Myriad.addons()) {
			Optional<UpdateSource> repo = UpdateSource.of(a.metadata());
			if (repo.isEmpty() && !library.pinned(a.id())) {
				states.put(a.id(), new State(Kind.NO_SOURCE, null, null, "Not checked: it names nowhere to update from"));
				continue;
			}
			State s = states.get(a.id());
			if (s != null && (s.kind() == Kind.DOWNLOADING || s.kind() == Kind.STAGED)) continue;
			if (jarOf(a) == null) {
				states.put(a.id(), new State(Kind.DEV_BUILD, null, null, "Running from a dev build; updates are off"));
				continue;
			}
			todo.put(a, repo);
		}
		if (todo.isEmpty()) {
			if (manual) toast("Updates", "Nothing to check", Notifications.Level.INFO);
			return;
		}
		checking = true;
		AtomicInteger left = new AtomicInteger(todo.size());
		for (Map.Entry<Addon, Optional<UpdateSource>> entry : todo.entrySet()) {
			Addon a = entry.getKey();
			String id = a.id(), installed = installedVersion(a);
			states.put(id, new State(Kind.CHECKING, null, null, null));
			boolean mustBeSigned = !trustedKeys(a).isEmpty();
			CompletableFuture<Releases.Found> look;
			if (library.pinned(id)) {
				look = fromLibrary(id);
			} else {
				fromLibrary.remove(id);
				look = find(id, entry.getValue().orElseThrow(), jarOf(a).getFileName().toString(), installed);
			}
			look.whenComplete((found, err) -> {
				Releases.Release r = found == null ? null : found.forThisMinecraft();
				Releases.Release newest = found == null ? null : found.newest();
				if (err != null) {
					String why = reason(err);
					LOG.info("Could not check {} for updates: {}", id, why);
					states.put(id, new State(Kind.UNKNOWN, null, null, why.contains("HTTP 404") ? "No releases yet" : "Couldn't check: " + why));
				} else if (newest == null) {
					states.put(id, new State(Kind.UNKNOWN, null, null, "No releases yet"));
				} else if (r == null || !Releases.isNewer(r.version(), installed)) {
					// Up to date for this Minecraft; a newer release for another one is only said, never installed.
					boolean elsewhere = Releases.isNewer(newest.version(), installed) && newest != r;
					String note = elsewhere ? newest.version() + " is out for Minecraft " + newest.minecraftShown() : null;
					states.put(id, new State(Kind.UP_TO_DATE, r != null ? r.version() : installed, elsewhere ? newest.url() : r != null ? r.url() : null, note));
				} else if (r.asset() == null) {
					states.put(id, new State(Kind.UNKNOWN, r.version(), r.url(), r.version() + " is out, but its release has no jar to install"));
				} else if (mustBeSigned && !r.asset().signed()) {
					// Not worth downloading: it would be refused.
					states.put(id, new State(Kind.UNKNOWN, r.version(), r.url(), r.version() + " is out, but isn't signed by its developer yet"));
				} else {
					releases.put(id, r);
					states.put(id, new State(Kind.AVAILABLE, r.version(), r.url(), null));
				}
				if (left.decrementAndGet() == 0) Async.onRenderThread(() -> {
					checking = false;
					announce(manual);
				});
			});
		}
	}

	private void announce(boolean manual) {
		// Newer releases only for another Minecraft: said once each (every time when asked), as there's nothing to install.
		for (Addon a : Myriad.addons()) {
			State s = states.get(a.id());
			if (s == null || s.kind() != Kind.UP_TO_DATE || s.message() == null) continue;
			if (noted.add(a.id() + " " + s.message()) || manual) {
				toast("Update for another Minecraft", a.name() + " " + s.message() + "; you're on " + minecraftVersion().getFriendlyString() + ".", Notifications.Level.INFO);
				if (Myriad.isReady()) Myriad.config().markDirty();
			}
		}
		List<String> ids = updatable();
		if (ids.isEmpty()) {
			if (!manual) return;
			// Only claim "up to date" for what was actually checked, and say what wasn't and why.
			List<String> unchecked = new ArrayList<>();
			int upToDate = 0;
			for (Addon a : Myriad.addons()) {
				State s = states.get(a.id());
				if (s == null) continue;
				if (s.kind() == Kind.UP_TO_DATE) upToDate++;
				else if (s.kind() == Kind.UNKNOWN || s.kind() == Kind.NO_SOURCE) unchecked.add(a.name() + " (" + s.message() + ")");
			}
			if (unchecked.isEmpty()) toast("Up to date", upToDate + (upToDate == 1 ? " addon" : " addons") + " checked, all on their latest releases", Notifications.Level.SUCCESS);
			else toast("Couldn't check everything", upToDate + " up to date; " + String.join(", ", unchecked), Notifications.Level.WARNING);
			return;
		}
		String list = String.join(", ", ids.stream().map(id -> name(id) + " " + states.get(id).version()).toList());
		toast(ids.size() == 1 ? "Update available" : "Updates available", list + ". Open Addons to install.", Notifications.Level.INFO);
	}

	/**
	 * Looks up what {@code link} (a GitHub repo or a feed) offers to install: downloaded and checked, nothing written.
	 * Fails with why it can't be installed.
	 */
	public CompletableFuture<LinkInstall.Offer> lookUp(String link, @Nullable String token) {
		Set<String> myriadIds = otherAddons("");
		return LinkInstall.look(link, token == null || token.isBlank() ? null : token.strip(), minecraftVersion(), coreVersionAfterUpdates(), myriadIds,
			id -> FabricLoader.getInstance().isModLoaded(id) || addon(id) != null, modsFolder());
	}

	/** Installs what {@link #lookUp} offered into the mods folder; it loads at the next start. */
	public void install(LinkInstall.Offer offer) throws IOException {
		LinkInstall.install(offer, modsFolder());
		installer.added(offer.id(), offer.version());
		if (offer.token() != null) tokens.set(offer.id(), offer.token());
		if (offer.library() != null) library.pin(offer.id());
		LOG.info("Installed {} {} from {} ({}); it loads at the next start", offer.id(), offer.version(), offer.from(),
			offer.signed() ? "signed by key " + offer.keyId() : "unsigned");
	}

	private static Path modsFolder() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	/**
	 * A pinned addon's releases, from the Library alone: when it's not listed now, or the Library can't be read or is out
	 * of date, it gets no update (never one from its own source instead, which would skip the Library's checks).
	 */
	private CompletableFuture<Releases.Found> fromLibrary(String id) {
		LibraryIndex.Index index = library.index();
		if (index == null) return CompletableFuture.failedFuture(new IOException("the Myriad Library couldn't be reached" + problemSuffix()));
		if (index.stale()) return CompletableFuture.failedFuture(new IOException("the Myriad Library's information is out of date"));
		LibraryIndex.Entry e = index.entry(id);
		if (e == null) return CompletableFuture.failedFuture(new IOException("it's not in the Myriad Library right now, so it gets no updates (unpin it in Addons to update from its own source)"));
		fromLibrary.add(id);
		return CompletableFuture.completedFuture(Releases.newest(LibraryInstall.releases(e), minecraftVersion()));
	}

	private String problemSuffix() {
		String p = library.problem();
		return p == null ? "" : ": " + p;
	}

	// ---- the Library, for the Addons window -------------------------------------------------------------------------

	/** One Library listing as Browse shows it. {@code version}: the newest for this Minecraft, or null when none works here. */
	public record LibraryAddon(String id, String name, String description, String author, @Nullable String repo, boolean verified,
		@Nullable String version, @Nullable String minecraft, @Nullable String newestElsewhere, boolean installed, @Nullable String keyId) {
	}

	/** Fetches the Library's index (only changes), for Browse. */
	public CompletableFuture<Void> refreshLibrary() {
		return library.refresh().handle((i, e) -> null);
	}

	public boolean libraryLoading() {
		return library.loading();
	}

	/** Why the Library can't be shown or used right now, or null. */
	public @Nullable String libraryProblem() {
		return library.problem();
	}

	/** Whether the Library's information is too old to install from (it can still be browsed). */
	public boolean libraryStale() {
		LibraryIndex.Index i = library.index();
		return i != null && i.stale();
	}

	/** Every listing, for Browse; empty until the index has been read. */
	public List<LibraryAddon> libraryAddons() {
		LibraryIndex.Index index = library.index();
		if (index == null) return List.of();
		Version mc = minecraftVersion();
		List<LibraryAddon> out = new ArrayList<>();
		for (LibraryIndex.Entry e : index.entries()) {
			LibraryIndex.Offered here = LibraryInstall.forMinecraft(e, mc);
			LibraryIndex.Offered newest = e.versions().isEmpty() ? null : e.versions().getFirst();
			String key = e.listing().keys().isEmpty() ? null : e.listing().keys().getFirst().id();
			boolean installed = FabricLoader.getInstance().isModLoaded(e.listing().id()) || addon(e.listing().id()) != null || installer.pendingAdded(e.listing().id());
			out.add(new LibraryAddon(e.listing().id(), e.name(), e.description(), e.author(), e.repo(), e.verified(), here == null ? null : here.version().version(),
				here == null ? null : here.minecraft(), here == null && newest != null ? newest.version().version() + " for Minecraft " + newest.minecraft() : null, installed, key));
		}
		out.sort(Comparator.comparing(a -> a.name().toLowerCase(java.util.Locale.ROOT)));
		return out;
	}

	/** Looks up {@code id}'s newest release for this Minecraft in the Library: downloaded and checked, nothing written. */
	public CompletableFuture<LinkInstall.Offer> lookUpLibrary(String id) {
		LibraryIndex.Index index = library.index();
		LibraryIndex.Entry e = index == null ? null : index.entry(id);
		if (e == null) return CompletableFuture.failedFuture(new IOException("it isn't in the Library"));
		LibraryIndex.Offered o = LibraryInstall.forMinecraft(e, minecraftVersion());
		if (o == null) return CompletableFuture.failedFuture(new IOException("it has no release for Minecraft " + minecraftVersion().getFriendlyString()));
		return LibraryInstall.look(index, e, o, minecraftVersion(), coreVersionAfterUpdates(), otherAddons(""),
			x -> FabricLoader.getInstance().isModLoaded(x) || addon(x) != null || installer.pendingAdded(x), modsFolder());
	}

	/** How the Library vouches for {@code a}'s installed jar (exactly that jar, by its hash), or null. */
	public LinkInstall.@Nullable Library vouched(Addon a) {
		String sha = jarHash(a);
		LibraryIndex.Entry e = sha == null ? null : library.vouching(a.id(), sha);
		return e == null ? null : e.verified() ? LinkInstall.Library.VERIFIED : LinkInstall.Library.REVIEWED;
	}

	public boolean pinnedToLibrary(String id) {
		return library.pinned(id);
	}

	/** The player's choice: {@code id} updates from its own source again, not only through the Library. */
	public void unpinFromLibrary(String id) {
		library.unpin(id);
		fromLibrary.remove(id);
		releases.remove(id);
		states.remove(id);
	}

	private @Nullable String jarHash(Addon a) {
		Path jar = jarOf(a);
		if (jar == null) return null;
		try {
			String key = jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
			return jarHashes.computeIfAbsent(key, k -> {
				try {
					return java.util.HexFormat.of().formatHex(JarCheck.sha256(Files.readAllBytes(jar)));
				} catch (IOException e) {
					return "";
				}
			});
		} catch (IOException e) {
			return null;
		}
	}

	/** The releases at {@code source}, for this Minecraft. */
	private CompletableFuture<Releases.Found> find(String id, UpdateSource source, String installedFile, String installed) {
		String token = tokens.get(id);
		return switch (source) {
			case UpdateSource.GitHub g -> GitHubReleases.releases(g.repo(), token, installedFile, installed, minecraftVersion());
			case UpdateSource.Feed f -> Fetch.text(f.uri(), FeedReleases.MAX_FEED, token, "application/json").thenApply(text -> {
				try {
					FeedReleases.Feed feed = FeedReleases.parse(text, f.uri());
					if (!feed.id().equals(id)) throw new IOException("the feed is for " + feed.id() + ", not " + id);
					return Releases.newest(feed.releases(), minecraftVersion());
				} catch (IOException e) {
					throw new CompletionException(e);
				}
			});
		};
	}

	/** Downloads, checks and stages {@code id}'s update. The future never fails; the result is in {@link #state}. */
	public CompletableFuture<Void> update(String id) {
		Releases.Release r = releases.get(id);
		State s = states.get(id);
		Addon addon = addon(id);
		Path jar = addon == null ? null : jarOf(addon);
		if (r == null || r.asset() == null || s == null || jar == null || (s.kind() != Kind.AVAILABLE && s.kind() != Kind.FAILED)) {
			return CompletableFuture.completedFuture(null);
		}
		states.put(id, new State(Kind.DOWNLOADING, r.version(), r.url(), null));
		String token = tokens.get(id);
		List<DevKey> trusted = trustedKeys(addon);
		Set<String> others = otherAddons(id);
		Version core = addon.isCore() ? null : coreVersionAfterUpdates();
		URI origin = UpdateSource.of(addon.metadata()).map(UpdateManager::tokenOrigin).orElse(null);
		return download(id, r, token, origin).thenAccept(got -> {
			Verified v = verify(id, r.version(), got, trusted, addon.isCore(), others, minecraftVersion(), core);
			if (v.problem() != null) throw new CompletionException(new IOException(v.problem()));
			if (fromLibrary.contains(id)) {
				// From the Library: still listed and fresh, and signed by a key the installed jar, the listing and the new jar all name.
				String why = libraryKeyRule(id, v.signer(), got.jar(), r.version(), others, core);
				if (why != null) throw new CompletionException(new IOException(why));
			}
			try {
				installer.stage(id, r.version(), UpdateInstaller.installName(id, addon.version(), r.version(), jar.getFileName().toString()), got.jar(), jar);
			} catch (IOException e) {
				throw new CompletionException(new IOException("couldn't save it to " + installer.dir() + ": " + e.getMessage(), e));
			}
			states.put(id, new State(Kind.STAGED, r.version(), r.url(), null));
			LOG.info("Staged {} {} ({}); it installs when the game exits", id, r.version(), v.signer() == null ? "unsigned" : "signed by key " + v.signer().id());
			Async.onRenderThread(() -> toast("Update ready", name(id) + " " + r.version() + " installs when you quit", Notifications.Level.SUCCESS));
		}).exceptionally(err -> {
			String why = reason(err);
			LOG.warn("Could not update {} to {}: {}", id, r.version(), why);
			states.put(id, new State(Kind.FAILED, r.version(), r.url(), why));
			Async.onRenderThread(() -> toast("Update failed", name(id) + ": " + why, Notifications.Level.ERROR));
			return null;
		});
	}

	private @Nullable String libraryKeyRule(String id, @Nullable DevKey signer, byte[] jar, String version, Set<String> others, @Nullable Version core) {
		LibraryIndex.Index index = library.index();
		LibraryIndex.Entry e = index == null ? null : index.entry(id);
		if (e == null || index.stale()) return "the Myriad Library no longer lists it, or its information is out of date";
		if (signer == null || !e.listing().keys().contains(signer)) return "it isn't signed by a key its Library listing names";
		if (!JarCheck.check(jar, id, version, minecraftVersion(), core, others).keys().contains(signer)) return "the new jar doesn't list the key it's signed with";
		return null;
	}

	/** A download: the jar, and what it must be (from the release, and its {@code .sig} when it has one). */
	record Downloaded(byte[] jar, long size, @Nullable String sha256, List<SignatureCheck.Sig> signatures) {
	}

	/** The jar and its signatures: a GitHub release's {@code .sig} is read first, and must describe this very jar. */
	static CompletableFuture<Downloaded> download(String id, Releases.Release r, @Nullable String token) {
		return download(id, r, token, null);
	}

	/**
	 * {@code origin}: where the token belongs (the feed's address, or GitHub's API); downloads elsewhere go without it,
	 * whatever the release lists.
	 */
	static CompletableFuture<Downloaded> download(String id, Releases.Release r, @Nullable String token, @Nullable URI origin) {
		Releases.Asset a = r.asset();
		CompletableFuture<Releases.Release> described = a.sigUrl() == null ? CompletableFuture.completedFuture(r)
			: Fetch.text(URI.create(a.sigUrl()), 64 * 1024, tokenFor(token, origin, URI.create(a.sigUrl())), "application/octet-stream").thenApply(text -> {
			try {
				Releases.Release sig = FeedReleases.entry(com.google.gson.JsonParser.parseString(text).getAsJsonObject(), URI.create(a.url()));
				if (sig == null || !sig.version().equals(r.version()) || !sig.asset().name().equals(a.name()) || sig.asset().size() != a.size()
					|| (a.sha256() != null && !a.sha256().equals(sig.asset().sha256()))) {
					throw new IOException("its signature file is for another download");
				}
				return new Releases.Release(r.version(), r.url(), new Releases.Asset(a.name(), a.url(), a.size(), sig.asset().sha256(),
					sig.asset().signatures(), a.sigUrl()), r.minecraft());
			} catch (IOException e) {
				throw new CompletionException(e);
			} catch (RuntimeException e) {
				throw new CompletionException(new IOException("its signature file can't be read"));
			}
		});
		return described.thenCompose(d -> {
			Releases.Asset asset = d.asset();
			if (asset.size() <= 0 || asset.size() > JarCheck.MAX_JAR) {
				return CompletableFuture.failedFuture(new IOException("the release lists a size Myriad won't download"));
			}
			return Fetch.bytes(URI.create(asset.url()), asset.size(), tokenFor(token, origin, URI.create(asset.url())), "application/octet-stream")
				.thenApply(bytes -> new Downloaded(bytes, asset.size(), asset.sha256(), asset.signatures()));
		});
	}

	private static @Nullable String tokenFor(@Nullable String token, @Nullable URI origin, URI url) {
		return token != null && origin != null && Fetch.sameOrigin(origin, url) ? token : null;
	}

	/** Where {@code source}'s token belongs. */
	static URI tokenOrigin(UpdateSource source) {
		return switch (source) {
			case UpdateSource.GitHub g -> URI.create("https://api.github.com/");
			case UpdateSource.Feed f -> f.uri();
		};
	}

	/** What {@link #verify} found: the problem, or the key that signed it (null when it's unsigned and may be). */
	record Verified(@Nullable String problem, @Nullable DevKey signer) {
	}

	/**
	 * Every check a download must pass before it's staged, in order: it's the file described, it's signed by a key the
	 * installed version trusts (when that lists any), and it's the mod and version asked for and will load here.
	 */
	static Verified verify(String id, String version, Downloaded got, List<DevKey> trusted, boolean isCore, Set<String> others, Version minecraft,
		@Nullable Version core) {
		String why = JarCheck.verifyDownload(got.jar(), got.size(), got.sha256());
		if (why != null) return new Verified(why, null);
		DevKey signer = null;
		if (!trusted.isEmpty()) {
			if (got.signatures().isEmpty()) return new Verified("it isn't signed, and " + (isCore ? "Myriad" : "this addon") + " only takes signed updates", null);
			ReleaseStatement statement;
			try {
				statement = ReleaseStatement.of(id, version, got.jar(), JarCheck.sha256(got.jar()));
			} catch (IllegalArgumentException e) {
				return new Verified("its version can't be signed for", null);
			}
			signer = SignatureCheck.signer(statement.bytes(), got.signatures(), trusted);
			if (signer == null) return new Verified(isCore ? "it isn't signed with Myriad's keys" : "it isn't signed with its developer's key", null);
		}
		JarCheck.Checked checked = JarCheck.check(got.jar(), id, version, minecraft, core, others);
		if (checked.problem() != null) return new Verified(checked.problem(), signer);
		if (!trusted.isEmpty() && checked.keys().isEmpty()) {
			if (isCore) return new Verified("it drops Myriad's signing keys", signer);
			// Signed by a trusted key, so its developer chose to stop signing.
			LOG.warn("{} {} lists no signing keys: its later updates won't need a signature", id, version);
		}
		return new Verified(null, signer);
	}

	private static Set<String> otherAddons(String id) {
		Set<String> out = new java.util.HashSet<>();
		out.add(Myriad.MOD_ID);
		for (Addon a : Myriad.addons()) out.add(a.id());
		out.remove(id);
		return out;
	}

	/** Updates everything that can be, core first so addons that need the newer core pass their checks. */
	public CompletableFuture<Void> updateAll() {
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		List<String> ids = new ArrayList<>(updatable());
		ids.sort(Comparator.comparing(id -> !id.equals(Myriad.MOD_ID)));
		for (String id : ids) chain = chain.thenCompose(v -> update(id));
		return chain;
	}

	// ---- helpers ----------------------------------------------------------------------------------------------------

	/** The jar {@code a} was loaded from, or null when it runs from a folder or from inside another jar. */
	static @Nullable Path jarOf(Addon a) {
		ModOrigin o = a.container().getOrigin();
		if (o.getKind() != ModOrigin.Kind.PATH || o.getPaths().size() != 1) return null;
		Path p = o.getPaths().getFirst();
		return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? p : null;
	}

	private String installedVersion(Addon a) {
		return pretend.getOrDefault(a.id(), a.version());
	}

	private Version coreVersionAfterUpdates() {
		UpdateInstaller.Pending core = installer.pending(Myriad.MOD_ID);
		return parse(core != null ? core.version() : Myriad.version());
	}

	private static Version minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion();
	}

	private static Version parse(String v) {
		try {
			return Version.parse(v);
		} catch (VersionParsingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static @Nullable Addon addon(String id) {
		for (Addon a : Myriad.addons()) if (a.id().equals(id)) return a;
		return null;
	}

	private static String name(String id) {
		Addon a = addon(id);
		return a == null ? id : a.name();
	}

	/** Why {@code err} happened, for the user. */
	public static String reasonOf(Throwable err) {
		return reason(err);
	}

	private static String reason(Throwable err) {
		while ((err instanceof CompletionException || err instanceof ExecutionException) && err.getCause() != null) err = err.getCause();
		return err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
	}

	private static Map<String, String> parsePretend(String spec) {
		Map<String, String> out = new HashMap<>();
		for (String part : spec.split(",")) {
			int eq = part.indexOf('=');
			if (eq > 0) out.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
		}
		return out;
	}

	// ---- toasts -------------------------------------------------------------------------------------------------------

	/** Shows a toast now if the menu or the HUD is up to draw it, otherwise when one of them next is. Render thread. */
	private void toast(String title, String message, Notifications.Level level) {
		Runnable post = () -> Myriad.notifications().toast(title, message).level(level).lasting(8000).replacing(TOAST_KEY + ":" + title).show();
		Minecraft mc = Minecraft.getInstance();
		// Startup runs before the game's GUI exists.
		if (mc != null && mc.gui != null && (mc.gui.screen() instanceof UiScreen || mc.player != null)) post.run();
		else queuedToasts.add(post);
	}

	private void flushToasts() {
		List<Runnable> now = List.copyOf(queuedToasts);
		queuedToasts.clear();
		now.forEach(Runnable::run);
	}
}
