package dev.myriadclient.api.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * A chat command (run with the prefix, {@code .} by default) built on Brigadier. Register it with
 * {@link dev.myriadclient.api.addon.AddonContext#registerCommand}; for argument types, see
 * {@link dev.myriadclient.api.command.arguments.Arguments}.
 * <p>
 * Commands run on the render thread. An executor that throws a {@code CommandSyntaxException} has its message shown in
 * chat in red, and any other exception is logged and reported in chat the same way, so a command can't crash the game.
 * The command source is the game's suggestion provider, null while not connected to a world: don't rely on it.
 *
 * <pre>{@code
 * public class Hello extends Command {
 *     public Hello() { super("hello", "Says hello."); }
 *
 *     @Override
 *     public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
 *         b.executes(ctx -> { info("Hello!"); return SINGLE_SUCCESS; });
 *     }
 * }
 * }</pre>
 */
public abstract class Command implements Identified {
	/** What an executor returns when it ran: Brigadier's {@code Command.SINGLE_SUCCESS}, 1. */
	public static final int SINGLE_SUCCESS = com.mojang.brigadier.Command.SINGLE_SUCCESS;
	/** The game client. Bound again when Myriad starts, in case this class loaded before the client existed. */
	protected static Minecraft mc = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final List<String> aliases;
	private MyriadId id;

	/**
	 * @param name        what's typed after the prefix ({@code "friend"}); one word, lower case. The command's id path is
	 *                    made from it as {@link MyriadId#toPath} does
	 * @param description one sentence for the help listing
	 * @param aliases     other names it can be typed by ({@code "f"}); none if left out
	 */
	protected Command(String name, String description, String... aliases) {
		this.name = name;
		this.description = description;
		this.aliases = List.of(aliases);
	}

	/**
	 * Add arguments and executors to the literal for {@link #name()} (and every alias). Called once for the name and once
	 * for each alias, each with a fresh builder, and again whenever a command is registered or removed, so build the same
	 * tree each time and keep state out of it.
	 */
	public abstract void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder);

	/** The name it's typed by, given to the constructor. */
	public String name() {
		return name;
	}

	/** The one-sentence description given to the constructor. */
	public String description() {
		return description;
	}

	/** Other names it can be typed by; unmodifiable, empty if none. */
	public List<String> aliases() {
		return aliases;
	}

	/**
	 * {@code <addon id>:<name as an id path>}, given when the command is registered.
	 *
	 * @throws IllegalStateException before the command is registered
	 */
	@Override
	public MyriadId id() {
		if (id == null) throw new IllegalStateException("Command '" + name + "' is not registered yet");
		return id;
	}

	/** Core's: gives the command its id in the registering addon's namespace. */
	@ApiStatus.Internal
	public void assignNamespace(String namespace) {
		id = MyriadId.of(namespace, MyriadId.toPath(name));
	}

	/**
	 * An argument node named {@code name} (the name {@code get(ctx, name)} reads it back by), parsed by {@code type}.
	 */
	protected static <T> RequiredArgumentBuilder<SharedSuggestionProvider, T> argument(String name, ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	/** A literal word node, for sub-commands ({@code literal("add")}). */
	protected static LiteralArgumentBuilder<SharedSuggestionProvider> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	/** Prints {@code message} in chat with the Myriad prefix. */
	protected void info(String message) {
		Myriad.chat(Component.literal(message));
	}

	/** Prints {@code message} in chat with the Myriad prefix, keeping its formatting. */
	protected void info(Component message) {
		Myriad.chat(message);
	}

	/** Prints {@code message} in red in chat with the Myriad prefix. */
	protected void error(String message) {
		Myriad.chat(Component.literal(message).withStyle(ChatFormatting.RED));
	}

	/** Core's: binds {@link #mc} once the game client exists. */
	@ApiStatus.Internal
	public static void bindClient(Minecraft client) {
		mc = client;
	}
}
