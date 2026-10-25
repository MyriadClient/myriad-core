package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.api.util.Slots;
import dev.myriadclient.impl.ui.ThemedHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hotbar, the selected item's name and the armour bar show what masks and borrows keep them showing (see
 * Inventory.mask); the hotbar and the experience level are drawn in the theme while it's on (see ThemedHud).
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownSlot(Inventory inventory, int slot, Operation<ItemStack> original) {
		return Myriad.isReady() && slot >= 0 && slot < 9 ? Myriad.inventory().shown(slot) : original.call(inventory, slot);
	}

	@ModifyExpressionValue(method = "extractItemHotbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getOffhandItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownOffhand(ItemStack stack) {
		return Myriad.isReady() ? Myriad.inventory().shown(Slots.OFF_HAND) : stack;
	}

	/** Whether this frame's hotbar and what sits on it were moved up off the screen's edge (see ThemedHud#MARGIN). */
	private boolean myriad$lifted;

	@Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"))
	private void myriad$lift(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		myriad$lifted = ThemedHud.on();
		if (myriad$lifted) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(0, -ThemedHud.MARGIN);
		}
	}

	@Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
	private void myriad$lower(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (myriad$lifted) graphics.pose().popMatrix();
		myriad$lifted = false;
	}

	/** The hotbar's pieces, drawn in the theme while it's on (see ThemedHud). */
	@WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$hotbarSprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedHud.hotbar(graphics, sprite, x, y)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	/** Hearts and food, each icon in the theme where Minecraft draws it (its wave, shake and flashing are Minecraft's own). */
	@WrapOperation(method = {"extractHeart", "extractFood"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$statusIcon(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedHud.statusIcon(graphics, sprite, x, y)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@WrapOperation(method = "extractArmor", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private static void myriad$armorIcon(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedHud.statusIcon(graphics, sprite, x, y)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
	private void myriad$level(GuiGraphicsExtractor graphics, Font font, int level, Operation<Void> original) {
		if (!ThemedHud.level(graphics, level)) original.call(graphics, font, level);
	}

	@ModifyExpressionValue(method = "extractArmor", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getArmorValue()I"))
	private static int myriad$shownArmor(int armor) {
		return Myriad.isReady() ? Myriad.inventory().shownArmor() : armor;
	}

	/** Nor does the selected item's name flash up for a masked swap. */
	@ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownSelected(ItemStack stack) {
		var player = Minecraft.getInstance().player;
		return Myriad.isReady() && player != null ? Myriad.inventory().shown(player.getInventory().getSelectedSlot()) : stack;
	}
}
