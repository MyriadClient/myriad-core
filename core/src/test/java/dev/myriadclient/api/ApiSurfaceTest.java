package dev.myriadclient.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public API is what addons compile against, so it must not expose internals: a type from {@code dev.myriadclient.impl}
 * in a public signature would tie every addon to code that changes without notice. This walks every public type in
 * {@code dev.myriadclient.api} and fails on any public or protected member that mentions one. Nor may the API use
 * {@code impl} inside, so it loads without it (a test harness puts its own behind {@code MyriadApi}): no API class file
 * may refer to an {@code impl} class at all.
 */
class ApiSurfaceTest {
	private static final String IMPL = "dev.myriadclient.impl.";

	@Test
	void publicApiDoesNotExposeInternals() throws Exception {
		List<String> leaks = new ArrayList<>();
		for (Class<?> c : apiClasses()) {
			if (!Modifier.isPublic(c.getModifiers())) continue;
			check(leaks, c.getName() + " extends", c.getGenericSuperclass());
			for (Type t : c.getGenericInterfaces()) check(leaks, c.getName() + " implements", t);
			for (Field f : c.getDeclaredFields()) if (visible(f)) check(leaks, where(c, f), f.getGenericType());
			for (Constructor<?> k : c.getDeclaredConstructors()) {
				if (visible(k)) for (Type t : k.getGenericParameterTypes()) check(leaks, where(c, k), t);
			}
			for (Method m : c.getDeclaredMethods()) {
				if (!visible(m) || m.isSynthetic() || m.isBridge()) continue;
				check(leaks, where(c, m), m.getGenericReturnType());
				for (Type t : m.getGenericParameterTypes()) check(leaks, where(c, m), t);
			}
		}
		assertTrue(leaks.isEmpty(), "Public API exposes internal types:\n" + String.join("\n", leaks));
	}

	@Test
	void apiClassesNeverReferToImpl() throws Exception {
		Path root = Path.of(Myriad.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		// A class file names every class it uses in its constant pool, slashed: imports, fully qualified names, casts.
		byte[] impl = "dev/myriadclient/impl/".getBytes(StandardCharsets.UTF_8);
		List<String> users = new ArrayList<>();
		try (Stream<Path> files = Files.walk(root.resolve("dev/myriadclient/api"))) {
			for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
				if (contains(Files.readAllBytes(p), impl)) users.add(root.relativize(p).toString());
			}
		}
		assertTrue(users.isEmpty(), "API classes that use dev.myriadclient.impl (route them through MyriadApi):\n" + String.join("\n", users));
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
			return true;
		}
		return false;
	}

	private static boolean visible(Member m) {
		return Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers());
	}

	private static String where(Class<?> c, Member m) {
		return c.getName() + "#" + m.getName();
	}

	private static void check(List<String> leaks, String where, Type type) {
		if (type == null) return;
		switch (type) {
			case Class<?> c -> {
				if (c.isArray()) check(leaks, where, c.getComponentType());
				else if (c.getName().startsWith(IMPL)) leaks.add(where + " -> " + c.getName());
			}
			case ParameterizedType p -> {
				check(leaks, where, p.getRawType());
				for (Type a : p.getActualTypeArguments()) check(leaks, where, a);
			}
			case GenericArrayType g -> check(leaks, where, g.getGenericComponentType());
			case WildcardType w -> {
				for (Type b : w.getUpperBounds()) check(leaks, where, b);
				for (Type b : w.getLowerBounds()) check(leaks, where, b);
			}
			case TypeVariable<?> v -> {
			}
			default -> {
			}
		}
	}

	/** Every class compiled from {@code dev.myriadclient.api}, loaded without running static initialisers. */
	private static List<Class<?>> apiClasses() throws URISyntaxException, Exception {
		Path root = Path.of(Myriad.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path api = root.resolve("dev/myriadclient/api");
		List<Class<?>> classes = new ArrayList<>();
		try (Stream<Path> files = Files.walk(api)) {
			for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
				String name = root.relativize(p).toString().replace('/', '.').replace('\\', '.');
				name = name.substring(0, name.length() - ".class".length());
				if (name.endsWith("package-info")) continue;
				classes.add(Class.forName(name, false, ApiSurfaceTest.class.getClassLoader()));
			}
		}
		assertTrue(classes.size() > 100, "Found only " + classes.size() + " API classes under " + api);
		return classes;
	}
}
