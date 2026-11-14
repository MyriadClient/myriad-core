package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.myriadclient.api.setting.Setting;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.concurrent.CompletableFuture;

/**
 * The rest of the line, as a value for the setting named by preceding arguments called {@code module} and
 * {@code setting} (see {@link SettingArgumentType}), suggesting what that setting accepts. It's taken as text: hand it
 * to {@link Setting#parse}. Read it back with {@code ctx.getArgument(name, String.class)}.
 */
public class SettingValueArgumentType implements ArgumentType<String> {
	/** A setting value argument. Being greedy, it must be the last argument. */
	public static SettingValueArgumentType value() {
		return new SettingValueArgumentType();
	}

	@Override
	public String parse(StringReader reader) {
		String rest = reader.getRemaining();
		reader.setCursor(reader.getTotalLength());
		return rest;
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		try {
			Setting<?> s = SettingArgumentType.get(context, "module", "setting");
			return SharedSuggestionProvider.suggest(s.suggestions(), builder);
		} catch (CommandSyntaxException | IllegalArgumentException e) {
			return Suggestions.empty();
		}
	}
}
