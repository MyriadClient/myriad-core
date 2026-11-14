package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import dev.myriadclient.api.registry.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A typed, serialisable, user-editable value. Subclasses define how the value is stored as JSON and parsed
 * from command input; the UI picks a widget by setting class (see {@code SettingWidgets}), so addons can add
 * new setting types with their own widgets.
 * <p>
 * <b>Layers.</b> The value the player sets is the bottom layer, and the only one saved. Others can lay a value on top
 * for as long as they need it ({@link #override}): a control panel while it's on, a module that uses this one, later
 * a server profile. {@link #get()} is the topmost layer's value; releasing an override uncovers what's under it, so
 * nothing has to be put back and nothing is left changed. Subclasses keep reading and writing {@link #value} (the
 * player's) for saving, parsing and in-place edits, and use {@link #get()} for behaviour.
 * <p>
 * Settings are made with the builders of a {@link SettingGroup} and live as long as their owner. Read them freely
 * (from mixins too); change them on the render thread. A change runs the {@link #onChanged} listeners right away, on
 * the thread that made it, and has the owner's profile saved shortly after.
 *
 * <pre>{@code
 * private final DoubleSetting range = sgGeneral.decimal("Range").defaultValue(4.5).range(0, 6).build();
 *
 * double r = range.get();                                         // in effect now
 * otherModule.speed.override(this, Setting.Layer.MODULE, name(), 1.0);   // while this module needs it
 * otherModule.speed.release(this);                                // back to what was there
 * }</pre>
 */
public abstract class Setting<T> {
	private static Runnable globalChangeHook = () -> {
	};

	/** The display name, as given to the builder. */
	protected final String name;
	/** {@link #name} in snake_case: the key in config files. */
	protected final String id;
	/** The tooltip text; empty for none. */
	protected final String description;
	/**
	 * The value it starts at and {@link #reset} goes back to. Never changed: {@link #copy} keeps it apart from {@link
	 * #value}.
	 */
	protected final T defaultValue;
	/** The player's own value, the one saved: subclasses read and write it for saving, parsing and in-place edits. */
	protected T value;
	private final Supplier<Boolean> visible;
	private final List<Consumer<T>> listeners = new ArrayList<>(1);
	private @Nullable SettingGroup group;
	/** Values laid over the player's, lowest first by layer; null when there are none (the usual case). */
	private @Nullable List<Layered<T>> overrides;
	/** While decoding or encoding a value without touching the setting: no listeners fire. */
	private boolean muted;

	/** Who lays a value over the player's, lowest first: a later layer wins over an earlier one. */
	public enum Layer {
		/**
		 * Values for the server you're on, under everything else: an addon's per-server profile ({@link #override}).
		 * Core sets none.
		 */
		SERVER,
		/** A control panel, while it's on. */
		CONTROL_PANEL,
		/** A module or addon that needs this one to behave a certain way while it uses it. */
		MODULE
	}

	private record Layered<T>(Object owner, Layer layer, String label, T value, @Nullable Consumer<T> editor) {
	}

	/**
	 * A setting starting at {@code defaultValue}. Addons make settings with a {@link SettingGroup}'s builders; this is for
	 * the {@link Builder#create()} of a setting type of your own.
	 *
	 * @param description the tooltip; null for none
	 * @param visible     whether the settings window shows it right now (asked as it draws); null for always
	 */
	protected Setting(String name, @Nullable String description, T defaultValue, @Nullable Supplier<Boolean> visible) {
		this.name = Objects.requireNonNull(name);
		this.id = MyriadId.toPath(name);
		this.description = description == null ? "" : description;
		this.defaultValue = defaultValue;
		this.value = copy(defaultValue);
		this.visible = visible == null ? () -> true : visible;
	}

	/** Whether its value is never shared (see {@link Builder#protect()}). */
	private boolean isProtected;

	/**
	 * Whether this setting's value is private to the player (a webhook URL, coordinates, a password a macro types):
	 * shared configs leave it out, and taking one in leaves the player's own value as it is.
	 */
	public boolean isProtected() {
		return isProtected;
	}

	/** The display name, as given to the builder. */
	public String name() {
		return name;
	}

	/** Stable key used in config files: the name in snake_case. */
	public String id() {
		return id;
	}

	/** The tooltip text; empty for none. */
	public String description() {
		return description;
	}

	/** The value in effect: the topmost override's, or the player's. */
	public T get() {
		return overrides == null ? value : top().value();
	}

	/** The player's own value (the one saved), whatever overrides lie on it. */
	public T playerValue() {
		return value;
	}

	/**
	 * Lays {@code value} over the player's for {@code owner} until {@link #release}d (replacing any it laid before).
	 * {@code label} says who, in the UI ("Hunting"). An {@code editor}, if given, takes edits made in the UI while this
	 * override is the one in effect (a control panel saving them); without one, edits go to the player's value.
	 * Listeners fire if the value in effect changes. Overrides aren't saved: lay yours again after a restart.
	 */
	public void override(Object owner, Layer layer, String label, T value, @Nullable Consumer<T> editor) {
		T before = get();
		if (overrides == null) overrides = new ArrayList<>(1);
		overrides.removeIf(o -> o.owner() == owner);
		overrides.add(new Layered<>(owner, layer, label, validate(copy(value)), editor));
		if (!Objects.equals(before, get())) changed();
	}

	/**
	 * {@link #override(Object, Layer, String, Object, Consumer)} without an editor: UI edits go to the player's value.
	 */
	public void override(Object owner, Layer layer, String label, T value) {
		override(owner, layer, label, value, null);
	}

	/** Takes {@code owner}'s override off, uncovering what's under it; nothing if it laid none. */
	public void release(Object owner) {
		if (overrides == null) return;
		T before = get();
		if (!overrides.removeIf(o -> o.owner() == owner)) return;
		if (overrides.isEmpty()) overrides = null;
		if (!Objects.equals(before, get())) changed();
	}

	/** Whether any override lies on the player's value (so {@link #get()} may differ from {@link #playerValue()}). */
	public boolean isOverridden() {
		return overrides != null;
	}

	/** Who set the value in effect ("Hunting"), or null when it's the player's own. */
	public @Nullable String overriddenBy() {
		return overrides == null ? null : top().label();
	}

	private Layered<T> top() {
		Layered<T> top = overrides.getFirst();
		for (Layered<T> o : overrides) if (o.layer().ordinal() >= top.layer().ordinal()) top = o;
		return top;
	}

	/** Whether {@link #edit} would change the value in effect: no override lies on it, or the one in effect takes edits. */
	@ApiStatus.AvailableSince("2.0")
	public boolean editsTakeEffect() {
		return overrides == null || overrides.isEmpty() || top().editor() != null;
	}

	/**
	 * An edit from the UI: to the override in effect if it takes edits (a control panel saving what you tune while it's
	 * on), otherwise to the player's value. Code that sets values on purpose uses {@link #set}.
	 */
	public void edit(T newValue) {
		Layered<T> top = overrides == null ? null : top();
		if (top == null || top.editor() == null) {
			set(newValue);
			return;
		}
		T validated = validate(newValue);
		if (Objects.equals(validated, top.value())) return;
		overrides.set(overrides.indexOf(top), new Layered<>(top.owner(), top.layer(), top.label(), validated, top.editor()));
		top.editor().accept(validated);
		changed();
	}

	/** The value {@code json} stands for, read the way {@link #fromJson} reads it, without changing this setting. */
	public T decode(JsonElement json) {
		T base = value;
		muted = true;
		try {
			value = copy(base);
			fromJson(json);
			return value;
		} finally {
			value = base;
			muted = false;
		}
	}

	/** The value {@code input} stands for, read the way {@link #parse} reads it (null if it isn't valid), without changing this setting. */
	public @Nullable T decode(String input) {
		T base = value;
		muted = true;
		try {
			value = copy(base);
			return parse(input) ? value : null;
		} finally {
			value = base;
			muted = false;
		}
	}

	/** {@code v} as JSON, the way {@link #toJson} writes it. */
	public JsonElement encode(T v) {
		T base = value;
		muted = true;
		try {
			value = v;
			return toJson();
		} finally {
			value = base;
			muted = false;
		}
	}

	/** The value it starts at and {@link #reset} goes back to. */
	public T defaultValue() {
		return defaultValue;
	}

	/**
	 * Sets the player's value (after {@link #validate}: clamped, rounded or replaced as the type requires); listeners fire
	 * only if it changed. While an override is in effect, {@link #get()} keeps the override's value.
	 */
	public void set(T newValue) {
		T validated = validate(newValue);
		if (Objects.equals(validated, value)) return;
		value = validated;
		changed();
	}

	/**
	 * Runs the listeners with the value in effect and has the owner saved. {@link #set} calls it; call it yourself after
	 * changing a collection value in place.
	 */
	public void changed() {
		if (muted) return;
		T now = get();
		for (Consumer<T> l : listeners) l.accept(now);
		if (group != null) group.owner().changed(this);
		globalChangeHook.run();
	}

	/** Sets the player's value back to the default. */
	public void reset() {
		set(copy(defaultValue));
	}

	/** Whether the player's value is the default (then it isn't written to config). */
	public boolean isDefault() {
		return Objects.equals(value, defaultValue);
	}

	/** Whether the settings window shows it right now, from the builder's {@link Builder#visible}. */
	public boolean isVisible() {
		return visible.get();
	}

	/**
	 * Runs {@code listener} with the value in effect each time it changes, by the player, an override or code. Returns
	 * this setting. A listener can't be removed, so add it once.
	 */
	public Setting<T> onChanged(Consumer<T> listener) {
		listeners.add(listener);
		return this;
	}

	/** The group it was built into or added to; null for a setting made on its own. */
	public @Nullable SettingGroup group() {
		return group;
	}

	void setGroup(SettingGroup group) {
		this.group = group;
	}

	/** Clamps or normalises a candidate value before it's set or laid over; null gives the default. */
	protected T validate(@Nullable T v) {
		return v == null ? copy(defaultValue) : v;
	}

	/** Copy a value so the default is never mutated. Override for mutable collection types. */
	protected T copy(T v) {
		return v;
	}

	/** Whether this setting is written to config (actions aren't). */
	public boolean isSerializable() {
		return true;
	}

	/** The player's value as JSON, for config and shares. */
	public abstract JsonElement toJson();

	/** Load from JSON; invalid input must leave the value unchanged rather than throw. */
	public abstract void fromJson(JsonElement json);

	/**
	 * Sets the player's value from what a player typed (a command, a text box). Returns false, leaving the value as it
	 * was, if the input isn't valid.
	 */
	public abstract boolean parse(String input);

	/** Completions for command input; none by default. */
	public List<String> suggestions() {
		return List.of();
	}

	/** The value in effect for people, the way {@link #parse} reads it back where it can. */
	public String valueString() {
		return String.valueOf(get());
	}

	/** Core's: told of every change to any setting, for the config's autosave. */
	@ApiStatus.Internal
	public static void setGlobalChangeHook(Runnable hook) {
		globalChangeHook = hook;
	}

	/**
	 * Fluent builder base. Obtain builders from {@link SettingGroup} (e.g. {@code sgGeneral.bool("Rotate")}) so the
	 * built setting is added to the group automatically.
	 */
	@SuppressWarnings("unchecked")
	public abstract static class Builder<B extends Builder<B, T, S>, T, S extends Setting<T>> {
		/** The name given to the builder. */
		protected final String name;
		/** From {@link #description}; empty by default. */
		protected String description = "";
		/** From {@link #defaultValue}; each type starts with its own (false, 0, empty). */
		protected T defaultValue;
		/** From {@link #visible}; always visible by default. */
		protected Supplier<Boolean> visible = () -> true;
		/** From {@link #onChanged}; none by default. */
		protected @Nullable Consumer<T> onChanged;
		/** From {@link #protect()}; off by default, on for coordinates and file paths. */
		protected boolean protect;
		SettingGroup group;

		/** A builder for a setting called {@code name}, with the type's own default value. */
		protected Builder(String name, T defaultValue) {
			this.name = name;
			this.defaultValue = defaultValue;
		}

		/** The tooltip: one sentence on what the setting does. */
		public B description(String description) {
			this.description = description;
			return (B) this;
		}

		/** The value it starts at, and goes back to on reset. Only values that differ from it are saved. */
		public B defaultValue(T value) {
			this.defaultValue = value;
			return (B) this;
		}

		/**
		 * When the settings window shows it, asked as the window draws: e.g. {@code () -> mode.get() == Mode.CUSTOM}. A
		 * hidden setting still applies and is still saved.
		 */
		public B visible(Supplier<Boolean> visible) {
			this.visible = visible;
			return (B) this;
		}

		/** A listener for changes, as {@link Setting#onChanged} adds; one per builder. */
		public B onChanged(Consumer<T> onChanged) {
			this.onChanged = onChanged;
			return (B) this;
		}

		/**
		 * Keeps the value out of shared configs: for anything private to the player (a webhook URL, a server address,
		 * coordinates, a password). Shares say the setting is there, never what it's set to, and taking a share in
		 * leaves the player's own value alone. Coordinates ({@link BlockPosSetting}) and file paths
		 * ({@link FileSetting}) are protected unless you say otherwise.
		 *
		 * <pre>{@code
		 * private final StringSetting webhook = sgGeneral.string("Webhook").description("Discord webhook URL.").protect().build();
		 * }</pre>
		 */
		public B protect() {
			return protect(true);
		}

		/** {@link #protect()}, or with {@code false} shareable (coordinates that are a public place, say). */
		public B protect(boolean protect) {
			this.protect = protect;
			return (B) this;
		}

		/**
		 * Makes the setting from the builder's fields; {@link #build()} does the rest. For setting types of your own.
		 */
		protected abstract S create();

		/** Makes the setting and, for a builder from a {@link SettingGroup}, adds it to the group. Call it once. */
		public S build() {
			S s = create();
			((Setting<?>) s).isProtected = protect;
			if (onChanged != null) s.onChanged(onChanged);
			if (group != null) group.add(s);
			return s;
		}
	}
}
