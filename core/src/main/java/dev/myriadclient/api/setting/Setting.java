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

	protected Setting(String name, String description, T defaultValue, Supplier<Boolean> visible) {
		this.name = Objects.requireNonNull(name);
		this.id = MyriadId.toPath(name);
		this.description = description == null ? "" : description;
		this.defaultValue = defaultValue;
		this.value = copy(defaultValue);
		this.visible = visible == null ? () -> true : visible;
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

	public T get() {
		return value;
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
		for (Consumer<T> l : listeners) l.accept(value);
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
		return String.valueOf(value);
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

		protected abstract S create();

		public S build() {
			S s = create();
			if (onChanged != null) s.onChanged(onChanged);
			if (group != null) group.add(s);
			return s;
		}
	}
}
