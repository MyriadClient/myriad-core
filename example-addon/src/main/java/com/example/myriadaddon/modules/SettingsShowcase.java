package com.example.myriadaddon.modules;

import com.example.myriadaddon.settings.RangeSetting;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.BlockPosSetting;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.FileSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.ModuleListSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.StringListSetting;
import dev.myriadclient.api.setting.StringSetting;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Every built-in setting type, plus this addon's own {@link RangeSetting}, in one place to look things up. It lives
 * with the addon's own tag (registered in {@code ExampleAddon#registerTags}).
 *
 * <p>Settings are fields built from a group. Myriad saves them, shows them in the menu, makes them settable with
 * {@code .set settings_showcase <setting> <value>}, and resets them with a right-click on their name.
 */
public final class SettingsShowcase extends Module {
	public enum Flavour {
		VANILLA, CHOCOLATE, STRAWBERRY
	}

	// Basic types, in the General group.
	private final BoolSetting greet = sgGeneral.bool("Greet On Enable").description("Say hello when enabled.").defaultValue(true).build();
	// visible(...) hides a setting until it matters.
	private final StringSetting message = sgGeneral.string("Message").defaultValue("Hello from an addon!").visible(greet::get).build();
	// protect(): private to the player. Shared configs say it's there but never carry its value, and taking a share in
	// leaves yours alone. Coordinates (BlockPosSetting, like Origin below) and file paths are protected already.
	private final StringSetting webhook = sgGeneral.string("Webhook").description("A Discord webhook URL (never shared).").protect().build();
	private final IntSetting count = sgGeneral.intSetting("Count").defaultValue(3).range(0, 10).build();
	// range() is the hard limit; sliderRange() only narrows the slider, so typed values can go further.
	private final DoubleSetting speed = sgGeneral.doubleSetting("Speed").defaultValue(1.5).range(0, 20).sliderRange(0, 5).decimals(2).build();
	private final EnumSetting<Flavour> flavour = sgGeneral.enumSetting("Flavour", Flavour.VANILLA).build();

	// Each group is a folding section in the menu.
	private final SettingGroup sgColors = settings.group("Colors");
	// A theme role follows the theme; SettingColor.of(argb) is a fixed colour. Players can pick either, or rainbow.
	private final ColorSetting themed = sgColors.color("Themed").description("Defaults to the theme's magenta.").defaultValue(SettingColor.role(SettingColor.Mode.MAGENTA)).build();
	private final ColorSetting fixed = sgColors.color("Fixed").defaultValue(0xCCF5A97F).build();

	private final SettingGroup sgLists = settings.group("Lists");
	private final StringListSetting words = sgLists.stringList("Words").defaultValue("myriad", "addons").build();
	private final RegistryListSetting<Block> blocks = sgLists.blocks("Blocks").defaultValue(Blocks.DIAMOND_ORE, Blocks.ANCIENT_DEBRIS).build();
	private final RegistryListSetting<Item> items = sgLists.items("Items").defaultValue(Items.TOTEM_OF_UNDYING).build();
	private final RegistryListSetting<EntityType<?>> entities = sgLists.entityTypes("Entities").build();
	private final RegistryListSetting<MobEffect> effects = sgLists.statusEffects("Effects").build();

	// Single values picked from somewhere: a registry, a position, a runtime list, other modules, a file.
	private final SettingGroup sgPicks = settings.group("Picks");
	private final RegistrySetting<Item> buildWith = sgPicks.item("Build With", Items.OBSIDIAN).description("A single item.")
		.filter(item -> item instanceof BlockItem).build();
	private final BlockPosSetting origin = sgPicks.blockPos("Origin").description("Type x y z, or press Here.").build();
	private final ChoiceSetting preset = sgPicks.choice("Preset", () -> List.of("Small", "Medium", "Large")).defaultValue("Medium")
		.description("A dropdown whose options come from code (files in a folder, saved kits…).").build();
	private final ModuleListSetting pauseWith = sgPicks.modules("Pause With").description("Pause while any of these are on.")
		.defaultValue("myriad-essentials:freecam").build();
	private final FileSetting file = sgPicks.file("File").extensions("txt", "json").description("Browse opens your system's file picker.").build();

	private final SettingGroup sgInput = settings.group("Input");
	private final KeybindSetting extraKey = sgInput.keybind("Extra Key").description("Held while the module is on: shows a message.").build();
	private final ActionSetting sayHi = sgInput.action("Say Hi", () -> info("Hi! Count is " + count.get())).build();
	// onChanged runs whenever the value changes, from the menu, a command or a config load.
	private final BoolSetting announce = sgInput.bool("Announce Changes").description("Tell you when Flavour changes.").build();

	private final SettingGroup sgCustom = settings.group("Custom");
	// A setting type this addon defines; its widget is registered in ExampleAddon.initialize.
	private final RangeSetting delay = sgCustom.add(new RangeSetting("Delay", "A min-max pair with its own two-slider widget.", new RangeSetting.Range(1, 3), 0, 10));

	private boolean keyWasDown;

	public SettingsShowcase(Tag tag) {
		super(tag, "Settings Showcase", "Every setting type, including a custom one.");
		flavour.onChanged(f -> {
			if (announce.get() && isEnabled()) info("Flavour is now " + flavour.valueString());
		});
	}

	@Override
	protected void onEnable() {
		if (greet.get()) info(message.get());
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (pauseWith.anyEnabled()) return;
		boolean down = extraKey.get().isPressed();
		if (down && !keyWasDown) info("Extra Key pressed; delay is " + delay.valueString());
		keyWasDown = down;
	}

	@Override
	public String hudInfo() {
		return flavour.valueString();
	}
}
