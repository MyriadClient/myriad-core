package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.myriadclient.api.util.ColorUtil;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * A colour: a fixed ARGB colour, a rainbow, or a theme role that follows the theme (see {@link SettingColor}), with a
 * picker. Typed as a hex colour ({@code #AARRGGBB}, or {@code #RRGGBB} for opaque), {@code rainbow}, or a role's name
 * ({@code accent}, {@code red}). Draw with {@link #argb()} each frame rather than caching it, so roles and rainbows stay
 * current.
 */
public class ColorSetting extends Setting<SettingColor> {
	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#color(String)}, or call this from a
	 * subclass.
	 */
	public ColorSetting(String name, @Nullable String description, SettingColor defaultValue, @Nullable Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	/** Shortcut for {@code get().argb()}. */
	public int argb() {
		return get().argb();
	}

	@Override
	public JsonElement toJson() {
		JsonObject o = new JsonObject();
		o.add("color", new JsonPrimitive(ColorUtil.toHex(value.color())));
		o.add("mode", new JsonPrimitive(value.mode().name()));
		return o;
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			if (json.isJsonPrimitive()) {
				set(SettingColor.of(ColorUtil.parseHex(json.getAsString())));
			} else if (json.isJsonObject()) {
				JsonObject o = json.getAsJsonObject();
				set(new SettingColor(ColorUtil.parseHex(o.get("color").getAsString()), SettingColor.Mode.valueOf(o.get("mode").getAsString())));
			}
		} catch (RuntimeException ignored) {
		}
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		if (in.equalsIgnoreCase("rainbow")) {
			set(value.withMode(SettingColor.Mode.RAINBOW));
			return true;
		}
		for (SettingColor.Mode m : SettingColor.Mode.values()) {
			if (m.isThemeRole() && in.equalsIgnoreCase(m.name())) {
				set(value.withMode(m));
				return true;
			}
		}
		try {
			set(SettingColor.of(ColorUtil.parseHex(in)));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public List<String> suggestions() {
		return List.of("#FFFFFFFF", "rainbow", "accent", "secondary", "red", "green", "yellow", "blue", "magenta", "cyan", "text");
	}

	@Override
	public String valueString() {
		return get().mode() == SettingColor.Mode.STATIC ? ColorUtil.toHex(get().color()) : get().mode().name().toLowerCase();
	}

	/** Builds a {@link ColorSetting}; opaque white by default. Prefer a theme role for the default. */
	public static class Builder extends Setting.Builder<ColorSetting.Builder, SettingColor, ColorSetting> {
		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#color(String)}, which adds it to the
		 * group.
		 */
		public Builder(String name) {
			super(name, SettingColor.of(0xFFFFFFFF));
		}

		/** A fixed ARGB colour as the default ({@code 0xFF7FD8AE}). */
		public ColorSetting.Builder defaultValue(int argb) {
			return defaultValue(SettingColor.of(argb));
		}

		@Override
		protected ColorSetting create() {
			return new ColorSetting(name, description, defaultValue, visible);
		}
	}
}
