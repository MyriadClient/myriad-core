package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ChatReceiveEvent;
import dev.myriadclient.impl.ChatLines;
import dev.myriadclient.impl.ui.ThemedChat;
import dev.myriadclient.impl.ui.ThemedText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
	@Unique
	private boolean myriad$readding;
	/** The text of the last message, the message it was added as, and how many times it came in a row. */
	@Unique
	private @Nullable String myriad$repeatOf;
	@Unique
	private @Nullable GuiMessage myriad$repeated;
	@Unique
	private int myriad$repeats;

	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	private void refreshTrimmedMessages() {
	}

	@Shadow
	private void addMessage(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag) {
	}

	/** Chat's lines split, and so drawn, in the theme's font while it's chat's (see ThemedChat); the message itself is kept as it came. */
	@WrapOperation(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
	private List<FormattedCharSequence> myriad$themeFont(GuiMessage message, Font font, int width, Operation<List<FormattedCharSequence>> original) {
		if (!ThemedChat.themeFont()) return original.call(message, font, width);
		return original.call(new GuiMessage(message.addedTime(), ThemedText.styled(message.content()), message.signature(), message.source(), message.tag()), font, width);
	}

	@Inject(method = "addMessageToQueue", at = @At("HEAD"))
	private void myriad$lineAdded(GuiMessage line, CallbackInfo ci) {
		ChatLines.onLineAdded(line);
	}

	/** As many messages kept as Theme → Chat → History says, where Minecraft keeps 100. */
	@ModifyConstant(method = {"addMessageToQueue", "addMessageToDisplayQueue"}, constant = @Constant(intValue = 100))
	private int myriad$history(int vanilla) {
		return ThemedChat.history();
	}

	/** Messages a filter is in aren't shown (Theme → Chat → Filters), as Minecraft's own filter hides others. */
	@ModifyExpressionValue(method = {"addMessage", "refreshTrimmedMessages"}, at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;visibleMessageFilter:Ljava/util/function/Predicate;"))
	private Predicate<GuiMessage> myriad$filters(Predicate<GuiMessage> vanilla) {
		return vanilla.and(ThemedChat::shown);
	}

	/**
	 * The same message again replaces the last, with how many times it came (Theme → Chat → Stack Repeats): the last
	 * one's lines are taken away and it's added again counted.
	 */
	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
	private Component myriad$stack(Component contents) {
		if (!ThemedChat.stackRepeats()) {
			myriad$repeatOf = null;
			return contents;
		}
		String text = contents.getString();
		if (text.equals(myriad$repeatOf) && !allMessages.isEmpty() && allMessages.getFirst() == myriad$repeated) {
			allMessages.removeFirst();
			refreshTrimmedMessages();
			return contents.copy().append(Component.literal(" (x" + ++myriad$repeats + ")").withStyle(ChatFormatting.GRAY));
		}
		myriad$repeatOf = text;
		myriad$repeats = 1;
		return contents;
	}

	/** The message just added, which a repeat replaces. */
	@Inject(method = "addMessageToQueue", at = @At("TAIL"))
	private void myriad$added(GuiMessage message, CallbackInfo ci) {
		myriad$repeated = message;
	}

	/** Chat drawing: its text shadow and line backgrounds follow Theme → Chat (ThemedChat). */
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("HEAD"), cancellable = true)
	private void myriad$drawing(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY, ChatComponent.DisplayMode mode, boolean cursor, CallbackInfo ci) {
		// Shown only while open: the HUD's chat (any mod's that draws it) isn't, the open chat screen's (foreground) is.
		if (!mode.foreground && ThemedChat.hiddenClosed()) {
			ci.cancel();
			return;
		}
		ThemedChat.drawing = true;
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("RETURN"))
	private void myriad$drawn(CallbackInfo ci) {
		ThemedChat.drawing = false;
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
