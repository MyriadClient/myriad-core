package dev.myriadclient.api.util;

import dev.myriadclient.api.util.ChatMessages.Kind;
import dev.myriadclient.api.util.ChatMessages.Parsed;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessagesTest {
	private static final Predicate<String> ONLINE = Set.of("Steve", "Alex_99")::contains;

	private static Parsed parse(String line) {
		return ChatMessages.parse(line, ONLINE);
	}

	@Test
	void vanillaFormats() {
		assertEquals(new Parsed(Kind.PUBLIC, "Notch", "hello there"), parse("<Notch> hello there"));
		assertEquals(new Parsed(Kind.WHISPER_IN, "Notch", "psst"), parse("Notch whispers to you: psst"));
		assertEquals(new Parsed(Kind.WHISPER_OUT, "Notch", "hi"), parse("You whisper to Notch: hi"));
	}

	@Test
	void pluginFormatsNeedAnOnlinePlayer() {
		assertEquals(new Parsed(Kind.WHISPER_IN, "Steve", "come here"), parse("[Steve -> me] come here"));
		assertEquals(new Parsed(Kind.WHISPER_OUT, "Alex_99", "ok"), parse("[me -> Alex_99] ok"));
		assertEquals(new Parsed(Kind.WHISPER_IN, "Steve", "yo"), parse("Steve whispers: yo"));
		assertEquals(new Parsed(Kind.WHISPER_IN, "Steve", "yo"), parse("From Steve: yo"));
		assertEquals(new Parsed(Kind.WHISPER_OUT, "Steve", "yo"), parse("To Steve: yo"));
		assertEquals(new Parsed(Kind.PUBLIC, "Steve", "gg"), parse("[VIP] [Builder] Steve: gg"));
		assertEquals(new Parsed(Kind.PUBLIC, "Alex_99", "hey"), parse("Alex_99 » hey"));
	}

	@Test
	void serverLinesStayOther() {
		assertEquals(Kind.OTHER, parse("Server: restarting in 5 minutes").kind());
		assertEquals(Kind.OTHER, parse("Steve was slain by Zombie").kind());
		assertEquals(Kind.OTHER, parse("[Myriad] Velocity enabled").kind());
		assertFalse(parse("From Herobrine: boo").isWhisper()); // not online
	}
}
