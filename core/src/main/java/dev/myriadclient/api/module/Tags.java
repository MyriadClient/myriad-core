package dev.myriadclient.api.module;

import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The shared tags, registered by Myriad core so that every addon uses the same words: {@code #combat} finds combat
 * modules from every addon, in the same colour and with the same icon.
 */
public final class Tags {
	public static final Tag COMBAT = of("combat", "Combat", "", SettingColor.Mode.RED);
	public static final Tag MOVEMENT = of("movement", "Movement", "", SettingColor.Mode.BLUE);
	public static final Tag RENDER = of("render", "Render", "", SettingColor.Mode.MAGENTA);
	public static final Tag PLAYER = of("player", "Player", "", SettingColor.Mode.GREEN);
	public static final Tag WORLD = of("world", "World", "", SettingColor.Mode.YELLOW);
	public static final Tag MISC = of("misc", "Misc", "", SettingColor.Mode.CYAN);
	/** Modules that do a job for you on their own: eating, picking tools, walking, mending, sorting, building. */
	@ApiStatus.AvailableSince("1.1")
	public static final Tag AUTOMATION = of("automation", "Automation", "", SettingColor.Mode.SECONDARY);

	public static final List<Tag> DEFAULTS = List.of(COMBAT, MOVEMENT, RENDER, PLAYER, WORLD, AUTOMATION, MISC);

	private Tags() {
	}

	private static Tag of(String path, String name, String icon, SettingColor.Mode role) {
		return new Tag(MyriadId.of("myriad", path), name, icon, SettingColor.role(role));
	}
}
