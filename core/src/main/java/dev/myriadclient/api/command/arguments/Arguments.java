package dev.myriadclient.api.command.arguments;

import net.minecraft.core.Registry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Every argument type Myriad provides, in one place. Each type also has a static {@code get(ctx, name)} to read the
 * parsed value back.
 *
 * <pre>{@code
 * b.then(argument("pos", Arguments.blockPos()).then(argument("item", Arguments.item()).executes(c -> {
 *     BlockPos pos = BlockPosArgumentType.get(c, "pos");
 *     Item item = RegistryArgumentType.getItem(c, "item");
 *     ...
 * })));
 * }</pre>
 */
public final class Arguments {
	private Arguments() {
	}

	/** {@code x y z}, with {@code ~} relative to you. */
	public static BlockPosArgumentType blockPos() {
		return BlockPosArgumentType.blockPos();
	}

	public static RegistryArgumentType<Item> item() {
		return RegistryArgumentType.item();
	}

	public static RegistryArgumentType<Block> block() {
		return RegistryArgumentType.block();
	}

	public static RegistryArgumentType<EntityType<?>> entityType() {
		return RegistryArgumentType.entityType();
	}

	/** An entry of any registry; {@code what} names it in errors ("enchantment"). Read it with {@code ctx.getArgument(name, Type.class)}. */
	public static <T> RegistryArgumentType<T> registry(Registry<T> registry, String what) {
		return RegistryArgumentType.of(registry, what);
	}

	public static <E extends Enum<E>> EnumArgumentType<E> enumValue(Class<E> type) {
		return EnumArgumentType.of(type);
	}

	/** Milliseconds, typed as {@code 10s}, {@code 5m}, {@code 20t}, {@code 1m30s}. */
	public static DurationArgumentType duration() {
		return DurationArgumentType.duration();
	}

	/** One of {@code choices}, rejecting anything else. */
	public static ChoiceArgumentType choice(Supplier<? extends Collection<String>> choices) {
		return ChoiceArgumentType.strict(choices);
	}

	/** Any name, suggesting {@code choices}. */
	public static ChoiceArgumentType suggesting(Supplier<? extends Collection<String>> choices) {
		return ChoiceArgumentType.suggesting(choices);
	}

	/** A module, by name. */
	public static ModuleArgumentType module() {
		return ModuleArgumentType.module();
	}

	/** A player in the tab list. */
	public static PlayerArgumentType player() {
		return PlayerArgumentType.player();
	}
}
