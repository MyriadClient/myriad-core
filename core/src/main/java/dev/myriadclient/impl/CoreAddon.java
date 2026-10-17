package dev.myriadclient.impl;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.MyriadAddon;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.BlockPosSetting;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntListSetting;
import dev.myriadclient.api.setting.FileSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.ModuleListSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.setting.StringListSetting;
import dev.myriadclient.api.setting.StringSetting;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.ColorPicker;
import dev.myriadclient.api.ui.widget.Dropdown;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.KeybindButton;
import dev.myriadclient.api.ui.widget.RegistryPicker;
import dev.myriadclient.api.ui.widget.Slider;
import dev.myriadclient.api.ui.widget.StringListEditor;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.command.CoreCommands;
import dev.myriadclient.impl.ui.CoreThemes;
import dev.myriadclient.impl.ui.FilePicker;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.ui.bar.CoreBarWidgets;
import dev.myriadclient.impl.ui.layout.ColumnsLayout;
import dev.myriadclient.impl.ui.layout.DwindleLayout;
import dev.myriadclient.impl.ui.panels.CorePanels;

import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Myriad core registers its own pieces through the same addon API everyone else uses: categories, layouts, themes,
 * panels, bar widgets, commands, key actions and the setting editors. It registers no modules.
 */
public final class CoreAddon implements MyriadAddon {
	public static final MyriadId OPEN_MENU = MyriadId.of("myriad", "open_menu");

	@Override
	public void registerCategories(AddonContext ctx) {
		for (Category c : Categories.DEFAULTS) ctx.registerCategory(c);
		ctx.registerCategory(dev.myriadclient.impl.macro.MacroManager.CATEGORY);
	}

	@Override
	public void initialize(AddonContext ctx) {
		WindowManager wm = (WindowManager) Myriad.ui();
		registerSettingWidgets(ctx.settingWidgets());
		ctx.registerLayout(new DwindleLayout());
		ctx.registerLayout(new ColumnsLayout());
		CoreThemes.register(ctx);
		CorePanels.register(ctx, wm);
		CoreBarWidgets.register(ctx, wm);
		CoreCommands.register(ctx);
		// Before the profile loads, so the macros' settings and binds load with it.
		dev.myriadclient.impl.macro.MacroManager.load(ctx.storage());
		// Unbound: mod+1..9 opens the menu on a workspace. For a single key (Right Shift, as other clients use), bind it.
		ctx.registerKeyAction("Open Menu", Keybind.NONE, () -> Myriad.ui().open());
	}

	private static <E extends Enum<E>> Widget enumDropdown(EnumSetting<E> s) {
		return new Dropdown<>(() -> List.of(s.values()), s::get, s::set, EnumSetting::displayName);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void registerSettingWidgets(SettingWidgets w) {
		w.register(BoolSetting.class, s -> new Toggle(s::get, s::set), false);
		w.register(IntSetting.class, s -> new Slider(s::get, v -> s.set((int) Math.round(v)), s.sliderMin(), s.sliderMax(), 0), false);
		w.register(DoubleSetting.class, s -> new Slider(s::get, s::set, s.sliderMin(), s.sliderMax(), s.decimals()), false);
		w.register(EnumSetting.class, s -> enumDropdown((EnumSetting) s), false);
		w.register(StringSetting.class, s -> new TextField(s::get).onSubmit(s::set), false);
		w.register(StringListSetting.class, StringListEditor::new, true);
		w.register(IntListSetting.class, s -> new TextField(s::valueString).onSubmit(s::parse).placeholder("1, 2, 3"), false);
		w.register(EnumSetSetting.class, s -> new RegistryPicker<>(enumSetSource((EnumSetSetting) s)), true);
		w.register(ColorSetting.class, s -> new ColorPicker(s::get, s::set), false);
		w.register(KeybindSetting.class, s -> new KeybindButton(s::get, s::set), false);
		w.register(ActionSetting.class, s -> new Button("Run", s::run), false);
		w.register(RegistryListSetting.class, s -> new RegistryPicker<>(s), true);
		w.register(RegistrySetting.class, s -> RegistryPicker.single(s), true);
		w.register(ModuleListSetting.class, CoreAddon::modulePicker, true);
		w.register(ChoiceSetting.class, s -> new Dropdown<>(s::options, s::get, s::set, o -> o), false);
		w.register(BlockPosSetting.class, s -> {
			HBox row = new HBox(3);
			row.addWeighted(new TextField(s::valueString).onSubmit(s::parse), 1);
			row.addFixed(new Button("Here", () -> {
				var player = Minecraft.getInstance().player;
				if (player != null) s.set(player.blockPosition());
			}), 32).tooltip("Where you're standing");
			return row;
		}, false);
		w.register(FileSetting.class, s -> {
			HBox row = new HBox(3);
			row.addWeighted(new TextField(s::get).onSubmit(s::set).placeholder("No file"), 1);
			row.addFixed(new Button("Browse", () -> FilePicker.open(s)), 40);
			return row;
		}, false);
	}

	private static <E extends Enum<E>> RegistryPicker.Source<E> enumSetSource(EnumSetSetting<E> s) {
		return new RegistryPicker.Source<>() {
			@Override
			public Iterable<E> all() {
				return List.of(s.values());
			}

			@Override
			public boolean accepts(E e) {
				return true;
			}

			@Override
			public boolean contains(E e) {
				return s.contains(e);
			}

			@Override
			public void toggle(E e) {
				s.toggle(e);
			}

			@Override
			public int size() {
				return s.get().size();
			}

			@Override
			public String label() {
				return s.get().isEmpty() ? "none" : s.get().size() + " selected";
			}

			@Override
			public String id(E e) {
				return e.name();
			}

			@Override
			public String name(E e) {
				return EnumSetting.displayName(e);
			}
		};
	}

	/** Module lists reuse the searchable picker over every registered module. */
	private static Widget modulePicker(ModuleListSetting s) {
		return new RegistryPicker<>(new RegistryPicker.Source<Module>() {
			@Override
			public Iterable<Module> all() {
				return Myriad.modules().values();
			}

			@Override
			public boolean accepts(Module m) {
				return true;
			}

			@Override
			public boolean contains(Module m) {
				return s.contains(m);
			}

			@Override
			public void toggle(Module m) {
				s.toggle(m);
			}

			@Override
			public int size() {
				return s.get().size();
			}

			@Override
			public String label() {
				return s.get().size() + " selected";
			}

			@Override
			public String id(Module m) {
				return m.id().toString();
			}

			@Override
			public String name(Module m) {
				return m.name();
			}
		});
	}
}
