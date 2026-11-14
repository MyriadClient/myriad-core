package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A name from a list you supply at runtime (waypoints, saved kits, schematic files), with suggestions. Names with
 * spaces can be quoted. {@link #strict} rejects anything not in the list; otherwise any name is accepted, which suits
 * "create" commands that should still suggest existing names.
 */
public class ChoiceArgumentType implements ArgumentType<String> {
	private static final DynamicCommandExceptionType UNKNOWN = new DynamicCommandExceptionType(v -> Component.literal("Unknown '" + v + "'"));
	private final Supplier<? extends Collection<String>> choices;
	private final boolean strict;

	ChoiceArgumentType(Supplier<? extends Collection<String>> choices, boolean strict) {
		this.choices = choices;
		this.strict = strict;
	}

	/**
	 * One of {@code choices}, matched ignoring case and given back as the list spells it; anything else is an error.
	 * {@code choices} is asked at each parse and suggestion, so it can change.
	 */
	public static ChoiceArgumentType strict(Supplier<? extends Collection<String>> choices) {
		return new ChoiceArgumentType(choices, true);
	}

	/** Any name, given back as typed, with {@code choices} as suggestions. */
	public static ChoiceArgumentType suggesting(Supplier<? extends Collection<String>> choices) {
		return new ChoiceArgumentType(choices, false);
	}

	/**
	 * The name parsed for the argument {@code name}, without quotes. Brigadier throws {@link IllegalArgumentException}
	 * if the command has no such argument.
	 */
	public static String get(CommandContext<?> ctx, String name) {
		return ctx.getArgument(name, String.class);
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		int start = reader.getCursor();
		String value = reader.canRead() && StringReader.isQuotedStringStart(reader.peek()) ? reader.readQuotedString() : ArgUtil.readWord(reader);
		if (strict) {
			for (String c : choices.get()) if (c.equalsIgnoreCase(value)) return c;
			reader.setCursor(start);
			throw UNKNOWN.createWithContext(reader, value);
		}
		return value;
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(choices.get().stream().map(c -> c.contains(" ") ? "\"" + c + "\"" : c), builder);
	}
}
