package dev.myriadclient.api.setting;

import org.jetbrains.annotations.ApiStatus;

import java.util.function.Supplier;

/**
 * An icon the player picks from {@link dev.myriadclient.api.ui.Icons} (a glyph of the UI's icon font). Empty means
 * "the default": whatever the setting's owner shows without a choice.
 */
public class IconSetting extends StringSetting {
	private String fallback = "";

	public IconSetting(String name, String description, String defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	/** The picked icon, or {@code fallback} when none is. */
	public String getOr(String fallback) {
		return get().isEmpty() ? fallback : get();
	}

	/** What the picker shows for "the default", if its owner said; empty otherwise. */
	@ApiStatus.AvailableSince("1.4.0")
	public String fallback() {
		return fallback;
	}

	public static class Builder extends Setting.Builder<IconSetting.Builder, String, IconSetting> {
		private String fallback = "";

		public Builder(String name) {
			super(name, "");
		}

		/** The glyph its owner shows without a choice, for the picker to show as "the default". */
		@ApiStatus.AvailableSince("1.4.0")
		public Builder fallback(String glyph) {
			this.fallback = glyph;
			return this;
		}

		@Override
		protected IconSetting create() {
			IconSetting s = new IconSetting(name, description, defaultValue, visible);
			s.fallback = fallback;
			return s;
		}
	}
}
