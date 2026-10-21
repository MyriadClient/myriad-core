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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

	@SuppressWarnings("deprecation")
	public void registerTags() {
		runPhase("registerTags", (a, ctx) -> {
			a.registerTags(ctx);
			a.registerCategories(ctx);
		});
	}

	public void initialize() {
		runPhase("initialize", MyriadAddon::initialize);
	}

	public void postInitialize() {
		runPhase("postInitialize", MyriadAddon::postInitialize);
		for (Addon a : addons) a.markLoaded();
	}

	private void runPhase(String phase, BiConsumer<MyriadAddon, AddonContext> action) {
		for (Addon addon : addons) {
			if (addon.state() == AddonState.FAILED) continue;
			long start = System.nanoTime();
			try {
				action.accept(addon.entrypoint(), contexts.get(addon));
			} catch (Throwable t) {
				LOG.error("Addon {} failed during {}; disabling it", addon.id(), phase, t);
				addon.markFailed(t);
				rollback(addon);
				continue;
			}
			long ms = (System.nanoTime() - start) / 1_000_000;
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
