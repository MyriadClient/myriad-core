package dev.myriadclient.api.event.events;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.render.ShapeBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.jetbrains.annotations.ApiStatus;

/**
 * Fired every frame while the world's geometry is collected for drawing. Draw with {@link #shapes()} in world
 * coordinates; it is cleared after the frame. {@link #submits()} takes anything else vanilla can draw, such as entity
 * models (its positions are relative to the camera: translate by {@code -camera.position()}).
 * <p>
 * Anything that doesn't change every frame is cheaper as a {@code WorldMesh} or {@code ChunkCache}, which are drawn
 * without a handler.
 */
public final class Render3DEvent {
	private final PoseStack matrices;
	private final Camera camera;
	private final float tickDelta;
	private final SubmitNodeCollector submits;

	@ApiStatus.Internal
	public Render3DEvent(PoseStack matrices, Camera camera, float tickDelta, SubmitNodeCollector submits) {
		this.matrices = matrices;
		this.camera = camera;
		this.tickDelta = tickDelta;
		this.submits = submits;
	}

	public PoseStack matrices() {
		return matrices;
	}

	public Camera camera() {
		return camera;
	}

	public float tickDelta() {
		return tickDelta;
	}

	/** This frame's shapes (boxes, lines, block shapes), in world coordinates. */
	public ShapeBuilder shapes() {
		return Renderer3D.shapes();
	}

	public SubmitNodeCollector submits() {
		return submits;
	}
}
