package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Slots;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * One {@link Containers.View#take} or {@link Containers.View#put}: rounds of clicks planned by {@link TransferPlanner},
 * each followed by asking the server what it has ({@link MenuSync}). What moved is counted from the server's answer, and
 * anything a round left undone (a click the server didn't take, a slot someone else changed) gets another round.
 */
final class ContainerTransfer {
	/** Rounds before settling for what moved. */
	private static final int ROUNDS = 3;
	/** Clicks one round may make: well past any real plan, so a plan that goes round in circles ends. */
	private static final int MAX_CLICKS = 400;
	/** Clicks in a row that change nothing before the round ends. */
	private static final int STUCK_CLICKS = 3;

	private final Minecraft mc = Minecraft.getInstance();
	private final AbstractContainerMenu menu;
	private final Predicate<ItemStack> what;
	private final boolean take;
	private final int wanted;
	private final int[] container, from, to;
	private final boolean shiftClicks;
	final CompletableFuture<Integer> result = new CompletableFuture<>();

	private TransferPlanner plan;
	private int baseline, rounds, clicks, stuck;
	private boolean started;
	private CompletableFuture<Void> check;
	/** What the cursor held after the last click made here: anything else means a click came from elsewhere. */
	private ItemStack leftOnCursor = ItemStack.EMPTY;

	ContainerTransfer(AbstractContainerMenu menu, Predicate<ItemStack> what, boolean take, Containers.Transfer how) {
		this.menu = menu;
		this.what = what;
		this.take = take;
		this.wanted = how.count();
		int size = Math.max(0, menu.slots.size() - Slots.MAIN_END);
		container = IntStream.range(0, size).toArray();
		// The main inventory before the hotbar: items put away come from the hotbar last, and come into it last.
		IntStream inventory = IntStream.range(Slots.MAIN_START, Slots.MAIN_END);
		if (how.hotbar()) inventory = IntStream.concat(inventory, IntStream.range(Slots.HOTBAR_START, Slots.HOTBAR_END));
		int[] player = inventory.map(i -> Slots.containerScreen(size, i)).toArray();
		from = take ? container : player;
		to = take ? player : container;
		// Storage menus shift-click a stack straight across, into anywhere on the other side; the hotbar left out is
		// somewhere it could go, so then items are put down by hand.
		boolean storage = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
		shiftClicks = storage && (how.hotbar() || !take);
	}

	/** One tick's work; true once it's over (the result is complete). */
	boolean tick(MenuSync sync) {
		if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != menu) return fail("The container closed");
		if (!started) {
			if (!menu.getCarried().isEmpty()) return fail("Something is on the cursor");
			started = true;
			baseline = containerCount();
			plan = new TransferPlanner(from, to, shiftClicks);
		}
		if (check != null) {
			if (!check.isDone()) return false;
			// A check that got no answer leaves the client's count, which is all there is.
			check = null;
			int moved = moved();
			if (moved >= wanted || ++rounds >= ROUNDS || !possible()) {
				result.complete(Math.max(0, moved));
				return true;
			}
			plan = new TransferPlanner(from, to, shiftClicks);
			clicks = stuck = 0;
		}
		if (!ItemStack.matches(menu.getCarried(), leftOnCursor)) return fail("Something else clicked in the container");
		while (true) {
			TransferPlanner.Click c = clicks < MAX_CLICKS && stuck < STUCK_CLICKS ? plan.next(new MenuGrid(), (int) Math.min(Integer.MAX_VALUE, (long) wanted - moved())) : null;
			if (c == null) {
				// Something picked up and nowhere to put it (the menu changed under the plan): it can't be checked like that.
				if (!menu.getCarried().isEmpty()) return fail("Couldn't put down the items on the cursor");
				check = sync.refresh(menu);
				return false;
			}
			if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) || !Myriad.inventory().prepareClick()) return false;
			int before = fingerprint();
			mc.gameMode.handleContainerInput(menu.containerId, c.slot(), c.button(), c.input(), mc.player);
			clicks++;
			leftOnCursor = menu.getCarried().copy();
			stuck = fingerprint() == before ? stuck + 1 : 0;
		}
	}

	/** Items moved so far: out of the container, or into it. Only exact with the cursor empty. */
	private int moved() {
		return take ? baseline - containerCount() : containerCount() - baseline;
	}

	private int containerCount() {
		int n = 0;
		for (int s : container) {
			ItemStack stack = menu.getSlot(s).getItem();
			if (!stack.isEmpty() && what.test(stack)) n += stack.getCount();
		}
		return n;
	}

	/** Whether something left to move has room where it goes. */
	private boolean possible() {
		MenuGrid g = new MenuGrid();
		for (int s : from) if (g.wanted(s) && g.mayPickup(s) && TransferPlanner.room(g, to, g.kind(s)) > 0) return true;
		return false;
	}

	/** Changes with any slot's item or count, or the cursor's. */
	private int fingerprint() {
		int h = ItemStack.hashItemAndComponents(menu.getCarried()) * 31 + menu.getCarried().getCount();
		for (Slot slot : menu.slots) h = (h * 31 + ItemStack.hashItemAndComponents(slot.getItem())) * 31 + slot.getItem().getCount();
		return h;
	}

	private boolean fail(String why) {
		result.completeExceptionally(new IllegalStateException(why));
		return true;
	}

	/** The menu as it is now, for the planner. */
	private final class MenuGrid implements TransferPlanner.Grid {
		private final List<ItemStack> kinds = new ArrayList<>();
		private final int[] kindOf = new int[menu.slots.size()];
		private final int cursorKind;

		MenuGrid() {
			for (int i = 0; i < kindOf.length; i++) kindOf[i] = kindOf(menu.getSlot(i).getItem());
			cursorKind = kindOf(menu.getCarried());
		}

		private int kindOf(ItemStack stack) {
			if (stack.isEmpty()) return 0;
			for (int k = 0; k < kinds.size(); k++) if (ItemStack.isSameItemSameComponents(kinds.get(k), stack)) return k + 1;
			kinds.add(stack);
			return kinds.size();
		}

		@Override
		public int kind(int slot) {
			return kindOf[slot];
		}

		@Override
		public int count(int slot) {
			return menu.getSlot(slot).getItem().getCount();
		}

		@Override
		public int capacity(int slot, int kind) {
			Slot s = menu.getSlot(slot);
			ItemStack item = kinds.get(kind - 1);
			return s.mayPlace(item) ? s.getMaxStackSize(item) : 0;
		}

		@Override
		public boolean wanted(int slot) {
			ItemStack stack = menu.getSlot(slot).getItem();
			return !stack.isEmpty() && what.test(stack);
		}

		@Override
		public boolean mayPickup(int slot) {
			return menu.getSlot(slot).mayPickup(mc.player);
		}

		@Override
		public int cursorKind() {
			return cursorKind;
		}

		@Override
		public int cursorCount() {
			return menu.getCarried().getCount();
		}
	}
}
