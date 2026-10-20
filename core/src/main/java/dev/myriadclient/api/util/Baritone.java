package dev.myriadclient.api.util;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
	/** Baritone's path and goal accessors, for drawing them; resolved on first use. */
	private static Method getGoal, getCurrent, getNext, executorPath, executorPosition, positions, goalPos, goalX, goalZ, compositeGoals;
	private static Class<?> goalRenderPos, goalXZType, goalComposite;
	private static Boolean pathAccess;
	/** Baritone's SettingsUtil and the Setting methods the settings list needs; resolved on first use. */
	private static Method valueToString, defaultToString, parseAndApply, getName, getValueClass, getType, isJavaOnly;
	private static Field defaultValue;
	private static Boolean settingsAccess;

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

	/**
	 * One place Baritone's goal names: a block ({@code column} false), or a column at any height ({@code column} true,
	 * {@code y} meaningless), for drawing where it's going.
	 */
	public record GoalSpot(int x, int y, int z, boolean column) {
	}

	/**
	 * Where Baritone's goal is: one spot for a goal at a block or a column, several for a goal of several, none for
	 * goals with no place (a height to reach, running away) or when it has no goal.
	 */
	public static List<GoalSpot> goalSpots() {
		Object b = primary();
		if (b == null || !pathAccess()) return List.of();
		try {
			Object goal = getGoal.invoke(getPathingBehavior.invoke(b));
			List<GoalSpot> out = new ArrayList<>();
			addSpots(goal, out, 0);
			return out;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return List.of();
		}
	}

	private static void addSpots(Object goal, List<GoalSpot> out, int depth) throws ReflectiveOperationException {
		if (goal == null || depth > 4) return;
		if (goalRenderPos.isInstance(goal)) {
			BlockPos pos = (BlockPos) goalPos.invoke(goal);
			out.add(new GoalSpot(pos.getX(), pos.getY(), pos.getZ(), false));
		} else if (goalXZType.isInstance(goal)) {
			out.add(new GoalSpot((int) goalX.invoke(goal), 0, (int) goalZ.invoke(goal), true));
		} else if (goalComposite.isInstance(goal)) {
			for (Object g : (Object[]) compositeGoals.invoke(goal)) addSpots(g, out, depth + 1);
		}
	}

	/**
	 * The path Baritone is walking, from where it is along it now: the rest of the current segment, then the next one
	 * it has planned. Empty while it isn't pathing.
	 */
	public static List<BlockPos> path() {
		Object b = primary();
		if (b == null || !pathAccess()) return List.of();
		try {
			Object behavior = getPathingBehavior.invoke(b);
			List<BlockPos> out = new ArrayList<>();
			Object current = getCurrent.invoke(behavior);
			if (current != null) {
				List<?> list = (List<?>) positions.invoke(executorPath.invoke(current));
				int from = Math.max(0, Math.min(list.size(), (int) executorPosition.invoke(current)));
				for (Object p : list.subList(from, list.size())) out.add((BlockPos) p);
			}
			Object next = getNext.invoke(behavior);
			if (next != null) for (Object p : (List<?>) positions.invoke(executorPath.invoke(next))) out.add((BlockPos) p);
			return out;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return List.of();
		}
	}

	private static boolean pathAccess() {
		if (pathAccess != null) return pathAccess;
		if (!installed()) return pathAccess = false;
		try {
			Class<?> pathing = Class.forName("baritone.api.behavior.IPathingBehavior");
			Class<?> executor = Class.forName("baritone.api.pathing.path.IPathExecutor");
			Class<?> path = Class.forName("baritone.api.pathing.calc.IPath");
			goalRenderPos = Class.forName("baritone.api.utils.interfaces.IGoalRenderPos");
			goalXZType = Class.forName("baritone.api.pathing.goals.GoalXZ");
			goalComposite = Class.forName("baritone.api.pathing.goals.GoalComposite");
			getGoal = pathing.getMethod("getGoal");
			getCurrent = pathing.getMethod("getCurrent");
			getNext = pathing.getMethod("getNext");
			executorPath = executor.getMethod("getPath");
			executorPosition = executor.getMethod("getPosition");
			positions = path.getMethod("positions");
			goalPos = goalRenderPos.getMethod("getGoalPos");
			goalX = goalXZType.getMethod("getX");
			goalZ = goalXZType.getMethod("getZ");
			compositeGoals = goalComposite.getMethod("goals");
			pathAccess = true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("Baritone's path and goal don't look as expected; drawing them is off", e);
			pathAccess = false;
		}
		return pathAccess;
	}

	/**
	 * One of Baritone's settings, as {@link #settings()} lists them: its name ({@code allowBreak}), the class of its value
	 * ({@code Boolean.class}, {@code List.class} for a block list, with {@code genericType} saying of what) and its
	 * default.
	 */
	public record SettingInfo(String name, Class<?> valueClass, Type genericType, Object defaultValue) {
	}

	/**
	 * Every one of Baritone's settings that can be changed from outside its Java API (not its callbacks), in the order
	 * Baritone declares them; empty without Baritone.
	 */
	public static List<SettingInfo> settings() {
		if (!settingsAccess()) return List.of();
		List<SettingInfo> out = new ArrayList<>();
		try {
			Object settings = getSettings.invoke(null);
			for (Field f : settings.getClass().getFields()) {
				if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals("baritone.api.Settings$Setting")) continue;
				Object setting = f.get(settings);
				if ((boolean) isJavaOnly.invoke(setting)) continue;
				out.add(new SettingInfo((String) getName.invoke(setting), (Class<?>) getValueClass.invoke(setting), (Type) getType.invoke(setting),
					defaultValue.get(setting)));
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
		}
		return out;
	}

	/** A setting's value as Baritone writes it in its settings file ({@code true}, {@code stone,dirt}), or null. */
	public static @Nullable String settingText(String name) {
		return settingString(name, valueToString);
	}

	/** A setting's default as Baritone writes it, or null. */
	public static @Nullable String settingDefaultText(String name) {
		return settingString(name, defaultToString);
	}

	/**
	 * Changes a setting from text, read as Baritone's own {@code #set} reads it, for now (not saved to Baritone's
	 * settings file). False if Baritone isn't installed, has no such setting, or can't read the text.
	 */
	public static boolean setSettingText(String name, String text) {
		if (!settingsAccess() || setting(name) == null) return false;
		try {
			parseAndApply.invoke(null, getSettings.invoke(null), name.toLowerCase(Locale.ROOT), text);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Baritone throws when the text doesn't parse for that setting's type.
			return false;
		}
	}

	private static @Nullable String settingString(String name, Method how) {
		if (!settingsAccess()) return null;
		Object setting = setting(name);
		if (setting == null) return null;
		try {
			return (String) how.invoke(null, setting);
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return null;
		}
	}

	private static boolean settingsAccess() {
		if (settingsAccess != null) return settingsAccess;
		if (!installed()) return settingsAccess = false;
		try {
			Class<?> util = Class.forName("baritone.api.utils.SettingsUtil");
			Class<?> setting = Class.forName("baritone.api.Settings$Setting");
			valueToString = util.getMethod("settingValueToString", setting);
			defaultToString = util.getMethod("settingDefaultToString", setting);
			parseAndApply = util.getMethod("parseAndApply", Class.forName("baritone.api.Settings"), String.class, String.class);
			getName = setting.getMethod("getName");
			getValueClass = setting.getMethod("getValueClass");
			getType = setting.getMethod("getType");
			isJavaOnly = setting.getMethod("isJavaOnly");
			defaultValue = setting.getField("defaultValue");
			settingsAccess = true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("Baritone's settings don't look as expected; listing and editing them is off", e);
			settingsAccess = false;
		}
		return settingsAccess;
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
