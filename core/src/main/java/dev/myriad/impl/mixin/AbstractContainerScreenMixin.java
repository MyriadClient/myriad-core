package dev.myriad.impl.mixin;

import dev.myriad.api.Myriad;
import dev.myriad.impl.service.InventoryManager;
import dev.myriad.api.event.events.ContainerScreenEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	protected @Nullable Slot hoveredSlot;

	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void myriad$tooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(ContainerScreenEvent.Tooltip.class)
			&& Myriad.events().post(new ContainerScreenEvent.Tooltip((AbstractContainerScreen<?>) (Object) this, hoveredSlot, graphics, mouseX, mouseY)).isCancelled()) ci.cancel();
	}

	/** Your inventory's slots show what masks keep them showing (see Inventory.mask). */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
	private net.minecraft.world.item.ItemStack myriad$shownSlot(net.minecraft.world.item.ItemStack stack, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Slot slot) {
		return shownIn(slot, stack);
	}

	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
	private net.minecraft.world.item.ItemStack myriad$shownTooltip(net.minecraft.world.item.ItemStack stack) {
		return hoveredSlot == null ? stack : shownIn(hoveredSlot, stack);
	}

	private static net.minecraft.world.item.ItemStack shownIn(Slot slot, net.minecraft.world.item.ItemStack stack) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (!Myriad.isReady() || player == null || slot.container != player.getInventory()) return stack;
		return Myriad.inventory().shown(slot.getContainerSlot());
	}

	@Inject(method = "extractSlot", at = @At("TAIL"))
	private void myriad$slot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(ContainerScreenEvent.SlotDrawn.class)) {
			Myriad.events().post(new ContainerScreenEvent.SlotDrawn((AbstractContainerScreen<?>) (Object) this, slot, graphics));
		}
	}

	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void myriad$click(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
		if (!Myriad.isReady()) return;
		if (Myriad.events().hasListeners(ContainerScreenEvent.Click.class)
			&& Myriad.events().post(new ContainerScreenEvent.Click((AbstractContainerScreen<?>) (Object) this, slot, slotId, button, input)).isCancelled()) {
			ci.cancel();
			return;
		}
		// While you move (Inventory Move), the click waits for a tick the server takes it on (see InventoryManager).
		int id = slot != null ? slot.index : slotId;
		if (Myriad.inventory() instanceof InventoryManager inventory
			&& inventory.holdScreenClick(((AbstractContainerScreen<?>) (Object) this).getMenu().containerId, id, button, input)) ci.cancel();
	}
}
