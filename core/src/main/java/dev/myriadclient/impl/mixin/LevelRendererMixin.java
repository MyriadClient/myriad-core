package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.myriadclient.impl.render.GpuTimings;
import dev.myriadclient.impl.render.HighlightRenderer;
import dev.myriadclient.impl.render.MeshRenderer;
import dev.myriadclient.impl.render.MyriadPipelines;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/** Highlights (HighlightRenderer) take over vanilla's entity outline target in frames that have any; retained meshes draw here too. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	@Shadow
	@Final
	public RenderTarget entityOutlineTarget;
	/** Whether the outline pass ran this frame (anything was drawn with an outline), clearing the outline target first. */
	@Shadow
	private boolean currentFrameRendersEntityOutline;

	@Inject(method = "render", at = @At("HEAD"))
	private void myriad$highlightCamera(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
										Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepth, CallbackInfo ci) {
		HighlightRenderer.INSTANCE.camera(cameraState.pos, cameraState.viewRotationMatrix, cameraState.cullFrustum, levelRenderState.shouldShowEntityOutlines);
	}

	/** The outline target holds highlight ids, not colours: skip vanilla's glow post effect on it. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderManager;getPostChain(Lnet/minecraft/resources/Identifier;Ljava/util/Set;)Lnet/minecraft/client/renderer/PostChain;"))
	private @Nullable PostChain myriad$vanillaGlow(ShaderManager shaders, Identifier id, Set<Identifier> targets, Operation<PostChain> original) {
		return id.getPath().equals("entity_outline") && HighlightRenderer.INSTANCE.ownsOutlines() ? null : original.call(shaders, id, targets);
	}

	@Inject(method = "prepareTranslucents", at = @At("HEAD"))
	private void myriad$prepareMeshes(CallbackInfo ci) {
		MeshRenderer.prepare(levelRenderState.cameraRenderState);
	}

	/** Retained meshes (WorldMesh, ChunkCache) straight after the per-frame shapes, in the main pass. */
	@Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V", shift = At.Shift.AFTER))
	private void myriad$meshes(CallbackInfo ci, @Local(argsOnly = true) RenderPass pass) {
		MeshRenderer.draw(pass);
	}

	/** With improved transparency the frame has no one main pass by now: the meshes get their own. */
	@Inject(method = "executeOit", at = @At("TAIL"))
	private void myriad$meshesAfterOit(CallbackInfo ci) {
		MeshRenderer.drawAlone();
	}

	/** For .bench render: the outline pass, which draws highlighted silhouettes into the masks. */
	@Inject(method = "executeOutline", at = @At("HEAD"))
	private void myriad$beforeOutlines(CallbackInfo ci) {
		GpuTimings.begin(GpuTimings.Pass.MASKS);
	}

	/** Once the silhouettes are drawn, before translucent terrain (so water doesn't count as hiding what's under it). */
	@Inject(method = "executeOutline", at = @At("TAIL"))
	private void myriad$afterOutlines(CallbackInfo ci) {
		HighlightRenderer.INSTANCE.afterMask(currentFrameRendersEntityOutline);
	}

	/** The outline target gets a depth buffer, for HighlightPasses to tell which silhouettes the world hides. */
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/TextureTarget;<init>(Ljava/lang/String;IILcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V"), index = 4)
	private @Nullable GpuFormat myriad$outlineDepth(@Nullable GpuFormat depth) {
		return GpuFormat.D32_FLOAT;
	}

	@WrapOperation(method = "executeOutline", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass myriad$outlinePassDepth(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor, GpuTextureView depth,
											   OptionalDouble clearDepth, Operation<RenderPass> original) {
		return original.call(encoder, label, color, clearColor, entityOutlineTarget.getDepthTextureView(), OptionalDouble.of(0.0));
	}

	/** The silhouettes go into that depth buffer with the depth-tested outline pipelines; vanilla's own stay without depth. */
	@WrapOperation(method = "executeOutline", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeOutline(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void myriad$outlinePipelines(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, Operation<Void> original) {
		MyriadPipelines.withOutlineDepth(() -> original.call(frame, pass));
	}

	/** Vanilla blits its processed glow onto the screen here; highlights draw themselves instead. */
	@Inject(method = "blitEntityOutline", at = @At("HEAD"), cancellable = true)
	private void myriad$blitEntityOutline(CallbackInfo ci) {
		if (HighlightRenderer.INSTANCE.ownsOutlines()) ci.cancel();
	}
}
