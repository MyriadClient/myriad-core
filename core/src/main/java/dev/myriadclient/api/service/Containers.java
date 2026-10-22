package dev.myriadclient.api.service;

import dev.myriadclient.api.util.ItemMatch;
import dev.myriadclient.api.util.RenderThread;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Containers (chests, barrels, shulkers, furnaces, …): opening one at a position, reading it once its contents have
 * arrived, and moving items. For work in several steps, start them as a job ({@code Steps.openContainer},
 * {@code take}, {@code closeContainer}), and listen to {@code ContainerEvent} to see containers the player opens by
 * hand.
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
@RenderThread
public interface Containers {
	/** The open container, if one is open (the player's own inventory doesn't count). */
	Optional<View> current();

	/**
	 * Right-clicks the block at {@code pos} (on the nearest face in reach) and completes once the container's
	 * contents have arrived. Fails with a {@link TimeoutException} after {@code timeoutTicks}, or
	 * an {@link IllegalStateException} if the block is out of reach. Opening another replaces a pending one.
	 */
	CompletableFuture<View> open(BlockPos pos, int timeoutTicks);

	/**
	 * {@link #open(BlockPos, int)} on behalf of {@code owner} (your module), which the container then reports as its
	 * {@link View#opener()}: modules that act on containers as they open (sorting, stealing) can leave other modules'
	 * alone.
	 */
	CompletableFuture<View> open(Object owner, BlockPos pos, int timeoutTicks);

	/**
	 * {@link #open(Object, BlockPos, int)} with the click made as {@code options} say, through
	 * {@link Placement#use}: with {@code Placement.Options.forServer()} on servers that check looks, the look turns
	 * onto the block first (Grim checks a click on a block as a placement). The timeout counts from the click.
	 */
	default CompletableFuture<View> open(Object owner, BlockPos pos, int timeoutTicks, Placement.Options options) {
		return open(owner, pos, timeoutTicks);
	}

	/**
	 * Opens the shulker box or ender chest at inventory index {@code inventoryIndex} (0-35) where you stand: places it
	 * beside you on the floor (sneaking, so a chest underneath isn't opened instead), opens it, and completes with the
	 * session once its contents have arrived. Where the server checks looks ({@code options}, usually
	 * {@code Placement.Options.forServer()}) the placement and the opening click turn first. {@link ItemSession#finish()}
	 * puts it back. An ender chest needs a Silk Touch pickaxe in the hotbar, or breaking it would only drop obsidian.
	 * <p>
	 * It runs as a job of {@code owner}'s (a module, or a job: then it ends with that job), so the Jobs window shows it.
	 * Fails with a {@code JobException} whose reason says why: {@code NO_ITEM} (not a box there, or no Silk Touch
	 * pickaxe), {@code NO_ROOM} (nowhere beside you to put it), {@code SERVER_REFUSED}, {@code TIMEOUT}. Once it's
	 * placed, a failure or the owner turning off still breaks it and picks it back up. For work inside a box from a
	 * job, {@code Steps.inItem} does the whole round trip.
	 */
	CompletableFuture<ItemSession> openItem(Object owner, int inventoryIndex, int ticks, Placement.Options options);

	/**
	 * A shulker box or ender chest from your inventory, placed and open ({@link #openItem}). Put it back with
	 * {@link #finish()} when you're done, whatever happened.
	 */
	@ApiStatus.NonExtendable
	interface ItemSession {
		/** The open box. */
		View view();

		/** Where it stands. */
		BlockPos where();

		/**
		 * Closes it, breaks it (with the Silk Touch pickaxe for an ender chest), picks it up and puts it back in the
		 * slot it came from; completes once it's back, or fails saying where it was left. Runs as Myriad's own job, so
		 * it finishes even if your module turns off meanwhile. Asking again returns the same future.
		 */
		CompletableFuture<Void> finish();
	}

	/**
	 * Closes the open container (and its screen), if any, at a moment the server takes it: Grim flags a close made while
	 * you move just as it refuses a click then (MultiActionsD), so where clicks have to wait for a still moment
	 * ({@link Inventory#prepareClick()}), so does the close, first thing in a coming tick. {@link View#isOpen()} says
	 * when it has.
	 */
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

		/**
		 * The owner that opened it through {@link Containers#open(Object, BlockPos, int)}, or null: opened by hand, by the
		 * server, or through {@link Containers#open(BlockPos, int)} without an owner (see {@link #byHand()}).
		 */
		@Nullable Object opener();

		/** Whether you opened it yourself (or the server did), rather than code through {@link Containers#open}. */
		boolean byHand();

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

		/**
		 * Makes some of the menu's slots hold an exact layout (see {@link Arrangement}): sorting, filling a kit, putting
		 * a hotbar back. Planned one click at a time from the menu as it is, so a click the server didn't take or a slot
		 * that changed is planned around; once there's nothing more to click the server is asked what it has, and if
		 * that isn't the layout it goes again, three rounds at most. Slots are put right in order: everything before the
		 * first wrong one stays put and what's after it is room to work in, so a full container sorts without a free
		 * slot of yours. Clicks wait for ticks the server takes them on, within the packet budget, queued with
		 * {@link #take}/{@link #put} on this container so they never interleave.
		 * <p>
		 * Completes with whether the layout was reached; fails if the container closes, or another click moves the
		 * cursor's items meanwhile.
		 */
		CompletableFuture<Boolean> arrange(Arrangement arrangement);

		/**
		 * Runs your own {@link Planner}'s clicks the way {@link #arrange} runs its own: at tick starts, within the budget
		 * and the server's rules, then checked against the server, three rounds at most. Completes with
		 * {@link Planner#done()}.
		 */
		CompletableFuture<Boolean> run(Planner planner);

		void close();
	}

	/** One click in a menu: screen slot id, button, input, as {@code handleContainerInput} takes them. */
	record Click(int slot, int button, ContainerInput input) {
	}

	/** Clicks worked out one at a time from the menu as it is, for {@link View#run}. */
	interface Planner {
		/** The next click, or null when there's nothing more to do (see {@link #stuck()}). */
		@Nullable Click next();

		/** Whether the goal is reached (asked again once the server has said what it has). */
		boolean done();

		/** Why the last {@link #next()} returned null short of the goal, if something's in the way; null otherwise. */
		default @Nullable String stuck() {
			return null;
		}
	}

	/**
	 * A layout for {@link View#arrange}: what each of some menu slots should hold, where the items may come from, and
	 * where what doesn't belong goes. Slot numbers are the menu's (screen) ids: the container's own slots are
	 * {@code 0..size()-1}, yours {@link View#playerSlot}.
	 *
	 * <pre>{@code
	 * // Sort a chest: its own slots, from themselves, spilling into your inventory if it has to.
	 * view.arrange(Containers.Arrangement.of(chestSlots, sortedLayout).dumpTo(mySlots));
	 * }</pre>
	 */
	final class Arrangement {
		private final int[] slots, sources, dumps;
		private final @Nullable ItemStack[] layout;
		private final ItemMatch match;
		private final @Nullable ToIntFunction<ItemStack> allowance;
		private final int @Nullable [] reserved;

		private Arrangement(int[] slots, @Nullable ItemStack[] layout, int[] sources, int[] dumps, ItemMatch match,
		                    @Nullable ToIntFunction<ItemStack> allowance, int @Nullable [] reserved) {
			this.slots = slots;
			this.layout = layout;
			this.sources = sources;
			this.dumps = dumps;
			this.match = match;
			this.allowance = allowance;
			this.reserved = reserved;
		}

		/**
		 * {@code layout.get(i)} is what slot {@code slots[i]} should hold: that item with that count, an empty stack for
		 * nothing, or null to leave it be. Items come from the slots themselves and go back among them; widen that with
		 * {@link #from} and {@link #dumpTo}.
		 */
		public static Arrangement of(int[] slots, List<ItemStack> layout) {
			if (slots.length != layout.size()) throw new IllegalArgumentException("slots and layout differ in length");
			ItemStack[] l = new ItemStack[slots.length];
			for (int i = 0; i < l.length; i++) l[i] = layout.get(i) == null ? null : layout.get(i).copy();
			return new Arrangement(slots.clone(), l, slots.clone(), slots.clone(), ItemMatch.EXACT, null, null);
		}

		/** Where the items come from (any of them may be in the layout's slots too: only what doesn't belong is taken). */
		public Arrangement from(int... sources) {
			return new Arrangement(slots, layout, sources.clone(), dumps, match, allowance, reserved);
		}

		/** Where what doesn't belong, or is in the way, goes. Defaults to {@link #from}'s slots. */
		public Arrangement dumpTo(int... dumps) {
			return new Arrangement(slots, layout, sources, dumps.clone(), match, allowance, reserved);
		}

		/** When a stack counts as the layout's item (default {@link ItemMatch#EXACT}). */
		public Arrangement match(ItemMatch match) {
			return new Arrangement(slots, layout, sources, dumps, match, allowance, reserved);
		}

		/**
		 * Caps how many of each item may come into the layout's slots from sources outside them: {@code allowance}
		 * says how many of an item (a layout's stack) may be taken, counted from the start. For using only what was
		 * gathered for the job, not your own.
		 */
		public Arrangement limit(ToIntFunction<ItemStack> allowance) {
			return new Arrangement(slots, layout, sources, dumps, match, allowance, reserved);
		}

		/**
		 * Keeps {@code counts[slot]} items (by menu slot id) in each source slot that holds them at the start (the same
		 * item and components): yours, not the job's. Only what's above them is taken.
		 */
		public Arrangement reserve(int[] counts) {
			return new Arrangement(slots, layout, sources, dumps, match, allowance, counts.clone());
		}

		public int[] slots() {
			return slots.clone();
		}

		/** What each slot should hold; null entries are left be. */
		public @Nullable ItemStack[] layout() {
			return layout.clone();
		}

		public int[] sources() {
			return sources.clone();
		}

		public int[] dumps() {
			return dumps.clone();
		}

		public ItemMatch match() {
			return match;
		}

		public @Nullable ToIntFunction<ItemStack> allowance() {
			return allowance;
		}

		public int @Nullable [] reserved() {
			return reserved == null ? null : reserved.clone();
		}
	}
}
