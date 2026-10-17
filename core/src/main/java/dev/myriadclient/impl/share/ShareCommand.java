package dev.myriadclient.impl.share;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.share.ConfigShare;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.api.util.Texts;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import static dev.myriadclient.api.command.arguments.ModuleArgumentType.module;

/**
 * {@code .share}: share a module, an addon's modules, a control panel or the theme as a code, take a code in (or try
 * it as a control panel), list and delete your shares. The Share window does the same with checkboxes.
 */
public final class ShareCommand extends Command {
	public ShareCommand() {
		super("share", "Shares modules, an addon, a control panel or the theme as a code; takes a code in.");
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		b.executes(c -> {
			var mine = Myriad.shares().published();
			if (mine.isEmpty()) info("Nothing shared from here yet: .share module <module>, .share addon <id>, .share panel <name>, .share theme.");
			for (var p : mine) info(Component.literal(p.title() + "  ").append(code(p.code())));
			return SINGLE_SUCCESS;
		});
		b.then(literal("module").then(argument("module", module()).executes(c -> {
			Module m = c.getArgument("module", Module.class);
			publish(Myriad.shares().builder().title(m.name()).module(m).build());
			return SINGLE_SUCCESS;
		})));
		b.then(literal("addon").then(argument("id", StringArgumentType.word()).suggests((c, s) -> SharedSuggestionProvider.suggest(
			Myriad.addons().stream().map(a -> a.id()), s)).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			var addon = Myriad.addons().stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
			if (addon == null) {
				error("No addon " + id);
				return 0;
			}
			publish(Myriad.shares().builder().title(addon.name()).addon(id).build());
			return SINGLE_SUCCESS;
		})));
		b.then(literal("panel").then(argument("name", StringArgumentType.greedyString()).executes(c -> {
			String name = StringArgumentType.getString(c, "name");
			var panel = Myriad.controls().byName(name).orElse(null);
			if (panel == null) {
				error("No control panel called " + name);
				return 0;
			}
			publish(Myriad.shares().builder().title(panel.name()).controlPanel(panel).modules(panel.modules()).build());
			return SINGLE_SUCCESS;
		})));
		b.then(literal("theme").executes(c -> {
			publish(Myriad.shares().builder().title("Theme").theme().build());
			return SINGLE_SUCCESS;
		}));
		b.then(literal("import").then(argument("code", StringArgumentType.greedyString()).executes(c -> {
			take(StringArgumentType.getString(c, "code"), ConfigShares.Apply.REPLACE);
			return SINGLE_SUCCESS;
		})));
		b.then(literal("try").then(argument("code", StringArgumentType.greedyString()).executes(c -> {
			take(StringArgumentType.getString(c, "code"), ConfigShares.Apply.AS_CONTROL_PANEL);
			return SINGLE_SUCCESS;
		})));
		b.then(literal("delete").then(argument("code", StringArgumentType.word()).suggests((c, s) -> SharedSuggestionProvider.suggest(
			Myriad.shares().published().stream().map(ConfigShares.Published::code), s)).executes(c -> {
			String code = StringArgumentType.getString(c, "code");
			Myriad.shares().unpublish(code).whenComplete((v, t) -> onGame(() -> {
				if (t != null) error("Couldn't delete " + code + ": " + cause(t));
				else info("Deleted " + code);
			}));
			return SINGLE_SUCCESS;
		})));
	}

	private void publish(ConfigShare share) {
		info("Sharing " + share.title() + "...");
		Myriad.shares().publish(share).whenComplete((p, t) -> onGame(() -> {
			if (t != null) {
				error("Couldn't share it: " + cause(t));
				return;
			}
			info(Component.literal("Shared " + p.title() + ": ").append(code(p.code())).append(Component.literal("  "))
				.append(Texts.copy("[link]", p.url()).withStyle(ChatFormatting.GRAY)));
		}));
	}

	private void take(String code, ConfigShares.Apply how) {
		Myriad.shares().fetch(code).whenComplete((share, t) -> onGame(() -> {
			if (t != null) {
				error("Couldn't get " + code.trim() + ": " + cause(t));
				return;
			}
			var applied = Myriad.shares().apply(share, how);
			String what = String.join(", ", applied.applied());
			info((how == ConfigShares.Apply.AS_CONTROL_PANEL ? "Added " + share.title() + " as a control panel: " : "Took in " + share.title() + ": ") + what);
			if (!applied.missing().isEmpty()) info("Not installed here: " + String.join(", ", applied.missing()));
			if (how == ConfigShares.Apply.AS_CONTROL_PANEL) Myriad.controls().byName(share.title()).ifPresent(cp -> cp.setActive(true));
		}));
	}

	private static Component code(String code) {
		return Texts.copy(code, code).withStyle(ChatFormatting.AQUA);
	}

	private static void onGame(Runnable r) {
		Async.onRenderThread(r);
	}

	private static String cause(Throwable t) {
		while (t.getCause() != null && t.getMessage() != null && t.getMessage().startsWith("java.")) t = t.getCause();
		return t.getCause() != null ? t.getCause().getMessage() : t.getMessage();
	}
}
