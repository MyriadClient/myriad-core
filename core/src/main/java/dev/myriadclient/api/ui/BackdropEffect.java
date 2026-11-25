package dev.myriadclient.api.ui;

import dev.myriadclient.api.setting.SettingGroup;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A background effect: a shader drawn as the UI's window backgrounds (and its tooltips, toasts and launcher) while a theme picks it
 * (Theme, then Background: Style, by name). Core has none of its own; an addon makes them while it initialises, with
 * the options each theme keeps for it:
 *
 * <pre>{@code
 * BackdropEffect.register(Identifier.fromNamespaceAndPath("myaddon", "effects/smoke"), "Smoke", o -> {
 *     o.decimal("Density").defaultValue(0.5).range(0, 1).decimals(2).build();
 *     o.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT, 0x60)).build();
 * });
 * }</pre>
 *
 * Its shader is {@code assets/<namespace>/shaders/<path>.fsh}, the effect's colour at each pixel, which goes over the
 * background's own colour (and leaves widgets on it alone):
 *
 * <pre>{@code
 * #version 330
 * #extension GL_ARB_separate_shader_objects : require
 * #include <myriad:effect.glsl>
 *
 * vec4 effect(EffectInput e) {
 *     float n = effectFbm(e.uv * 4.0 + vec2(e.time * 0.05, 0.0));
 *     return vec4(e.accent.rgb, n * effectOption(0).x * 0.5);
 * }
 * }</pre>
 *
 * {@code EffectInput} has {@code uv} (0 to 1 across the screen, from its top left), {@code pixel} (the same in pixels),
 * {@code resolution}, {@code local} and {@code size} (the same across the background being drawn, a window, a tooltip or a
 * toast, and its size in pixels), {@code time} (seconds; it stands still while the theme's animations are off), and the theme's {@code accent}, {@code secondary}, {@code text} and
 * {@code background} colours. {@code effectOption(i)} is option {@code i}, in the order they were built, a {@code vec4}: a number
 * from 0 to 1 across its range in x, a switch as 1 or 0, a colour as RGBA, a choice or enum as its index over 255. The
 * include also has {@code effectHash}, {@code effectNoise}, {@code effectFbm} and {@code effectBayer4}. Return the colour
 * unpremultiplied; its alpha is how much of it covers the background.
 * <p>
 * A shader that doesn't compile is drawn as no effect (its errors are in the log) until resources reload.
 */
@ApiStatus.AvailableSince("2.6")
public final class BackdropEffect {
	/** How many options an effect's shader can read. */
	public static final int MAX_OPTIONS = 12;

	private static final Map<Identifier, BackdropEffect> BY_SHADER = new LinkedHashMap<>();
	private static final List<BackdropEffect> ALL = new ArrayList<>();

	private final Identifier shader;
	private final String name;
	private final int number;
	private final @Nullable Consumer<SettingGroup> options;

	private BackdropEffect(Identifier shader, String name, int number, @Nullable Consumer<SettingGroup> options) {
		this.shader = shader;
		this.name = name;
		this.number = number;
		this.options = options;
	}

	/**
	 * The effect drawn by {@code shader} (see the class comment), listed as {@code name}, with options: {@code options}
	 * builds them into a settings group, once for each theme, which keeps and shares them with its other values. They show
	 * after Background in Theme while the effect is picked, and its shader reads them in order ({@code effectOption(0)}
	 * on; up to {@value #MAX_OPTIONS}). The same {@code shader} always gives the same effect; a name already taken gets the
	 * shader's namespace after it.
	 */
	public static BackdropEffect register(Identifier shader, String name, @Nullable Consumer<SettingGroup> options) {
		BackdropEffect effect;
		synchronized (BackdropEffect.class) {
			BackdropEffect existing = BY_SHADER.get(shader);
			if (existing != null) return existing;
			String listed = byName(name) == null && !name.equals(ThemeSettings.PLAIN) ? name : name + " (" + shader.getNamespace() + ")";
			effect = new BackdropEffect(shader, listed, ALL.size(), options);
			BY_SHADER.put(shader, effect);
			ALL.add(effect);
		}
		ThemeSettings.effectAdded(effect);
		return effect;
	}

	/** The effect drawn by {@code shader}, listed as {@code name}, with no options. */
	public static BackdropEffect register(Identifier shader, String name) {
		return register(shader, name, null);
	}

	/** Builds its options into {@code group}, a theme's. */
	void buildOptions(SettingGroup group) {
		if (options != null) options.accept(group);
	}

	/** What it's listed as. */
	public String name() {
		return name;
	}

	/** Its fragment shader. */
	public Identifier shader() {
		return shader;
	}

	/** A number of its own, from 0, in the order effects were made. */
	public int number() {
		return number;
	}

	/** Every effect, in the order they were made. */
	public static synchronized List<BackdropEffect> all() {
		return Collections.unmodifiableList(new ArrayList<>(ALL));
	}

	/** The effect listed as {@code name}, or null. */
	public static synchronized @Nullable BackdropEffect byName(String name) {
		for (BackdropEffect e : ALL) if (e.name.equals(name)) return e;
		return null;
	}
}
