package com.example.myriadaddon.mixin;

import com.example.myriadaddon.modules.ChatTimestamps;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Addons ship mixins like any Fabric mod (see myriad-example.mixins.json). Keep them thin: hand off to the module and
 * let it decide. Prefix handler names with your mod id so they can't clash with other mods' mixins.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"), argsOnly = true)
	private Component example$timestamp(Component message) {
		return ChatTimestamps.decorate(message);
	}
}
