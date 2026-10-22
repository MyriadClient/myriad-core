package dev.myriadclient.api.job;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.ItemInfo;
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

	/** The container at {@code pos} (a chest, a barrel): opened, taken from, closed. */
	static MaterialSource container(BlockPos pos) {
		return (what, count) -> Steps.openContainer(pos, OPEN_TICKS, Placement.Options.forServer())
			.then(view -> Steps.take(view, what, Containers.Transfer.upTo(count)))
			.then(moved -> Steps.closeContainer().then(Step.supply("Taken", () -> moved)))
			.named("From " + pos.toShortString());
	}

	/**
	 * The shulker boxes in your inventory that hold any: the fullest opened ({@link Steps#inItem}) and taken from, then
	 * the next, until there's enough, no box holds any, or a box gave nothing (your inventory is full).
	 */
	static MaterialSource shulkerBoxes() {
		return (what, count) -> {
			int[] before = new int[1];
			boolean[] gaveNothing = new boolean[1];
			Step<Void> nextBox = Step.choose("Next box", () -> {
				int box = Myriad.inventory().bestInInventory(s -> ItemInfo.isShulkerBox(s) ? held(s, what) : 0);
				int missing = count - (Myriad.inventory().count(what) - before[0]);
				if (box < 0 || missing <= 0) return Step.nothing();
				return Steps.inItem(box, view -> Steps.take(view, what, Containers.Transfer.upTo(missing)))
					.then(moved -> Step.run("Took " + moved, () -> gaveNothing[0] = moved == 0));
			});
			return Step.run("Count", () -> before[0] = Myriad.inventory().count(what))
				.then(nextBox.repeatWhile(() -> !gaveNothing[0] && Myriad.inventory().count(what) - before[0] < count && hasBox(what)))
				.then(Step.supply("Taken", () -> Myriad.inventory().count(what) - before[0]))
				.named("From shulker boxes");
		};
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
