package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ItemTooltipEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * ItemTooltipEvent: lines added to or changed in an item's tooltip, after vanilla has built them, wherever the tooltip
 * is shown.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void myriad$tooltip(Item.TooltipContext context, Player player, TooltipFlag type, CallbackInfoReturnable<List<Component>> cir) {
		if (Myriad.isReady() && Myriad.events().hasListeners(ItemTooltipEvent.class)) {
			Myriad.events().post(new ItemTooltipEvent((ItemStack) (Object) this, cir.getReturnValue(), type));
		}
	}
}
