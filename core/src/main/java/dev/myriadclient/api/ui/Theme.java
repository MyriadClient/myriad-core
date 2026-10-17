package dev.myriadclient.api.ui;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A theme preset: applying it writes values into the live {@link ThemeSettings} (anything it doesn't set keeps
 * Myriad's default). Players can edit a preset; their edits are saved on top of it.
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
	private Supplier<String> live;
	private Supplier<String> notice;
	private List<MyriadId> aliases = List.of();
	private boolean preferOnFirstRun;

	public Theme(MyriadId id, String name, Consumer<ThemeSettings> apply) {
		this.id = id;
		this.name = name;
		this.apply = apply;
	}

	@Override
	public MyriadId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public Consumer<ThemeSettings> apply() {
		return apply;
	}

	/**
	 * Makes the theme follow something that changes outside the game. While it's active, Myriad calls
	 * {@code version} every couple of seconds and re-applies the theme when the returned string changes (return
	 * e.g. a file's modification time).
	 */
	public Theme live(Supplier<String> version) {
		this.live = version;
		return this;
	}

	/** A message the Theme panel shows (highlighted) while {@code notice} returns non-null, e.g. why it can't load. */
	public Theme notice(Supplier<String> notice) {
		this.notice = notice;
		return this;
	}

	/** Ids this theme replaces (from an older version or another addon); saved choices and edits carry over. */
	public Theme aliases(String... oldIds) {
		this.aliases = Arrays.stream(oldIds).map(MyriadId::parse).toList();
		return this;
	}

	/** Use this theme instead of Myriad's default on a fresh install (e.g. one that matches the player's system). */
	public Theme preferOnFirstRun() {
		this.preferOnFirstRun = true;
		return this;
	}

	public @Nullable Supplier<String> live() {
		return live;
	}

	public @Nullable String notice() {
		return notice == null ? null : notice.get();
	}

	public List<MyriadId> aliases() {
		return aliases;
	}

	public boolean prefersFirstRun() {
		return preferOnFirstRun;
	}

	@Override
	public String toString() {
		return name;
	}
}
