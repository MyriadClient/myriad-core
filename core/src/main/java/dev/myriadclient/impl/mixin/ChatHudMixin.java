package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ChatLines;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ChatReceiveEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
	@Unique
	private boolean myriad$readding;

	@Shadow
	private void addMessage(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag) {
	}

	@Inject(method = "addMessageToQueue", at = @At("HEAD"))
	private void myriad$lineAdded(GuiMessage line, CallbackInfo ci) {
		ChatLines.onLineAdded(line);
	}

	@Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
	private void myriad$receive(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag indicator, CallbackInfo ci) {
		if (myriad$readding || !Myriad.isReady() || !Myriad.events().hasListeners(ChatReceiveEvent.class)) return;
		ChatReceiveEvent event = Myriad.events().post(new ChatReceiveEvent(message));
		if (event.isCancelled()) {
			ci.cancel();
		} else if (event.message() != message) {
			ci.cancel();
			myriad$readding = true;
			try {
				addMessage(event.message(), signature, source, indicator);
			} finally {
				myriad$readding = false;
			}
		}
	}
}
