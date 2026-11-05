package dev.myriadclient.impl.render.title;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the title screen's scene through a {@link TitleScenePicture}, let go of when the screen closes: it's the size of
 * the screen and twice its resolution, too much to keep through a whole session of playing.
 */
public final class TitleSceneRenderer extends PictureInPictureRenderer<TitleSceneState> {
	private static @Nullable TitleSceneRenderer instance;

	private @Nullable TitleScenePicture picture;

	public TitleSceneRenderer() {
		instance = this;
	}

	/** Frees the picture until the scene is next drawn. */
	public static void release() {
		if (instance != null && instance.picture != null) {
			instance.picture.close();
			instance.picture = null;
		}
	}

	@Override
	public void prepare(TitleSceneState state, GuiRenderState guiRenderState, FeatureRenderDispatcher dispatcher, int guiScale) {
		if (picture == null) picture = new TitleScenePicture();
		picture.prepare(state, guiRenderState, dispatcher, guiScale);
	}

	@Override
	public Class<TitleSceneState> getRenderStateClass() {
		return TitleSceneState.class;
	}

	@Override
	protected String getTextureLabel() {
		return "myriad title scenes";
	}

	@Override
	protected void renderToTexture(TitleSceneState state, PoseStack poseStack, SubmitNodeCollector collector) {
		// It draws through its picture (prepare).
	}

	@Override
	public void close() {
		super.close();
		release();
	}
}
