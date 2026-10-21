package dev.myriadclient.api.module;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ModuleToggleEvent;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.util.MyriadId;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A toggleable feature. While enabled, the module is subscribed to the event bus, so {@code @Subscribe} methods
 * only run when it is on.
 *
 * <pre>{@code
 * public class Sprint extends Module {
 *     private final BoolSetting omni = sgGeneral.bool("Omnidirectional").build();
 *
 *     public Sprint() {
 *         super(Tags.MOVEMENT, "Sprint", "Sprints automatically.");
 *     }
 *
 *     @Subscribe
 *     private void onTick(TickEvent.Post e) { ... }
 * }
 * }</pre>
 *
 * Register it from your addon with {@code ctx.registerModule(new Sprint())}. Elsewhere (other modules, mixins, other
 * addons) reach it with {@link Modules#active(Class)}, which returns null while it's off or not installed.
 */
public abstract class Module implements Identified {
	/** The game client. Bound again when Myriad starts, in case this class loaded before the client existed. */
	protected static Minecraft mc = Minecraft.getInstance();
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Module");

	private final String name;
	private final String path;
	private final String description;
	private final List<Tag> tags = new ArrayList<>(1);
	private ModuleGroup group;
	private MyriadId id;
	private boolean enabled;
	private final List<Runnable[]> bindings = new ArrayList<>(0);
	private List<String> formerNames = List.of();
	private List<Class<? extends Module>> conflicts = List.of();

	public final Settings settings = new Settings();
	protected final SettingGroup sgGeneral = settings.group("General");

	private final SettingGroup sgModule = settings.group("Module");
	public final KeybindSetting keybind = sgModule.keybind("Keybind").description("Toggles the module.").build();
	public final BoolSetting holdMode = sgModule.bool("Hold").description("Only active while the keybind is held.").build();
	public final BoolSetting visibleInList = sgModule.bool("Show In List").description("Show in the module list HUD.").defaultValue(true).build();
	public final BoolSetting chatFeedback = sgModule.bool("Chat Feedback").description("Announce toggles in chat.").defaultValue(true).build();

	/**
	 * @param tag what the module is about (see {@link Tags}): its colour and icon, and {@code #tag} in the launcher.
	 *            More can be added with {@link #alsoTagged}.
	 */
	protected Module(Tag tag, String name, String description) {
		this.tags.add(tag);
		this.name = name;
		this.path = MyriadId.toPath(name);
		this.description = description;
	}

	/** @deprecated categories are {@link Tag}s now: use {@link #Module(Tag, String, String)} */
	@Deprecated(since = "0.7.0")
	protected Module(Category category, String name, String description) {
		this(category.tag(), name, description);
	}

	@Override
	public MyriadId id() {
		if (id == null) throw new IllegalStateException("Module '" + name + "' is not registered yet");
		return id;
	}

	/** Called by the module registry with the registering addon's namespace. */
	@ApiStatus.Internal
	public void assignNamespace(String namespace) {
		if (id != null && !id.namespace().equals(namespace)) throw new IllegalStateException("Module '" + name + "' already registered as " + id);
		id = MyriadId.of(namespace, path);
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	/** The module's main tag: the one that gives it its colour and icon. */
	public Tag tag() {
		return tags.getFirst();
	}

	/** Every tag of the module, the main one first. */
	public List<Tag> tags() {
		return List.copyOf(tags);
	}

	public boolean hasTag(Tag tag) {
		return tags.contains(tag);
	}

	/** Adds tags besides the main one, for a module about more than one thing (Reach: combat and world). Call it in the constructor. */
	protected final void alsoTagged(Tag... more) {
		for (Tag t : more) if (!tags.contains(t)) tags.add(t);
	}

	/** @deprecated categories are {@link Tag}s now: use {@link #tag()} */
	@Deprecated(since = "0.7.0")
	public Category category() {
		return Category.of(tag());
	}

	/**
	 * The group the module shows in, in the menu: the one its addon registered it into, or the addon's own window
	 * ({@link ModuleGroup#isAddonWindow()}). Null until it's registered.
	 */
	public ModuleGroup group() {
		return group;
	}

	/** Called by the module registry with the group it's registered into. */
	@ApiStatus.Internal
	public void assignGroup(ModuleGroup group) {
		this.group = group;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void enable() {
		setEnabled(true);
	}

	public void disable() {
		setEnabled(false);
	}

	public void setEnabled(boolean enable) {
		setEnabled(enable, true);
	}

	private void setEnabled(boolean enable, boolean feedback) {
		if (enable == enabled) return;
		enabled = enable;
		if (enable) {
			for (Class<? extends Module> type : conflicts) {
				Module other = Modules.active(type);
				if (other != null) other.setEnabled(false, feedback);
			}
			Myriad.events().subscribe(this);
			try {
				for (Runnable[] b : bindings) b[0].run();
				onEnable();
			} catch (Throwable t) {
				LOG.error("{} failed to enable", id, t);
				error("Failed to enable: " + t);
				enabled = false;
				Myriad.events().unsubscribe(this);
				stopBindings();
				return;
			}
		} else {
			Myriad.events().unsubscribe(this);
			Myriad.tasks().cancel(this);
			Myriad.tickSpeed().clear(this);
			Myriad.breaking().cancel(this);
			Myriad.building().stop(this);
			Myriad.placement().cancel(this);
			try {
				onDisable();
			} catch (Throwable t) {
				LOG.error("{} failed to disable cleanly", id, t);
			}
			stopBindings();
		}
		Myriad.events().post(new ModuleToggleEvent(this, enabled));
		Myriad.config().markDirty();
		if (feedback && chatFeedback.get() && mc.player != null && !holdMode.get()) {
			// One line per module: toggling it again replaces the line instead of adding another.
			Myriad.chat(Component.literal(name).withStyle(ChatFormatting.WHITE).append(Component.literal(enabled ? " enabled" : " disabled")
				.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)), "myriad:toggle:" + id);
		}
	}

	private void stopBindings() {
		for (Runnable[] b : bindings) {
			try {
				b[1].run();
			} catch (Throwable t) {
				LOG.error("{} failed to stop a helper", id, t);
			}
		}
	}

	/**
	 * Runs {@code start} each time the module turns on (right away if it already is), before {@link #onEnable}, and
	 * {@code stop} each time it turns off, after {@link #onDisable}. Helpers that hold resources only while their
	 * module runs bind themselves here, such as {@code ChunkCache} and {@code WorldMesh}.
	 */
	public void whileEnabled(Runnable start, Runnable stop) {
		bindings.add(new Runnable[]{start, stop});
		if (enabled) start.run();
	}

	/**
	 * Sets the state without chat feedback, for state that comes from elsewhere rather than from the user: config
	 * being restored, or a {@linkplain #isMirror mirror} following the mod it mirrors.
	 */
	public void setEnabledSilently(boolean enable) {
		setEnabled(enable, false);
	}

	/**
	 * True for a module that mirrors a feature of another mod (a bridge to another client, say): its on/off state,
	 * settings and keybind belong to that mod, which saves them and handles the key. Myriad then neither saves the
	 * module with the profile nor toggles it on its keybind, and only shows and edits it.
	 */
	public boolean isMirror() {
		return false;
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/**
	 * The version of this module's settings layout. Bump it when you rename, move or change the meaning of settings,
	 * and update older saved settings in {@link #migrateSettings}. Settings that keep their group and name need
	 * nothing: only the ones a user would otherwise lose do.
	 */
	public int settingsVersion() {
		return 1;
	}

	/**
	 * Updates saved settings from {@code fromVersion} to {@code fromVersion + 1}. Called once per version step, in
	 * order, before saved settings written by an older {@link #settingsVersion} load.
	 */
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
	}

	/**
	 * Modules that can't be on at the same time as this one (two flight modules, Freecam and Free Look): turning this
	 * one on turns them off. Call it from the constructor; the other modules needn't know.
	 */
	@SafeVarargs
	protected final void conflictsWith(Class<? extends Module>... others) {
		conflicts = List.of(others);
	}

	/**
	 * Names (or full ids, {@code "other-addon:old_name"}) this module was saved under before, so a renamed or moved
	 * module keeps its settings, keybind and on/off state. Call it from the constructor.
	 */
	protected final void formerNames(String... names) {
		formerNames = List.of(names);
	}

	/** The ids this module was saved under before, from {@link #formerNames}. */
	public List<MyriadId> formerIds() {
		List<MyriadId> ids = new ArrayList<>(formerNames.size());
		for (String n : formerNames) ids.add(n.indexOf(':') > 0 ? MyriadId.parse(n) : MyriadId.of(id().namespace(), MyriadId.toPath(n)));
		return ids;
	}

	/** Brings settings saved at {@code savedVersion} up to {@link #settingsVersion}, step by step. */
	@ApiStatus.Internal
	public void upgradeSavedSettings(JsonObject saved, int savedVersion) {
		SavedSettings view = new SavedSettings(saved);
		for (int v = Math.max(1, savedVersion); v < settingsVersion(); v++) migrateSettings(v, view);
	}

	/** Extra text shown next to the module in the module list, e.g. the current mode. */
	public String hudInfo() {
		return null;
	}

	/** True when a world and player exist. */
	protected static boolean inGame() {
		return mc.level != null && mc.player != null;
	}

	public void info(String message) {
		Myriad.notifications().info(name, message);
	}

	public void warn(String message) {
		Myriad.notifications().warn(name, message);
	}

	public void error(String message) {
		Myriad.notifications().error(name, message);
	}

	/** Sends a client-side chat line prefixed with the Myriad tag. */
	protected void sendChat(Component text) {
		Myriad.chat(text);
	}

	@Override
	public String toString() {
		return name;
	}

	@ApiStatus.Internal
	public static void bindClient(Minecraft client) {
		mc = client;
	}
}
