package dev.myriadclient;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.MyriadApi;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.ModuleRegistry;
import dev.myriadclient.api.registry.Registry;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Just enough of Myriad for unit tests of API classes that call through {@code Myriad}: real module and group registries, every
 * other service a stub whose methods do nothing and return null, zero or false (and stubs for what returns another
 * interface). Install it around a test and uninstall after.
 */
public final class FakeApi {
	private final ModuleRegistry modules = new ModuleRegistry();
	private final Registry<ModuleGroup> groups = new Registry<>("group");
	private final Map<Class<?>, Object> stubs = new HashMap<>();

	public static FakeApi install() {
		FakeApi f = new FakeApi();
		Myriad.install(f.stub(MyriadApi.class));
		return f;
	}

	public static void uninstall() {
		Myriad.install(null);
	}

	public ModuleRegistry modules() {
		return modules;
	}

	/** The declared groups, real too, for {@code ModuleRegistry.groups()}. */
	public Registry<ModuleGroup> groups() {
		return groups;
	}

	@SuppressWarnings("unchecked")
	private <T> T stub(Class<T> type) {
		return (T) stubs.computeIfAbsent(type, t -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{t}, (proxy, m, args) -> {
			if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
			Class<?> r = m.getReturnType();
			if (r == ModuleRegistry.class) return modules;
			if (m.getName().equals("groups") && r == Registry.class) return groups;
			if (m.getName().equals("toString")) return "stub " + t.getSimpleName();
			if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
			if (m.getName().equals("equals")) return proxy == args[0];
			if (r == boolean.class) return false;
			if (r == int.class || r == long.class || r == short.class || r == byte.class || r == char.class) return 0;
			if (r == float.class) return 0f;
			if (r == double.class) return 0d;
			if (r.isInterface()) return stub(r);
			return null;
		}));
	}
}
