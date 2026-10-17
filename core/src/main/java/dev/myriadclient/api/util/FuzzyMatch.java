package dev.myriadclient.api.util;

import java.util.Locale;

/**
 * Search scoring for short names (modules, panels, themes). Tiers, best first: exact, prefix, word prefix
 * ("aura" → "Kill Aura"), initials ("ka" → "Kill Aura"), substring ignoring spaces ("killaura"), and a strict fuzzy
 * match whose letters must either be consecutive or jump to the start of a word ("kaur" → "Kill AURa"). Scattered
 * letters don't count, so short queries stay precise.
 */
public final class FuzzyMatch {
	public static final int NO_MATCH = -1;

	private FuzzyMatch() {
	}

	/** Higher is better; {@link #NO_MATCH} if {@code query} doesn't match. A blank query matches everything with 0. */
	public static int score(String text, String query) {
		String q = normalize(query);
		if (q.isEmpty()) return 0;
		String t = text.toLowerCase(Locale.ROOT).trim();
		String compactQ = q.replace(" ", "");
		String compactT = compact(t);
		if (compactT.equals(compactQ)) return 10_000;
		if (t.startsWith(q) || compactT.startsWith(compactQ)) return 9_000 - t.length();
		String[] words = words(t);
		for (int i = 0; i < words.length; i++) {
			if (words[i].startsWith(q)) return 8_000 - i * 50 - t.length();
		}
		if (compactQ.length() >= 2 && initials(words).startsWith(compactQ)) return 7_000 - t.length();
		int idx = compactT.indexOf(compactQ);
		if (idx >= 0) return 6_000 - idx * 10 - t.length();
		int fuzzy = fuzzy(t, compactQ);
		return fuzzy < 0 ? NO_MATCH : 1_000 + fuzzy - t.length();
	}

	public static boolean matches(String text, String query) {
		return score(text, query) != NO_MATCH;
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
	}

	private static String compact(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c)) sb.append(c);
		}
		return sb.toString();
	}

	private static String[] words(String t) {
		return t.split("[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})");
	}

	private static String initials(String[] words) {
		StringBuilder sb = new StringBuilder();
		for (String w : words) if (!w.isEmpty()) sb.append(w.charAt(0));
		return sb.toString();
	}

	private static boolean wordStart(String t, int i) {
		return i == 0 || !Character.isLetterOrDigit(t.charAt(i - 1));
	}

	/** Each query letter must follow the previous match directly or land on a word start. */
	private static int fuzzy(String t, String q) {
		if (q.length() < 2) return -1;
		int score = 0, pos = -1;
		for (int qi = 0; qi < q.length(); qi++) {
			char c = q.charAt(qi);
			int found = -1;
			if (pos + 1 < t.length() && t.charAt(pos + 1) == c && pos >= 0) {
				found = pos + 1;
				score += 15;
			} else {
				for (int i = pos + 1; i < t.length(); i++) {
					if (t.charAt(i) == c && wordStart(t, i)) {
						found = i;
						score += 10;
						break;
					}
				}
			}
			if (found < 0) return -1;
			pos = found;
		}
		return score;
	}
}
