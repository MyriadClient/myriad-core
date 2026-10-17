package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public class ModuleArgumentType implements ArgumentType<Module> {
	private static final DynamicCommandExceptionType NO_SUCH_MODULE =
		new DynamicCommandExceptionType(name -> Component.literal("No module named '" + name + "'"));

	public static ModuleArgumentType module() {
		return new ModuleArgumentType();
	}

	public static Module get(CommandContext<?> ctx, String name) {
		return ctx.getArgument(name, Module.class);
	}

	@Override
	public Module parse(StringReader reader) throws CommandSyntaxException {
		String name = ArgUtil.readWord(reader);
		return Myriad.modules().byName(name).orElseThrow(() -> NO_SUCH_MODULE.create(name));
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(Myriad.modules().values().stream().map(m -> m.name().replace(" ", "")), builder);
	}
}
