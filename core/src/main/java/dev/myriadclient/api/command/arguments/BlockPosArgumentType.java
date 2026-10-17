package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Three coordinates, each a whole number or {@code ~} / {@code ~5} relative to where you stand, like vanilla's.
 * Suggests your position and the block you're looking at.
 */
public class BlockPosArgumentType implements ArgumentType<BlockPos> {
	private static final SimpleCommandExceptionType INCOMPLETE = new SimpleCommandExceptionType(Component.literal("Expected three coordinates: x y z"));
	private final Supplier<BlockPos> origin;

	BlockPosArgumentType(Supplier<BlockPos> origin) {
		this.origin = origin;
	}

	public static BlockPosArgumentType blockPos() {
		return new BlockPosArgumentType(() -> {
			var player = Minecraft.getInstance().player;
			return player == null ? BlockPos.ZERO : player.blockPosition();
		});
	}

	public static BlockPos get(CommandContext<?> ctx, String name) {
		return ctx.getArgument(name, BlockPos.class);
	}

	@Override
	public BlockPos parse(StringReader reader) throws CommandSyntaxException {
		BlockPos base = origin.get();
		int x = coordinate(reader, base.getX());
		expectSpace(reader);
		int y = coordinate(reader, base.getY());
		expectSpace(reader);
		int z = coordinate(reader, base.getZ());
		return new BlockPos(x, y, z);
	}

	private static void expectSpace(StringReader reader) throws CommandSyntaxException {
		if (!reader.canRead() || reader.peek() != ' ') throw INCOMPLETE.createWithContext(reader);
		reader.skip();
	}

	private static int coordinate(StringReader reader, int base) throws CommandSyntaxException {
		if (!reader.canRead()) throw INCOMPLETE.createWithContext(reader);
		if (reader.peek() == '~') {
			reader.skip();
			if (!reader.canRead() || reader.peek() == ' ') return base;
			return base + reader.readInt();
		}
		return reader.readInt();
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		Minecraft mc = Minecraft.getInstance();
		List<String> options = new ArrayList<>(List.of("~ ~ ~"));
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos p = hit.getBlockPos();
			options.add(p.getX() + " " + p.getY() + " " + p.getZ());
		}
		return SharedSuggestionProvider.suggest(options, builder);
	}

	@Override
	public Collection<String> getExamples() {
		return List.of("~ ~ ~", "100 64 -20", "~ ~1 ~");
	}
}
