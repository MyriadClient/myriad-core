package dev.myriadclient.api.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Maps world positions to the screen using the camera of the last rendered frame. Use it from a
 * {@code Render2DEvent} handler to place labels over things in the world (nametags, logout spots, item names).
 * Screen coordinates are scaled GUI pixels, the same units as {@code Render2DEvent.canvas()}.
 */
public final class Projection {
	private static final Matrix4f view = new Matrix4f();
	private static final Matrix4f projection = new Matrix4f();
	private static Vec3 camera = Vec3.ZERO;
	private static boolean valid;

	private Projection() {
	}

	/** Core's: called after the world renders, with that frame's camera. */
	@ApiStatus.Internal
	public static void update(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3 cameraPos) {
		view.set(positionMatrix);
		projection.set(projectionMatrix);
		camera = cameraPos;
		valid = true;
	}

	/** The camera position of the last frame, in world coordinates; {@link Vec3#ZERO} before the first. */
	public static Vec3 camera() {
		return camera;
	}

	/**
	 * Projects {@code world} to the screen. Returns {@code (x, y, depth)} in scaled GUI pixels, or null when the point
	 * is behind the camera (or no frame has rendered yet). Points off to the side still project (with x/y outside the
	 * screen). The depth is the distance along the view, for sorting labels far to near or scaling them.
	 */
	public static @Nullable Vec3 toScreen(Vec3 world) {
		if (!valid) return null;
		Vector4f v = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1f);
		view.transform(v);
		projection.transform(v);
		if (v.w <= 0.05f) return null;
		float nx = v.x / v.w, ny = v.y / v.w;
		var window = Minecraft.getInstance().getWindow();
		double x = (nx * 0.5 + 0.5) * window.getGuiScaledWidth();
		double y = (0.5 - ny * 0.5) * window.getGuiScaledHeight();
		return new Vec3(x, y, v.w);
	}

	/**
	 * Whether a point from {@link #toScreen} lies on screen (with {@code margin} extra pixels around the edges); false
	 * for null, so the result of {@code toScreen} can be passed straight in.
	 */
	public static boolean onScreen(@Nullable Vec3 screen, double margin) {
		var window = Minecraft.getInstance().getWindow();
		return screen != null && screen.x >= -margin && screen.y >= -margin
			&& screen.x <= window.getGuiScaledWidth() + margin && screen.y <= window.getGuiScaledHeight() + margin;
	}
}
