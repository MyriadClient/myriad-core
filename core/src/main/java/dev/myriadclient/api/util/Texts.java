package dev.myriadclient.api.util;

import dev.myriadclient.api.Myriad;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * Chat text that does something: run a Myriad command on click, fill the chat box, copy a value, show a tooltip.
 * Build messages from these and send them with {@code Myriad.chat(...)} or a command's {@code info(Text)}.
 *
 * <pre>{@code
 * info(Text.literal("Saved Home ").append(Texts.coords(pos)).append(" ").append(Texts.command("[remove]", "waypoint remove Home")));
 * }</pre>
 */
public final class Texts {
	private Texts() {
	}

	/** {@code label}, which runs the Myriad command {@code command} (without the prefix) when clicked. */
	public static MutableComponent command(String label, String command) {
		String full = Myriad.config().commandPrefix() + command;
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.RunCommand(full))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(full).withStyle(ChatFormatting.GRAY))));
	}

	/** {@code label}, which puts {@code command} (without the prefix) in the chat box to finish typing. */
	public static MutableComponent suggest(String label, String command) {
		String full = Myriad.config().commandPrefix() + command;
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.SuggestCommand(full))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to edit").withStyle(ChatFormatting.GRAY))));
	}

	/** {@code label}, which copies {@code value} to the clipboard when clicked. */
	public static MutableComponent copy(String label, String value) {
		return Component.literal(label).withStyle(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(value))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy").withStyle(ChatFormatting.GRAY))));
	}

	/** "x y z", copied when clicked. */
	public static MutableComponent coords(BlockPos pos) {
		String c = Format.coords(pos);
		return copy(c, c).withStyle(ChatFormatting.GRAY);
	}

	/** {@code text} with a tooltip. */
	public static MutableComponent hover(Component text, Component tooltip) {
		return text.copy().withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(tooltip)));
	}

	/** The item's name, showing its full tooltip on hover. */
	public static MutableComponent item(ItemStack stack) {
		return stack.getHoverName().copy().withStyle(s -> s.withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromStack(stack))));
	}

	/** {@code text} in an ARGB colour (e.g. a theme colour: {@code Myriad.ui().theme().accent.argb()}). */
	public static MutableComponent colored(String text, int argb) {
		return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
	}
}
