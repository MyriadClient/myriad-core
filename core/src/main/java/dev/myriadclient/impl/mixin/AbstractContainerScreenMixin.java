package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ContainerScreenEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.service.InventoryManager;
import dev.myriadclient.impl.ui.ThemedScreens;
import dev.myriadclient.impl.util.ScreenSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	protected @Nullable Slot hoveredSlot;

	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void myriad$tooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(ContainerScreenEvent.Tooltip.class)
			&& Myriad.events().post(new ContainerScreenEvent.Tooltip((AbstractContainerScreen<?>) (Object) this, hoveredSlot, graphics, mouseX, mouseY)).isCancelled()) ci.cancel();
	}

	/**
	 * You closing a screen yourself: the boxes standing go back (ContainerTabs), and a held container's stays open on the
	 * server (Containers.hold), no close going out.
	 */
	@WrapOperation(method = "onClose", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;closeContainer()V"))
	private void myriad$keepHeld(LocalPlayer player, Operation<Void> original) {
		AbstractContainerMenu menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
		// You closed it yourself: the boxes standing go back (see ContainerTabs).
		if (Myriad.isReady()) MyriadImpl.get().containerTabs().userClosed(menu);
		if (!Myriad.isReady() || !MyriadImpl.get().containerTracker().keepOpen(menu)) original.call(player);
	}

	/** The swap bar over a screen not drawn in the theme, under what's on the cursor (see ThemedScreens). */
	@Inject(method = "extractCarriedItem", at = @At("HEAD"))
	private void myriad$plainTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		ThemedScreens.plainTabs((AbstractContainerScreen<?>) (Object) this, graphics);
	}

	/** A click on the swap bar switches to that tab (see ThemedScreens). */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void myriad$tab(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (ThemedScreens.tabClicked((AbstractContainerScreen<?>) (Object) this, event.x(), event.y(), event.button())) cir.setReturnValue(true);
	}

	/** The title and inventory labels of a screen drawn in the theme: the themed window draws them (see ThemedScreens). */
	@WrapOperation(method = "extractLabels", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void myriad$label(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, boolean shadow, Operation<Void> original) {
		if (!ThemedScreens.themed((AbstractContainerScreen<?>) (Object) this)) original.call(graphics, font, text, x, y, color, shadow);
	}

	/** The slot under the mouse, lit in the theme on a screen drawn in it (see ThemedScreens). */
	@Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true)
	private void myriad$highlightBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (ThemedScreens.highlight((AbstractContainerScreen<?>) (Object) this, hoveredSlot, graphics)) ci.cancel();
	}

	@Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true)
	private void myriad$highlightFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (ThemedScreens.themed((AbstractContainerScreen<?>) (Object) this)) ci.cancel();
	}

	/** Your inventory's slots show what masks keep them showing (see Inventory.mask). */
	@ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
	private ItemStack myriad$shownSlot(ItemStack stack, @Local(argsOnly = true) Slot slot) {
		return shownIn(slot, stack);
	}

	@ModifyExpressionValue(method = "extractTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownTooltip(ItemStack stack) {
		return hoveredSlot == null ? stack : shownIn(hoveredSlot, stack);
	}

	/** What a slot shows: a running sort's result (see ContainerTracker.shown), else what masks keep your inventory's showing. */
	private ItemStack shownIn(Slot slot, ItemStack stack) {
		var player = Minecraft.getInstance().player;
		if (!Myriad.isReady() || player == null) return stack;
		ItemStack sorted = MyriadImpl.get().containerTracker().shown(((AbstractContainerScreen<?>) (Object) this).getMenu(), slot.index);
		if (sorted != null) return sorted;
		Slot real = ScreenSlots.real(slot);
		if (real.container != player.getInventory()) return stack;
		return Myriad.inventory().shown(real.getContainerSlot());
	}

	/** Nothing on the cursor while a sort shows its result: what its clicks carry would flicker across the screen. */
	@ModifyExpressionValue(method = "extractCarriedItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;getCarried()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownCarried(ItemStack stack) {
		return Myriad.isReady() && MyriadImpl.get().containerTracker().showsFinished(((AbstractContainerScreen<?>) (Object) this).getMenu()) ? ItemStack.EMPTY : stack;
	}

	@Inject(method = "extractSlot", at = @At("TAIL"))
	private void myriad$slot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(ContainerScreenEvent.SlotDrawn.class)) {
			Myriad.events().post(new ContainerScreenEvent.SlotDrawn((AbstractContainerScreen<?>) (Object) this, slot, graphics));
		}
	}

	/**
	 * A mouse button let go while an arrangement (a sort) runs here does nothing: vanilla clicks the slot under the mouse
	 * on a release whenever the cursor holds something, to put it down, and while a sort runs it's the sort's (shown
	 * empty). That click would drop it there, and count as yours (stopping the sort): the release of the right-click
	 * that opened the chest, or of a middle-click sort.
	 */
	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void myriad$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (Myriad.isReady() && MyriadImpl.get().containerTracker().arranging(((AbstractContainerScreen<?>) (Object) this).getMenu())) cir.setReturnValue(true);
	}

	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void myriad$click(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
		if (!Myriad.isReady()) return;
		// Aimed at a sort's result before it's real: the sort stops and the click is dropped.
		if (MyriadImpl.get().containerTracker().yourClick(((AbstractContainerScreen<?>) (Object) this).getMenu())) {
			ci.cancel();
			return;
		}
		if (Myriad.events().hasListeners(ContainerScreenEvent.Click.class)
			&& Myriad.events().post(new ContainerScreenEvent.Click((AbstractContainerScreen<?>) (Object) this, slot, slotId, button, input)).isCancelled()) {
			ci.cancel();
			return;
		}
		// While you move (Inventory Move), the click waits for a tick the server takes it on (see InventoryManager).
		int id = slot != null ? slot.index : slotId;
		if (MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory
			&& inventory.holdScreenClick(((AbstractContainerScreen<?>) (Object) this).getMenu().containerId, id, button, input)) ci.cancel();
	}
}
