package com.example.myriadaddon.modules;

import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingColor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Prefixes chat messages with the time they arrived.
 *
 * <p>There's no Myriad event for "a chat line is being added", so this module is driven by a mixin
 * ({@code ChatHudMixin}). That's the standard shape for mixin-driven modules:
 * <ul>
 *   <li>the module holds the settings and the logic, in a static method the mixin calls;</li>
 *   <li>that method looks the module up with {@link Modules#active(Class)}, which is null while it's off (or before
 *       Myriad has started, since mixins can run very early), so the mixin stays a one-liner.</li>
 * </ul>
 */
public final class ChatTimestamps extends Module {
	public enum Format {
		HOURS_MINUTES("HH:mm"), WITH_SECONDS("HH:mm:ss"), TWELVE_HOUR("h:mm a");

		final DateTimeFormatter formatter;

		Format(String pattern) {
			formatter = DateTimeFormatter.ofPattern(pattern);
		}
	}

	private final EnumSetting<Format> format = sgGeneral.enumSetting("Format", Format.HOURS_MINUTES).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.TEXT, 0x88)).build();

	public ChatTimestamps() {
		super(Categories.MISC, "Chat Timestamps", "Shows when each chat message arrived.");
	}

	/** Called by the mixin for every chat line. Returns the line unchanged while the module is off. */
	public static Component decorate(Component message) {
		ChatTimestamps m = Modules.active(ChatTimestamps.class);
		if (m == null) return message;
		String time = LocalTime.now().format(m.format.get().formatter);
		int rgb = m.color.argb() & 0xFFFFFF;
		return Component.literal("[" + time + "] ").withStyle(s -> s.withColor(TextColor.fromRgb(rgb))).append(message);
	}
}
