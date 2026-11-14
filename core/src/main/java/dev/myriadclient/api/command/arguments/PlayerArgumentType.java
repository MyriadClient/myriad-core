package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A player name, as one word; suggests names from the tab list. Any name is accepted, online or not; read it back with
 * {@code ctx.getArgument(name, String.class)}.
 */
public class PlayerArgumentType implements ArgumentType<String> {
	/** A player name argument. */
	public static PlayerArgumentType player() {
		return new PlayerArgumentType();
	}

	@Override
	public String parse(StringReader reader) {
		return ArgUtil.readWord(reader);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		var handler = Minecraft.getInstance().getConnection();
		List<String> names = handler == null ? List.of()
			: handler.getOnlinePlayers().stream().map(PlayerInfo::getProfile).map(p -> p.name()).toList();
		return SharedSuggestionProvider.suggest(names, builder);
	}
}
