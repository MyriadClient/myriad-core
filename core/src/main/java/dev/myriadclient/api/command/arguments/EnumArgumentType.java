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

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** One of an enum's constants, typed in any case ({@code fast}, {@code FAST}); suggests them in lower case. */
public class EnumArgumentType<E extends Enum<E>> implements ArgumentType<E> {
	private final Class<E> type;
	private final DynamicCommandExceptionType unknown;

	EnumArgumentType(Class<E> type) {
		this.type = type;
		String options = String.join(", ", Arrays.stream(type.getEnumConstants()).map(EnumArgumentType::name).toList());
		this.unknown = new DynamicCommandExceptionType(v -> Component.literal("'" + v + "' isn't one of: " + options));
	}

	public static <E extends Enum<E>> EnumArgumentType<E> of(Class<E> type) {
		return new EnumArgumentType<>(type);
	}

	public static <E extends Enum<E>> E get(CommandContext<?> ctx, String name, Class<E> type) {
		return ctx.getArgument(name, type);
	}

	private static String name(Enum<?> e) {
		return e.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public E parse(StringReader reader) throws CommandSyntaxException {
		int start = reader.getCursor();
		String raw = ArgUtil.readWord(reader);
		for (E e : type.getEnumConstants()) {
			if (e.name().equalsIgnoreCase(raw) || e.name().replace("_", "").equalsIgnoreCase(raw.replace("_", "").replace("-", ""))) return e;
		}
		reader.setCursor(start);
		throw unknown.createWithContext(reader, raw);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(Arrays.stream(type.getEnumConstants()).map(EnumArgumentType::name), builder);
	}
}
