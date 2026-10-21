package dev.myriadclient.api.setting;

import java.util.function.Supplier;

/**
 * An icon the player picks from {@link dev.myriadclient.api.ui.Icons} (a glyph of the UI's icon font). Empty means
 * "the default": whatever the setting's owner shows without a choice.
 */
public class IconSetting extends StringSetting {
	public IconSetting(String name, String description, String defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	/** The picked icon, or {@code fallback} when none is. */
	public String getOr(String fallback) {
		return get().isEmpty() ? fallback : get();
	}

	public static class Builder extends Setting.Builder<IconSetting.Builder, String, IconSetting> {
		public Builder(String name) {
			super(name, "");
		}

		@Override
		protected IconSetting create() {
			return new IconSetting(name, description, defaultValue, visible);
		}
	}
}
