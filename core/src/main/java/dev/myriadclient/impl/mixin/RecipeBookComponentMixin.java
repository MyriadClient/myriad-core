package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.util.List;

/** The recipe book's own window, in place of its texture, and its tabs' bar beside it (see ThemedScreens). */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
	@Shadow
	@Final
	private List<RecipeBookTabButton> tabButtons;

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
	private void myriad$background(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
	                               int textureW, int textureH, Operation<Void> original) {
		int tabs = 0;
		for (RecipeBookTabButton tab : tabButtons) if (tab.visible) tabs++;
		if (!ThemedScreens.recipeBook(graphics, x, y, w, h, tabs)) original.call(graphics, pipeline, texture, x, y, u, v, w, h, textureW, textureH);
	}

	/** The tabs stand clear of the book, in a bar of their own, rather than tucked 5 pixels under it. */
	@ModifyConstant(method = "updateTabs", constant = @Constant(intValue = 30))
	private int myriad$tabsOut(int out) {
		return out + ThemedScreens.recipeTabsOut();
	}
}
