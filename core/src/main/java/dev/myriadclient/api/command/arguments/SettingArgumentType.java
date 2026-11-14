package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.Setting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * A setting id of the module parsed by a preceding {@link ModuleArgumentType} argument named {@code module}: its id
 * ({@code range}), or {@code group.id} where two groups share one. Parsed as a plain word; {@link #get} looks it up.
 * Suggests the module's saved settings (not buttons).
 *
 * <pre>{@code
 * b.then(argument("module", Arguments.module()).then(argument("setting", SettingArgumentType.setting())
 *     .then(argument("value", SettingValueArgumentType.value()).executes(c -> {
 *         Setting<?> s = SettingArgumentType.get(c, "module", "setting");
 *         ...
 *     }))));
 * }</pre>
 */
public class SettingArgumentType implements ArgumentType<String> {
	private static final DynamicCommandExceptionType NO_SUCH_SETTING =
		new DynamicCommandExceptionType(name -> Component.literal("No setting named '" + name + "'"));

	private final String moduleArg;

	private SettingArgumentType(String moduleArg) {
		this.moduleArg = moduleArg;
	}

	/** A setting argument, for the module of the argument named {@code module}. */
	public static SettingArgumentType setting() {
		return new SettingArgumentType("module");
	}

	/**
	 * The setting named by the argument {@code name}, of the module parsed for the argument {@code moduleArg}.
	 *
	 * @throws CommandSyntaxException if the module has no such setting
	 */
	public static Setting<?> get(CommandContext<?> ctx, String moduleArg, String name) throws CommandSyntaxException {
		Module m = ModuleArgumentType.get(ctx, moduleArg);
		String id = ctx.getArgument(name, String.class);
		return m.settings.get(id).orElseThrow(() -> NO_SUCH_SETTING.create(id));
	}

	@Override
	public String parse(StringReader reader) {
		return ArgUtil.readWord(reader);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		try {
			Module m = ModuleArgumentType.get(context, moduleArg);
			return SharedSuggestionProvider.suggest(m.settings.all().stream().filter(Setting::isSerializable).map(m.settings::keyOf), builder);
		} catch (IllegalArgumentException e) {
			return Suggestions.empty();
		}
	}
}
