package dev.myriadclient.impl.render;

import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.Rendering;
import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.api.render.WorldMesh;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** {@link Rendering}: the API's rendering helpers, onto core's renderers. */
public final class CoreRendering implements Rendering {
	@Override
	public ShapeBuilder shapes() {
		return WorldRenderQueue.INSTANCE;
	}

	@Override
	public void tracer(Vec3 to, int color) {
		WorldRenderQueue.INSTANCE.tracer(to, color);
	}

	@Override
	public void highlightBox(AABB box, HighlightStyle style, int color, boolean below) {
		HighlightRenderer.INSTANCE.box(box, style, color, below);
	}

	@Override
	public void highlightBlock(BlockPos pos, HighlightStyle style, int color, double inflate, boolean below) {
		HighlightRenderer.INSTANCE.block(pos, style, color, inflate, below);
	}

	@Override
	public void highlightMesh(WorldMesh mesh, HighlightStyle style, int[] palette, boolean below) {
		HighlightRenderer.INSTANCE.mesh(mesh, style, palette, below);
	}

	@Override
	public long frame() {
		return HighlightRenderer.INSTANCE.frame();
	}

	@Override
	public void registerFill(int shaderId, Identifier shader) {
		HighlightRenderer.registerFill(shaderId, shader);
	}

	@Override
	public void ghost(Render3DEvent event, LivingEntityRenderState state, Vec3 feet, float alpha, @Nullable HighlightStyle style, int color) {
		GhostRenderer.submit(event, state, feet, alpha, style, color);
	}

	@Override
	public @Nullable Entity entity(EntityRenderState state) {
		return ((EntityRenderStateAccess) state).myriad$entity();
	}

	@Override
	public RenderType layer(Layer layer, boolean throughWalls) {
		return switch (layer) {
			case LINES -> throughWalls ? MyriadPipelines.LINES_XRAY : MyriadPipelines.LINES;
			case QUADS -> throughWalls ? MyriadPipelines.QUADS_XRAY : MyriadPipelines.QUADS;
			case MODEL -> throughWalls ? MyriadPipelines.MODEL_XRAY : MyriadPipelines.MODEL;
		};
	}

	@Override
	public void showMesh(WorldMesh mesh, boolean shown) {
		if (shown) MeshRenderer.add(mesh);
		else MeshRenderer.remove(mesh);
	}

	@Override
	public void freeLater(AutoCloseable buffer) {
		GpuGarbage.close(buffer);
	}
}
