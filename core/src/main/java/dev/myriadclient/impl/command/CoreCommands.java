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
import dev.myriadclient.api.command.arguments.ChoiceArgumentType;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.util.Baritone;
import dev.myriadclient.api.util.FakePlayers;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.api.util.Texts;
import dev.myriadclient.impl.Diagnostics;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.dev.Bench;
import dev.myriadclient.impl.dev.PacketLog;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.render.CoreRenderSettings;
import dev.myriadclient.impl.render.GpuTimings;
import dev.myriadclient.impl.event.Profiler;
import dev.myriadclient.impl.hook.HookManager;
import dev.myriadclient.impl.network.JoinedVersion;
import dev.myriadclient.impl.update.UpdateManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.Locale;

import static dev.myriadclient.api.command.arguments.ModuleArgumentType.module;
import static dev.myriadclient.api.command.arguments.PlayerArgumentType.player;
import static dev.myriadclient.api.command.arguments.ProfileArgumentType.profile;

/** System commands that operate on Myriad itself (not features), so they live in core. */
public final class CoreCommands {
	private CoreCommands() {
	}

	/** "Anti-cheat: Auto (Grim detected), joined as 1.20.4 (ViaFabricPlus)" and the like. */
	public static String antiCheatStatus() {
		AntiCheat ac = Myriad.antiCheat();
		AntiCheat.Profile detected = ac.detected();
		String status = ac.choice() == null
			? "Anti-cheat: Auto (" + (detected.equals(AntiCheat.Profile.VANILLA) ? "Vanilla, none detected)" : detected.name() + " detected)")
			: "Anti-cheat: " + ac.profile().name();
		return Minecraft.getInstance().getConnection() == null ? status : status + ", joined as " + JoinedVersion.get().describe();
	}

	/** "auto", then each profile: Myriad's by path ("grim"), addons' by id. */
	private static List<String> antiCheatChoices() {
		List<String> choices = new ArrayList<>(List.of("auto"));
		for (AntiCheat.Profile p : Myriad.antiCheat().profiles()) {
			choices.add(p.id().namespace().equals("myriad") ? p.id().path() : p.id().toString());
		}
		return choices;
	}

	public static void register(AddonContext ctx) {
		// In this order: .help lists them as registered.
		for (Command c : List.of(
			ModuleCommands.toggle(), ModuleCommands.bind(), ModuleCommands.set(), ModuleCommands.reset(),
			ConfigCommands.profile(), ConfigCommands.friend(), addons(), update(), diagnostics(), help(),
			ModuleCommands.panic(), baritone(), ConfigCommands.prefix(), antiCheat(), profiler(),
			ConfigCommands.control(), hooks(), ConfigCommands.macro(), ConfigCommands.cape(), ConfigCommands.theme(),
			JobCommands.jobs(), JobCommands.job(), packets(),
			ModuleCommands.modules(), ModuleCommands.binds(), say(), ConfigCommands.reload(), disconnect(),
			fakePlayer(), menu(), render()
		)) ctx.registerCommand(c);
		if (Boolean.getBoolean("myriad.dev")) ctx.registerCommand(bench());
	}

	private static Command render() {
		return new Command("render", "Shows or switches how modules draw: .render shader, .render box.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					info("Render mode: " + EnumSetting.displayName(CoreRenderSettings.mode()));
					return SINGLE_SUCCESS;
				});
				for (RenderMode m : RenderMode.values()) {
					b.then(literal(m.name().toLowerCase(Locale.ROOT)).executes(c -> {
						CoreRenderSettings.set(m);
						info("Render mode: " + EnumSetting.displayName(m));
						return SINGLE_SUCCESS;
					}));
				}
			}
		};
	}

	private static Command addons() {
		return new Command("addons", "Lists loaded addons.") {
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
		};
	}

	private static Command update() {
		return new Command("update", "Updates Myriad and addons from GitHub: .update lists, .update check, .update all, .update <addon>.") {
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
		};
	}

	private static Command diagnostics() {
		return new Command("diagnostics", "Copies a report for bug reports: versions, addons, mods and enabled modules' changed settings.", "diag") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					String report = Diagnostics.build();
					mc.keyboardHandler.setClipboard(report);
					info("Copied diagnostics to the clipboard (" + report.lines().count() + " lines)");
					return SINGLE_SUCCESS;
				});
			}
		};
	}

	private static Command help() {
		return new Command("help", "Lists commands.", "commands") {
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
		};
	}

	private static Command baritone() {
		return new Command("baritone", "Runs a Baritone command, as its # prefix does: .b goto 0 64 0, .b mine diamond_ore, .b stop.", "b") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> baritone("help"));
				b.then(argument("command", StringArgumentType.greedyString()).executes(c -> baritone(StringArgumentType.getString(c, "command"))));
			}

			/** Baritone answers in chat itself; this only says when it can't. */
			private int baritone(String command) {
				if (!Baritone.isAvailable()) error("Baritone isn't installed (or not ready until you're in a world).");
				else if (!Baritone.command(command)) error("Baritone didn't take \"" + command + "\".");
				return SINGLE_SUCCESS;
			}
		};
	}

	private static Command antiCheat() {
		return new Command("anticheat", "Shows or sets what the server checks: auto, grim, vanilla, ncp or an addon's profile.", "ac") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					info(antiCheatStatus());
					return SINGLE_SUCCESS;
				});
				b.then(argument("profile", ChoiceArgumentType.strict(CoreCommands::antiCheatChoices)).executes(c -> {
					String choice = ChoiceArgumentType.get(c, "profile");
					Myriad.antiCheat().choose(choice.equals("auto") ? null : Myriad.antiCheat().profiles().get(MyriadId.parse(choice, "myriad")).orElseThrow());
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
		};
	}

	private static Command profiler() {
		return new Command("profiler", "Times every event handler: .profiler on|off, or .profiler for the heaviest right now.") {
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
						String blocks = e.blockReads() > 0 ? String.format(Locale.ROOT, ", %,d blocks/s", e.blockReads()) : "";
						info(String.format(Locale.ROOT, "%s: %.2f ms/s (tick %.2f, render %.2f%s)", e.name(), e.totalMs(), e.tickMs(), e.renderMs(), blocks));
					}
					if (n == 0) info("Nothing measured yet");
					for (Profiler.Entry e : p.snapshot()) if (e.hint() != null) info(e.name() + ": " + e.hint());
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
		};
	}

	private static Command packets() {
		return new Command("packets", "Logs every packet both ways to a file: .packets log on [all | words to keep], .packets log off.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(literal("log").executes(c -> {
					info(PacketLog.status());
					return SINGLE_SUCCESS;
				}).then(literal("on").executes(c -> {
					info(PacketLog.start(List.of()));
					return SINGLE_SUCCESS;
				}).then(argument("filter", StringArgumentType.greedyString()).executes(c -> {
					info(PacketLog.start(List.of(StringArgumentType.getString(c, "filter").trim().split("\\s+"))));
					return SINGLE_SUCCESS;
				}))).then(literal("off").executes(c -> {
					info(PacketLog.stop());
					return SINGLE_SUCCESS;
				})));
			}
		};
	}

	/**
	 * .bench render: uncapped while it measures (no frame limit, no vsync, no slowing down when you don't touch the
	 * keys), so what a frame costs shows in its time, all put back after.
	 */
	private static void benchRender(boolean compare, Consumer<String> chat) {
		Logger log = LoggerFactory.getLogger("Myriad/Bench");
		var options = Minecraft.getInstance().options;
		int limit = options.framerateLimit().get();
		boolean vsync = options.enableVsync().get();
		InactivityFpsLimit inactivity = options.inactivityFpsLimit().get();
		options.framerateLimit().set(260);
		options.enableVsync().set(false);
		options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		int[] reports = {compare ? 2 : 1};
		Consumer<String> report = r -> {
			chat.accept(r);
			log.info("{}", r);
			if (--reports[0] > 0) return;
			options.framerateLimit().set(limit);
			options.enableVsync().set(vsync);
			options.inactivityFpsLimit().set(inactivity);
		};
		if (compare) GpuTimings.compare(600, report);
		else GpuTimings.measure(600, report);
	}

	/** Dev runs only: what core's hot paths cost here, for the CHANGELOG's numbers. */
	private static Command bench() {
		return new Command("bench", "Measures core's hot paths on this machine (the game stands still for a few seconds).") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					if (Minecraft.getInstance().player == null) {
						error("Join a world first");
						return SINGLE_SUCCESS;
					}
					Logger log = LoggerFactory.getLogger("Myriad/Bench");
					Bench.run(r -> {
						info(r.toString());
						log.info("{}", r);
					});
					return SINGLE_SUCCESS;
				});
				// What highlights cost the GPU here, for the scene as it is: run it once per look to compare.
				b.then(literal("render").then(literal("old").executes(c -> {
					// Draws highlights the way they were before, to compare screenshots: again to put it back.
					info(GpuTimings.toggleLegacy() ? "Highlights drawn the old way" : "Highlights drawn as they are now");
					return SINGLE_SUCCESS;
				})).then(literal("compare").executes(c -> {
					info("Measuring 600 frames the way highlights were drawn before, then 600 as they are now...");
					benchRender(true, r -> info(r));
					return SINGLE_SUCCESS;
				})).executes(c -> {
					info("Measuring 600 frames...");
					benchRender(false, r -> info(r));
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	private static Command hooks() {
		return new Command("hooks", "Lists what addons replaced or hooked, turns a contribution on or off, or shows which hooks a module's calls go through.") {
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
		};
	}

	private static Command say() {
		return new Command("say", "Sends a chat message as-is, even one starting with the command prefix.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("message", StringArgumentType.greedyString()).executes(c -> {
					MyriadImpl.get().commandManager().sendRaw(StringArgumentType.getString(c, "message"));
					return SINGLE_SUCCESS;
				}));
			}
		};
	}

	private static Command disconnect() {
		return new Command("disconnect", "Leaves the server or world.", "dc") {
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
		};
	}

	private static Command fakePlayer() {
		return new Command("fakeplayer", "Spawns client-side dummy players for testing (only you see them).", "fp") {
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
		};
	}

	private static Command menu() {
		return new Command("menu", "Opens the Myriad menu.", "gui", "desktop") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				// Opening a screen from chat must wait until the chat screen has closed.
				b.executes(c -> {
					mc.schedule(() -> Myriad.ui().open());
					return SINGLE_SUCCESS;
				});
			}
		};
	}
}
