package dev.myriadclient.impl.render.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.ui.widget.Preview;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws {@link Preview}s in the GUI. The GUI keeps one renderer per kind of picture, and a renderer one texture, so
 * this hands each preview to a {@link PreviewPicture} of its own: several previews on screen at once each keep theirs.
 * A picture whose preview hasn't been drawn for a while is closed.
 */
public final class PreviewRenderer extends PictureInPictureRenderer<PreviewRenderState> {
	/** How long (ns) a picture is kept after its preview was last drawn. */
	private static final long KEEP = 2_000_000_000L;

	private final Map<Preview, PreviewPicture> pictures = new IdentityHashMap<>();

	/**
	 * What the GUI renders for {@code preview} at (x0, y0)-(x1, y1) in GUI units, clipped to {@code scissor};
	 * {@code unitsToGui} turns the preview's own units (its pan) into GUI units.
	 */
	public static PictureInPictureRenderState state(Preview preview, int x0, int y0, int x1, int y1, float unitsToGui, @Nullable ScreenRectangle scissor) {
		return PreviewRenderState.of(preview, x0, y0, x1, y1, unitsToGui, scissor);
	}

	@Override
	public void prepare(PreviewRenderState state, GuiRenderState guiRenderState, FeatureRenderDispatcher dispatcher, int guiScale) {
		long now = System.nanoTime();
		pictures.values().removeIf(p -> {
			if (now - p.used < KEEP) return false;
			p.close();
			return true;
		});
		PreviewPicture picture = pictures.computeIfAbsent(state.preview(), p -> new PreviewPicture());
		picture.used = now;
		picture.prepare(state, guiRenderState, dispatcher, guiScale);
	}

	@Override
	public Class<PreviewRenderState> getRenderStateClass() {
		return PreviewRenderState.class;
	}

	@Override
	protected String getTextureLabel() {
		return "myriad previews";
	}

	@Override
	protected void renderToTexture(PreviewRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		// Each preview draws through its own picture (prepare).
	}

	@Override
	public void close() {
		super.close();
		pictures.values().forEach(PreviewPicture::close);
		pictures.clear();
	}
}
