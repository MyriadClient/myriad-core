package dev.myriadclient.api.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * How what a module shows in the world looks, in either {@link RenderMode}: the standard options of its Render group,
 * so every module offers the same ones. Only the options for the mode that's on show: Fill, Fill Opacity and Through
 * Walls in both; Line Width in {@link RenderMode#BOX}; Outline Width, Glow, Gradient and the fill's pattern size in
 * {@link RenderMode#SHADER}. Colours stay the module's own (one per kind of thing it shows); hand them with each thing to
 * {@code RenderEvent}, which draws it in the mode that's on.
 *
 * <pre>{@code
 * private final SettingGroup sgRender = settings.group("Render");
 * private final RenderSettings look = RenderSettings.builder(sgRender).build();
 *
 * @Subscribe
 * private void onRender(RenderEvent e) {
 *     for (Entity entity : targets) e.entity(entity, look, color.argb());
 * }
 * }</pre>
 * Several looks in one group (a hand and an item) take a {@link Builder#named name} each, which goes before their
 * options' names.
 */
public final class RenderSettings implements RenderLook {
	public final ChoiceSetting fill;
	public final DoubleSetting fillOpacity;
	/** A patterned fill's size and spacing (the dots', or an addon fill's scale), in pixels. */
	public final DoubleSetting patternSpacing;
	public final DoubleSetting patternSize;
	public final DoubleSetting lineWidth;
	public final DoubleSetting outlineWidth;
	public final DoubleSetting glow;
	public final BoolSetting gradient;
	public final ColorSetting gradientColor;
	/** Null when built with {@link Builder#noThroughWalls()}: the look keeps its default's. */
	public final @Nullable BoolSetting throughWalls;

	private final boolean defaultThroughWalls;
	private HighlightStyle style;
	private long builtFrame = -1;

	public static Builder builder(SettingGroup group) {
		return new Builder(group);
	}

	private RenderSettings(Builder b) {
		SettingGroup group = b.group;
		Supplier<Boolean> visible = b.visible;
		Supplier<Boolean> box = b.alwaysShader ? () -> false : () -> visible.get() && RenderMode.current() == RenderMode.BOX;
		Supplier<Boolean> shader = b.alwaysShader ? visible : () -> visible.get() && RenderMode.current() == RenderMode.SHADER;
		HighlightStyle d = b.defaults;
		String p = b.name == null ? "" : b.name + " ";
		fill = group.choice(p + "Fill", () -> HighlightStyle.Fill.all().stream().map(HighlightStyle.Fill::name).toList())
			.description("What's drawn inside: flat, or a pattern (in Box mode, faces are always flat).")
			.defaultValue(d.fill().name()).visible(visible).build();
		fillOpacity = group.decimal(p + "Fill Opacity").defaultValue(snap(d.fillOpacity(), 2)).range(0, 1).decimals(2)
			.visible(() -> visible.get() && fill() != HighlightStyle.Fill.NONE).build();
		patternSpacing = group.decimal(p + "Pattern Spacing").description("Distance between the pattern's marks, in pixels.")
			.defaultValue(snap(Math.min(d.dotSpacing(), 24), 1)).range(2, 24).decimals(1).visible(() -> shader.get() && patterned()).build();
		patternSize = group.decimal(p + "Pattern Size").description("Size of the pattern's marks, in pixels.")
			.defaultValue(snap(Math.min(d.dotSize(), 12), 1)).range(0.5, 12).decimals(1).visible(() -> shader.get() && patterned()).build();
		lineWidth = group.decimal(p + "Line Width").description("The box's edges; 0 for none.")
			.defaultValue(snap(b.lineWidth, 1)).range(0, 5).decimals(1).visible(box).build();
		outlineWidth = group.decimal(p + "Outline Width").description("In pixels; 0 for no outline.")
			.defaultValue(snap(Math.min(d.outlineWidth(), 6), 1)).range(0, 6).decimals(1).visible(shader).build();
		glow = group.decimal(p + "Glow").description("A soft glow beyond the outline, in pixels; 0 for none.")
			.defaultValue(snap(Math.min(d.glow(), 24), 0)).range(0, 24).decimals(0).visible(shader).build();
		gradient = group.bool(p + "Gradient").description("Fade to a second colour towards the bottom.").defaultValue(d.hasGradient()).visible(shader).build();
		gradientColor = group.color(p + "Gradient Color")
			.defaultValue(d.hasGradient() ? SettingColor.of(d.gradient()) : SettingColor.role(SettingColor.Mode.SECONDARY))
			.visible(() -> shader.get() && gradient.get()).build();
		defaultThroughWalls = d.throughWalls();
		throughWalls = b.throughWallsOption ? group.bool(p + "Through Walls").defaultValue(d.throughWalls()).visible(visible).build() : null;
	}

	/** {@code v} rounded to the setting's decimals, so a float default saves and compares as the number it reads as. */
	private static double snap(float v, int decimals) {
		double f = Math.pow(10, decimals);
		return Math.round(v * f) / f;
	}

	/** The fill chosen; none if it names one that's gone (an addon's, uninstalled). */
	public HighlightStyle.Fill fill() {
		HighlightStyle.Fill f = HighlightStyle.Fill.byName(fill.get());
		return f == null ? HighlightStyle.Fill.NONE : f;
	}

	private boolean patterned() {
		HighlightStyle.Fill f = fill();
		return f != HighlightStyle.Fill.NONE && f != HighlightStyle.Fill.SOLID;
	}

	@Override
	public boolean throughWalls() {
		return throughWalls == null ? defaultThroughWalls : throughWalls.get();
	}

	@Override
	public float lineWidth() {
		return lineWidth.getFloat();
	}

	/** The look in {@link RenderMode#SHADER}; rebuilt at most once a frame (theme and rainbow colours change over time). */
	@Override
	public HighlightStyle style() {
		long frame = Myriad.render().frame();
		if (style == null || builtFrame != frame) {
			builtFrame = frame;
			HighlightStyle s = HighlightStyle.OUTLINE
				.withOutlineWidth(outlineWidth.getFloat())
				.withGlow(glow.getFloat())
				.withFill(fill())
				.withFillOpacity(fillOpacity.getFloat())
				.withDots(patternSpacing.getFloat(), patternSize.getFloat())
				.withThroughWalls(throughWalls());
			style = gradient.get() ? s.withGradient(gradientColor.argb()) : s;
		}
		return style;
	}

	/** {@code color} for a box's faces in {@link RenderMode#BOX}: at the fill opacity times {@code fade}, or clear with no fill. */
	@Override
	public int boxFill(int color, float fade) {
		if (fill() == HighlightStyle.Fill.NONE) return 0;
		return ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * fillOpacity.get() * fade));
	}

	/** {@code color} for a box's edges in {@link RenderMode#BOX}, faded by {@code fade}; clear with no line width. */
	@Override
	public int boxLine(int color, float fade) {
		if (lineWidth.get() <= 0) return 0;
		return ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * fade));
	}

	/**
	 * Carries saved options of the looks before this one (a {@code BoxStyle} and a {@code HighlightSettings} in group
	 * {@code group}, their names after {@code name} if any) over to a {@link RenderSettings} there, for a module's
	 * {@code migrateSettings}: the fill, shared by both modes now, is the shader one's if it was saved, else the box's
	 * (its Shape: edges only means no fill, faces only means no edges); Dot Spacing and Dot Size become the pattern's.
	 */
	public static void migrate(SavedSettings saved, String group, @Nullable String name) {
		String p = name == null ? "" : name + " ";
		Optional<JsonElement> shape = saved.get(group, p + "Shape");
		if (saved.get(group, p + "Fill").isEmpty() && shape.isPresent()) {
			String fill = "LINES".equalsIgnoreCase(shape.get().getAsString()) ? HighlightStyle.Fill.NONE.name() : HighlightStyle.Fill.SOLID.name();
			saved.set(group, p + "Fill", new JsonPrimitive(fill));
		}
		if (shape.isPresent() && "FILL".equalsIgnoreCase(shape.get().getAsString())) saved.set(group, p + "Line Width", new JsonPrimitive(0));
		saved.remove(group, p + "Shape");
		// A fill saved as the old option's constant (SOLID) reads as its name (Solid).
		saved.map(group, p + "Fill", v -> {
			HighlightStyle.Fill f = v.isJsonPrimitive() ? HighlightStyle.Fill.byName(v.getAsString()) : null;
			return f == null ? v : new JsonPrimitive(f.name());
		});
		saved.rename(group, p + "Dot Spacing", p + "Pattern Spacing");
		saved.rename(group, p + "Dot Size", p + "Pattern Size");
	}

	/** Sets up {@link RenderSettings} before its options are added; see {@link #builder}. */
	public static final class Builder {
		private final SettingGroup group;
		private Supplier<Boolean> visible = () -> true;
		private HighlightStyle defaults = HighlightStyle.OUTLINE.withFill(HighlightStyle.Fill.SOLID).withFillOpacity(0.2f).withDots(2.5f, 1);
		private float lineWidth = 1.5f;
		private boolean throughWallsOption = true;
		private @Nullable String name;
		private boolean alwaysShader;

		private Builder(SettingGroup group) {
			this.group = group;
		}

		/** Shows the options only while {@code visible} says so (while a toggle is on, say). */
		public Builder visible(Supplier<Boolean> visible) {
			this.visible = visible;
			return this;
		}

		/** The look the options start from, in both modes (its fill, fill opacity and through walls are shared). */
		public Builder defaults(HighlightStyle defaults) {
			this.defaults = defaults;
			return this;
		}

		/** The box edges' width the options start from (1.5 unless set; 0 for faces only). */
		public Builder lineWidth(float width) {
			this.lineWidth = width;
			return this;
		}

		/** Through walls on or off to start with. */
		public Builder throughWalls(boolean on) {
			this.defaults = defaults.withThroughWalls(on);
			return this;
		}

		/** No Through Walls option, for what's never behind anything (your hand); the defaults' is kept. */
		public Builder noThroughWalls() {
			this.throughWallsOption = false;
			return this;
		}

		/**
		 * For what's only ever drawn by the shader, in either mode (your hand and held items, which have no box): its
		 * shader options show whatever the mode, the box's never.
		 */
		public Builder alwaysShader() {
			this.alwaysShader = true;
			return this;
		}

		/** A name before each option's ("Hand" makes "Hand Fill", "Hand Glow"), for several looks in one group. */
		public Builder named(String name) {
			this.name = name;
			return this;
		}

		public RenderSettings build() {
			return new RenderSettings(this);
		}
	}
}
