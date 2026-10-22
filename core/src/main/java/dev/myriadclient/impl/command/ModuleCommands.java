package dev.myriadclient.impl.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.command.arguments.Arguments;
import dev.myriadclient.api.command.arguments.SettingArgumentType;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.util.Texts;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

import static dev.myriadclient.api.command.arguments.ModuleArgumentType.module;
import static dev.myriadclient.api.command.arguments.SettingArgumentType.setting;
import static dev.myriadclient.api.command.arguments.SettingValueArgumentType.value;

/** Commands for modules: toggling, binding, settings, and lists of them. */
final class ModuleCommands {
	private ModuleCommands() {
	}

	static Command toggle() {
		return new Command("toggle", "Toggles a module, or turns it on or off (.toggle sprint on).", "t") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("module", module()).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					m.toggle();
					if (!m.chatFeedback.get()) info(m.name() + (m.isEnabled() ? " enabled" : " disabled"));
					return SINGLE_SUCCESS;
				}).then(argument("state", Arguments.choice(() -> List.of("on", "off"))).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					m.setEnabled(StringArgumentType.getString(c, "state").equals("on"));
					if (!m.chatFeedback.get()) info(m.name() + (m.isEnabled() ? " enabled" : " disabled"));
					return SINGLE_SUCCESS;
				})));
			}
		};
	}

	static Command bind() {
		return new Command("bind", "Binds a module to a key (e.g. .bind sprint r, .bind sprint none).") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("module", module()).then(argument("key", StringArgumentType.word()).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					KeybindSetting k = m.keybind;
					if (!k.parse(StringArgumentType.getString(c, "key"))) {
						error("Unknown key. Use names like r, ctrl+t, ctrl+alt+pgdown, shift+f6, left.shift, mouse5 or none.");
						return 0;
					}
					info(m.name() + " bound to " + k.valueString());
					return SINGLE_SUCCESS;
				})));
			}
		};
	}

	static Command set() {
		return new Command("set", "Shows or changes a module setting.", "s") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("module", module())
					.executes(c -> {
						Module m = c.getArgument("module", Module.class);
						info(Component.literal(m.name()).withStyle(ChatFormatting.BOLD));
						for (Setting<?> s : m.settings.all()) {
							if (!s.isSerializable()) continue;
							info(Component.literal("  " + m.settings.keyOf(s) + " = ").withStyle(ChatFormatting.GRAY).append(Component.literal(s.valueString()).withStyle(ChatFormatting.WHITE)));
						}
						return SINGLE_SUCCESS;
					})
					.then(argument("setting", setting())
						.executes(c -> {
							Setting<?> s = SettingArgumentType.get(c, "module", "setting");
							info(s.name() + " = " + s.valueString() + (s.description().isEmpty() ? "" : "  (" + s.description() + ")"));
							return SINGLE_SUCCESS;
						})
						.then(argument("value", value()).executes(c -> {
							Setting<?> s = SettingArgumentType.get(c, "module", "setting");
							String v = c.getArgument("value", String.class);
							if (!setOrEdit(s, v)) {
								error("Invalid value '" + v + "' for " + s.name());
								return 0;
							}
							info(s.name() + " set to " + s.valueString() + (s.isOverridden() ? " (for " + s.overriddenBy() + ")" : ""));
							return SINGLE_SUCCESS;
						}))));
			}
		};
	}

	static Command reset() {
		return new Command("reset", "Resets a module's settings to defaults.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("module", module()).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					m.settings.resetAll();
					info("Reset " + m.name());
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	static Command panic() {
		return new Command("panic", "Disables every module.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					int n = 0;
					for (Module m : Myriad.modules().enabled()) {
						m.disable();
						n++;
					}
					info("Disabled " + n + " modules");
					return SINGLE_SUCCESS;
				});
			}
		};
	}

	static Command modules() {
		return new Command("modules", "Lists modules by group; click one to toggle it.", "mods") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					for (ModuleGroup group : Myriad.modules().groups()) {
						var modules = Myriad.modules().inGroup(group);
						MutableComponent line = Component.literal(group.name() + ": ").withStyle(ChatFormatting.GRAY);
						for (int i = 0; i < modules.size(); i++) {
							Module m = modules.get(i);
							if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
							line.append(Texts.command(m.name(), "toggle " + m.id().path()).withStyle(m.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.WHITE));
						}
						info(line);
					}
					return SINGLE_SUCCESS;
				});
			}
		};
	}

	static Command binds() {
		return new Command("binds", "Lists every keybind: modules and key actions.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					int n = 0;
					for (Module m : Myriad.modules()) {
						if (!m.keybind.get().isSet()) continue;
						info(Component.literal(m.keybind.get().displayName()).withStyle(ChatFormatting.AQUA).append(Component.literal("  " + m.name()).withStyle(ChatFormatting.WHITE)));
						n++;
					}
					for (KeyAction a : Myriad.keyActions()) {
						if (!a.bind().isSet()) continue;
						info(Component.literal(a.bind().displayName()).withStyle(ChatFormatting.AQUA).append(Component.literal("  " + a.name()).withStyle(ChatFormatting.GRAY)));
						n++;
					}
					if (n == 0) info("Nothing is bound. Bind a module with .bind <module> <key>.");
					return SINGLE_SUCCESS;
				});
			}
		};
	}

	/** Like the UI: while something lays a value over this setting (a control panel that's on), the edit is its. */
	private static <T> boolean setOrEdit(Setting<T> s, String input) {
		if (!s.isOverridden()) return s.parse(input);
		T v = s.decode(input);
		if (v == null) return false;
		s.edit(v);
		return true;
	}
}
