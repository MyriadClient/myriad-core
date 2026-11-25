package dev.myriadclient.api.job;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.item.ItemInfo;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Somewhere items can be fetched from, for {@link Steps#acquire}: a chest at a place, the shulker boxes you carry, or
 * whatever an addon knows of (its own memory of a storage room). Core knows nothing about where items are; acquire only
 * tries the sources in turn. Implement {@link #fetch}; this interface only ever gains {@code default} methods.
 *
 * <pre>{@code
 * Steps.acquire(s -> s.is(Items.OBSIDIAN), 64, List.of(MaterialSource.shulkerBoxes(), MaterialSource.container(chest)))
 * }</pre>
 */
@FunctionalInterface
public interface MaterialSource {
	/** How long a container may take to open before the source gives up. */
	int OPEN_TICKS = 100;

	/**
	 * A step that moves up to {@code count} items matching {@code what} into your inventory, producing how many it
	 * moved: 0 when this source has none. A failure (the chest is gone, out of reach for good) makes acquire move on to
	 * the next source.
	 */
	Step<Integer> fetch(Predicate<ItemStack> what, int count);

	/**
	 * The container at {@code pos} (a chest, a barrel): opened, taken from, closed. What's taken goes into the main
	 * inventory, leaving your hotbar as it is (a build brings a block into the hotbar when it places it); into the hotbar
	 * only once the main inventory has no room. Not while you have your inventory or a container open: it waits till you
	 * close it (opening one would close yours under what you're holding).
	 */
	static MaterialSource container(BlockPos pos) {
		return (what, count) -> yoursClosed().then(Steps.openContainer(pos, OPEN_TICKS, Placement.Options.forServer()))
			.then(view -> intoInventory(view, what, count))
			.then(moved -> Steps.closeContainer().then(Step.supply("Taken", () -> moved)))
			.named("From a container");
	}

	/**
	 * The shulker boxes in your inventory that hold any: the fullest opened ({@link Steps#inItem}) and taken from, then
	 * the next, until there's enough, no box holds any, or a box gave nothing (your inventory is full). What's taken goes
	 * into the main inventory first, as from a {@link #container}, never into the slot the box left: it needs that to
	 * come back to. A box isn't opened when there's no room besides, nor while you have your inventory or a container
	 * open (as {@link #container}).
	 */
	static MaterialSource shulkerBoxes() {
		return (what, count) -> {
			int[] before = new int[1];
			boolean[] gaveNothing = new boolean[1];
			Step<Void> nextBox = Step.choose("Next box", () -> {
				int box = Myriad.inventory().bestInInventory(s -> ItemInfo.isShulkerBox(s) ? held(s, what) : 0);
				int missing = box < 0 ? 0 : Math.min(count - (Myriad.inventory().count(what) - before[0]),
					room(what, Minecraft.getInstance().player.getInventory().getItem(box)));
				if (box < 0 || missing <= 0) {
					gaveNothing[0] = box >= 0;
					return Step.nothing();
				}
				return Steps.inItem(box, view -> intoInventory(view, what, missing))
					.then(moved -> Step.run("Took " + moved, () -> gaveNothing[0] = moved == 0));
			});
			return yoursClosed().then(Step.run("Count", () -> before[0] = Myriad.inventory().count(what)))
				.then(nextBox.repeatWhile(() -> !gaveNothing[0] && Myriad.inventory().count(what) - before[0] < count && hasBox(what)))
				.then(Step.supply("Taken", () -> Myriad.inventory().count(what) - before[0]))
				.named("From shulker boxes");
		};
	}

	/**
	 * Waits while you have your inventory or a container open: a container opened meanwhile would close it on the server,
	 * leaving what you hold on the cursor out of step with it.
	 */
	private static Step<Void> yoursClosed() {
		return Step.of("Waiting for your inventory to close", () -> ctx -> Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>
			? Progress.running() : Progress.done());
	}

	/** Up to {@code count} of {@code what} from {@code view} into the main inventory, then (for what didn't fit) the hotbar. */
	private static Step<Integer> intoInventory(Containers.View view, Predicate<ItemStack> what, int count) {
		return Steps.take(view, what, Containers.Transfer.upTo(count).withoutHotbar()).then(moved -> moved >= count ? Step.supply("Taken", () -> moved)
			: Steps.take(view, what, Containers.Transfer.upTo(count - moved)).then(more -> Step.supply("Taken", () -> moved + more)));
	}

	/**
	 * How many of what {@code box} holds matching {@code what} fit in your inventory besides the box's own slot (the
	 * box's slot isn't empty, so it's left out): the room on stacks of it, and a stack's worth in each empty slot.
	 */
	private static int room(Predicate<ItemStack> what, ItemStack box) {
		ItemStack like = ItemStack.EMPTY;
		for (ItemStack s : ItemInfo.contents(box)) if (what.test(s)) {
			like = s;
			break;
		}
		if (like.isEmpty()) return 0;
		int room = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = Minecraft.getInstance().player.getInventory().getItem(i);
			if (s.isEmpty()) room += like.getMaxStackSize();
			else if (ItemStack.isSameItemSameComponents(s, like)) room += Math.max(0, s.getMaxStackSize() - s.getCount());
		}
		return room;
	}

	private static int held(ItemStack box, Predicate<ItemStack> what) {
		int n = 0;
		for (ItemStack s : ItemInfo.contents(box)) if (what.test(s)) n += s.getCount();
		return n;
	}

	private static boolean hasBox(Predicate<ItemStack> what) {
		return Myriad.inventory().bestInInventory(s -> ItemInfo.isShulkerBox(s) ? held(s, what) : 0) >= 0;
	}
}
