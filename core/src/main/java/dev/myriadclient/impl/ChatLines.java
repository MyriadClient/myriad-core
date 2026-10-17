package dev.myriadclient.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/** Chat lines sent with an id, so a newer message with the same id replaces the old one. Render thread only. */
public final class ChatLines {
	private static final Map<String, GuiMessage> byId = new HashMap<>();
	private static String pending;

	private ChatLines() {
	}

	/** Adds {@code message} to the chat, removing the last line added with {@code id}. */
	public static void add(Component message, String id) {
		ChatComponent hud = Minecraft.getInstance().gui.hud.getChat();
		GuiMessage previous = byId.remove(id);
		if (previous != null && hud.allMessages.remove(previous)) hud.refreshTrimmedMessages();
		pending = id;
		try {
			hud.addClientSystemMessage(message);
		} finally {
			pending = null;
		}
	}

	/** Called by ChatHudMixin as each line is stored. */
	public static void onLineAdded(GuiMessage line) {
		if (pending != null) byId.put(pending, line);
	}
}
