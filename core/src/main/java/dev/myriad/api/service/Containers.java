package dev.myriad.api.service;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Containers (chests, barrels, shulkers, furnaces, …): opening one at a position, reading it once its contents have
 * arrived, and moving items. Pair it with {@code Myriad.tasks()} for multi-step jobs, and listen to
 * {@code ContainerEvent} to see containers the player opens by hand.
 *
 * <pre>{@code
 * Myriad.containers().open(chestPos, 20).thenAccept(view -> {
 *     int slot = view.find(s -> s.isOf(Items.DIAMOND));
 *     if (slot != -1) view.quickMove(slot);
 *     view.close();
 * });
 * }</pre>
 *
 * Futures complete on the render thread, so it's safe to touch the game from them.
 * <p>
 * To move many items, {@link View#take} and {@link View#put} plan the clicks and check the result with the server:
 *
 * <pre>{@code
 * Myriad.containers().open(chestPos, 20)
 *     .thenCompose(view -> view.take(s -> s.is(Items.DIAMOND), Containers.Transfer.all()))
 *     .thenAccept(moved -> info("Took " + moved + " diamonds"));
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Containers {
	/** The open container, if one is open (the player's own inventory doesn't count). */
	Optional<View> current();

	/**
	 * Right-clicks the block at {@code pos} (on the nearest face in reach) and completes once the container's
	 * contents have arrived. Fails with a {@link java.util.concurrent.TimeoutException} after {@code timeoutTicks}, or
	 * an {@link IllegalStateException} if the block is out of reach. Opening another replaces a pending one.
	 */
	CompletableFuture<View> open(BlockPos pos, int timeoutTicks);

	/** Closes the open container (and its screen), if any. */
	void close();

	/**
	 * How much a {@link View#take} or {@link View#put} moves, and whether your hotbar takes part. Start from
	 * {@link #all()} or {@link #upTo(int)}.
	 */
	final class Transfer {
		private static final Transfer ALL = new Transfer(Integer.MAX_VALUE, true);

		private final int count;
		private final boolean hotbar;

		private Transfer(int count, boolean hotbar) {
			this.count = count;
			this.hotbar = hotbar;
		}

		/** Everything that matches, as far as there's room. */
		public static Transfer all() {
			return ALL;
		}

		/** At most {@code count} items. */
		public static Transfer upTo(int count) {
			return ALL.withCount(count);
		}

		public Transfer withCount(int count) {
			return new Transfer(Math.max(0, count), hotbar);
		}

		/**
		 * Leaves your hotbar out: what's taken only goes into the main inventory (put down by hand, two clicks a stack
		 * instead of a shift click's one), and what's put only comes from the main inventory.
		 */
		public Transfer withoutHotbar() {
			return new Transfer(count, false);
		}

		public int count() {
			return count;
		}

		/** Whether the hotbar takes part (it does unless {@link #withoutHotbar()}). */
		public boolean hotbar() {
			return hotbar;
		}
	}

	/** An open container. Slot numbers are the container's own (0 = its first slot), unless noted. */
	@ApiStatus.NonExtendable
	interface View {
		AbstractContainerMenu handler();

		int syncId();

		/** The block it was opened from, when known (it was opened by clicking a block just before). */
		@Nullable BlockPos pos();

		/** Whether the contents have arrived. Before that, slots read as empty. */
		boolean isLoaded();

		/** Whether this container is still the one open. */
		boolean isOpen();

		/** Number of the container's own slots (the player's inventory below them isn't counted). */
		int size();

		ItemStack stack(int slot);

		/** The container's own slots. */
		List<ItemStack> stacks();

		/** First container slot matching, or -1. */
		int find(Predicate<ItemStack> predicate);

		/** First empty container slot, or -1 if it's full. */
		int emptySlot();

		/** Total count of matching items in the container. */
		int count(Predicate<ItemStack> predicate);

		/** The screen slot id for an inventory index (0-35) in this container's screen. */
		int playerSlot(int inventoryIndex);

		// The clicks below do nothing on a tick the server would refuse them (while you move, see
		// Inventory.safeToClick); your keys are then released for the next tick, so a click asked for again then works.
		// Check Inventory.prepareClick() before a run of clicks that belong together.

		/** Shift-clicks a container slot: moves its stack into your inventory. */
		void quickMove(int slot);

		/** Shift-clicks your inventory index (0-35): moves that stack into the container. */
		void quickMoveFromPlayer(int inventoryIndex);

		/** Swaps a container slot with a hotbar slot (0-8), like pressing a number key over it. */
		void swapWithHotbar(int slot, int hotbarSlot);

		/** Drops a container slot's stack (or one item). */
		void drop(int slot, boolean wholeStack);

		/** Any click, with a raw screen slot id. */
		void click(int screenSlot, int button, ContainerInput action);

		/**
		 * Moves items {@code what} accepts from the container into your inventory, as many as {@code how} allows and there
		 * is room for, and completes with how many went by the server's count. Whole stacks go with a shift click; part
		 * of one is picked up and shared out (onto stacks of the same item first), and the cursor is empty again
		 * after each stack. The clicks wait for ticks the server takes them on, within the packet budget
		 * ({@link PacketLimits}), so a large move takes a few seconds. Once the planned clicks are made, the server is
		 * asked what it has ({@link #refresh()}); a click it never took or a slot someone else changed meanwhile gets
		 * another round, up to three.
		 * <p>
		 * Moves on one container run one after another. It fails if the container closes, or another click (yours, or a
		 * module's) moves the cursor's items while it runs.
		 */
		CompletableFuture<Integer> take(Predicate<ItemStack> what, Transfer how);

		/** Like {@link #take}, the other way: items {@code what} accepts from your inventory into the container. */
		CompletableFuture<Integer> put(Predicate<ItemStack> what, Transfer how);

		/**
		 * Asks the server for the container's contents (and your inventory's) and completes once they're here: what the
		 * client shows is then what the server has (see {@link Inventory#refresh()}).
		 */
		CompletableFuture<View> refresh();

		void close();
	}
}
