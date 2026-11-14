package dev.myriadclient.api.ui;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A theme preset: applying it writes values into the live {@link ThemeSettings} (anything it doesn't set keeps
 * Myriad's default). Players can edit a preset; their edits are saved on top of it. Register one with
 * {@code AddonContext.registerTheme}; it shows in the Theme window, and {@link Ui#applyTheme} applies it.
 *
 * <pre>{@code
 * ctx.registerTheme(new Theme(ctx.id("sunset"), "Sunset", t -> ThemePalette.of(...).apply(t)));
 * }</pre>
 *
 * Optional extras for themes that come from outside the game, like a system theme:
 * {@link #live} (re-apply when it changes), {@link #notice} (explain a problem in the Theme panel),
 * {@link #aliases} (old ids it replaces) and {@link #preferOnFirstRun}.
 */
public final class Theme implements Identified {
	private final MyriadId id;
	private final String name;
	private final Consumer<ThemeSettings> apply;
	private @Nullable Supplier<String> live;
	private @Nullable Supplier<@Nullable String> notice;
	private List<MyriadId> aliases = List.of();
	private boolean preferOnFirstRun;

	/**
	 * @param id    its registry id, also what saved choices refer to
	 * @param name  the name players see
	 * @param apply writes the preset's values into the theme; it runs after every theme value was reset to Myriad's
	 *              default, and before the player's own edits are put back on top
	 */
	public Theme(MyriadId id, String name, Consumer<ThemeSettings> apply) {
		this.id = id;
		this.name = name;
		this.apply = apply;
	}

	@Override
	public MyriadId id() {
		return id;
	}

	/** The name players see. */
	public String name() {
		return name;
	}

	/** What writes the preset's values into the theme. */
	public Consumer<ThemeSettings> apply() {
		return apply;
	}

	/**
	 * Makes the theme follow something that changes outside the game. While it's active, Myriad calls
	 * {@code version} every couple of seconds and re-applies the theme when the returned string changes (return
	 * e.g. a file's modification time). A call that throws counts as an empty string. Returns this theme.
	 */
	public Theme live(Supplier<String> version) {
		this.live = version;
		return this;
	}

	/**
	 * A message the Theme panel shows (highlighted) while {@code notice} returns non-null, e.g. why it can't load.
	 * Returns this theme.
	 */
	public Theme notice(Supplier<@Nullable String> notice) {
		this.notice = notice;
		return this;
	}

	/**
	 * Ids this theme replaces (from an older version or another addon), as {@code namespace:path} strings; saved
	 * choices and edits carry over. Returns this theme.
	 */
	public Theme aliases(String... oldIds) {
		this.aliases = Arrays.stream(oldIds).map(MyriadId::parse).toList();
		return this;
	}

	/**
	 * Use this theme instead of Myriad's default on a fresh install (e.g. one that matches the player's system). If
	 * several themes ask, the first in the theme registry wins. Returns this theme.
	 */
	public Theme preferOnFirstRun() {
		this.preferOnFirstRun = true;
		return this;
	}

	/** What {@link #live(Supplier)} set, or null for a theme that doesn't follow anything. */
	public @Nullable Supplier<String> live() {
		return live;
	}

	/** The message {@link #notice(Supplier)} gives now, or null when there's none (or no notice was set). */
	public @Nullable String notice() {
		return notice == null ? null : notice.get();
	}

	/** The ids {@link #aliases(String...)} set; empty by default. */
	public List<MyriadId> aliases() {
		return aliases;
	}

	/** Whether {@link #preferOnFirstRun()} was called. */
	public boolean prefersFirstRun() {
		return preferOnFirstRun;
	}

	/** Its name. */
	@Override
	public String toString() {
		return name;
	}
}
