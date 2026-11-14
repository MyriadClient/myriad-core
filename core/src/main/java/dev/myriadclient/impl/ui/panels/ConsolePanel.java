package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.Shortcuts;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** A terminal for Myriad commands: output above, prompt below, Up/Down history, Tab completion. */
public final class ConsolePanel extends Panel {
	private final List<String> history = new ArrayList<>();
	private String input = "";
	private int historyIndex = -1;
	private float scroll;
	private String suggestion = "";

	@Override
	public String title() {
		return "Console";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		float size = c.defaultFontSize();
		float lh = c.textHeight(FontFamily.MONO, size) + 1;
		float promptH = lh + 8;
		List<String> lines = ConsoleLog.lines();
		c.push();
		c.clip(0, 0, w, h - promptH);
		float y = h - promptH - 4 - lh + scroll;
		float room = Math.max(1, w - 12);
		for (int i = lines.size() - 1; i >= 0 && y > -lh; i--) {
			// Bottom up, so a wrapped line's last piece first.
			List<String> pieces = Label.wrap(c, FontFamily.MONO, size, lines.get(i), room);
			for (int p = pieces.size() - 1; p >= 0 && y > -lh; p--) {
				c.text(FontFamily.MONO, size, pieces.get(p), 6, y, Myriad.ui().theme().text.argb());
				y -= lh;
			}
		}
		c.pop();
		var theme = Myriad.ui().theme();
		c.rect(0, h - promptH, w, 1, ColorUtil.withAlpha(theme.textDim.argb(), 40));
		float py = h - promptH + 4;
		String prompt = Myriad.config().commandPrefix();
		c.text(FontFamily.MONO, size, "", 6, py, theme.accent.argb());
		String prefixHint = "prefix " + prompt;
		float pw = c.textWidth(FontFamily.SANS, size * 0.85f, prefixHint);
		// The hint goes when the window's too narrow for it beside a command; a long line scrolls so its end shows.
		boolean showHint = w >= 150;
		float tx = 16, right = showHint ? w - pw - 10 : w - 4;
		float iw = c.textWidth(FontFamily.MONO, size, input);
		float shift = Math.max(0, tx + iw + 2 - right);
		c.push();
		c.clip(tx - 1, h - promptH, Math.max(0, right - tx + 1), promptH);
		c.text(FontFamily.MONO, size, input, tx - shift, py, theme.text.argb());
		if (!suggestion.isEmpty()) c.text(FontFamily.MONO, size, suggestion, tx - shift + iw, py, theme.textDim.argb());
		if ((System.nanoTime() / 500_000_000L) % 2 == 0) c.rect(tx - shift + iw, py, 0.75f, lh - 1, theme.text.argb());
		if (input.isEmpty()) c.text(FontFamily.MONO, size, "help", tx + 2, py, ColorUtil.withAlpha(theme.textDim.argb(), 100));
		c.pop();
		if (showHint) c.text(FontFamily.SANS, size * 0.85f, prefixHint, w - pw - 6, py + 1, theme.textDim.argb());
	}

	@Override
	public boolean capturesKeyboard() {
		return true;
	}

	private void updateSuggestion() {
		suggestion = "";
		if (input.isEmpty()) return;
		var commands = MyriadImpl.get().commandManager();
		var parse = commands.parse(input);
		try {
			var s = commands.dispatcher().getCompletionSuggestions(parse).getNow(null);
			if (s != null && !s.isEmpty()) {
				var first = s.getList().getFirst();
				String typed = input.substring(first.getRange().getStart());
				if (first.getText().startsWith(typed)) suggestion = first.getText().substring(typed.length());
			}
		} catch (RuntimeException ignored) {
		}
	}

	@Override
	public boolean keyPressed(int key, int scancode, int mods) {
		// Alt/Super (and Ctrl, apart from the combos below) aren't typing: those keys are the UI's shortcuts.
		if ((mods & (InputConstants.MOD_ALT | InputConstants.MOD_SUPER)) != 0) return false;
		switch (Keybind.letter(key)) {
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				String line = input.trim();
				if (line.startsWith(Myriad.config().commandPrefix())) line = line.substring(Myriad.config().commandPrefix().length());
				if (!line.isEmpty()) {
					ConsoleLog.add("> " + line);
					history.add(line);
					MyriadImpl.get().commandManager().execute(line);
				}
				input = "";
				historyIndex = -1;
				scroll = 0;
			}
			case InputConstants.KEY_BACKSPACE -> {
				if (!input.isEmpty()) input = Shortcuts.ctrl(mods) ? "" : input.substring(0, input.length() - 1);
			}
			case InputConstants.KEY_TAB -> input += suggestion;
			case InputConstants.KEY_UP -> {
				if (history.isEmpty()) return true;
				historyIndex = historyIndex < 0 ? history.size() - 1 : Math.max(0, historyIndex - 1);
				input = history.get(historyIndex);
			}
			case InputConstants.KEY_DOWN -> {
				if (historyIndex < 0) return true;
				historyIndex++;
				if (historyIndex >= history.size()) {
					historyIndex = -1;
					input = "";
				} else input = history.get(historyIndex);
			}
			case InputConstants.KEY_V -> {
				if (Shortcuts.ctrl(mods)) input += Minecraft.getInstance().keyboardHandler.getClipboard().replace("\n", " ");
			}
			case InputConstants.KEY_L -> {
				if (Shortcuts.ctrl(mods)) ConsoleLog.clear();
			}
			case InputConstants.KEY_ESCAPE -> {
				return false;
			}
			default -> {
				if (Shortcuts.ctrl(mods)) return false;
			}
		}
		updateSuggestion();
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (chr < 32 || chr == 127) return false;
		input += chr;
		updateSuggestion();
		return true;
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		scroll = Math.max(0, scroll + amount * 12);
		return true;
	}

	@Override
	public void save(JsonObject out) {
		JsonArray a = new JsonArray();
		for (String h : history.subList(Math.max(0, history.size() - 50), history.size())) a.add(h);
		out.add("history", a);
	}

	@Override
	public void load(JsonObject in) {
		if (in.has("history")) for (var e : in.getAsJsonArray("history")) history.add(e.getAsString());
	}
}
