package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.myriadclient.impl.render.UiRenderer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Frosted backgrounds blur whatever is under them, as CSS's backdrop-filter does: the UI renderer blurs the scene before
 * the GUI draws, and where a frosted background comes after something drawn under it (the HUD under the windows, a
 * window under another, a tooltip over a window), the GUI's drawing is split there and what's drawn so far blurred
 * again, only where those backgrounds are. (Widgets on a window's effect background show it blurred without this: see
 * UiRenderer's frost.)
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@Shadow
	@Final
	private List<?> draws;
	@Shadow
	private @Nullable RenderPipeline previousPipeline;

	/** What's been drawn since the last blur (GUI coordinates), and the draws to blur before. */
	@Unique
	private final List<ScreenRectangle> myriad$sinceBlur = new ArrayList<>();
	@Unique
	private final IntArrayList myriad$blurBefore = new IntArrayList();
	/** For each of those, where the frosted backgrounds that use it are (GUI coordinates). */
	@Unique
	private final List<ScreenRectangle> myriad$blurArea = new ArrayList<>();

	@Inject(method = "draw", at = @At("HEAD"))
	private void myriad$beforeDraw(CallbackInfo ci) {
		UiRenderer renderer = UiRenderer.peek();
		if (renderer != null) renderer.beforeGuiDraw();
	}

	@Inject(method = "prepare", at = @At("HEAD"))
	private void myriad$prepare(CallbackInfo ci) {
		myriad$sinceBlur.clear();
		myriad$blurBefore.clear();
		myriad$blurArea.clear();
	}

	/** A frosted background over something drawn since the last blur starts a draw of its own, blurred before. */
	@Inject(method = "addElementToMesh", at = @At("HEAD"))
	private void myriad$element(GuiElementRenderState element, CallbackInfo ci) {
		List<ScreenRectangle> frosted = UiRenderer.backdrops(element);
		if (frosted != null) {
			if (myriad$over(frosted)) {
				// A new draw from here (vanilla merges an element into the last draw when nothing about it changed).
				previousPipeline = null;
				myriad$blurBefore.add(draws.size());
				myriad$blurArea.add(frosted.getFirst());
				myriad$sinceBlur.clear();
			}
			// What the last blur has to cover (the frame's first, before the GUI, covers the whole screen).
			int last = myriad$blurArea.size() - 1;
			if (last >= 0) for (ScreenRectangle f : frosted) myriad$blurArea.set(last, myriad$union(myriad$blurArea.get(last), f));
		}
		// A shadow under a frosted background needn't be blurred again for it (a window's falls on its neighbours).
		ScreenRectangle bounds = UiRenderer.solidBounds(element);
		if (bounds != null) myriad$sinceBlur.add(bounds);
	}

	@Unique
	private static ScreenRectangle myriad$union(ScreenRectangle a, ScreenRectangle b) {
		int left = Math.min(a.left(), b.left()), top = Math.min(a.top(), b.top());
		return new ScreenRectangle(left, top, Math.max(a.right(), b.right()) - left, Math.max(a.bottom(), b.bottom()) - top);
	}

	@Unique
	private boolean myriad$over(List<ScreenRectangle> frosted) {
		for (ScreenRectangle f : frosted) {
			for (ScreenRectangle drawn : myriad$sinceBlur) {
				if (f.left() < drawn.right() && drawn.left() < f.right() && f.top() < drawn.bottom() && drawn.top() < f.bottom()) return true;
			}
		}
		return false;
	}

	/** Draws the range in parts, blurring what's drawn so far before each frosted background that needs it. */
	@WrapOperation(method = "draw", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;II)V"))
	private void myriad$drawRange(GuiRenderer self, Supplier<String> label, RenderTarget target, GpuBufferSlice transforms, int start, int end,
		Operation<Void> original) {
		UiRenderer renderer = UiRenderer.peek();
		int from = start;
		for (int i = 0; renderer != null && i < myriad$blurBefore.size(); i++) {
			int at = myriad$blurBefore.getInt(i);
			if (at < start || at >= end || at == 0) continue;
			if (at > from) original.call(self, label, target, transforms, from, at);
			from = at;
			renderer.blurAgain(myriad$blurArea.get(i));
		}
		if (end > from) original.call(self, label, target, transforms, from, end);
	}
}
