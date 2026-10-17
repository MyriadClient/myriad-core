package dev.myriadclient.api.command.arguments;

import com.mojang.brigadier.StringReader;

final class ArgUtil {
	private ArgUtil() {
	}

	/** Reads until whitespace (allows ':' and other characters Brigadier's unquoted strings reject). */
	static String readWord(StringReader reader) {
		int start = reader.getCursor();
		while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
		return reader.getString().substring(start, reader.getCursor());
	}
}
