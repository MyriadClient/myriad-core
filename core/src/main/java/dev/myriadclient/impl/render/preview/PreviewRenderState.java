package dev.myriadclient.impl.render.preview;

import dev.myriadclient.api.ui.widget.Preview;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * A preview as the GUI renders it this frame: the subject, the view (turned, tipped, moved in GUI units), the
 * decorations' shapes collected for it, and where on screen. {@code scale} is GUI units per block, to fit the subject.
 */
record PreviewRenderState(Preview.Subject subject, AABB box, float yaw, float pitch, float panX, float panY, float zoom, PreviewShapes shapes,
						  int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
	implements PictureInPictureRenderState {

	static PreviewRenderState of(Preview preview, int x0, int y0, int x1, int y1, float unitsToGui, @Nullable ScreenRectangle scissor) {
		Preview.Subject subject = preview.subject();
		AABB box = PreviewSubjects.box(subject);
		PreviewShapes shapes = new PreviewShapes();
		var entity = PreviewSubjects.entity(subject);
		for (Preview.Decoration d : preview.decorations()) d.draw(shapes, box, entity);
		// Fits the subject's largest extent (an item: the cube's diagonal) in the smaller side, with a margin.
		double extent = subject instanceof Preview.ItemSubject ? 1.7 : Math.max(box.getYsize(), Math.max(box.getXsize(), box.getZsize()) * 1.4);
		float fit = (float) (Math.min(x1 - x0, y1 - y0) * 0.8 / Math.max(0.1, extent)) * preview.zoom();
		return new PreviewRenderState(subject, box, preview.yaw(), preview.pitch(), preview.panX() * unitsToGui, preview.panY() * unitsToGui,
			preview.zoom(), shapes, x0, y0, x1, y1, fit, scissor, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor));
	}
}
