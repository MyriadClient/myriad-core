package dev.myriadclient.api.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The standard options for a {@link HighlightStyle}, so every module that highlights things offers the same ones:
 * outline width, glow, fill (none, solid or a dot grid), an optional gradient, and through walls. {@link #builder}
 * starts them from another look, or leaves Through Walls out where it means nothing.
 *
 * <pre>{@code
 * private final HighlightSettings highlight = new HighlightSettings(settings.group("Highlight"));
 *
 * @Subscribe
 * private void onHighlight(HighlightEvent.Entity e) {
 *     e.highlight(highlight.style(), color);
 * }
 * }</pre>
 */
public final class HighlightSettings {
	public enum FillMode {
		NONE(HighlightStyle.Fill.NONE), SOLID(HighlightStyle.Fill.SOLID), DOTS(HighlightStyle.Fill.DOTS);

		public final HighlightStyle.Fill fill;

		FillMode(HighlightStyle.Fill fill) {
			this.fill = fill;
		}
	}

	public final DoubleSetting outlineWidth;
	public final DoubleSetting glow;
	public final EnumSetting<FillMode> fill;
	public final DoubleSetting fillOpacity;
	public final DoubleSetting dotSpacing;
	public final DoubleSetting dotSize;
	public final BoolSetting gradient;
	public final ColorSetting gradientColor;
	/** Null when built with {@link Builder#noThroughWalls()}: the style then keeps its default's. */
	public final @Nullable BoolSetting throughWalls;

	/** The look the options start from unless a module picks another ({@link Builder#defaults}). */
	private static final HighlightStyle STANDARD = HighlightStyle.OUTLINE.withFillOpacity(0.3f).withDots(2.5f, 1);

	private final boolean defaultThroughWalls;
	private HighlightStyle style;
	private long builtFrame = -1;

	/** Adds the options to {@code group}. */
	public HighlightSettings(SettingGroup group) {
		this(group, () -> true);
	}

	/** Adds the options to {@code group}, shown only while {@code visible} says so (e.g. in one mode of a module). */
	public HighlightSettings(SettingGroup group, Supplier<Boolean> visible) {
		this(group, visible, true);
	}

	/**
	 * Adds the options to {@code group}, shown only while {@code visible} says so, with Through Walls on or off by
	 * default (off suits highlighting something you can see, like the block you're looking at).
	 */
	public HighlightSettings(SettingGroup group, Supplier<Boolean> visible, boolean throughWallsByDefault) {
		this(builder(group).visible(visible).defaults(STANDARD.withThroughWalls(throughWallsByDefault)));
	}

	/**
	 * The options for {@code group}, to set up before they're added: when they show, the look they start from, and
	 * whether Through Walls is offered.
	 *
	 * <pre>{@code
	 * private final HighlightSettings mining = HighlightSettings.builder(settings.group("Mining"))
	 *     .defaults(HighlightStyle.OUTLINE.withOutlineWidth(0).withFill(HighlightStyle.Fill.SOLID)).build();
	 * }</pre>
	 */
	public static Builder builder(SettingGroup group) {
		return new Builder(group);
	}

	private HighlightSettings(Builder b) {
		SettingGroup group = b.group;
		Supplier<Boolean> visible = b.visible;
		HighlightStyle d = b.defaults;
		outlineWidth = group.decimal("Outline Width").description("In pixels; 0 for no outline.")
			.defaultValue(snap(Math.min(d.outlineWidth(), 6), 1)).range(0, 6).decimals(1).visible(visible).build();
		glow = group.decimal("Glow").description("A soft glow beyond the outline, in pixels; 0 for none.")
			.defaultValue(snap(Math.min(d.glow(), 24), 0)).range(0, 24).decimals(0).visible(visible).build();
		fill = group.oneOf("Fill", fillMode(d.fill())).description("What's drawn inside the outline.").visible(visible).build();
		fillOpacity = group.decimal("Fill Opacity").defaultValue(snap(d.fillOpacity(), 2)).range(0, 1).decimals(2)
			.visible(() -> visible.get() && fill.get() != FillMode.NONE).build();
		dotSpacing = group.decimal("Dot Spacing").description("Distance between dots, in pixels.")
			.defaultValue(snap(Math.min(d.dotSpacing(), 24), 1)).range(2, 24).decimals(1).visible(() -> visible.get() && fill.get() == FillMode.DOTS).build();
		dotSize = group.decimal("Dot Size").description("Dot diameter, in pixels.")
			.defaultValue(snap(Math.min(d.dotSize(), 12), 1)).range(0.5, 12).decimals(1).visible(() -> visible.get() && fill.get() == FillMode.DOTS).build();
		gradient = group.bool("Gradient").description("Fade to a second colour towards the bottom.").defaultValue(d.hasGradient()).visible(visible).build();
		gradientColor = group.color("Gradient Color")
			.defaultValue(d.hasGradient() ? SettingColor.of(d.gradient()) : SettingColor.role(SettingColor.Mode.SECONDARY))
			.visible(() -> visible.get() && gradient.get()).build();
		defaultThroughWalls = d.throughWalls();
		throughWalls = b.throughWallsOption ? group.bool("Through Walls").defaultValue(d.throughWalls()).visible(visible).build() : null;
	}

	/** {@code v} rounded to the setting's decimals, so a float default saves and compares as the number it reads as. */
	private static double snap(float v, int decimals) {
		double f = Math.pow(10, decimals);
		return Math.round(v * f) / f;
	}

	/** The built-in fill closest to {@code fill}: an addon's own isn't one of the choices, so it starts from none. */
	private static FillMode fillMode(HighlightStyle.Fill fill) {
		for (FillMode m : FillMode.values()) if (m.fill == fill) return m;
		return FillMode.NONE;
	}

	/** The style these settings describe; rebuilt at most once a frame (theme and rainbow colours change over time). */
	public HighlightStyle style() {
		long frame = Myriad.render().frame();
		if (style == null || builtFrame != frame) {
			builtFrame = frame;
			HighlightStyle s = HighlightStyle.OUTLINE
				.withOutlineWidth(outlineWidth.getFloat())
				.withGlow(glow.getFloat())
				.withFill(fill.get().fill)
				.withFillOpacity(fillOpacity.getFloat())
				.withDots(dotSpacing.getFloat(), dotSize.getFloat())
				.withThroughWalls(throughWalls == null ? defaultThroughWalls : throughWalls.get());
			style = gradient.get() ? s.withGradient(gradientColor.argb()) : s;
		}
		return style;
	}

	/** Sets up {@link HighlightSettings} before its options are added; see {@link #builder}. */
	public static final class Builder {
		private final SettingGroup group;
		private Supplier<Boolean> visible = () -> true;
		private HighlightStyle defaults = STANDARD;
		private boolean throughWallsOption = true;

		private Builder(SettingGroup group) {
			this.group = group;
		}

		/** Shows the options only while {@code visible} says so (e.g. in one mode of a module, or while a toggle is on). */
		public Builder visible(Supplier<Boolean> visible) {
			this.visible = visible;
			return this;
		}

		/**
		 * The look the options start from (by default a 2 px outline, through walls, as the constructors give): its
		 * outline, glow, fill, fill opacity, dots, gradient and through walls. An addon's own fill isn't one of the choices: it starts from none.
		 */
		public Builder defaults(HighlightStyle defaults) {
			this.defaults = defaults == null ? STANDARD : defaults;
			return this;
		}

		/**
		 * Leaves out Through Walls, for highlights where it means nothing (the first-person hand, which nothing is ever
		 * in front of): the style keeps the default's.
		 */
		public Builder noThroughWalls() {
			throughWallsOption = false;
			return this;
		}

		/** Adds the options to the group. */
		public HighlightSettings build() {
			return new HighlightSettings(this);
		}
	}
}
