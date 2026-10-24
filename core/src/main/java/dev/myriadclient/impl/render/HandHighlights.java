package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.ListenerFlag;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.impl.render.HighlightPasses.Layer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.function.Consumer;

/**
 * Highlights on your first-person hand and held items ({@link HighlightEvent.Hand}), drawn over the hand after the
 * world. They go in {@link HighlightRenderer}'s table after the world's, numbered from the first hand part, with a mask,
 * a buffer and a pass of their own: nothing hides the hand, so there's no depth to resolve.
 */
public final class HandHighlights {
	public static final HandHighlights INSTANCE = new HandHighlights();

	private final HighlightTable table = HighlightRenderer.INSTANCE.highlights;
	private final HighlightEvent.Hand handEvent = new HighlightEvent.Hand();
	private @Nullable ListenerFlag listeners;
	private @Nullable TextureTarget mask;
	/** Written after the world's data, in the same frame, so it's a buffer of its own. */
	private MappableRingBuffer handBuffer;

	private boolean drawingHand;
	/** A preview drawing your hands now (see {@link #previewHands}). */
	private @Nullable PreviewHighlights previewHands;
	/** The first hand part's id this frame, -1 while there's none. */
	private int handBase = -1;
	private final Matrix4f handProjection = new Matrix4f();
	private final Matrix4f handTransform = new Matrix4f();
	private final Vector4f handCorner = new Vector4f();
	/** The hand's screen area while its parts come in (min x, min y, max x, max y), and one part's. */
	private final float[] handArea = new float[4], partArea = new float[4];

	private HandHighlights() {
	}

	/** Start of a frame, from {@link HighlightRenderer#beginFrame}. */
	void beginFrame() {
		drawingHand = false;
		handBase = -1;
	}

	/** Whether anything listens for {@link HighlightEvent.Hand}. */
	boolean listening() {
		if (listeners == null) listeners = Myriad.events().flag(HighlightEvent.Hand.class);
		return listeners.isSet();
	}

	/**
	 * A preview is drawing your hands ({@code preview}), or has done (null): their parts are asked about as in the world,
	 * and highlighted into the preview's texture.
	 */
	public void previewHands(@Nullable PreviewHighlights preview) {
		previewHands = preview;
	}

	/**
	 * The first-person hand is about to be submitted, with {@code projection} (the hand's own field of view): its parts
	 * are asked about as they're submitted ({@link #hand}) while anything listens for {@link HighlightEvent.Hand}.
	 */
	public void beginHand(Matrix4fc projection) {
		drawingHand = HighlightRenderer.INSTANCE.isArmed() && listening();
		if (drawingHand) handProjection.set(projection);
	}

	/**
	 * A pass that drew the hand is over, but the hand's highlights aren't drawn yet: a shader pack's hand passes (Iris
	 * draws the hand inside the level) keep what they highlighted for {@link #endHand}, after the pack's image is done.
	 */
	public void pauseHand() {
		drawingHand = false;
	}

	/** Whether the hand is being submitted with highlights asked about (in the world, or a preview's). */
	public boolean drawingHand() {
		return drawingHand || previewHands != null && listening();
	}

	/**
	 * A part of the first-person hand is being submitted at {@code pose}: who highlights it, as the outline colour to
	 * submit it with (0 for nobody). {@code extents} visits points around its shape, relative to {@code pose}, for its
	 * screen area; it's only called when the part is highlighted.
	 */
	public int hand(HighlightEvent.Hand.Part part, InteractionHand hand, HumanoidArm side, ItemStack item, Matrix4fc pose,
					Consumer<Consumer<Vector3fc>> extents) {
		float tickDelta = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		if (previewHands != null) {
			if (!listening()) return 0;
			HighlightEvent.Hand e = Myriad.events().post(handEvent.reset(part, hand, side, item, tickDelta));
			return previewHands.add(e.style(), e.color());
		}
		if (!drawingHand) return 0;
		HighlightEvent.Hand e = Myriad.events().post(handEvent.reset(part, hand, side, item, tickDelta));
		if (e.style() == null || table.count >= HighlightRenderer.MAX_HIGHLIGHTS) return 0;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int w = main.width, h = main.height;
		if (handBase < 0) {
			handBase = table.count;
			handArea[0] = handArea[1] = Float.MAX_VALUE;
			handArea[2] = handArea[3] = -Float.MAX_VALUE;
			TextureTarget mask = this.mask;
			if (mask == null) mask = this.mask = new TextureTarget("Myriad highlight hand", w, h, true, GpuFormat.RGBA8_UNORM);
			else if (mask.width != w || mask.height != h) mask.resize(w, h);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(mask.getColorTexture(), new Vector4f(0), mask.getDepthTexture(), 0.0);
		}
		AABB none = new AABB(table.camX, table.camY, table.camZ, table.camX, table.camY, table.camZ);
		int id = HighlightRenderer.INSTANCE.add(HighlightRenderer.HAND, e.style(), e.color(), none, none);
		if (id < 0) return 0;
		// Where it lands on screen: the hand's projection, the model-view it's drawn with, then its pose.
		handProjection.mul(RenderSystem.getModelViewStack(), handTransform).mul(pose);
		float[] r = partArea;
		r[0] = r[1] = Float.MAX_VALUE;
		r[2] = r[3] = -Float.MAX_VALUE;
		extents.accept(v -> {
			handTransform.transform(handCorner.set(v.x(), v.y(), v.z(), 1));
			float cw = Math.max(handCorner.w, 1e-4f);
			float px = (handCorner.x / cw * 0.5f + 0.5f) * w, py = (handCorner.y / cw * 0.5f + 0.5f) * h;
			r[0] = Math.min(r[0], px);
			r[1] = Math.min(r[1], py);
			r[2] = Math.max(r[2], px);
			r[3] = Math.max(r[3], py);
		});
		// Nothing visited (a model without extents): the whole screen, as for something partly behind the camera.
		if (r[0] > r[2]) {
			r[0] = r[1] = 0;
			r[2] = w;
			r[3] = h;
		}
		System.arraycopy(r, 0, table.data, id * HighlightTable.FLOATS + 8, 4);
		handArea[0] = Math.min(handArea[0], r[0]);
		handArea[1] = Math.min(handArea[1], r[1]);
		handArea[2] = Math.max(handArea[2], r[2]);
		handArea[3] = Math.max(handArea[3], r[3]);
		return HighlightRenderer.encode(id - handBase, table.groupOf[id]);
	}

	/** The hand's mask: the outline target while the hand is drawn with highlights ({@link #handHighlighted}). */
	public RenderTarget handMask() {
		return mask;
	}

	/** Whether the hand is being drawn with highlights, so its outline pass goes into {@link #handMask}. */
	public boolean handHighlighted() {
		return drawingHand && handBase >= 0;
	}

	/**
	 * The hand is drawn: its highlights over it, from its own mask (spread, then composite; nothing hides the hand),
	 * including any a shader pack's hand passes collected earlier in the frame ({@link #pauseHand}).
	 */
	public void endHand() {
		drawingHand = false;
		if (handBase < 0) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int w = main.width, h = main.height;
		int x0 = Math.max(0, (int) Math.floor(handArea[0]) - 1), y0 = Math.max(0, (int) Math.floor(handArea[1]) - 1);
		int x1 = Math.min(w, (int) Math.ceil(handArea[2]) + 1), y1 = Math.min(h, (int) Math.ceil(handArea[3]) + 1);
		if (x1 <= x0 || y1 <= y0) return;
		Layer layer = HighlightRenderer.INSTANCE.layers[HighlightRenderer.HAND];
		layer.area = new int[]{x0, y0, x1, y1};
		layer.anyDepthTested = false;
		float[] widths = table.fillData(handBase, table.count);
		int radius = Math.min(HighlightRenderer.MAX_RADIUS, (int) Math.ceil(widths[0]) + 1);
		int boundary = Math.min(radius, (int) Math.ceil(widths[1]) + 1);
		handBuffer = HighlightPasses.ring(handBuffer, "Myriad hand highlights", table.count - handBase);
		GpuBuffer gpuData = HighlightPasses.write(handBuffer, radius, boundary, table.data, handBase, table.count);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuTimings.begin(GpuTimings.Pass.HAND);
		HighlightPasses.prepare(encoder, layer, mask, main, gpuData, radius, boundary, w, h);
		if (layer.prepared) {
			layer.prepared = false;
			HighlightPasses.composite(encoder, layer, main, gpuData);
		}
		GpuTimings.end(GpuTimings.Pass.HAND);
	}
}
