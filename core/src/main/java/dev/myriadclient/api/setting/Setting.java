package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;

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
 */
public abstract class Setting<T> {
	private static Runnable globalChangeHook = () -> {
	};

	protected final String name;
	protected final String id;
	protected final String description;
	protected final T defaultValue;
	protected T value;
	private final Supplier<Boolean> visible;
	private final List<Consumer<T>> listeners = new ArrayList<>(1);
	private SettingGroup group;
	/** Values laid over the player's, lowest first by layer; null when there are none (the usual case). */
	private List<Layered<T>> overrides;
	/** While decoding or encoding a value without touching the setting: no listeners fire. */
	private boolean muted;

	/** Who lays a value over the player's, lowest first: a later layer wins over an earlier one. */
	public enum Layer {
		/** Values for a kind of server (not used yet). */
		SERVER,
		/** A control panel, while it's on. */
		CONTROL_PANEL,
		/** A module or addon that needs this one to behave a certain way while it uses it. */
		MODULE
	}

	private record Layered<T>(Object owner, Layer layer, String label, T value, Consumer<T> editor) {
	}

	protected Setting(String name, String description, T defaultValue, Supplier<Boolean> visible) {
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

	public String name() {
		return name;
	}

	/** Stable key used in config files: the name in snake_case. */
	public String id() {
		return id;
	}

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
	 */
	public void override(Object owner, Layer layer, String label, T value, Consumer<T> editor) {
		T before = get();
		if (overrides == null) overrides = new ArrayList<>(1);
		overrides.removeIf(o -> o.owner() == owner);
		overrides.add(new Layered<>(owner, layer, label, validate(copy(value)), editor));
		if (!Objects.equals(before, get())) changed();
	}

	public void override(Object owner, Layer layer, String label, T value) {
		override(owner, layer, label, value, null);
	}

	/** Takes {@code owner}'s override off, uncovering what's under it. */
	public void release(Object owner) {
		if (overrides == null) return;
		T before = get();
		if (!overrides.removeIf(o -> o.owner() == owner)) return;
		if (overrides.isEmpty()) overrides = null;
		if (!Objects.equals(before, get())) changed();
	}

	public boolean isOverridden() {
		return overrides != null;
	}

	/** Who set the value in effect ("Hunting"), or null when it's the player's own. */
	public String overriddenBy() {
		return overrides == null ? null : top().label();
	}

	private Layered<T> top() {
		Layered<T> top = overrides.getFirst();
		for (Layered<T> o : overrides) if (o.layer().ordinal() >= top.layer().ordinal()) top = o;
		return top;
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
	public T decode(String input) {
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

	public T defaultValue() {
		return defaultValue;
	}

	/** Sets the value (after {@link #validate}); listeners fire only if it changed. */
	public void set(T newValue) {
		T validated = validate(newValue);
		if (Objects.equals(validated, value)) return;
		value = validated;
		changed();
	}

	/** Call after mutating a collection value in place. */
	public void changed() {
		if (muted) return;
		T now = get();
		for (Consumer<T> l : listeners) l.accept(now);
		if (group != null) group.owner().changed(this);
		globalChangeHook.run();
	}

	public void reset() {
		set(copy(defaultValue));
	}

	public boolean isDefault() {
		return Objects.equals(value, defaultValue);
	}

	public boolean isVisible() {
		return visible.get();
	}

	public Setting<T> onChanged(Consumer<T> listener) {
		listeners.add(listener);
		return this;
	}

	public SettingGroup group() {
		return group;
	}

	void setGroup(SettingGroup group) {
		this.group = group;
	}

	/** Clamp / normalise a candidate value. */
	protected T validate(T v) {
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

	public abstract JsonElement toJson();

	/** Load from JSON; invalid input must leave the value unchanged rather than throw. */
	public abstract void fromJson(JsonElement json);

	/** Parse user input from a command. Returns false if the input is invalid. */
	public abstract boolean parse(String input);

	/** Completions for command input. */
	public List<String> suggestions() {
		return List.of();
	}

	/** Human-readable current value. */
	public String valueString() {
		return String.valueOf(get());
	}

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
		protected final String name;
		protected String description = "";
		protected T defaultValue;
		protected Supplier<Boolean> visible = () -> true;
		protected Consumer<T> onChanged;
		protected boolean protect;
		SettingGroup group;

		protected Builder(String name, T defaultValue) {
			this.name = name;
			this.defaultValue = defaultValue;
		}

		public B description(String description) {
			this.description = description;
			return (B) this;
		}

		public B defaultValue(T value) {
			this.defaultValue = value;
			return (B) this;
		}

		public B visible(Supplier<Boolean> visible) {
			this.visible = visible;
			return (B) this;
		}

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

		protected abstract S create();

		public S build() {
			S s = create();
			((Setting<?>) s).isProtected = protect;
			if (onChanged != null) s.onChanged(onChanged);
			if (group != null) group.add(s);
			return s;
		}
	}
}
