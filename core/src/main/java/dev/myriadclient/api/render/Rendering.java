package dev.myriadclient.api.render;

import dev.myriadclient.api.event.events.Render3DEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What the rendering helpers ({@link Renderer3D}, {@link WorldMesh}, {@link HighlightStyle}, {@link Ghosts},
 * {@link RenderStates}, {@link RenderLayers}, {@code HighlightEvent}) draw through, so the API needs no part of core's
 * renderer to load (a test harness supplies its own). Use those helpers; this is core's.
 */
@ApiStatus.Internal
public interface Rendering {
	/** The render types {@link RenderLayers} hands out. */
	enum Layer {
		LINES, QUADS, MODEL
	}

	/** This frame's shape queue. */
	ShapeBuilder shapes();

	/** A line from the centre of the screen to {@code to}, this frame. */
	void tracer(Vec3 to, int color);

	void highlightBox(AABB box, HighlightStyle style, int color, boolean below);

	void highlightBlock(BlockPos pos, HighlightStyle style, int color, double inflate, boolean below);

	void highlightMesh(WorldMesh mesh, HighlightStyle style, int[] palette, boolean below);

	/** The frame count, for work done at most once a frame. */
	long frame();

	/** Compiles a custom highlight fill's shader under {@code shaderId}. */
	void registerFill(int shaderId, Identifier shader);

	void ghost(Render3DEvent event, LivingEntityRenderState state, Vec3 feet, float alpha, @Nullable HighlightStyle style, int color);

	@Nullable Entity entity(EntityRenderState state);

	RenderType layer(Layer layer, boolean throughWalls);

	/** Draws {@code mesh} every frame while {@code shown}. */
	void showMesh(WorldMesh mesh, boolean shown);

	/** Frees a GPU buffer a few frames from now, once no frame in flight can still use it. */
	void freeLater(AutoCloseable buffer);
}
