package dev.myriadclient.impl.update;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.event.events.GameReadyEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.api.util.Http;
import dev.myriadclient.impl.ui.DesktopScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks GitHub for newer releases of Myriad and its addons, downloads the ones the user picks and installs them when the
 * game exits (see {@link UpdateInstaller}). An addon is checked when its {@code fabric.mod.json} names a GitHub repo
 * (see {@link UpdateSource}) and it runs from a jar.
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
		/** Not checked: its {@code fabric.mod.json} names no GitHub repository (or turns updates off). */
		NO_SOURCE
	}

	/** An addon's update state; {@code version} is the release it's about, when there is one. */
	public record State(Kind kind, @Nullable String version, @Nullable String releaseUrl, @Nullable String message) {
	}

	private final UpdateInstaller installer;
	private final Map<String, State> states = new ConcurrentHashMap<>();
	private final Map<String, GitHubReleases.Release> releases = new ConcurrentHashMap<>();
	/** Installed versions to pretend, for testing: {@code -Dmyriad.updates.pretend=myriad=0.1.0,myriad-essentials=0.0.9}. */
	private final Map<String, String> pretend = parsePretend(System.getProperty("myriad.updates.pretend", ""));
	/** Toasts wait until something draws them (the menu or the HUD); render thread only. */
	private final List<Runnable> queuedToasts = new ArrayList<>();
	private volatile boolean checkEnabled = true;
	private volatile boolean checking;

	public UpdateManager(Path root) {
		this.installer = new UpdateInstaller(root.resolve("updates"));
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
		for (UpdateInstaller.Pending p : o.stillWaiting()) {
			states.put(p.id(), new State(Kind.STAGED, p.version(), null, "Couldn't be installed last time; trying again when you quit"));
			toast("Update not installed", name(p.id()) + " " + p.version() + " couldn't be installed. Trying again when you quit; "
				+ "or move it from myriad/updates into mods yourself.", Notifications.Level.WARNING);
		}
		events.listen(GameReadyEvent.class, e -> {
			if (checkEnabled) check(false);
		});
		events.listen(ScreenOpenEvent.class, e -> {
			if (e.screen() instanceof DesktopScreen) flushToasts();
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
	 * Asks GitHub about every addon that names a repo. {@code manual} (the user asked) always reports the result; the
	 * automatic check only speaks up when there's something to install.
	 */
	public void check(boolean manual) {
		if (checking) return;
		Map<Addon, String> todo = new LinkedHashMap<>();
		for (Addon a : Myriad.addons()) {
			Optional<String> repo = UpdateSource.of(a.metadata());
			if (repo.isEmpty()) {
				states.put(a.id(), new State(Kind.NO_SOURCE, null, null, "Not checked: it names no GitHub repository"));
				continue;
			}
			State s = states.get(a.id());
			if (s != null && (s.kind() == Kind.DOWNLOADING || s.kind() == Kind.STAGED)) continue;
			if (jarOf(a) == null) {
				states.put(a.id(), new State(Kind.DEV_BUILD, null, null, "Running from a dev build; updates are off"));
				continue;
			}
			todo.put(a, repo.get());
		}
		if (todo.isEmpty()) {
			if (manual) toast("Updates", "Nothing to check", Notifications.Level.INFO);
			return;
		}
		checking = true;
		AtomicInteger left = new AtomicInteger(todo.size());
		for (Map.Entry<Addon, String> entry : todo.entrySet()) {
			Addon a = entry.getKey();
			String id = a.id(), installed = installedVersion(a);
			states.put(id, new State(Kind.CHECKING, null, null, null));
			GitHubReleases.latest(entry.getValue(), jarOf(a).getFileName().toString(), installed).whenComplete((r, err) -> {
				if (err != null) {
					String why = reason(err);
					LOG.info("Could not check {} for updates: {}", id, why);
					states.put(id, new State(Kind.UNKNOWN, null, null, why.contains("HTTP 404") ? "No releases yet" : "Couldn't check: " + why));
				} else if (!GitHubReleases.isNewer(r.version(), installed)) {
					states.put(id, new State(Kind.UP_TO_DATE, r.version(), r.url(), null));
				} else if (r.asset() == null) {
					states.put(id, new State(Kind.UNKNOWN, r.version(), r.url(), r.version() + " is out, but its release has no jar to install"));
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

	/** Downloads, checks and stages {@code id}'s update. The future never fails; the result is in {@link #state}. */
	public CompletableFuture<Void> update(String id) {
		GitHubReleases.Release r = releases.get(id);
		State s = states.get(id);
		Addon addon = addon(id);
		Path jar = addon == null ? null : jarOf(addon);
		if (r == null || r.asset() == null || s == null || jar == null || (s.kind() != Kind.AVAILABLE && s.kind() != Kind.FAILED)) {
			return CompletableFuture.completedFuture(null);
		}
		states.put(id, new State(Kind.DOWNLOADING, r.version(), r.url(), null));
		return Http.getBytes(r.asset().url()).thenAccept(bytes -> {
			String why = JarCheck.verifyDownload(bytes, r.asset());
			if (why == null) why = JarCheck.check(bytes, id, r.version(), minecraftVersion(), addon.isCore() ? null : coreVersionAfterUpdates());
			if (why != null) throw new CompletionException(new IOException(why));
			try {
				installer.stage(id, r.version(), r.asset().name(), bytes, jar);
			} catch (IOException e) {
				throw new CompletionException(new IOException("couldn't save it to " + installer.dir() + ": " + e.getMessage(), e));
			}
			states.put(id, new State(Kind.STAGED, r.version(), r.url(), null));
			LOG.info("Staged {} {}; it installs when the game exits", id, r.version());
			Async.onRenderThread(() -> toast("Update ready", name(id) + " " + r.version() + " installs when you quit", Notifications.Level.SUCCESS));
		}).exceptionally(err -> {
			String why = reason(err);
			LOG.warn("Could not update {} to {}: {}", id, r.version(), why);
			states.put(id, new State(Kind.FAILED, r.version(), r.url(), why));
			Async.onRenderThread(() -> toast("Update failed", name(id) + ": " + why, Notifications.Level.ERROR));
			return null;
		});
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
		if (mc != null && mc.gui != null && (mc.gui.screen() instanceof DesktopScreen || mc.player != null)) post.run();
		else queuedToasts.add(post);
	}

	private void flushToasts() {
		List<Runnable> now = List.copyOf(queuedToasts);
		queuedToasts.clear();
		now.forEach(Runnable::run);
	}
}
