package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A button: clicking it in the settings window, or setting it from a command with any value, runs its action on the
 * render thread. Not saved to config, and not part of shared configs.
 */
public class ActionSetting extends Setting<Runnable> {
	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#action(String, Runnable)}, or call
	 * this from a subclass.
	 */
	public ActionSetting(String name, @Nullable String description, Runnable action, @Nullable Supplier<Boolean> visible) {
		super(name, description, action, visible);
	}

	/** Runs the action now, as a click does. */
	public void run() {
		value.run();
	}

	@Override
	public boolean isSerializable() {
		return false;
	}

	@Override
	public JsonElement toJson() {
		return JsonNull.INSTANCE;
	}

	@Override
	public void fromJson(JsonElement json) {
	}

	@Override
	public boolean parse(String input) {
		run();
		return true;
	}

	@Override
	public String valueString() {
		return "";
	}

	/** Builds an {@link ActionSetting}. Its default value is the action. */
	public static class Builder extends Setting.Builder<ActionSetting.Builder, Runnable, ActionSetting> {
		/**
		 * A builder for a button called {@code name}; prefer {@link SettingGroup#action(String, Runnable)}, which adds
		 * it to the group.
		 */
		public Builder(String name, Runnable action) {
			super(name, action);
		}

		@Override
		protected ActionSetting create() {
			return new ActionSetting(name, description, defaultValue, visible);
		}
	}
}
