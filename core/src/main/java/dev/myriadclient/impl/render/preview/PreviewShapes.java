package dev.myriadclient.impl.render.preview;

import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.impl.render.ShapeLayer;

/** The shapes a preview's decorations draw, in the subject's coordinates: depth-tested, and through walls on top. */
final class PreviewShapes extends ShapeBuilder {
	final ShapeLayer depth = new ShapeLayer(), xray = new ShapeLayer();

	@Override
	protected void emitQuad(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz,
							float cx, float cy, float cz, float dx, float dy, float dz, int color) {
		(throughWalls ? xray : depth).quad(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, color);
	}

	@Override
	protected void emitLine(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz, int fromColor, int toColor, float width) {
		(throughWalls ? xray : depth).line(ax, ay, az, bx, by, bz, fromColor, toColor, width);
	}
}
