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

/** Greedy value text for the setting parsed by preceding {@code module} and {@code setting} arguments. */
public class SettingValueArgumentType implements ArgumentType<String> {
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
