package dev.myriadclient.harness;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Objects made without their constructors, and fields set and methods called past their access: the harness's tools. */
final class Fields {
	private static final Unsafe UNSAFE;

	static {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			UNSAFE = (Unsafe) f.get(null);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private Fields() {
	}

	/** An instance of {@code type} with every field at its default: no constructor runs. */
	@SuppressWarnings("unchecked")
	static <T> T allocate(Class<T> type) {
		try {
			return (T) UNSAFE.allocateInstance(type);
		} catch (InstantiationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Sets field {@code name} of {@code target} (or of its class, if static), declared on it or a superclass; finals too. */
	static void set(Object target, String name, Object value) {
		Field f = find(target instanceof Class<?> c ? c : target.getClass(), name);
		boolean statik = Modifier.isStatic(f.getModifiers());
		Object base = statik ? UNSAFE.staticFieldBase(f) : target;
		long offset = statik ? UNSAFE.staticFieldOffset(f) : UNSAFE.objectFieldOffset(f);
		Class<?> t = f.getType();
		if (t == boolean.class) UNSAFE.putBoolean(base, offset, (Boolean) value);
		else if (t == int.class) UNSAFE.putInt(base, offset, (Integer) value);
		else if (t == float.class) UNSAFE.putFloat(base, offset, (Float) value);
		else if (t == double.class) UNSAFE.putDouble(base, offset, (Double) value);
		else if (t == long.class) UNSAFE.putLong(base, offset, (Long) value);
		else UNSAFE.putObject(base, offset, value);
	}

	/** Calls method {@code name} of {@code target} with no arguments, private or not. */
	static void call(Object target, String name) {
		try {
			Method m = target.getClass().getDeclaredMethod(name);
			m.setAccessible(true);
			m.invoke(target);
		} catch (java.lang.reflect.InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException r) throw r;
			throw new IllegalStateException(e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Calls static method {@code name} of {@code type} with no arguments, private or not. */
	static void callStatic(Class<?> type, String name) {
		try {
			Method m = type.getDeclaredMethod(name);
			m.setAccessible(true);
			m.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Field find(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new IllegalArgumentException("No field " + name + " on " + type.getName());
	}
}
