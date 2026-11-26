package dev.myriadclient.gradle.signing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer for the Library tools, so the signer jar runs with nothing else on its class
 * path. Objects come back as {@code Map<String, Object>} (in order), arrays as {@code List<Object>}, numbers as
 * {@link BigDecimal}, and strings, booleans and {@code null} as themselves. Nesting and size are capped; duplicate keys,
 * trailing commas and anything after the value are refused.
 */
public final class Json {
	private static final int MAX_DEPTH = 64;
	private static final int MAX_LENGTH = 16 * 1024 * 1024;
	private final String s;
	private int i;

	private Json(String s) {
		this.s = s;
	}

	public static Object parse(String text) {
		if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("JSON too big");
		Json j = new Json(text);
		j.space();
		Object v = j.value(0);
		j.space();
		if (j.i != text.length()) throw j.error("extra text after the value");
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(Object v, String what) {
		if (!(v instanceof Map)) throw new IllegalArgumentException(what + " isn't an object");
		return (Map<String, Object>) v;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> array(Object v, String what) {
		if (!(v instanceof List)) throw new IllegalArgumentException(what + " isn't a list");
		return (List<Object>) v;
	}

	public static String string(Object v, String what) {
		if (!(v instanceof String s)) throw new IllegalArgumentException(what + " isn't text");
		return s;
	}

	public static long number(Object v, String what) {
		if (v instanceof Long || v instanceof Integer) return ((Number) v).longValue();
		if (!(v instanceof BigDecimal d)) throw new IllegalArgumentException(what + " isn't a number");
		try {
			return d.longValueExact();
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException(what + " isn't a whole number");
		}
	}

	private Object value(int depth) {
		if (depth > MAX_DEPTH) throw error("nested too deep");
		if (i >= s.length()) throw error("unexpected end");
		char c = s.charAt(i);
		return switch (c) {
			case '{' -> object(depth);
			case '[' -> array(depth);
			case '"' -> string();
			case 't' -> word("true", Boolean.TRUE);
			case 'f' -> word("false", Boolean.FALSE);
			case 'n' -> word("null", null);
			default -> {
				if (c == '-' || (c >= '0' && c <= '9')) yield number();
				throw error("unexpected character");
			}
		};
	}

	private Map<String, Object> object(int depth) {
		Map<String, Object> m = new LinkedHashMap<>();
		i++;
		space();
		if (peek() == '}') {
			i++;
			return m;
		}
		while (true) {
			space();
			if (peek() != '"') throw error("expected a key");
			String k = string();
			space();
			expect(':');
			space();
			if (m.containsKey(k)) throw error("duplicate key " + k);
			m.put(k, value(depth + 1));
			space();
			char c = next();
			if (c == '}') return m;
			if (c != ',') throw error("expected , or }");
		}
	}

	private List<Object> array(int depth) {
		List<Object> l = new ArrayList<>();
		i++;
		space();
		if (peek() == ']') {
			i++;
			return l;
		}
		while (true) {
			space();
			l.add(value(depth + 1));
			space();
			char c = next();
			if (c == ']') return l;
			if (c != ',') throw error("expected , or ]");
		}
	}

	private String string() {
		expect('"');
		StringBuilder b = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') return b.toString();
			if (c < 0x20) throw error("control character in a string");
			if (c != '\\') {
				b.append(c);
				continue;
			}
			char e = next();
			switch (e) {
				case '"', '\\', '/' -> b.append(e);
				case 'b' -> b.append('\b');
				case 'f' -> b.append('\f');
				case 'n' -> b.append('\n');
				case 'r' -> b.append('\r');
				case 't' -> b.append('\t');
				case 'u' -> {
					if (i + 4 > s.length()) throw error("bad escape");
					try {
						b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
					} catch (NumberFormatException ex) {
						throw error("bad escape");
					}
					i += 4;
				}
				default -> throw error("bad escape");
			}
		}
	}

	private BigDecimal number() {
		int start = i;
		if (peek() == '-') i++;
		while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
		try {
			return new BigDecimal(s.substring(start, i));
		} catch (NumberFormatException e) {
			throw error("bad number");
		}
	}

	private Object word(String w, Object v) {
		if (!s.startsWith(w, i)) throw error("unexpected word");
		i += w.length();
		return v;
	}

	private void space() {
		while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
	}

	private char peek() {
		if (i >= s.length()) throw error("unexpected end");
		return s.charAt(i);
	}

	private char next() {
		char c = peek();
		i++;
		return c;
	}

	private void expect(char c) {
		if (next() != c) throw error("expected " + c);
	}

	private IllegalArgumentException error(String what) {
		return new IllegalArgumentException("bad JSON at " + i + ": " + what);
	}

	// ---- writing ----------------------------------------------------------------------------------------------------

	/** {@code v} as JSON: maps, lists, strings, numbers, booleans and null. */
	public static String write(Object v) {
		StringBuilder b = new StringBuilder();
		write(b, v);
		return b.toString();
	}

	private static void write(StringBuilder b, Object v) {
		switch (v) {
			case null -> b.append("null");
			case String str -> quote(b, str);
			case Number n -> b.append(n instanceof BigDecimal d ? d.toPlainString() : n.toString());
			case Boolean bool -> b.append(bool);
			case Map<?, ?> m -> {
				b.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> e : m.entrySet()) {
					if (!first) b.append(',');
					first = false;
					quote(b, String.valueOf(e.getKey()));
					b.append(':');
					write(b, e.getValue());
				}
				b.append('}');
			}
			case List<?> l -> {
				b.append('[');
				for (int k = 0; k < l.size(); k++) {
					if (k > 0) b.append(',');
					write(b, l.get(k));
				}
				b.append(']');
			}
			default -> throw new IllegalArgumentException("can't write " + v.getClass().getSimpleName() + " as JSON");
		}
	}

	private static void quote(StringBuilder b, String s) {
		b.append('"');
		for (int k = 0; k < s.length(); k++) {
			char c = s.charAt(k);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
				}
			}
		}
		b.append('"');
	}
}
