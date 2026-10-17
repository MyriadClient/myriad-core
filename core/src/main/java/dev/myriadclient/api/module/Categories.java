package dev.myriadclient.api.module;

import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.MyriadId;

import java.util.List;

/**
 * The conventional categories, registered by Myriad core so that every addon shares the same vocabulary. They hold
 * no modules themselves. Each addon's modules in a category get their own window ("Combat · Essentials"); sharing the
 * category gives those windows the same icon and colour and places a new addon's window beside the others.
 */
public final class Categories {
	public static final Category COMBAT = of("combat", "Combat", "\uf05b", SettingColor.Mode.RED);
	public static final Category MOVEMENT = of("movement", "Movement", "\uf135", SettingColor.Mode.BLUE);
	public static final Category RENDER = of("render", "Render", "\uf06e", SettingColor.Mode.MAGENTA);
	public static final Category PLAYER = of("player", "Player", "\uf007", SettingColor.Mode.GREEN);
	public static final Category WORLD = of("world", "World", "\uf0ac", SettingColor.Mode.YELLOW);
	public static final Category MISC = of("misc", "Misc", "\uf0d0", SettingColor.Mode.CYAN);

	public static final List<Category> DEFAULTS = List.of(COMBAT, MOVEMENT, RENDER, PLAYER, WORLD, MISC);

	private Categories() {
	}

	private static Category of(String path, String name, String icon, SettingColor.Mode role) {
		return new Category(MyriadId.of("myriad", path), name, icon, SettingColor.role(role));
	}
}
