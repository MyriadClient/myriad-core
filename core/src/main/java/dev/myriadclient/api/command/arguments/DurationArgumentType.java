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
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A length of time, as milliseconds: {@code 500ms}, {@code 20t} (ticks of 50 ms), {@code 10s}, {@code 5m}, {@code 2h},
 * {@code 1d}, or combined ({@code 1m30s}). Fractions work ({@code 1.5s}), a bare number means seconds, and case doesn't
 * matter.
 */
public class DurationArgumentType implements ArgumentType<Long> {
	private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|t|s|m|h|d)?");
	private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(v -> Component.literal("'" + v + "' isn't a duration (try 10s, 5m, 20t)"));

	/** A duration argument. */
	public static DurationArgumentType duration() {
		return new DurationArgumentType();
	}

	/**
	 * The duration parsed for the argument {@code name}, in milliseconds. Brigadier throws {@link
	 * IllegalArgumentException} if the command has no such argument.
	 */
	public static long get(CommandContext<?> ctx, String name) {
		return ctx.getArgument(name, Long.class);
	}

	@Override
	public Long parse(StringReader reader) throws CommandSyntaxException {
		int start = reader.getCursor();
		String raw = ArgUtil.readWord(reader);
		Long ms = parse(raw);
		if (ms == null) {
			reader.setCursor(start);
			throw INVALID.createWithContext(reader, raw);
		}
		return ms;
	}

	/** Parses a duration string to milliseconds, rounded, or null if it isn't one (or is empty). */
	public static @Nullable Long parse(String raw) {
		String s = raw.toLowerCase();
		Matcher m = PART.matcher(s);
		int pos = 0;
		double total = 0;
		while (pos < s.length()) {
			if (!m.find(pos) || m.start() != pos) return null;
			double n = Double.parseDouble(m.group(1));
			String unit = m.group(2) == null ? "s" : m.group(2);
			total += n * switch (unit) {
				case "ms" -> 1;
				case "t" -> 50;
				case "m" -> 60_000;
				case "h" -> 3_600_000;
				case "d" -> 86_400_000;
				default -> 1000;
			};
			pos = m.end();
		}
		return pos == 0 ? null : Math.round(total);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(List.of("10s", "1m", "5m", "20t"), builder);
	}
}
