package dev.myriadclient.api.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out who a chat line is from, and whether it's a whisper, across vanilla and the common plugin formats
 * (Essentials, 2b2t-style, rank prefixes). Formats a server invents itself can't all be known, so names outside
 * vanilla's own formats only count when that player is in the tab list; a line like "Server: restarting" stays
 * {@link Kind#OTHER}.
 *
 * <pre>{@code
 * @Subscribe
 * private void onChat(ChatReceiveEvent e) {
 *     ChatMessages.Parsed m = e.parsed();
 *     if (m.kind() == ChatMessages.Kind.WHISPER_IN) reply(m.player(), "I'm away");
 * }
 * }</pre>
 */
public final class ChatMessages {
	public enum Kind {
		/** Said in public chat by {@link Parsed#player()} (possibly you). */
		PUBLIC,
		/** Whispered to you by {@link Parsed#player()}. */
		WHISPER_IN,
		/** You whispered to {@link Parsed#player()}. */
		WHISPER_OUT,
		/** Anything else: server messages, system lines, Myriad's own messages. */
		OTHER
	}

	/** @param player the other side of the message (the sender, or who you whispered to); null for {@link Kind#OTHER} */
	public record Parsed(Kind kind, String player, String body) {
		public static final Parsed NONE = new Parsed(Kind.OTHER, null, "");

		public boolean isWhisper() {
			return kind == Kind.WHISPER_IN || kind == Kind.WHISPER_OUT;
		}
	}

	private record Format(Kind kind, Pattern pattern, boolean vanilla) {
		Format(Kind kind, String regex, boolean vanilla) {
			this(kind, Pattern.compile(regex), vanilla);
		}
	}

	private static final String NAME = "(?<name>[A-Za-z0-9_]{1,16})";
	private static final String RANKS = "(?:\\[[^\\]]{1,24}\\] ?)*";
	private static final List<Format> FORMATS = List.of(
		new Format(Kind.PUBLIC, "^<" + NAME + "> (?<body>.*)$", true),
		new Format(Kind.WHISPER_IN, "^" + NAME + " whispers to you: (?<body>.*)$", true),
		new Format(Kind.WHISPER_OUT, "^You whisper to " + NAME + ": (?<body>.*)$", true),
		new Format(Kind.WHISPER_IN, "^" + NAME + " whispers: (?<body>.*)$", false),
		new Format(Kind.WHISPER_IN, "^(?i:from) " + NAME + ": (?<body>.*)$", false),
		new Format(Kind.WHISPER_OUT, "^(?i:to) " + NAME + ": (?<body>.*)$", false),
		new Format(Kind.WHISPER_IN, "^\\[" + RANKS + NAME + " -> (?i:me)\\] ?(?<body>.*)$", false),
		new Format(Kind.WHISPER_OUT, "^\\[(?i:me) -> " + RANKS + NAME + "\\] ?(?<body>.*)$", false),
		new Format(Kind.PUBLIC, "^" + RANKS + NAME + " ?(?::|»|>>) (?<body>.*)$", false)
	);

	private ChatMessages() {
	}

	/** Parses a chat line, checking non-vanilla formats against the tab list. */
	public static Parsed parse(Component message) {
		return parse(message.getString(), ChatMessages::isOnline);
	}

	/** Parses plain text; {@code isPlayer} decides whether a name from a non-vanilla format is a real player. */
	public static Parsed parse(String text, Predicate<String> isPlayer) {
		String plain = text.strip();
		for (Format f : FORMATS) {
			Matcher m = f.pattern.matcher(plain);
			if (!m.matches()) continue;
			String name = m.group("name");
			if (!f.vanilla && !isPlayer.test(name)) continue;
			return new Parsed(f.kind, name, m.group("body"));
		}
		return Parsed.NONE;
	}

	/** Whether {@code parsed} is your own public message. */
	public static boolean isFromSelf(Parsed parsed) {
		Minecraft mc = Minecraft.getInstance();
		return parsed.kind() == Kind.PUBLIC && mc.player != null && parsed.player().equalsIgnoreCase(mc.player.getGameProfile().name());
	}

	private static boolean isOnline(String name) {
		var connection = Minecraft.getInstance().getConnection();
		return connection != null && connection.getPlayerInfo(name) != null;
	}
}
