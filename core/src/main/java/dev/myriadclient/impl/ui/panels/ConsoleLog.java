package dev.myriadclient.impl.ui.panels;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ring buffer of Myriad chat output, shown in the console panel. */
public final class ConsoleLog {
	private static final int MAX = 300;
	private static final Deque<String> LINES = new ArrayDeque<>();

	private ConsoleLog() {
	}

	public static synchronized void add(String line) {
		for (String l : line.split("\n")) {
			LINES.addLast(l);
			while (LINES.size() > MAX) LINES.removeFirst();
		}
	}

	public static synchronized List<String> lines() {
		return new ArrayList<>(LINES);
	}

	public static synchronized void clear() {
		LINES.clear();
	}
}
