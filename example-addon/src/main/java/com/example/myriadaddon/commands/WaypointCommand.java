package com.example.myriadaddon.commands;

import com.example.myriadaddon.waypoints.Waypoint;
import com.example.myriadaddon.waypoints.WaypointStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.command.arguments.Arguments;
import dev.myriadclient.api.command.arguments.BlockPosArgumentType;
import dev.myriadclient.api.util.Format;
import dev.myriadclient.api.util.Texts;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code .waypoint} (or {@code .wp}): list, add, remove, hide and show waypoints. Commands are plain Brigadier, so
 * sub-commands, typed arguments and suggestions work as they do in vanilla.
 *
 * <pre>
 * .wp                       list waypoints in this world
 * .wp add Home              at your feet
 * .wp add "Iron Farm" 120 64 -300   (or ~ ~ ~ for relative coordinates)
 * .wp remove Home
 * .wp hide Home / .wp show Home
 * .wp clear
 * </pre>
 */
public final class WaypointCommand extends Command {
	private final WaypointStore store;

	public WaypointCommand(WaypointStore store) {
		super("waypoint", "Lists, adds and removes waypoints.", "wp");
		this.store = store;
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		b.executes(c -> list());
		b.then(literal("list").executes(c -> list()));

		// "pos" accepts "x y z" or "~ ~ ~" relative to you, and suggests where you're standing and looking.
		b.then(literal("add").then(argument("name", StringArgumentType.string())
			.executes(c -> {
				if (mc.player == null) return fail("Join a world first.");
				return add(StringArgumentType.getString(c, "name"), mc.player.blockPosition());
			})
			.then(argument("pos", Arguments.blockPos())
				.executes(c -> add(StringArgumentType.getString(c, "name"), BlockPosArgumentType.get(c, "pos"))))));

		b.then(literal("remove").then(argument("waypoint", new WaypointArgumentType(store)).executes(c -> {
			Waypoint w = WaypointArgumentType.get(c, "waypoint");
			store.remove(w.name());
			info("Removed " + w.name() + ".");
			return SINGLE_SUCCESS;
		})));

		b.then(literal("hide").then(argument("waypoint", new WaypointArgumentType(store)).executes(c -> setVisible(WaypointArgumentType.get(c, "waypoint"), false))));
		b.then(literal("show").then(argument("waypoint", new WaypointArgumentType(store)).executes(c -> setVisible(WaypointArgumentType.get(c, "waypoint"), true))));

		b.then(literal("clear").executes(c -> {
			int n = store.clear();
			info(n == 0 ? "No waypoints to clear." : "Removed " + n + " waypoint" + (n == 1 ? "" : "s") + ".");
			return SINGLE_SUCCESS;
		}));
	}

	private int list() {
		if (WaypointStore.worldKey() == null) return fail("Join a world first.");
		List<Waypoint> all = store.here();
		if (all.isEmpty()) {
			info("No waypoints here yet. Add one with .waypoint add <name>.");
			return SINGLE_SUCCESS;
		}
		info(all.size() + " waypoint" + (all.size() == 1 ? "" : "s") + ":");
		String dim = WaypointStore.currentDimension();
		for (Waypoint w : all) {
			// Texts builds clickable chat: coordinates copy on click, and [remove] runs the command.
			MutableComponent line = Component.literal(" " + w.name() + "  ").withStyle(w.visible() ? ChatFormatting.WHITE : ChatFormatting.GRAY).append(Texts.coords(w.pos()));
			if (!w.dimension().equals(dim)) line.append(Component.literal("  " + w.dimension().replace("minecraft:", "")).withStyle(ChatFormatting.DARK_GRAY));
			else if (mc.player != null) line.append(Component.literal("  " + Format.distance(w.center().distanceTo(mc.player.position()))).withStyle(ChatFormatting.AQUA));
			info(line.append("  ").append(Texts.command("[remove]", "waypoint remove " + quote(w.name()))));
		}
		return SINGLE_SUCCESS;
	}

	private int add(String name, BlockPos pos) {
		if (!store.put(name, pos)) return fail("Join a world first.");
		info("Added " + name + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
		return SINGLE_SUCCESS;
	}

	private int setVisible(Waypoint w, boolean visible) {
		store.setVisible(w.name(), visible);
		info((visible ? "Showing " : "Hiding ") + w.name() + ".");
		return SINGLE_SUCCESS;
	}

	private static String quote(String name) {
		return name.contains(" ") ? "\"" + name + "\"" : name;
	}

	private int fail(String message) {
		error(message);
		return 0;
	}
}
