package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.setting.SettingColor;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The shared tags, registered by Myriad core so that every addon uses the same words: {@code #combat} finds combat
 * modules from every addon, in the same colour and with the same icon.
 */
public final class Tags {
	/** Fighting: aim, attack, crystals, totems, armour. */
	public static final Tag COMBAT = of("combat", "Combat", "", SettingColor.Mode.RED);
	/** Getting around: speed, flight, elytra, stepping, sprinting. */
	public static final Tag MOVEMENT = of("movement", "Movement", "", SettingColor.Mode.BLUE);
	/** What you see: ESP, highlights, tracers, removing overlays. */
	public static final Tag RENDER = of("render", "Render", "", SettingColor.Mode.MAGENTA);
	/** Your own player and inventory: reach, interaction, hand and item tweaks. */
	public static final Tag PLAYER = of("player", "Player", "", SettingColor.Mode.GREEN);
	/** The blocks around you: placing, breaking, scaffolding, finding things. */
	public static final Tag WORLD = of("world", "World", "", SettingColor.Mode.YELLOW);
	/** Anything the other tags don't cover: chat, connection, client utilities. */
	public static final Tag MISC = of("misc", "Misc", "", SettingColor.Mode.CYAN);
	/** Modules that do a job for you on their own: eating, picking tools, walking, mending, sorting, building. */
	@ApiStatus.AvailableSince("1.1")
	public static final Tag AUTOMATION = of("automation", "Automation", "", SettingColor.Mode.SECONDARY);

	/** Every shared tag, the ones core registers. */
	public static final List<Tag> DEFAULTS = List.of(COMBAT, MOVEMENT, RENDER, PLAYER, WORLD, AUTOMATION, MISC);

	private Tags() {
	}

	private static Tag of(String path, String name, String icon, SettingColor.Mode role) {
		return new Tag(MyriadId.of("myriad", path), name, icon, SettingColor.role(role));
	}
}
