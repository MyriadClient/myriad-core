package dev.myriadclient.api.render;

import dev.myriadclient.api.event.events.Render3DEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * What the rendering helpers ({@link Renderer3D}, {@link WorldMesh}, {@link HighlightStyle}, {@link Ghosts},
 * {@link RenderStates}, {@link RenderLayers}, {@code HighlightEvent}) draw through, so the API needs no part of core's
 * renderer to load (a test harness supplies its own). Use those helpers; this is core's.
 */
@ApiStatus.Internal
public interface Rendering {
	/** The render types {@link RenderLayers} hands out. */
	enum Layer {
		/** Core's: {@link RenderLayers#lines}. */
		LINES,
		/** Core's: {@link RenderLayers#quads}. */
		QUADS,
		/** Core's: {@link RenderLayers#flatModel}. */
		MODEL
	}

	/** This frame's shape queue. */
	ShapeBuilder shapes();

	/** A line from the centre of the screen to {@code to}, this frame. */
	void tracer(Vec3 to, int color);

	/** The render mode every module draws in: the player's choice. */
	RenderMode mode();

	/** The frame count, for work done at most once a frame. */
	long frame();

	/** Compiles a custom highlight fill's shader under {@code shaderId}. */
	void registerFill(int shaderId, Identifier shader);

	/** Core's: what {@link Ghosts#submit} draws through; {@code style} null for no highlight. */
	void ghost(Render3DEvent event, LivingEntityRenderState state, Vec3 feet, float alpha, @Nullable HighlightStyle style, int color);

	/** Core's: what {@link RenderStates#entity} reads. */
	@Nullable Entity entity(EntityRenderState state);

	/** Core's: what {@link RenderLayers} hands out. */
	RenderType layer(Layer layer, boolean throughWalls);

	/** Draws {@code mesh} every frame while {@code shown}. */
	void showMesh(WorldMesh mesh, boolean shown);

	/** Frees a GPU buffer a few frames from now, once no frame in flight can still use it. */
	void freeLater(AutoCloseable buffer);
}
