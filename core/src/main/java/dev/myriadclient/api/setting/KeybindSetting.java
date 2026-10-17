package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.util.Keybind;

import java.util.function.Supplier;

/**
 * A key or mouse button. Beyond the module's own toggle bind, use one for an action inside a module (a "sub bind"):
 *
 * <pre>{@code
 * private final KeybindSetting swapKey = sgGeneral.keybind("Swap").build();
 *
 * @Subscribe private void onKey(KeyEvent e) { if (swapKey.wasPressed(e)) swap(); }
 * @Subscribe private void onMouse(MouseButtonEvent e) { if (swapKey.wasPressed(e)) swap(); }
 * }</pre>
 */
public class KeybindSetting extends Setting<Keybind> {
	public KeybindSetting(String name, String description, Keybind defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	/** Whether {@code e} is a press of this bind in game (not while typing in a screen). */
	public boolean wasPressed(KeyEvent e) {
		return e.isPress() && !e.inScreen() && get().matchesKey(e.key(), e.modifiers());
	}

	/** Whether {@code e} is a press of this bind in game, for binds on a mouse button. */
	public boolean wasPressed(MouseButtonEvent e) {
		return e.isPress() && !e.inScreen() && get().matchesMouse(e.button(), e.modifiers());
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value.serialize());
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive()) set(Keybind.deserialize(json.getAsString()));
	}

	@Override
	public boolean parse(String input) {
		Keybind bind = Keybind.parse(input);
		if (bind == null) return false;
		set(bind);
		return true;
	}

	@Override
	public String valueString() {
		return get().displayName();
	}

	public static class Builder extends Setting.Builder<KeybindSetting.Builder, Keybind, KeybindSetting> {
		public Builder(String name) {
			super(name, Keybind.NONE);
		}

		@Override
		protected KeybindSetting create() {
			return new KeybindSetting(name, description, defaultValue, visible);
		}
	}
}
