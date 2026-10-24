package dev.myriadclient.api.render;

import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.world.phys.AABB;

import java.util.Objects;

/**
 * How something shown through {@code RenderEvent} looks, in either {@link RenderMode}: the shader look, and a box's
 * fill, edges and through walls. A module's {@link RenderSettings} is one, from its Render options; {@link #of} makes a
 * fixed one in code, for a look the player doesn't set (a module's queue of blocks, say).
 */
public interface RenderLook {
	/** The look in {@link RenderMode#SHADER}. */
	HighlightStyle style();

	/** {@code color} for a box's faces in {@link RenderMode#BOX}, faded by {@code fade}; 0 for no faces. */
	int boxFill(int color, float fade);

	/** {@code color} for a box's edges in {@link RenderMode#BOX}, faded by {@code fade}; 0 for no edges. */
	int boxLine(int color, float fade);

	/** A box's edge width in {@link RenderMode#BOX}. */
	float lineWidth();

	boolean throughWalls();

	/** Draws {@code box} as a {@link RenderMode#BOX} box in this look onto {@code shapes} (sets its line width). */
	default void box(ShapeBuilder shapes, AABB box, int color, float fade) {
		int fillColor = boxFill(color, fade), lineColor = boxLine(color, fade);
		if (fillColor != 0) shapes.boxFill(box, fillColor, throughWalls());
		if (lineColor != 0) {
			shapes.lineWidth(lineWidth());
			shapes.boxLines(box, lineColor, throughWalls());
		}
	}

	/**
	 * Changes whenever something a kept mesh of boxes in this look depends on does: the mode, and the box's faces, edges
	 * and through walls ({@code ChunkCache} remeshes on it).
	 */
	default int meshStamp() {
		return Objects.hash(RenderMode.current(), boxFill(0xFFFFFFFF, 1), boxLine(0xFFFFFFFF, 1), lineWidth(), throughWalls());
	}

	/** A fixed look: {@code style} in Shader mode; in Box mode, its fill (flat, at its opacity) and edges 1.5 wide. */
	static RenderLook of(HighlightStyle style) {
		return of(style, 1.5f);
	}

	/** {@link #of(HighlightStyle)} with box edges {@code lineWidth} wide (0 for none). */
	static RenderLook of(HighlightStyle style, float lineWidth) {
		return new RenderLook() {
			@Override
			public HighlightStyle style() {
				return style;
			}

			@Override
			public int boxFill(int color, float fade) {
				if (style.fill() == HighlightStyle.Fill.NONE) return 0;
				return ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * style.fillOpacity() * fade));
			}

			@Override
			public int boxLine(int color, float fade) {
				return lineWidth <= 0 ? 0 : ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * fade));
			}

			@Override
			public float lineWidth() {
				return lineWidth;
			}

			@Override
			public boolean throughWalls() {
				return style.throughWalls();
			}
		};
	}
}
