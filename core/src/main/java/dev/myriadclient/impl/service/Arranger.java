package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Containers.Click;
import dev.myriadclient.api.util.ItemMatch;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * {@link Containers.View#arrange}: works out, one click at a time, how to make some of a menu's slots hold a layout:
 * each slot an item and an exact count, or nothing ({@link Want}). Read from the menu as it is before every click (the client predicts each click as
 * it's made), so a click that went wrong or a slot the server changed is planned around rather than trusted.
 * <p>
 * Items come from {@code sources} (and from slots of the area that hold something they shouldn't), and what has to
 * go goes to {@code dumps}. The cheapest move is chosen for each slot: a shift click where the menu would put the
 * stack exactly there, a number-key swap from the hotbar, two clicks for a whole stack, a {@link MovePlanner} plan for
 * part of one, and swapping through the cursor for a stack in the wrong place (sorting is mostly that: each click puts
 * one stack where it belongs).
 */
final class Arranger implements Containers.Planner {
	/** A slot's goal: {@code count} of {@code like} (as {@code match} compares), or empty when {@code count} is 0. */
	record Want(ItemStack like, int count) {
		static final Want EMPTY = new Want(ItemStack.EMPTY, 0);

		static Want of(ItemStack like, int count) {
			return count <= 0 || like.isEmpty() ? EMPTY : new Want(like.copyWithCount(1), count);
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final AbstractContainerMenu menu;
	private final int[] area;
	private final @Nullable Want[] wants;
	private final int[] sources, dumps;
	private final ItemMatch match;
	private final boolean[] inArea;
	/** Slots outside the area this arranger put items down in on the way: they count as sources from then on. */
	private final boolean[] parked;
	/** What each slot held at the start, and whether it's a source: a slot that isn't gives back only what was added to it. */
	private final ItemStack[] baseline;
	private final boolean[] isSource;
	/** Slots of the area nothing could be found for, by the last {@link #next()}. */
	private final boolean[] missing;
	/** The rest of a {@link MovePlanner} plan, and the cursor count expected before each of its clicks. */
	private final ArrayDeque<Click> plan = new ArrayDeque<>();
	private final ArrayDeque<Integer> planCursor = new ArrayDeque<>();
	private @Nullable String stuck;
	private java.util.function.@Nullable ToIntFunction<ItemStack> allowance;
	/** Items each slot keeps (see {@link Containers.Arrangement#reserve}), with the stack they belong to. */
	private int @Nullable [] reserved;
	/** The slot the last pick-up emptied (or -1). */
	private int vacated = -1;

	/** For {@code menu}, as {@code a} says. */
	Arranger(AbstractContainerMenu menu, Containers.Arrangement a) {
		this(menu, a.slots(), wants(a.layout()), a.sources(), a.dumps(), a.match());
		this.allowance = a.allowance();
		this.reserved = a.reserved();
	}

	private static @Nullable Want[] wants(@Nullable ItemStack[] layout) {
		Want[] w = new Want[layout.length];
		for (int i = 0; i < layout.length; i++) w[i] = layout[i] == null ? null : Want.of(layout[i], layout[i].getCount());
		return w;
	}

	/**
	 * {@code area} and {@code wants} line up (a null want: leave that slot be). Slot numbers are the menu's.
	 * {@code sources} are where the items come from, {@code dumps} where what doesn't belong goes; either may overlap
	 * the area (only slots that hold something they shouldn't are taken from).
	 */
	Arranger(AbstractContainerMenu menu, int[] area, @Nullable Want[] wants, int[] sources, int[] dumps, ItemMatch match) {
		if (area.length != wants.length) throw new IllegalArgumentException("area and wants differ in length");
		this.menu = menu;
		this.area = area.clone();
		this.wants = wants.clone();
		this.sources = sources.clone();
		this.dumps = dumps.clone();
		this.match = match;
		this.inArea = new boolean[menu.slots.size()];
		for (int s : area) inArea[s] = true;
		this.missing = new boolean[area.length];
		this.parked = new boolean[menu.slots.size()];
		this.baseline = new ItemStack[menu.slots.size()];
		for (int i = 0; i < baseline.length; i++) baseline[i] = menu.getSlot(i).getItem().copy();
		this.isSource = new boolean[menu.slots.size()];
		for (int s : sources) isSource[s] = true;
	}

	/** How many more of {@code like} may come from outside sources under {@link Containers.Arrangement#limit}. */
	private int allowed(ItemStack like) {
		if (allowance == null) return Integer.MAX_VALUE;
		int before = 0, now = 0;
		for (int s : sources) {
			if (inArea[s]) continue;
			if (!baseline[s].isEmpty() && same(baseline[s], like)) before += baseline[s].getCount();
			ItemStack c = stack(s);
			if (!c.isEmpty() && same(c, like)) now += c.getCount();
		}
		ItemStack cursor = menu.getCarried();
		if (!cursor.isEmpty() && same(cursor, like)) now += cursor.getCount();
		return allowance.applyAsInt(like) - (before - now);
	}

	/** Why the last {@link #next()} returned null with the layout not reached: no room, or the cursor can't be put down. */
	@Override
	public @Nullable String stuck() {
		return stuck;
	}

	/** Whether every slot of the area holds what it should. */
	@Override
	public boolean done() {
		for (int i = 0; i < area.length; i++) if (wants[i] != null && !holds(area[i], wants[i])) return false;
		return true;
	}

	/** The next click towards the layout, or null when it's reached or nothing more can be done (see {@link #stuck()}). */
	@Override
	public @Nullable Click next() {
		stuck = null;
		ItemStack cursor = menu.getCarried();
		// A plan under way, while the cursor is where it should be.
		if (!plan.isEmpty()) {
			if (planCursor.peek() == cursor.getCount()) {
				planCursor.poll();
				return remember(plan.poll());
			}
			plan.clear();
			planCursor.clear();
		}
		// Slots are put right in order: everything before the first wrong one stays as it is, and what's after it is
		// room to work in.
		Arrays.fill(missing, false);
		for (int i = 0; i < area.length; i++) {
			Want w = wants[i];
			if (w == null || holds(area[i], w)) continue;
			Click c = cursor.isEmpty() ? fix(i) : putDown(cursor, i);
			if (c != null) return remember(c);
			if (!cursor.isEmpty()) return null;
			missing[i] = true;
		}
		if (!cursor.isEmpty()) {
			// Everything's right but the cursor holds something: anywhere it can go.
			Click c = putDown(cursor, area.length);
			if (c != null) return remember(c);
		}
		return null;
	}

	/** Notes where a pick-up came from: that slot is empty now, a place to put down what the next click swaps out. */
	private Click remember(Click c) {
		if (c.input() == ContainerInput.PICKUP && menu.getCarried().isEmpty()) vacated = c.slot();
		return c;
	}

	// ---- cursor empty -----------------------------------------------------------------------------------------------

	/** One click towards area slot {@code i} holding its want, or null if there's nothing to do it with. */
	private @Nullable Click fix(int i) {
		Want w = wants[i];
		int slot = area[i];
		ItemStack here = stack(slot);
		boolean wrong = !here.isEmpty() && (w.count == 0 || !same(here, w.like));
		if (wrong) {
			// Pick up what belongs here; it's swapped in, and what's here goes where it belongs or where it came from.
			if (w.count > 0 && allowed(w.like) >= w.count) {
				int src = source(w, slot, here, true);
				if (src >= 0 && menu.getSlot(slot).mayPlace(stack(src)) && pickable(slot)) return pickup(src, 0);
			}
			// Nothing belongs here (or nothing's there to bring): it goes, by a shift click to the other side if it isn't
			// wanted on this side, otherwise picked up and put where it belongs.
			if (!inPlayer(slot) && storage() && !wanted(here) && room(dumps, here, true) >= here.getCount()) return new Click(slot, 0, ContainerInput.QUICK_MOVE);
			return pickable(slot) ? pickup(slot, 0) : null;
		}
		if (!here.isEmpty() && here.getCount() > w.count) {
			int excess = here.getCount() - w.count;
			int dump = dumpFor(here, i, slot, excess);
			if (dump < 0) {
				stuck = "No room for " + excess + " " + here.getHoverName().getString();
				return null;
			}
			return startPlan(slot, dump, here, here.getCount(), stack(dump).getCount(), w.count, true);
		}
		int need = Math.min(w.count - here.getCount(), allowed(w.like));
		if (need <= 0) return null;
		int src = source(w, slot, here, false);
		if (src < 0) return null;
		ItemStack from = stack(src);
		int free = free(src);
		if (here.isEmpty() && free == need && from.getCount() == need) {
			// The whole stack, and just what's needed.
			if (quickMoveLandsIn(src, slot)) return new Click(src, 0, ContainerInput.QUICK_MOVE);
			int hotbar = hotbarIndex(src);
			if (hotbar >= 0 && !inPlayer(slot)) return new Click(slot, hotbar, ContainerInput.SWAP);
			return pickup(src, 0);
		}
		return startPlan(src, slot, from, from.getCount(), here.getCount(), here.getCount() + Math.min(need, free), false);
	}

	/**
	 * Where to take {@code w}'s item from for {@code slot} (holding {@code here}): a stack with just what's needed
	 * first, then the one giving the most (the smallest of those). {@code whole}: only stacks that may be picked up
	 * whole and fit the slot, to swap in. -1 if none.
	 */
	private int source(Want w, int slot, ItemStack here, boolean whole) {
		int need = whole ? w.count : w.count - here.getCount();
		int best = -1, bestFree = 0;
		for (int k = 0; k < sources.length + parked.length; k++) {
			int s = k < sources.length ? sources[k] : k - sources.length;
			if (k >= sources.length && (!parked[s] || isSource[s])) continue;
			if (s == slot) continue;
			ItemStack src = stack(s);
			if (src.isEmpty() || !same(src, w.like) || !pickable(s)) continue;
			// Stacks onto what's there only if they stack together.
			if (!whole && !here.isEmpty() && !ItemStack.isSameItemSameComponents(here, src)) continue;
			int free = free(s);
			if (free <= 0 || whole && (free != src.getCount() || src.getCount() > capacity(slot, src))) continue;
			if (free == need) return s;
			int gives = Math.min(free, need), bestGives = Math.min(bestFree, need);
			if (best < 0 || gives > bestGives || gives == bestGives && free < bestFree) {
				best = s;
				bestFree = free;
			}
		}
		return best;
	}

	// ---- cursor full ------------------------------------------------------------------------------------------------

	/** Where to put down what the cursor holds, working on area slot {@code i} (or past the end: anywhere). */
	private @Nullable Click putDown(ItemStack cursor, int i) {
		int c = cursor.getCount();
		// Slot i first: swapped in if something else is there (fixed up after), else put down as much as it needs.
		if (i < area.length) {
			Click click = putInto(cursor, i, true);
			if (click != null) return click;
		}
		// Then where it belongs: slots it fills without overfilling, or wrong ones wanting exactly this many.
		int partial = -1;
		for (int j = 0; j < area.length; j++) {
			if (j == i) continue;
			Want w = wants[j];
			if (w == null || w.count == 0 || !same(cursor, w.like)) continue;
			int slot = area[j];
			ItemStack here = stack(slot);
			if (!here.isEmpty() && !same(here, w.like)) {
				if (c == w.count && menu.getSlot(slot).mayPlace(cursor) && pickable(slot)) return pickup(slot, 0);
				continue;
			}
			if (!here.isEmpty() && !ItemStack.isSameItemSameComponents(here, cursor)) continue;
			int need = w.count - here.getCount();
			if (need <= 0) continue;
			if (c <= need) return pickup(slot, 0);
			if (partial < 0) partial = j;
		}
		// Nowhere it all goes: put it down to work on later.
		int dump = dumpFor(cursor, i, -1, c);
		if (dump >= 0) return pickup(dump, 0);
		if (partial >= 0) return putInto(cursor, partial, false);
		stuck = "No room to put down " + c + " " + cursor.getHoverName().getString();
		return null;
	}

	/** Puts the cursor into area slot {@code j} towards its want; {@code swap}: also swapping out something else there. */
	private @Nullable Click putInto(ItemStack cursor, int j, boolean swap) {
		Want w = wants[j];
		if (w == null || w.count == 0 || !same(cursor, w.like)) return null;
		int slot = area[j], c = cursor.getCount();
		ItemStack here = stack(slot);
		if (!here.isEmpty() && !same(here, w.like)) {
			return swap && c <= capacity(slot, cursor) && menu.getSlot(slot).mayPlace(cursor) && pickable(slot) ? pickup(slot, 0) : null;
		}
		if (!here.isEmpty() && !ItemStack.isSameItemSameComponents(here, cursor)) return null;
		int need = w.count - here.getCount();
		if (need <= 0) return null;
		if (c <= need) return pickup(slot, 0);
		// More than it needs: top it up through a plan with somewhere for the rest, or one at a time.
		int dump = dumpFor(cursor, j, slot, c - need);
		if (dump >= 0) {
			Click click = startPlanWithCursor(dump, slot, cursor, stack(dump).getCount(), here.getCount(), w.count);
			if (click != null) return click;
		}
		return new Click(slot, 1, ContainerInput.PICKUP);
	}

	// ---- plans ------------------------------------------------------------------------------------------------------

	/** Starts a plan moving {@code from} → {@code to} until {@code to} holds {@code goal} ({@code reverse}: towards the source's own count instead). */
	private @Nullable Click startPlan(int from, int to, ItemStack item, int fromCount, int toCount, int goal, boolean reverse) {
		int stackMax = item.getMaxStackSize();
		MovePlanner.Step[] steps;
		if (reverse) {
			// Taking from "from" down to goal: the dump is the planner's source, "from" its destination.
			steps = MovePlanner.plan(0, toCount, fromCount, goal, capacity(to, item), capacity(from, item), stackMax);
			return queue(steps, to, from, 0, toCount, fromCount, item);
		}
		steps = MovePlanner.plan(0, fromCount, toCount, goal, capacity(from, item), capacity(to, item), stackMax);
		return queue(steps, from, to, 0, fromCount, toCount, item);
	}

	private @Nullable Click startPlanWithCursor(int source, int destination, ItemStack cursor, int sourceCount, int destinationCount, int goal) {
		MovePlanner.Step[] steps = MovePlanner.plan(cursor.getCount(), sourceCount, destinationCount, goal, capacity(source, cursor),
			capacity(destination, cursor), cursor.getMaxStackSize());
		return queue(steps, source, destination, cursor.getCount(), sourceCount, destinationCount, cursor);
	}

	/** Queues a plan's clicks with the cursor count each expects; returns the first. */
	private @Nullable Click queue(MovePlanner.Step[] steps, int source, int destination, int cursor, int sourceCount, int destinationCount, ItemStack item) {
		if (steps == null || steps.length == 0) return null;
		plan.clear();
		planCursor.clear();
		int c = cursor, s = sourceCount, d = destinationCount;
		int srcMax = capacity(source, item), dstMax = capacity(destination, item);
		for (MovePlanner.Step step : steps) {
			planCursor.add(c);
			plan.add(new Click(step.onDestination() ? destination : source, step.button(), ContainerInput.PICKUP));
			int slot = step.onDestination() ? d : s, max = step.onDestination() ? dstMax : srcMax;
			if (c == 0) {
				int taken = Math.min(item.getMaxStackSize(), step.button() == 0 ? slot : (slot + 1) / 2);
				c = taken;
				slot -= taken;
			} else {
				int put = step.button() == 0 ? Math.min(c, max - slot) : 1;
				c -= put;
				slot += put;
			}
			if (step.onDestination()) d = slot;
			else s = slot;
		}
		planCursor.poll();
		return plan.poll();
	}

	// ---- reading the menu -------------------------------------------------------------------------------------------

	private ItemStack stack(int slot) {
		return menu.getSlot(slot).getItem();
	}

	private boolean same(ItemStack a, ItemStack b) {
		return match.test(a, b);
	}

	private boolean holds(int slot, Want w) {
		ItemStack s = stack(slot);
		return w.count == 0 ? s.isEmpty() : !s.isEmpty() && same(s, w.like) && s.getCount() == w.count;
	}

	/** How many of the slot's items may be taken: all, unless it's part of the area and holds what it should (then any excess). */
	private int free(int slot) {
		ItemStack s = stack(slot);
		if (s.isEmpty()) return 0;
		if (!inArea[slot]) {
			if (isSource[slot]) {
				int keep = reserved == null || slot >= reserved.length || !ItemStack.isSameItemSameComponents(baseline[slot], s) ? 0 : reserved[slot];
				return Math.max(0, s.getCount() - keep);
			}
			// Only what this arranger parked there: the rest was there before and isn't its to take.
			ItemStack was = baseline[slot];
			if (was.isEmpty()) return s.getCount();
			return ItemStack.isSameItemSameComponents(was, s) ? Math.max(0, s.getCount() - was.getCount()) : 0;
		}
		for (int i = 0; i < area.length; i++) {
			if (area[i] != slot) continue;
			Want w = wants[i];
			if (w == null) return 0;
			if (w.count == 0 || !same(s, w.like)) return s.getCount();
			return Math.max(0, s.getCount() - w.count);
		}
		return 0;
	}

	/**
	 * Somewhere to put down {@code room} of {@code item} while working on area slot {@code i}, other than {@code not}:
	 * the slot the last pick-up emptied, an empty slot of the area past {@code i} (it's put right later), an empty
	 * dump slot (nothing of yours gets mixed in), then a dump slot stacking with it with that much room. -1 if none.
	 * Dump slots outside the area that take something are taken from again (see {@link #source}).
	 */
	private int dumpFor(ItemStack item, int i, int not, int room) {
		int dump = -1;
		if (vacated >= 0 && vacated != not && stack(vacated).isEmpty() && fits(vacated, item, room) && (!inArea[vacated] || areaIndex(vacated) > i)) {
			dump = vacated;
		}
		for (int j = i + 1; dump < 0 && j < area.length; j++) {
			int s = area[j];
			if (s != not && wants[j] != null && stack(s).isEmpty() && fits(s, item, room)) dump = s;
		}
		for (int k = 0; dump < 0 && k < dumps.length; k++) {
			int s = dumps[k];
			if (s != not && !inArea[s] && stack(s).isEmpty() && fits(s, item, room)) dump = s;
		}
		for (int k = 0; dump < 0 && k < dumps.length; k++) {
			int s = dumps[k];
			ItemStack d = stack(s);
			if (s != not && !inArea[s] && ItemStack.isSameItemSameComponents(d, item) && capacity(s, item) - d.getCount() >= room) dump = s;
		}
		if (dump >= 0 && !inArea[dump]) parked[dump] = true;
		return dump;
	}

	private boolean fits(int slot, ItemStack item, int room) {
		return menu.getSlot(slot).mayPlace(item) && capacity(slot, item) >= room;
	}

	private int areaIndex(int slot) {
		for (int j = 0; j < area.length; j++) if (area[j] == slot) return j;
		return -1;
	}

	/** Whether some slot of the area wants {@code item}. */
	private boolean wanted(ItemStack item) {
		for (Want w : wants) if (w != null && w.count > 0 && same(item, w.like)) return true;
		return false;
	}

	/** Room for {@code item} across {@code slots}, as a shift click would fill them (empty slots and stacks it joins). */
	private int room(int[] slots, ItemStack item, boolean playerSide) {
		int room = 0;
		for (int s : slots) {
			if (playerSide && !inPlayer(s)) continue;
			ItemStack d = stack(s);
			if (d.isEmpty()) room += capacity(s, item);
			else if (ItemStack.isSameItemSameComponents(d, item)) room += Math.max(0, capacity(s, item) - d.getCount());
		}
		return room;
	}

	private int capacity(int slot, ItemStack item) {
		Slot s = menu.getSlot(slot);
		return Math.min(item.getMaxStackSize(), s.getMaxStackSize(item));
	}

	private boolean pickable(int slot) {
		return mc.player != null && menu.getSlot(slot).mayPickup(mc.player);
	}

	private boolean inPlayer(int slot) {
		return mc.player != null && menu.getSlot(slot).container == mc.player.getInventory();
	}

	/** The hotbar index (0-8) of a player slot, or -1. */
	private int hotbarIndex(int slot) {
		Slot s = menu.getSlot(slot);
		return inPlayer(slot) && s.getContainerSlot() < 9 ? s.getContainerSlot() : -1;
	}

	private boolean storage() {
		return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
	}

	/**
	 * Whether shift-clicking player slot {@code from} puts its whole stack into {@code to} and nowhere else: storage
	 * menus fill stacks of the same item first (anywhere in the container), then the first empty slot.
	 */
	private boolean quickMoveLandsIn(int from, int to) {
		if (!storage() || !inPlayer(from) || inPlayer(to)) return false;
		ItemStack item = stack(from);
		int containerSlots = menu.slots.size() - 36;
		for (int s = 0; s < containerSlots; s++) {
			ItemStack d = stack(s);
			if (!d.isEmpty() && ItemStack.isSameItemSameComponents(d, item) && d.getCount() < capacity(s, item)) return false;
		}
		for (int s = 0; s < containerSlots; s++) {
			if (stack(s).isEmpty() && menu.getSlot(s).mayPlace(item)) return s == to;
		}
		return false;
	}

	private static Click pickup(int slot, int button) {
		return new Click(slot, button, ContainerInput.PICKUP);
	}
}
