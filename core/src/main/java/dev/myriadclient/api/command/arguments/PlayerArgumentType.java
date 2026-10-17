package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;

/** A player name; suggests names from the tab list. Any name is accepted. */
public class PlayerArgumentType implements ArgumentType<String> {
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
