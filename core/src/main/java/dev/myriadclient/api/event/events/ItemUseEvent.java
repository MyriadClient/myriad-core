package dev.myriadclient.api.event.events;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** The local player stopped using an item: food eaten, potion drunk, bow released. */
public abstract class ItemUseEvent {
	private final ItemStack stack;

	protected ItemUseEvent(ItemStack stack) {
		this.stack = stack;
	}

	/** A copy of the item as it was being used. */
	public ItemStack stack() {
		return stack;
	}

	/** The use completed (eating or drinking finished). */
	public static final class Finished extends ItemUseEvent {
		@ApiStatus.Internal
		public Finished(ItemStack stack) {
			super(stack);
		}
	}

	/** The use was let go early (releasing a bow or trident, stopping eating). */
	public static final class Stopped extends ItemUseEvent {
		@ApiStatus.Internal
		public Stopped(ItemStack stack) {
			super(stack);
		}
	}
}
