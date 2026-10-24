package dev.myriadclient.api.module;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Every registered module. For quick typed access from your own code and mixins, see {@link Modules}. */
public class ModuleRegistry extends Registry<Module> {
	/** Exact class to instance, read without locking so per-frame lookups stay cheap. */
	private final Map<Class<?>, Module> byClass = new ConcurrentHashMap<>();
	/** {@link #groups()} as worked out from these module and group snapshots; worked out again once either changes. */
	private volatile Groups groups;

	private record Groups(Collection<Module> modules, Collection<ModuleGroup> declared, List<ModuleGroup> list) {
	}

	public ModuleRegistry() {
		super("module");
	}

	@Override
	public synchronized Module register(Module module, String owner) {
		module.assignNamespace(owner);
		if (module.group() == null) module.assignGroup(ModuleGroup.addonWindow(owner, addonName(owner)));
		super.register(module, owner);
		byClass.put(module.getClass(), module);
		return module;
	}

	@Override
	public synchronized boolean unregister(MyriadId id) {
		Optional<Module> m = get(id);
		boolean removed = super.unregister(id);
		if (removed) byClass.remove(m.get().getClass(), m.get());
		return removed;
	}

	@Override
	public <C extends Module> Optional<C> get(Class<C> type) {
		return Optional.ofNullable(type.cast(byClass.get(type)));
	}

	/** Like {@link #get(Class)} without the Optional; null when not registered. */
	public <C extends Module> C getOrNull(Class<C> type) {
		return type.cast(byClass.get(type));
	}

	/** Finds a module by display name ("Kill Aura", "killaura"), id path ("kill_aura") or full id. */
	public Optional<Module> byName(String name) {
		String n = normalize(name);
		for (Module m : values()) {
			if (normalize(m.name()).equals(n) || normalize(m.id().path()).equals(n) || m.id().toString().equalsIgnoreCase(name)) return Optional.of(m);
		}
		return Optional.empty();
	}

	/** Every module with {@code tag}, in registration order. */
	public List<Module> withTag(Tag tag) {
		List<Module> list = new ArrayList<>();
		for (Module m : values()) if (m.hasTag(tag)) list.add(m);
		return list;
	}

	/** The modules of {@code group}, in registration order (their order in its window). */
	public List<Module> inGroup(ModuleGroup group) {
		List<Module> list = new ArrayList<>();
		for (Module m : values()) if (group.equals(m.group())) list.add(m);
		return list;
	}

	/**
	 * Every group with modules, each one window in the menu: addons in the order their modules were registered, each
	 * addon's groups in the order it declared them, then its own window. Unmodifiable, and kept until a module or group
	 * is added or removed (the menu asks every frame).
	 */
	public List<ModuleGroup> groups() {
		Collection<Module> modules = values();
		Collection<ModuleGroup> declared = Myriad.groups().values();
		Groups cached = groups;
		if (cached != null && cached.modules == modules && cached.declared == declared) return cached.list;
		Set<ModuleGroup> found = new LinkedHashSet<>();
		Map<String, Integer> addons = new HashMap<>();
		for (Module m : modules) {
			found.add(m.group());
			addons.putIfAbsent(m.group().addon(), addons.size());
		}
		List<ModuleGroup> order = List.copyOf(declared);
		List<ModuleGroup> out = new ArrayList<>(found);
		out.sort(Comparator.comparingInt((ModuleGroup g) -> addons.get(g.addon())).thenComparingInt(g -> {
			int i = order.indexOf(g);
			return i < 0 ? Integer.MAX_VALUE : i;
		}));
		List<ModuleGroup> list = List.copyOf(out);
		groups = new Groups(modules, declared, list);
		return list;
	}

	public List<Module> enabled() {
		List<Module> list = new ArrayList<>();
		for (Module m : values()) if (m.isEnabled()) list.add(m);
		return list;
	}

	public boolean isEnabled(Class<? extends Module> type) {
		return get(type).map(Module::isEnabled).orElse(false);
	}

	/** An addon's short name ("Essentials" for "Myriad Essentials"), for its own window. */
	private static String addonName(String owner) {
		for (Addon a : Myriad.addons()) {
			if (!a.id().equals(owner)) continue;
			String n = a.name();
			return n.startsWith("Myriad ") && n.length() > 7 ? n.substring(7) : n;
		}
		return owner;
	}

	private static String normalize(String s) {
		return s.replace(" ", "").replace("_", "").replace("-", "").toLowerCase();
	}
}
