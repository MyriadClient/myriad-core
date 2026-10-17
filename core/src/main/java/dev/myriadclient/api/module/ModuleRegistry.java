package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Every registered module. For quick typed access from your own code and mixins, see {@link Modules}. */
public class ModuleRegistry extends Registry<Module> {
	/** Exact class to instance, read without locking so per-frame lookups stay cheap. */
	private final Map<Class<?>, Module> byClass = new ConcurrentHashMap<>();

	public ModuleRegistry() {
		super("module");
	}

	@Override
	public synchronized Module register(Module module, String owner) {
		module.assignNamespace(owner);
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

	public List<Module> inCategory(Category category) {
		List<Module> list = new ArrayList<>();
		for (Module m : values()) if (m.category().equals(category)) list.add(m);
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

	private static String normalize(String s) {
		return s.replace(" ", "").replace("_", "").replace("-", "").toLowerCase();
	}
}
