package dev.myriadclient.impl.render.title;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jetbrains.annotations.Nullable;

/**
 * The title screen's scene this frame: which one, {@code time} in seconds, the camera's turn and tilt and the head's in
 * degrees, how far through a swing the arm is (0 for none), the green of the grass, how visible the whole is, and how far
 * across the picture you stand (-1 its left edge, 0 its middle, 1 its right edge).
 */
public record TitleSceneState(TitleScene scene, float time, float yaw, float pitch, float headYaw, float headPitch, float swing, int green, float alpha, float across,
							  int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
	implements PictureInPictureRenderState {

	/** The view of it, before it knows where on screen it goes. */
	public record View(TitleScene scene, float time, float yaw, float pitch, float headYaw, float headPitch, float swing, int green, float alpha, float across) {
	}

	public static TitleSceneState of(View v, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissor) {
		return new TitleSceneState(v.scene, v.time, v.yaw, v.pitch, v.headYaw, v.headPitch, v.swing, v.green, v.alpha, v.across, x0, y0, x1, y1, scissor,
			PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor));
	}

	@Override
	public float scale() {
		return 1;
	}
}
