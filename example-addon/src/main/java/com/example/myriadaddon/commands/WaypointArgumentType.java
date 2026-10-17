package com.example.myriadaddon.commands;

import com.example.myriadaddon.waypoints.Waypoint;
import com.example.myriadaddon.waypoints.WaypointStore;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * A command argument that names an existing waypoint: parsing fails with a readable error for unknown names, and chat
 * suggests the waypoints in this world as you type. Quote names with spaces: {@code .wp remove "Base 2"}.
 */
public final class WaypointArgumentType implements ArgumentType<Waypoint> {
	private static final DynamicCommandExceptionType UNKNOWN = new DynamicCommandExceptionType(name -> Component.literal("No waypoint called " + name));

	private final WaypointStore store;

	public WaypointArgumentType(WaypointStore store) {
		this.store = store;
	}

	public static Waypoint get(CommandContext<?> context, String name) {
		return context.getArgument(name, Waypoint.class);
	}

	@Override
	public Waypoint parse(StringReader reader) throws CommandSyntaxException {
		String name = reader.readString();
		return store.find(name).orElseThrow(() -> UNKNOWN.create(name));
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(store.here().stream().map(w -> w.name().contains(" ") ? "\"" + w.name() + "\"" : w.name()), builder);
	}
}
