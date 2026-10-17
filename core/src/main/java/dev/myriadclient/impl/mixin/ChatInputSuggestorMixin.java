package dev.myriadclient.impl.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.commands.SharedSuggestionProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.concurrent.CompletableFuture;

/** Feeds Myriad's dispatcher into vanilla chat autocompletion when the input starts with the command prefix. */
@Mixin(CommandSuggestions.class)
public abstract class ChatInputSuggestorMixin {
	@Shadow
	private ParseResults<SharedSuggestionProvider> currentParse;
	@Shadow
	@Final
	EditBox input;
	@Shadow
	boolean keepSuggestions;
	@Shadow
	@Nullable
	private CommandSuggestions.SuggestionsList suggestions;
	@Shadow
	@Nullable
	private CompletableFuture<Suggestions> pendingSuggestions;

	@Shadow
	private boolean currentParseIsCommand;
	@Shadow
	private boolean currentParseIsMessage;

	/** Takes the parse and its suggestions since 26.2; ours are typed to Myriad's source, so pass them raw. */
	@SuppressWarnings("rawtypes")
	@Shadow
	protected abstract void updateUsageInfo(ParseResults currentParse, Suggestions suggestions);

	@Inject(method = "updateCommandInfo", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;canRead()Z", remap = false),
		cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
	private void myriad$refresh(CallbackInfo ci, String input, StringReader reader) {
		if (!Myriad.isReady()) return;
		String prefix = Myriad.config().commandPrefix();
		if (!reader.getString().startsWith(prefix, reader.getCursor())) return;
		reader.setCursor(reader.getCursor() + prefix.length());
		var commands = MyriadImpl.get().commandManager();
		if (currentParse == null) currentParse = commands.dispatcher().parse(reader, commands.source());
		// Myriad's commands run on the client, so vanilla's "commands/messages not allowed" notes don't apply.
		currentParseIsCommand = false;
		currentParseIsMessage = false;
		int cursor = this.input.getCursorPosition();
		if (cursor >= prefix.length() && (suggestions == null || !keepSuggestions)) {
			pendingSuggestions = commands.dispatcher().getCompletionSuggestions(currentParse, cursor);
			ParseResults<SharedSuggestionProvider> parse = currentParse;
			pendingSuggestions.thenAccept(result -> {
				if (pendingSuggestions.isDone()) updateUsageInfo(parse, result);
			});
		}
		ci.cancel();
	}
}
