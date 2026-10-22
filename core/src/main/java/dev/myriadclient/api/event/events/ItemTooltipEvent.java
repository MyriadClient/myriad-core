package dev.myriadclient.api.event.events;

import dev.myriadclient.api.util.AnyThread;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * An item's tooltip lines were built. Add, remove or change {@link #lines()}; the first line is the item's name.
 * Usually on the render thread, but the creative search index builds tooltips on a background thread: only read the
 * item, and keep what you add independent of the world.
 */
@AnyThread
public final class ItemTooltipEvent {
	private final ItemStack stack;
	private final List<Component> lines;
	private final TooltipFlag type;

	@ApiStatus.Internal
	public ItemTooltipEvent(ItemStack stack, List<Component> lines, TooltipFlag type) {
		this.stack = stack;
		this.lines = lines;
		this.type = type;
	}

	public ItemStack stack() {
		return stack;
	}

	/** Mutable. */
	public List<Component> lines() {
		return lines;
	}

	/** Whether advanced tooltips (F3+H) are on. */
	public boolean advanced() {
		return type.isAdvanced();
	}
}
