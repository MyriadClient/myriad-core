package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

import java.util.function.Supplier;

/** A button. Not saved to config. */
public class ActionSetting extends Setting<Runnable> {
	public ActionSetting(String name, String description, Runnable action, Supplier<Boolean> visible) {
		super(name, description, action, visible);
	}

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

	public static class Builder extends Setting.Builder<ActionSetting.Builder, Runnable, ActionSetting> {
		public Builder(String name, Runnable action) {
			super(name, action);
		}

		@Override
		protected ActionSetting create() {
			return new ActionSetting(name, description, defaultValue, visible);
		}
	}
}
