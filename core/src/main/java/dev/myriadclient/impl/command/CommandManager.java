package dev.myriadclient.impl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ChatSendEvent;
import dev.myriadclient.api.registry.Registry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a Brigadier dispatcher from the command registry and runs prefixed chat messages through it. */
public final class CommandManager {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Commands");
	private final Minecraft mc = Minecraft.getInstance();
	private final Registry<Command> commands;
	private CommandDispatcher<SharedSuggestionProvider> dispatcher;

	public CommandManager(Registry<Command> commands) {
		this.commands = commands;
		commands.onAdd(c -> dispatcher = null);
		commands.onRemove(c -> dispatcher = null);
	}

	public CommandDispatcher<SharedSuggestionProvider> dispatcher() {
		if (dispatcher == null) {
			CommandDispatcher<SharedSuggestionProvider> d = new CommandDispatcher<>();
			for (Command c : commands) {
				try {
					register(d, c, c.name());
					for (String alias : c.aliases()) register(d, c, alias);
				} catch (RuntimeException e) {
					LOG.error("Command {} failed to build", c.id(), e);
				}
			}
			dispatcher = d;
		}
		return dispatcher;
	}

	private static void register(CommandDispatcher<SharedSuggestionProvider> d, Command c, String name) {
		LiteralArgumentBuilder<SharedSuggestionProvider> b = LiteralArgumentBuilder.literal(name);
		c.build(b);
		d.register(b);
	}

	public SharedSuggestionProvider source() {
		return mc.getConnection() != null ? mc.getConnection().getSuggestionsProvider() : null;
	}

	public ParseResults<SharedSuggestionProvider> parse(String input) {
		return dispatcher().parse(input, source());
	}

	/** Runs a command line (without prefix), reporting errors in chat. */
	public void execute(String input) {
		try {
			dispatcher().execute(input, source());
		} catch (CommandSyntaxException e) {
			Myriad.chat(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
		} catch (Exception e) {
			LOG.error("Command '{}' failed", input, e);
			Myriad.chat(Component.literal("Command failed: " + e).withStyle(ChatFormatting.RED));
		}
	}

	private boolean sendingRaw;

	/** Sends {@code message} to the server as chat, even if it starts with the command prefix. */
	public void sendRaw(String message) {
		if (mc.getConnection() == null) return;
		sendingRaw = true;
		try {
			mc.getConnection().sendChat(message);
		} finally {
			sendingRaw = false;
		}
	}

	@Subscribe(priority = Priority.HIGHEST)
	private void onChat(ChatSendEvent e) {
		String prefix = Myriad.config().commandPrefix();
		if (sendingRaw || !e.message().startsWith(prefix)) return;
		e.cancel();
		mc.gui.hud.getChat().addRecentChat(e.message());
		execute(e.message().substring(prefix.length()));
	}
}
