package dev.myriadclient.api.util;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives Baritone when it's installed, without depending on it: every call is a no-op (or false) when it's missing,
 * so a module can offer "use Baritone" options to everyone and they simply do nothing without it.
 *
 * <pre>{@code
 * if (Baritone.isAvailable()) Baritone.pathTo(x, y, z);
 * Baritone.pause(this);   // e.g. while eating
 * Baritone.resume(this);
 * }</pre>
 *
 * Pauses are counted by owner: Baritone resumes once every module that paused it has resumed, and never resumes a
 * pause it didn't make.
 */
public final class Baritone {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Baritone");
	private static final Set<Object> pausedBy = new HashSet<>();
	private static Boolean installed;
	private static boolean warned;

	private static Method getSettings, getProvider, getPrimaryBaritone, getCommandManager, execute, getPathingBehavior, isPathing, cancelEverything,
		getCustomGoalProcess, setGoalAndPath, isActive;
	private static Class<?> goalBlock, goalXZ, goal;

	private Baritone() {
	}

	/** Whether Baritone is installed and ready (it has a primary instance once a world is loaded). */
	public static boolean isAvailable() {
		return primary() != null;
	}

	/** Whether Baritone is walking a path right now. */
	public static boolean isPathing() {
		Object b = primary();
		if (b == null) return false;
		try {
			return (boolean) isPathing.invoke(getPathingBehavior.invoke(b)) || (boolean) isActive.invoke(getCustomGoalProcess.invoke(b));
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/** Walks to a block, mining and placing as Baritone's settings allow. */
	public static boolean pathTo(int x, int y, int z) {
		return path(goalBlock, new Class<?>[]{int.class, int.class, int.class}, x, y, z);
	}

	/** Walks to a column, at whatever height. */
	public static boolean pathTo(int x, int z) {
		return path(goalXZ, new Class<?>[]{int.class, int.class}, x, z);
	}

	private static boolean path(Class<?> goalType, Class<?>[] params, Object... args) {
		Object b = primary();
		if (b == null) return false;
		try {
			Object g = goalType.getConstructor(params).newInstance(args);
			setGoalAndPath.invoke(getCustomGoalProcess.invoke(b), g);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/** Stops whatever Baritone is doing. */
	public static void stop() {
		Object b = primary();
		if (b == null) return;
		try {
			cancelEverything.invoke(getPathingBehavior.invoke(b));
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
		}
	}

	/** Runs a Baritone command, without its prefix ("goto 0 64 0", "mine diamond_ore"). */
	public static boolean command(String command) {
		Object b = primary();
		if (b == null) return false;
		try {
			return (boolean) execute.invoke(getCommandManager.invoke(b), command);
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/**
	 * The current value of one of Baritone's settings by its name ({@code "allowPlace"}, {@code
	 * "acceptableThrowawayItems"}, ...); null if Baritone isn't installed or has no such setting.
	 */
	public static @Nullable Object getSetting(String name) {
		Field field = settingValue(name);
		try {
			return field == null ? null : field.get(setting(name));
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return null;
		}
	}

	/**
	 * Changes one of Baritone's settings by its name for now, without saving it to Baritone's settings file, and
	 * returns what it was so it can be put back. Null, with nothing changed, if Baritone isn't installed, has no such
	 * setting, or {@code value} is the wrong type for it (a list setting takes any list).
	 *
	 * <pre>{@code
	 * Object before = Baritone.setSetting("allowPlace", true);
	 * // ... later
	 * if (before != null) Baritone.setSetting("allowPlace", before);
	 * }</pre>
	 */
	public static @Nullable Object setSetting(String name, Object value) {
		Field field = settingValue(name);
		if (field == null || value == null) return null;
		try {
			Object setting = setting(name);
			Object before = field.get(setting);
			boolean fits = before != null && (before.getClass().isInstance(value) || before instanceof List && value instanceof List);
			if (!fits) return null;
			field.set(setting, value);
			return before;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return null;
		}
	}

	/** Baritone's setting object called {@code name}, or null. */
	private static @Nullable Object setting(String name) {
		if (!installed()) return null;
		try {
			Object settings = getSettings.invoke(null);
			return settings.getClass().getField(name).get(settings);
		} catch (NoSuchFieldException e) {
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return null;
		}
	}

	/** The {@code value} field of the setting called {@code name}, or null. */
	private static @Nullable Field settingValue(String name) {
		Object setting = setting(name);
		if (setting == null) return null;
		try {
			return setting.getClass().getField("value");
		} catch (NoSuchFieldException e) {
			return null;
		}
	}

	/** Pauses Baritone on behalf of {@code owner}, until it calls {@link #resume}. */
	public static void pause(Object owner) {
		if (!isAvailable() || !pausedBy.add(owner) || pausedBy.size() > 1) return;
		command("pause");
	}

	/** Ends {@code owner}'s pause; Baritone carries on once nobody else is pausing it. */
	public static void resume(Object owner) {
		if (!pausedBy.remove(owner) || !pausedBy.isEmpty()) return;
		command("resume");
	}

	public static boolean isPausedBy(Object owner) {
		return pausedBy.contains(owner);
	}

	private static Object primary() {
		if (!installed()) return null;
		try {
			return getPrimaryBaritone.invoke(getProvider.invoke(null));
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return null;
		}
	}

	private static boolean installed() {
		if (installed != null) return installed;
		try {
			// The API interfaces, not the implementation classes, so this works with any Baritone build.
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			Class<?> provider = Class.forName("baritone.api.IBaritoneProvider");
			Class<?> baritone = Class.forName("baritone.api.IBaritone");
			Class<?> commands = Class.forName("baritone.api.command.manager.ICommandManager");
			Class<?> pathing = Class.forName("baritone.api.behavior.IPathingBehavior");
			Class<?> custom = Class.forName("baritone.api.process.ICustomGoalProcess");
			Class<?> process = Class.forName("baritone.api.process.IBaritoneProcess");
			goal = Class.forName("baritone.api.pathing.goals.Goal");
			goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
			goalXZ = Class.forName("baritone.api.pathing.goals.GoalXZ");
			getProvider = api.getMethod("getProvider");
			getSettings = api.getMethod("getSettings");
			getPrimaryBaritone = provider.getMethod("getPrimaryBaritone");
			getCommandManager = baritone.getMethod("getCommandManager");
			execute = commands.getMethod("execute", String.class);
			getPathingBehavior = baritone.getMethod("getPathingBehavior");
			isPathing = pathing.getMethod("isPathing");
			cancelEverything = pathing.getMethod("cancelEverything");
			getCustomGoalProcess = baritone.getMethod("getCustomGoalProcess");
			setGoalAndPath = custom.getMethod("setGoalAndPath", goal);
			isActive = process.getMethod("isActive");
			installed = true;
		} catch (ClassNotFoundException e) {
			installed = false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("Baritone is installed but its API doesn't look as expected; Baritone options are off", e);
			installed = false;
		}
		return installed;
	}

	private static void fail(Throwable t) {
		if (warned) return;
		warned = true;
		LOG.warn("A Baritone call failed (further failures are not logged)", t);
	}
}
