package dev.myriadclient.impl.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.command.arguments.ProfileArgumentType;
import dev.myriadclient.impl.macro.MacroManager;
import dev.myriadclient.impl.ui.ThemeManager;
import dev.myriadclient.impl.ui.WindowManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.List;

import static dev.myriadclient.api.command.arguments.PlayerArgumentType.player;

/** Commands for what Myriad keeps for you: profiles, friends, the prefix, themes, the cape, control panels and macros. */
final class ConfigCommands {
	private ConfigCommands() {
	}

	static Command profile() {
		return new Command("profile", "Switches, creates or deletes config profiles.", "config") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					info("Profile: " + Myriad.config().activeProfile() + "  (all: " + String.join(", ", Myriad.config().profiles()) + ")");
					return SINGLE_SUCCESS;
				});
				b.then(literal("load").then(argument("name", ProfileArgumentType.profile()).executes(c -> {
					Myriad.config().switchProfile(c.getArgument("name", String.class));
					info("Switched to " + Myriad.config().activeProfile());
					return SINGLE_SUCCESS;
				})));
				b.then(literal("delete").then(argument("name", ProfileArgumentType.profile()).executes(c -> {
					String n = c.getArgument("name", String.class);
					if (Myriad.config().deleteProfile(n)) info("Deleted " + n);
					else error("Can't delete " + n + " (missing or active)");
					return SINGLE_SUCCESS;
				})));
				b.then(literal("save").executes(c -> {
					Myriad.config().saveNow();
					info("Saved");
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	static Command friend() {
		return new Command("friend", "Manages friends.", "f") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(literal("add").then(argument("name", player()).executes(c -> {
					String n = c.getArgument("name", String.class);
					info(Myriad.friends().add(n) ? "Added " + n : n + " is already a friend");
					return SINGLE_SUCCESS;
				})));
				b.then(literal("remove").then(argument("name", player()).executes(c -> {
					String n = c.getArgument("name", String.class);
					info(Myriad.friends().remove(n) ? "Removed " + n : n + " isn't a friend");
					return SINGLE_SUCCESS;
				})));
				b.then(literal("list").executes(c -> {
					info("Friends: " + (Myriad.friends().all().isEmpty() ? "none" : String.join(", ", Myriad.friends().all())));
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	static Command prefix() {
		return new Command("prefix", "Changes the command prefix.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("prefix", StringArgumentType.word()).executes(c -> {
					Myriad.config().setCommandPrefix(StringArgumentType.getString(c, "prefix"));
					info("Prefix is now " + Myriad.config().commandPrefix());
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	static Command reload() {
		return new Command("reload", "Reloads the active profile from disk (after editing config files by hand).") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					Myriad.config().reload();
					info("Reloaded profile " + Myriad.config().activeProfile());
					return SINGLE_SUCCESS;
				});
			}
		};
	}

	static Command theme() {
		return new Command("theme", "Lists themes, or applies one by name.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				ThemeManager themes = ((WindowManager) Myriad.ui()).themes();
				b.executes(c -> {
					StringBuilder sb = new StringBuilder("Themes: ");
					for (ThemeManager.Entry t : themes.all()) sb.append(t.id().path()).append(' ');
					info(sb.toString());
					return SINGLE_SUCCESS;
				});
				b.then(argument("name", StringArgumentType.greedyString())
					.suggests((c, s) -> SharedSuggestionProvider.suggest(themes.all().stream().map(t -> t.id().path()), s))
					.executes(c -> {
						String n = StringArgumentType.getString(c, "name");
						var found = themes.find(n);
						if (found.isEmpty()) {
							error("No theme " + n);
							return 0;
						}
						Myriad.ui().applyTheme(found.get().id());
						return SINGLE_SUCCESS;
					}));
			}
		};
	}

	static Command cape() {
		return new Command("cape", "Turns Myriad's cape on or off (seen by Myriad players near you).") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				var cape = ((WindowManager) Myriad.ui()).theme().myriadCape;
				b.executes(c -> {
					info(cape.get() ? "Wearing Myriad's cape: Myriad players near you see it. .cape off takes it off" : "Not wearing Myriad's cape. .cape on puts it on");
					return SINGLE_SUCCESS;
				});
				b.then(literal("on").executes(c -> {
					cape.set(true);
					Myriad.config().markDirty();
					info("Wearing Myriad's cape");
					return SINGLE_SUCCESS;
				}));
				b.then(literal("off").executes(c -> {
					cape.set(false);
					Myriad.config().markDirty();
					info("Myriad's cape is off: players near you see your own cape again");
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	static Command control() {
		return new Command("control", "Lists control panels, or turns one on or off by name.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					var all = Myriad.controls().all();
					if (all.isEmpty()) info("No control panels: make one in the launcher (New control panel) or the Control Panels window.");
					for (var cp : all) {
						info(Component.literal(cp.name()).withStyle(cp.isActive() ? ChatFormatting.GREEN : ChatFormatting.WHITE)
							.append(Component.literal("  " + cp.modules().size() + " modules" + (cp.keybind().isSet() ? ", " + cp.keybind().displayName() : "")).withStyle(ChatFormatting.GRAY)));
					}
					return SINGLE_SUCCESS;
				});
				for (String how : List.of("on", "off", "toggle")) {
					b.then(literal(how).then(argument("name", StringArgumentType.greedyString()).executes(c -> {
						String name = StringArgumentType.getString(c, "name");
						var cp = Myriad.controls().byName(name).orElse(null);
						if (cp == null) {
							error("No control panel called " + name);
							return 0;
						}
						if (how.equals("toggle")) cp.toggle();
						else cp.setActive(how.equals("on"));
						return SINGLE_SUCCESS;
					})));
				}
			}
		};
	}

	static Command macro() {
		return new Command("macro", "Lists macros, or runs, makes or deletes one by name.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					var all = MacroManager.all();
					if (all.isEmpty()) info("No macros: .macro new <name>, or New macro in the launcher.");
					for (var m : all) info(m.name() + (m.keybind.get().isSet() ? "  " + m.keybind.get().displayName() : ""));
					return SINGLE_SUCCESS;
				});
				b.then(literal("run").then(argument("name", StringArgumentType.greedyString()).executes(c -> {
					String name = StringArgumentType.getString(c, "name");
					var m = MacroManager.byName(name).orElse(null);
					if (m == null) {
						error("No macro called " + name);
						return 0;
					}
					m.toggle();
					return SINGLE_SUCCESS;
				})));
				b.then(literal("new").then(argument("name", StringArgumentType.greedyString()).executes(c -> {
					var m = MacroManager.create(StringArgumentType.getString(c, "name"));
					info("Macro " + m.name() + ": give it commands in its settings (.set \"" + m.name() + "\" commands …) or the menu.");
					return SINGLE_SUCCESS;
				})));
				b.then(literal("delete").then(argument("name", StringArgumentType.greedyString()).executes(c -> {
					String name = StringArgumentType.getString(c, "name");
					var m = MacroManager.byName(name).orElse(null);
					if (m == null) {
						error("No macro called " + name);
						return 0;
					}
					MacroManager.delete(m);
					info("Deleted macro " + m.name());
					return SINGLE_SUCCESS;
				})));
			}
		};
	}
}
