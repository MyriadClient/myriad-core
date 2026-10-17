package dev.myriadclient.impl.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.AddonState;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.command.arguments.Arguments;
import dev.myriadclient.api.command.arguments.EnumArgumentType;
import dev.myriadclient.api.command.arguments.SettingArgumentType;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.util.FakePlayers;
import dev.myriadclient.api.util.Texts;
import dev.myriadclient.impl.Diagnostics;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.event.Profiler;
import dev.myriadclient.impl.hook.HookManager;
import dev.myriadclient.impl.macro.MacroManager;
import dev.myriadclient.impl.network.JoinedVersion;
import dev.myriadclient.impl.ui.ThemeManager;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.update.UpdateManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;

import static dev.myriadclient.api.command.arguments.ModuleArgumentType.module;
import static dev.myriadclient.api.command.arguments.PlayerArgumentType.player;
import static dev.myriadclient.api.command.arguments.ProfileArgumentType.profile;
import static dev.myriadclient.api.command.arguments.SettingArgumentType.setting;
import static dev.myriadclient.api.command.arguments.SettingValueArgumentType.value;

/** System commands that operate on Myriad itself (not features), so they live in core. */
public final class CoreCommands {
	private CoreCommands() {
	}

	/** "Anti-cheat: Auto (Grim detected), joined as 1.20.4 (ViaFabricPlus)" and the like. */
	public static String antiCheatStatus() {
		AntiCheat ac = Myriad.antiCheat();
		String profile = ac.profile() == AntiCheat.Profile.GRIM ? "Grim" : "Vanilla";
		String status = ac.mode() == AntiCheat.Mode.AUTO ? "Anti-cheat: Auto (" + profile + (ac.detected() == AntiCheat.Profile.GRIM ? " detected)" : ", none detected)")
			: "Anti-cheat: " + profile;
		return Minecraft.getInstance().getConnection() == null ? status : status + ", joined as " + JoinedVersion.get().describe();
	}

	public static void register(AddonContext ctx) {
		ctx.registerCommand(new Command("toggle", "Toggles a module, or turns it on or off (.toggle sprint on).", "t") {
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
		});

		ctx.registerCommand(new Command("bind", "Binds a module to a key (e.g. .bind sprint r, .bind sprint none).") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("module", module()).then(argument("key", StringArgumentType.word()).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					KeybindSetting k = m.keybind;
					if (!k.parse(StringArgumentType.getString(c, "key"))) {
						error("Unknown key. Use names like r, f6, left.shift or none.");
						return 0;
					}
					info(m.name() + " bound to " + k.valueString());
					return SINGLE_SUCCESS;
				})));
			}
		});

		ctx.registerCommand(new Command("set", "Shows or changes a module setting.", "s") {
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
		});

		ctx.registerCommand(new Command("reset", "Resets a module's settings to defaults.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("module", module()).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					m.settings.resetAll();
					info("Reset " + m.name());
					return SINGLE_SUCCESS;
				}));
			}
		});

		ctx.registerCommand(new Command("profile", "Switches, creates or deletes config profiles.", "config") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					info("Profile: " + Myriad.config().activeProfile() + "  (all: " + String.join(", ", Myriad.config().profiles()) + ")");
					return SINGLE_SUCCESS;
				});
				b.then(literal("load").then(argument("name", profile()).executes(c -> {
					Myriad.config().switchProfile(c.getArgument("name", String.class));
					info("Switched to " + Myriad.config().activeProfile());
					return SINGLE_SUCCESS;
				})));
				b.then(literal("delete").then(argument("name", profile()).executes(c -> {
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
		});

		ctx.registerCommand(new Command("friend", "Manages friends.", "f") {
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
		});

		ctx.registerCommand(new Command("addons", "Lists loaded addons.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					for (Addon a : Myriad.addons()) {
						ChatFormatting f = a.state() == AddonState.FAILED ? ChatFormatting.RED : ChatFormatting.WHITE;
						info(Component.literal(a.name() + " " + a.version()).withStyle(f)
							.append(Component.literal("  " + Myriad.modules().ownedBy(a.id()).size() + " modules").withStyle(ChatFormatting.GRAY)));
					}
					return SINGLE_SUCCESS;
				});
			}
		});

		ctx.registerCommand(new Command("update", "Updates Myriad and addons from GitHub: .update lists, .update check, .update all, .update <addon>.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				UpdateManager updates = MyriadImpl.get().updates();
				b.executes(c -> {
					boolean any = false;
					for (Addon a : Myriad.addons()) {
						UpdateManager.State s = updates.state(a.id());
						if (s == null) continue;
						any = true;
						String what = switch (s.kind()) {
							case CHECKING -> "checking…";
							case UP_TO_DATE -> "up to date";
							case AVAILABLE -> s.version() + " available";
							case DOWNLOADING -> "downloading " + s.version() + "…";
							case STAGED -> s.version() + " installs when you quit";
							case FAILED -> "update to " + s.version() + " failed: " + s.message();
							case UNKNOWN, DEV_BUILD, NO_SOURCE -> s.message();
						};
						ChatFormatting f = s.kind() == UpdateManager.Kind.AVAILABLE ? ChatFormatting.AQUA
							: s.kind() == UpdateManager.Kind.FAILED ? ChatFormatting.RED : ChatFormatting.GRAY;
						info(Component.literal(a.name() + " " + a.version() + "  ").append(Component.literal(what).withStyle(f)));
					}
					if (!any) info("Not checked yet: .update check");
					else if (!updates.updatable().isEmpty()) info("Install with .update all or .update <addon>; they go in when you quit");
					return SINGLE_SUCCESS;
				});
				b.then(literal("check").executes(c -> {
					updates.check(true);
					info("Checking GitHub for updates…");
					return SINGLE_SUCCESS;
				}));
				b.then(literal("all").executes(c -> {
					if (updates.updatable().isEmpty()) {
						info("Nothing to update");
						return SINGLE_SUCCESS;
					}
					updates.updateAll();
					info("Downloading " + updates.updatable().size() + " update(s)…");
					return SINGLE_SUCCESS;
				}));
				b.then(argument("addon", Arguments.choice(updates::updatable)).executes(c -> {
					String id = StringArgumentType.getString(c, "addon");
					if (!updates.updatable().contains(id)) {
						error("No update for " + id + ". .update lists what's available");
						return 0;
					}
					updates.update(id);
					info("Downloading " + id + "…");
					return SINGLE_SUCCESS;
				}));
			}
		});

		ctx.registerCommand(new Command("diagnostics", "Copies a report for bug reports: versions, addons, mods and enabled modules' changed settings.", "diag") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					String report = Diagnostics.build();
					mc.keyboardHandler.setClipboard(report);
					info("Copied diagnostics to the clipboard (" + report.lines().count() + " lines)");
					return SINGLE_SUCCESS;
				});
			}
		});

		ctx.registerCommand(new Command("help", "Lists commands.", "commands") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					String p = Myriad.config().commandPrefix();
					for (Command cmd : Myriad.commands()) {
						info(Component.literal(p + cmd.name()).withStyle(ChatFormatting.AQUA).append(Component.literal("  " + cmd.description()).withStyle(ChatFormatting.GRAY)));
					}
					return SINGLE_SUCCESS;
				});
			}
		});

		ctx.registerCommand(new Command("panic", "Disables every module.") {
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
		});

		ctx.registerCommand(new Command("prefix", "Changes the command prefix.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("prefix", StringArgumentType.word()).executes(c -> {
					Myriad.config().setCommandPrefix(StringArgumentType.getString(c, "prefix"));
					info("Prefix is now " + Myriad.config().commandPrefix());
					return SINGLE_SUCCESS;
				}));
			}
		});

		ctx.registerCommand(new Command("anticheat", "Shows or sets what the server checks: auto, grim or vanilla.", "ac") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					info(antiCheatStatus());
					return SINGLE_SUCCESS;
				});
				b.then(argument("mode", Arguments.enumValue(AntiCheat.Mode.class)).executes(c -> {
					Myriad.antiCheat().setMode(EnumArgumentType.get(c, "mode", AntiCheat.Mode.class));
					info(antiCheatStatus());
					return SINGLE_SUCCESS;
				}));
				b.then(literal("known").executes(c -> {
					info("Known Grim servers: " + String.join(", ", Myriad.antiCheat().knownServers()));
					return SINGLE_SUCCESS;
				}).then(literal("add").then(argument("host", StringArgumentType.word()).executes(c -> {
					String host = StringArgumentType.getString(c, "host");
					info(Myriad.antiCheat().addKnownServer(host) ? "Added " + host : host + " is already known");
					return SINGLE_SUCCESS;
				}))).then(literal("remove").then(argument("host", StringArgumentType.word())
					.suggests((c, s) -> SharedSuggestionProvider.suggest(Myriad.antiCheat().knownServers(), s)).executes(c -> {
						String host = StringArgumentType.getString(c, "host");
						info(Myriad.antiCheat().removeKnownServer(host) ? "Removed " + host : host + " wasn't known");
						return SINGLE_SUCCESS;
					}))));
			}
		});

		ctx.registerCommand(new Command("profiler", "Times every event handler: .profiler on|off, or .profiler for the heaviest right now.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				MyriadEventBus bus = (MyriadEventBus) Myriad.events();
				b.executes(c -> {
					Profiler p = bus.profiler();
					if (p == null) {
						info("Not profiling: .profiler on, or open the Profiler window");
						return SINGLE_SUCCESS;
					}
					int n = 0;
					for (Profiler.Entry e : p.snapshot()) {
						if (n++ >= 10) break;
						info(String.format(Locale.ROOT, "%s: %.2f ms/s (tick %.2f, render %.2f)", e.name(), e.totalMs(), e.tickMs(), e.renderMs()));
					}
					if (n == 0) info("Nothing measured yet");
					return SINGLE_SUCCESS;
				});
				b.then(literal("on").executes(c -> {
					if (bus.profiler() == null) bus.setProfiler(new Profiler());
					info("Profiling every handler; .profiler shows the heaviest, .profiler off stops");
					return SINGLE_SUCCESS;
				}));
				b.then(literal("off").executes(c -> {
					bus.setProfiler(null);
					info("Profiling off");
					return SINGLE_SUCCESS;
				}));
			}
		});

		ctx.registerCommand(new Command("control", "Lists control panels, or turns one on or off by name.") {
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
		});

		ctx.registerCommand(new Command("hooks", "Lists what addons replaced or hooked, turns a contribution on or off, or shows which hooks a module's calls go through.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					var hooks = MyriadImpl.get().hookManager();
					for (var s : Service.all()) {
						var used = hooks.active(s);
						if (hooks.contributions(s.id()).isEmpty()) continue;
						info(s.name() + ": " + used.map(u -> u.label() + " (" + hooks.ownerOf(u) + ")").orElse("core's own") + " for everyone");
						for (var l : hooks.live(s)) if (l.match().hasCaller()) info("  " + l.label() + " " + l.match());
					}
					var all = hooks.contributions();
					if (all.isEmpty()) info("No addon replaces a service or handles a hook.");
					for (var k : all) {
						info(Component.literal(k.id().toString()).withStyle(k.failed() ? ChatFormatting.RED : k.enabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY)
							.append(Component.literal("  " + HookManager.targetName(k) + ": " + k.label() + ", " + k.match()).withStyle(ChatFormatting.GRAY)));
					}
					return SINGLE_SUCCESS;
				});
				for (String how : List.of("on", "off")) {
					b.then(literal(how).then(argument("id", StringArgumentType.greedyString()).executes(c -> {
						String id = StringArgumentType.getString(c, "id").trim();
						var k = Myriad.hooks().contributions().stream()
							.filter(x -> x.id().toString().equalsIgnoreCase(id) || x.label().equalsIgnoreCase(id)).findFirst().orElse(null);
						if (k == null) {
							error("No hook contribution " + id + " (.hooks lists them)");
							return 0;
						}
						k.setEnabled(how.equals("on"));
						info(k.label() + (k.enabled() ? " on" : " off"));
						return SINGLE_SUCCESS;
					})));
				}
				b.then(literal("why").then(argument("module", module()).executes(c -> {
					Module m = c.getArgument("module", Module.class);
					boolean any = false;
					for (var h : Hook.all()) {
						var chain = Myriad.hooks().chain(h, m);
						if (chain.isEmpty()) continue;
						any = true;
						info(h.name() + ": " + String.join(" → ", chain.stream().map(x -> x.label()).toList()) + " → default");
					}
					var hooksImpl = MyriadImpl.get().hookManager();
					for (var s : Service.all()) {
						var by = hooksImpl.serving(s, m);
						if (by.isEmpty()) continue;
						any = true;
						info(s.name() + ": " + by.get().label() + " (" + hooksImpl.ownerOf(by.get()) + ")");
					}
					if (!any) info(m.name() + "'s calls go through no hook contributions or replacements here.");
					return SINGLE_SUCCESS;
				})));
			}
		});

		ctx.registerCommand(new Command("macro", "Lists macros, or runs, makes or deletes one by name.") {
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
		});

		ctx.registerCommand(new Command("cape", "Turns Myriad's cape on or off (seen by Myriad players near you).") {
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
		});

		ctx.registerCommand(new Command("theme", "Lists themes, or applies one by name.") {
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
		});

		ctx.registerCommand(new Command("modules", "Lists modules by category; click one to toggle it.", "mods") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					for (Category category : Myriad.categories()) {
						var modules = Myriad.modules().inCategory(category);
						if (modules.isEmpty()) continue;
						MutableComponent line = Component.literal(category.name() + ": ").withStyle(ChatFormatting.GRAY);
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
		});

		ctx.registerCommand(new Command("binds", "Lists every keybind: modules and key actions.") {
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
		});

		ctx.registerCommand(new Command("say", "Sends a chat message as-is, even one starting with the command prefix.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("message", StringArgumentType.greedyString()).executes(c -> {
					MyriadImpl.get().commandManager().sendRaw(StringArgumentType.getString(c, "message"));
					return SINGLE_SUCCESS;
				}));
			}
		});

		ctx.registerCommand(new Command("reload", "Reloads the active profile from disk (after editing config files by hand).") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					Myriad.config().reload();
					info("Reloaded profile " + Myriad.config().activeProfile());
					return SINGLE_SUCCESS;
				});
			}
		});

		ctx.registerCommand(new Command("disconnect", "Leaves the server or world.", "dc") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					// After the command finishes: disconnecting tears down the chat that's running it.
					mc.execute(() -> {
						if (mc.level != null) mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
					});
					return SINGLE_SUCCESS;
				});
			}
		});

		ctx.registerCommand(new Command("fakeplayer", "Spawns client-side dummy players for testing (only you see them).", "fp") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(literal("add").executes(c -> spawn("Dummy", 20))
					.then(argument("name", StringArgumentType.word()).executes(c -> spawn(StringArgumentType.getString(c, "name"), 20))
						.then(argument("health", FloatArgumentType.floatArg(1, 1024)).executes(c ->
							spawn(StringArgumentType.getString(c, "name"), FloatArgumentType.getFloat(c, "health"))))));
				b.then(literal("remove").then(argument("name", Arguments.suggesting(() -> FakePlayers.all().stream().map(p -> p.getGameProfile().name()).toList()))
					.executes(c -> {
						String name = StringArgumentType.getString(c, "name");
						if (FakePlayers.remove(name)) info("Removed " + name);
						else error("No fake player called " + name);
						return SINGLE_SUCCESS;
					})));
				b.then(literal("clear").executes(c -> {
					int n = FakePlayers.all().size();
					FakePlayers.clear();
					info("Removed " + n + " fake player" + (n == 1 ? "" : "s"));
					return SINGLE_SUCCESS;
				}));
				b.then(literal("list").executes(c -> {
					if (FakePlayers.all().isEmpty()) info("No fake players.");
					for (var p : FakePlayers.all()) info(Component.literal(p.getGameProfile().name() + "  ").append(Texts.coords(p.blockPosition())));
					return SINGLE_SUCCESS;
				}));
			}

			private int spawn(String name, float health) {
				if (FakePlayers.spawn(name, health, true) == null) {
					error("Join a world first.");
					return 0;
				}
				info("Spawned " + name + " with " + health + " health");
				return SINGLE_SUCCESS;
			}
		});

		ctx.registerCommand(new Command("menu", "Opens the Myriad menu.", "gui", "desktop") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				// Opening a screen from chat must wait until the chat screen has closed.
				b.executes(c -> {
					mc.schedule(() -> Myriad.ui().open());
					return SINGLE_SUCCESS;
				});
			}
		});
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
