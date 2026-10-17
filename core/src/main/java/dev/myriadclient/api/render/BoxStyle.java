package dev.myriadclient.api.render;

import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.world.phys.AABB;

import java.util.function.Supplier;

/**
 * The standard options for drawing boxes, so every highlight (entities, storage, blocks, holes) offers the same
 * ones: outline, fill or both, fill opacity, line width, and whether to draw through walls.
 *
 * <pre>{@code
 * private final BoxStyle style = new BoxStyle(settings.group("Render"));
 *
 * // in a Render3DEvent handler
 * style.draw(e.shapes(), box, color.argb(), 1);
 * }</pre>
 */
public final class BoxStyle {
	public final EnumSetting<Renderer3D.ShapeMode> shape;
	public final DoubleSetting fillOpacity;
	public final DoubleSetting lineWidth;
	public final BoolSetting throughWalls;

	/** Adds the options to {@code group}. */
	public BoxStyle(SettingGroup group) {
		this(group, () -> true);
	}

	/** Adds the options to {@code group}, shown only while {@code visible} says so (e.g. in some modes of a module). */
	public BoxStyle(SettingGroup group, Supplier<Boolean> visible) {
		this(group, visible, true);
	}

	/** As {@link #BoxStyle(SettingGroup, Supplier)}, with Through Walls on or off by default. */
	public BoxStyle(SettingGroup group, Supplier<Boolean> visible, boolean throughWallsByDefault) {
		shape = group.enumSetting("Shape", Renderer3D.ShapeMode.BOTH).description("Outline, fill, or both.").visible(visible).build();
		fillOpacity = group.doubleSetting("Fill Opacity").defaultValue(0.2).range(0, 1).decimals(2)
			.visible(() -> visible.get() && shape.get() != Renderer3D.ShapeMode.LINES).build();
		lineWidth = group.doubleSetting("Line Width").defaultValue(1.5).range(0.5, 5).decimals(1)
			.visible(() -> visible.get() && shape.get() != Renderer3D.ShapeMode.FILL).build();
		throughWalls = group.bool("Through Walls").defaultValue(throughWallsByDefault).visible(visible).build();
	}

	/** {@code color} as a fill, at the fill opacity times {@code fade}. */
	public int fill(int color, float fade) {
		return ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * fillOpacity.get() * fade));
	}

	/** {@code color} as an outline, faded by {@code fade}. */
	public int line(int color, float fade) {
		return ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * fade));
	}

	/** Draws {@code box} in this style (sets the line width on {@code shapes}). */
	public void draw(ShapeBuilder shapes, AABB box, int color, float fade) {
		shapes.lineWidth(lineWidth.getFloat());
		shapes.box(box, fill(color, fade), line(color, fade), shape.get(), throughWalls.get());
	}
}
