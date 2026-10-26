package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Theme → Preferences → Chat. What Minecraft's own Chat Settings have (size, width, heights, opacities, line spacing,
 * what's shown) is kept in step with them, each tick: a change on either side is copied to the other, so they're the
 * same setting seen in two places (Minecraft's, saved with its options, are kept as the source on start: Myriad's
 * aren't copied over until it's ready). The rest is Myriad's own, read by the chat mixins: the font (ThemedText), the
 * background and text shadow while chat draws, the filters, repeats stacked, and how much is kept.
 */
public final class ThemedChat {
	public static final ThemedChat INSTANCE = new ThemedChat();
	/** A server's chat line: {@code <name> message}. */
	private static final Pattern CHAT_LINE = Pattern.compile("^<[^<>\\s]{1,32}> ");
	/** Ticks a change is saved to Minecraft's options after (a slider dragged changes it every frame). */
	private static final int SAVE_TICKS = 20;

	/** Chat is drawing (ChatHudMixin): its text shadow and background follow the settings. */
	public static boolean drawing;

	private final List<Link<?>> links = new ArrayList<>();
	/** Each link's Myriad value when last in step, to tell which side changed. */
	private Object[] last = new Object[0];
	private ThemeSettings.@Nullable ChatShown shownLast;
	private int saveIn = -1;
	private @Nullable List<String> filterSource;
	private List<Pattern> filters = List.of();

	private ThemedChat() {
	}

	private static ThemeSettings theme() {
		return MyriadImpl.get().windowManager().theme();
	}

	/** A Myriad setting and the Minecraft option it is, with the value as each holds it. */
	private record Link<T>(Supplier<T> mine, Consumer<T> setMine, Function<Options, OptionInstance<Double>> option,
	                       Function<T, Double> toOption, Function<Double, T> fromOption) {
		/** True if they differed and were brought back in step: {@code last} says which changed since. */
		boolean sync(Options o, Object[] last, int i) {
			T mine = mine().get();
			double theirs = option().apply(o).get();
			if (last[i] == null) last[i] = mine;
			if (!mine.equals(last[i])) {
				// Changed here: to Minecraft's.
				option().apply(o).set(toOption().apply(mine));
				last[i] = mine;
				return true;
			}
			T fromTheirs = fromOption().apply(theirs);
			if (!fromTheirs.equals(mine)) {
				// Changed in Minecraft's own settings (or on start): to Myriad's.
				setMine().accept(fromTheirs);
				last[i] = mine().get();
			}
			return false;
		}
	}

	private void link() {
		ThemeSettings t = theme();
		links.add(new Link<>(t.chatSize::get, t.chatSize::set, Options::chatScale, v -> v, v -> v));
		links.add(pixels(t.chatWidth, Options::chatWidth, 280, 40));
		links.add(pixels(t.chatHeight, Options::chatHeightFocused, 160, 20));
		links.add(pixels(t.chatHeightUnfocused, Options::chatHeightUnfocused, 160, 20));
		links.add(fraction(t.chatTextOpacity, Options::chatOpacity));
		links.add(fraction(t.chatBackgroundOpacity, Options::textBackgroundOpacity));
		links.add(fraction(t.chatLineSpacing, Options::chatLineSpacing));
		last = new Object[links.size()];
	}

	private static Link<Double> fraction(DoubleSetting s, Function<Options, OptionInstance<Double>> option) {
		return new Link<>(s::get, s::set, option, v -> v, v -> Math.round(v * 100) / 100.0);
	}

	/** Minecraft keeps widths and heights as a fraction: {@code span} pixels over {@code min}. */
	private static Link<Integer> pixels(IntSetting s, Function<Options, OptionInstance<Double>> option, int span, int min) {
		return new Link<>(s::get, s::set, option, px -> (px - min) / (double) span, f -> (int) Math.round(f * span + min));
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		var mc = Minecraft.getInstance();
		if (!Myriad.isReady() || mc.options == null) return;
		if (links.isEmpty()) link();
		boolean changed = false;
		for (int i = 0; i < links.size(); i++) changed |= links.get(i).sync(mc.options, last, i);
		changed |= syncShown(mc.options);
		if (changed) saveIn = SAVE_TICKS;
		if (saveIn >= 0 && saveIn-- == 0) mc.options.save();
	}

	private boolean syncShown(Options o) {
		ThemeSettings t = theme();
		ThemeSettings.ChatShown mine = t.chatShown.get();
		ThemeSettings.ChatShown theirs = switch (o.chatVisibility().get()) {
			case FULL -> ThemeSettings.ChatShown.ALL;
			case SYSTEM -> ThemeSettings.ChatShown.COMMANDS_ONLY;
			case HIDDEN -> ThemeSettings.ChatShown.HIDDEN;
		};
		if (shownLast == null) shownLast = mine;
		if (mine != shownLast) {
			o.chatVisibility().set(switch (mine) {
				case ALL, WHILE_OPEN -> ChatVisiblity.FULL;
				case COMMANDS_ONLY -> ChatVisiblity.SYSTEM;
				case HIDDEN -> ChatVisiblity.HIDDEN;
			});
			shownLast = mine;
			return true;
		}
		// Shown only while open is all of them to Minecraft.
		if (theirs != mine && !(mine == ThemeSettings.ChatShown.WHILE_OPEN && theirs == ThemeSettings.ChatShown.ALL)) {
			t.chatShown.set(theirs);
			shownLast = theirs;
		}
		return false;
	}

	// ---- read by the chat mixins ------------------------------------------------------------------------------------

	/** Whether chat closed shows nothing (Shown: While Open). */
	public static boolean hiddenClosed() {
		return Myriad.isReady() && theme().chatShown.get() == ThemeSettings.ChatShown.WHILE_OPEN;
	}

	/** Whether chat's text has its shadow. */
	public static boolean shadow() {
		return !Myriad.isReady() || theme().chatShadow.get();
	}

	/** The colour for one of chat's black line backgrounds ({@code color}): the theme's window colour, black, or none (0). */
	public static int background(int color) {
		if (!Myriad.isReady() || (color & 0xFFFFFF) != 0) return color;
		ThemeSettings t = theme();
		return switch (t.chatBackground.get()) {
			case VANILLA -> color;
			case NONE -> 0;
			case THEME -> ColorUtil.withAlpha(t.windowBackground.argb(), ColorUtil.alpha(color));
		};
	}

	/**
	 * Whether {@code message} is shown: none of the filters is in it, and with only commands' answers shown, it's no
	 * player's chat (Minecraft hides signed chat then, but a server like 2b2t sends chat as its own lines).
	 */
	public static boolean shown(GuiMessage message) {
		if (!Myriad.isReady()) return true;
		String text = message.content().getString();
		if (theme().chatShown.get() == ThemeSettings.ChatShown.COMMANDS_ONLY && (message.source() == GuiMessageSource.PLAYER || CHAT_LINE.matcher(text).find())) {
			return false;
		}
		List<Pattern> patterns = INSTANCE.patterns();
		if (patterns.isEmpty()) return true;
		for (Pattern p : patterns) if (p.matcher(text).find()) return false;
		return true;
	}

	/** The filters as patterns, compiled again when they change: /a regex/, else the words as they are; any case. */
	private List<Pattern> patterns() {
		List<String> source = theme().chatFilters.get();
		// The list is changed in place, so it's compared by what's in it.
		if (!source.equals(filterSource)) {
			filterSource = List.copyOf(source);
			List<Pattern> compiled = new ArrayList<>();
			for (String f : source) {
				String s = f.strip();
				if (s.isEmpty()) continue;
				try {
					compiled.add(s.length() > 2 && s.startsWith("/") && s.endsWith("/")
						? Pattern.compile(s.substring(1, s.length() - 1), Pattern.CASE_INSENSITIVE)
						: Pattern.compile(Pattern.quote(s.toLowerCase(Locale.ROOT)), Pattern.CASE_INSENSITIVE));
				} catch (PatternSyntaxException ignored) {
					// A pattern that isn't one filters nothing.
				}
			}
			filters = compiled;
		}
		return filters;
	}

	/** How many messages chat keeps. */
	public static int history() {
		return Myriad.isReady() ? theme().chatHistory.get() : 100;
	}

	/** Whether the same message again is stacked onto the last. */
	public static boolean stackRepeats() {
		return Myriad.isReady() && theme().chatStackRepeats.get();
	}

	/** Whether chat is in the theme's font. */
	public static boolean themeFont() {
		return Myriad.isReady() && theme().chatFont.get() == ThemeSettings.ChatFont.THEME;
	}
}
