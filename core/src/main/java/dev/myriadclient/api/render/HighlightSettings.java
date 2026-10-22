package dev.myriadclient.api.render;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.impl.render.HighlightRenderer;

import java.util.function.Supplier;

/**
 * The standard options for a {@link HighlightStyle}, so every module that highlights things offers the same ones:
 * outline width, glow, fill (none, solid or a dot grid), an optional gradient, and through walls.
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
	public final BoolSetting throughWalls;

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
		outlineWidth = group.decimal("Outline Width").description("In pixels; 0 for no outline.")
			.defaultValue(2).range(0, 6).decimals(1).visible(visible).build();
		glow = group.decimal("Glow").description("A soft glow beyond the outline, in pixels; 0 for none.")
			.defaultValue(0).range(0, 24).decimals(0).visible(visible).build();
		fill = group.oneOf("Fill", FillMode.NONE).description("What's drawn inside the outline.").visible(visible).build();
		fillOpacity = group.decimal("Fill Opacity").defaultValue(0.3).range(0, 1).decimals(2)
			.visible(() -> visible.get() && fill.get() != FillMode.NONE).build();
		dotSpacing = group.decimal("Dot Spacing").description("Distance between dots, in pixels.")
			.defaultValue(2.5).range(2, 24).decimals(1).visible(() -> visible.get() && fill.get() == FillMode.DOTS).build();
		dotSize = group.decimal("Dot Size").description("Dot diameter, in pixels.")
			.defaultValue(1).range(0.5, 12).decimals(1).visible(() -> visible.get() && fill.get() == FillMode.DOTS).build();
		gradient = group.bool("Gradient").description("Fade to a second colour towards the bottom.").visible(visible).build();
		gradientColor = group.color("Gradient Color").defaultValue(SettingColor.role(SettingColor.Mode.SECONDARY))
			.visible(() -> visible.get() && gradient.get()).build();
		throughWalls = group.bool("Through Walls").defaultValue(throughWallsByDefault).visible(visible).build();
	}

	/** The style these settings describe; rebuilt at most once a frame (theme and rainbow colours change over time). */
	public HighlightStyle style() {
		long frame = HighlightRenderer.INSTANCE.frame();
		if (style == null || builtFrame != frame) {
			builtFrame = frame;
			HighlightStyle s = HighlightStyle.OUTLINE
				.withOutlineWidth(outlineWidth.getFloat())
				.withGlow(glow.getFloat())
				.withFill(fill.get().fill)
				.withFillOpacity(fillOpacity.getFloat())
				.withDots(dotSpacing.getFloat(), dotSize.getFloat())
				.withThroughWalls(throughWalls.get());
			style = gradient.get() ? s.withGradient(gradientColor.argb()) : s;
		}
		return style;
	}
}
