package dev.myriadclient.impl.render.title;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The title screen's scene this frame: which one ({@code null} for you alone, as the menus' peek has it), {@code time} in
 * seconds, the camera's turn, tilt and roll and the head's in degrees, how far through a swing the arm is (0 for none),
 * the green of the grass, how visible the whole is, where on the picture the view's centre is ({@code across} from -1 its
 * left edge to 1 its right, {@code up} from -1 its bottom to 1 its top), the height it's centred on (blocks above the
 * floor's underside; you stand on it at 1), how many blocks fit up the picture, how far through your walk your legs are
 * and how hard they swing (Minecraft's walk animation; 0 standing), what's in your main hand, and whether frosted things
 * over it blur it (it's the frame's blur: not over the world, whose blur the pause menu's frost is).
 */
public record TitleSceneState(@Nullable TitleScene scene, float time, float yaw, float pitch, float roll, float headYaw, float headPitch, float swing, int green,
							  float alpha, float across, float up, float lookAt, float showHigh, float walk, float walkSpeed, ItemStack held, boolean frosted, int x0, int y0, int x1, int y1,
							  @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
	/** The height the title screen's view is centred on (a little above your head, so the scene's floor is in it), and how many blocks fit up it. */
	public static final float LOOK_AT = 1.9f, SHOW_HIGH = 4.2f;

	/** The view of it, before it knows where on screen it goes. */
	public record View(@Nullable TitleScene scene, float time, float yaw, float pitch, float roll, float headYaw, float headPitch, float swing, int green, float alpha,
					   float across, float up, float lookAt, float showHigh, float walk, float walkSpeed, ItemStack held, boolean frosted) {
	}

	public static TitleSceneState of(View v, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissor) {
		return new TitleSceneState(v.scene, v.time, v.yaw, v.pitch, v.roll, v.headYaw, v.headPitch, v.swing, v.green, v.alpha, v.across, v.up, v.lookAt,
			v.showHigh, v.walk, v.walkSpeed, v.held, v.frosted, x0, y0, x1, y1, scissor, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor));
	}

	@Override
	public float scale() {
		return 1;
	}
}
