package dev.myriadclient.impl.addon;

import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.AddonState;
import dev.myriadclient.api.addon.MyriadAddon;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.impl.MyriadImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Finds every {@code "myriad"} entrypoint and drives addons through their phases. Core always goes first so its
 * categories, layouts and widgets exist before other addons initialise. A throwing addon is marked failed and its
 * registrations are removed; the rest keep loading.
 */
public final class AddonLoader {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Addons");

	private final MyriadImpl myriad;
	private final List<Addon> addons = new ArrayList<>();
	private final Map<Addon, AddonContext> contexts = new HashMap<>();
	/** Package prefix -> addon id, to attribute event handler exceptions. */
	private final Map<String, String> packages = new LinkedHashMap<>();
	/** How long each addon's phases took, by addon id: [registering tags and initialize, postInitialize], in nanoseconds. */
	private final Map<String, long[]> startup = new HashMap<>();

	public AddonLoader(MyriadImpl myriad) {
		this.myriad = myriad;
	}

	public List<Addon> addons() {
		return Collections.unmodifiableList(addons);
	}

	public void discover() {
		List<EntrypointContainer<MyriadAddon>> found = new ArrayList<>();
		try {
			found.addAll(FabricLoader.getInstance().getEntrypointContainers("myriad", MyriadAddon.class));
		} catch (Throwable t) {
			LOG.error("Failed to instantiate Myriad addon entrypoints", t);
		}
		found.sort(Comparator.comparing((EntrypointContainer<MyriadAddon> c) -> !c.getProvider().getMetadata().getId().equals("myriad"))
			.thenComparing(c -> c.getProvider().getMetadata().getId()));
		for (EntrypointContainer<MyriadAddon> c : found) {
			Addon addon = new Addon(c.getProvider(), c.getEntrypoint());
			addons.add(addon);
			contexts.put(addon, new AddonContextImpl(addon, myriad));
			String pkg = c.getEntrypoint().getClass().getPackageName();
			packages.put(pkg.isEmpty() ? addon.id() : pkg + ".", addon.id());
			LOG.info("Found addon {} ({})", addon, addon.id());
		}
	}

	/**
	 * How long an addon took to start, for the Addons window: {@code initialize} (with registering its tags, just before)
	 * and {@code postInitialize}, up to the phase it failed in if it did. Null for an addon that never ran.
	 */
	public @Nullable Startup startup(String id) {
		long[] n = startup.get(id);
		return n == null ? null : new Startup(n[0], n[1]);
	}

	/** An addon's startup time in nanoseconds. */
	public record Startup(long initializeNanos, long postInitializeNanos) {
		public long totalNanos() {
			return initializeNanos + postInitializeNanos;
		}

		/**
		 * "Started in 38 ms (initialize 36 ms, postInitialize 2.1 ms)": what the addon added to the game's start, so a slow
		 * one can be found. A failed addon's time is up to the failure.
		 */
		public String describe(boolean failed) {
			String split = " (initialize " + ms(initializeNanos) + (failed ? "" : ", postInitialize " + ms(postInitializeNanos)) + ")";
			return (failed ? "Ran " + ms(totalNanos()) + " before failing" : "Started in " + ms(totalNanos())) + split;
		}

		private static String ms(long nanos) {
			double ms = nanos / 1e6;
			return (ms < 10 ? String.format(Locale.ROOT, "%.1f", ms) : String.valueOf(Math.round(ms))) + " ms";
		}
	}

	/** Best-effort owner of a class: the addon whose entrypoint package is the longest prefix of its name. */
	public String ownerOf(Class<?> klass) {
		String name = klass.getName();
		String best = null;
		int bestLen = -1;
		for (Map.Entry<String, String> e : packages.entrySet()) {
			if (name.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
				best = e.getValue();
				bestLen = e.getKey().length();
			}
		}
		return best != null ? best : name;
	}

	public void registerTags() {
		runPhase("registerTags", 0, MyriadAddon::registerTags);
	}

	public void initialize() {
		runPhase("initialize", 0, MyriadAddon::initialize);
	}

	public void postInitialize() {
		runPhase("postInitialize", 1, MyriadAddon::postInitialize);
		for (Addon a : addons) a.markLoaded();
	}

	/** Runs {@code phase} for each addon still loading, adding its time to {@code slot} of the addon's {@link Startup}. */
	private void runPhase(String phase, int slot, BiConsumer<MyriadAddon, AddonContext> action) {
		for (Addon addon : addons) {
			if (addon.state() == AddonState.FAILED) continue;
			long start = System.nanoTime();
			try {
				action.accept(addon.entrypoint(), contexts.get(addon));
			} catch (Throwable t) {
				startup.computeIfAbsent(addon.id(), k -> new long[2])[slot] += System.nanoTime() - start;
				LOG.error("Addon {} failed during {}; disabling it", addon.id(), phase, t);
				addon.markFailed(t);
				rollback(addon);
				continue;
			}
			long took = System.nanoTime() - start;
			startup.computeIfAbsent(addon.id(), k -> new long[2])[slot] += took;
			long ms = took / 1_000_000;
			if (ms > 250) LOG.warn("Addon {} took {} ms in {}", addon.id(), ms, phase);
		}
	}

	private void rollback(Addon addon) {
		String id = addon.id();
		for (Module m : myriad.modules().ownedBy(id)) {
			m.setEnabled(false);
		}
		for (Registry<?> r : myriad.registries()) r.unregisterAll(id);
	}
}
