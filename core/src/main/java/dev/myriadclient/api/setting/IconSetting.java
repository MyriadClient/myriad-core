package dev.myriadclient.api.setting;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * An icon the player picks from {@link dev.myriadclient.api.ui.Icons} (a glyph of the UI's icon font). Empty means
 * "the default": whatever the setting's owner shows without a choice.
 */
public class IconSetting extends StringSetting {
	private String fallback = "";

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#icon(String)}, or call this from a
	 * subclass.
	 */
	public IconSetting(String name, @Nullable String description, String defaultValue, @Nullable Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	/** The picked icon, or {@code fallback} when none is. */
	public String getOr(String fallback) {
		return get().isEmpty() ? fallback : get();
	}

	/** What the picker shows for "the default", if its owner said; empty otherwise. */
	@ApiStatus.AvailableSince("1.4")
	public String fallback() {
		return fallback;
	}

	/** Builds an {@link IconSetting}; empty (the default icon) by default. */
	public static class Builder extends Setting.Builder<IconSetting.Builder, String, IconSetting> {
		private String fallback = "";

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#icon(String)}, which adds it to the
		 * group.
		 */
		public Builder(String name) {
			super(name, "");
		}

		/** The glyph its owner shows without a choice, for the picker to show as "the default". */
		@ApiStatus.AvailableSince("1.4")
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
