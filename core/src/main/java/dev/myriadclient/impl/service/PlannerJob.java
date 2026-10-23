package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Ticks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Runs a {@link Containers.Planner} (an {@link Arranger}, say) on an open menu: its clicks go out at the start of each
 * tick, as many as the packet budget and the server's rules allow, until it has nothing more to do. Then the server is
 * asked what it really has ({@link MenuSync}), and if the planner isn't done, it goes again, three rounds at most.
 * Completes with whether the planner got there; fails if the menu closes, or once it's stopped (your click in the
 * menu), after putting down what it holds on the cursor.
 * <p>
 * After each tick's clicks it waits a round trip before the next: a server that answers every click (2b2t) sends the
 * whole menu back after each one of a burst, a few clicks behind, and a click planned on that view would act on slots
 * that aren't what it shows. The click budget spaces bursts about that far apart anyway, so it costs no speed.
 */
final class PlannerJob implements MenuJob {
	private static final int ROUNDS = 3;
	/** Clicks a round may take: far past any real arrangement (a double chest sorted from scratch is about 120). */
	private static final int MAX_CLICKS = 600;

	private final Minecraft mc = Minecraft.getInstance();
	private final AbstractContainerMenu menu;
	private final Containers.Planner planner;
	final CompletableFuture<Boolean> result = new CompletableFuture<>();
	private CompletableFuture<Void> check;
	private int rounds, clicks;
	/** The tick this job's last click went out in (see the class doc). */
	private long lastClickAt = Long.MIN_VALUE / 2;
	/** Stopped (see {@link #stop}): what's on the cursor is put down, then it ends with this. */
	private @Nullable String stopping;
	/** After a stop: whether the server confirmed the cursor's empty since the last put-down, and put-downs made. */
	private boolean confirmed;
	private int putDowns;
	/** Put-downs a stop makes before giving up (each one confirmed with the server). */
	private static final int PUT_DOWNS = 5;
	/**
	 * What the menu shows while this runs, by slot: the end result where it's known ({@link Arranger#finishedView}, a
	 * sort), so the clicks behind it don't show. With a server that answers every click, a burst of them comes back as
	 * a run of whole menus each a few clicks behind, which would replay the sort back and forth. Null: what it holds.
	 */
	private final @Nullable ItemStack[] finished;

	PlannerJob(AbstractContainerMenu menu, Containers.Planner planner) {
		this.menu = menu;
		this.planner = planner;
		this.finished = planner instanceof Arranger a ? a.finishedView() : null;
	}

	/** What {@code menu}'s slot {@code slot} shows while this runs, or null for what it holds. */
	@Nullable ItemStack shown(AbstractContainerMenu menu, int slot) {
		return showsFinished(menu) && slot >= 0 && slot < finished.length ? finished[slot] : null;
	}

	/** Whether {@code menu} shows this job's end result instead of what it holds (see {@link #shown}). */
	boolean showsFinished(AbstractContainerMenu menu) {
		return finished != null && menu == this.menu && !result.isDone() && stopping == null;
	}

	/** Whether this works in {@code menu} and hasn't ended. */
	boolean runsIn(AbstractContainerMenu menu) {
		return menu == this.menu && !result.isDone();
	}

	/** Stops it: no more of its own clicks; what it holds on the cursor is put down first, then it fails with {@code why}. */
	void stop(String why) {
		if (stopping == null) stopping = why;
	}

	@Override
	public boolean tick(MenuSync sync) {
		if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != menu) return fail("The container closed");
		if (stopping != null) return putDown(sync);
		if (check != null) {
			if (!check.isDone()) return false;
			check = null;
			if (planner.done() || ++rounds >= ROUNDS) {
				result.complete(planner.done());
				return true;
			}
			clicks = 0;
		}
		// The server's answers to the last burst in first: planned on anything older, a click acts on the wrong slots.
		if (Ticks.current() - lastClickAt <= SlotsInFlight.roundTrip()) return false;
		while (true) {
			Containers.Click c = clicks < MAX_CLICKS ? planner.next() : null;
			if (c == null) {
				// Done as far as it can tell, or stuck: what does the server say?
				if (!menu.getCarried().isEmpty()) {
					stop(planner.stuck() != null ? planner.stuck() : "Items left on the cursor");
					return false;
				}
				check = sync.refresh(menu);
				return false;
			}
			if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) || !Myriad.inventory().prepareClick()) return false;
			click(c.slot(), c.button(), c.input());
			clicks++;
		}
	}

	/**
	 * Puts down what's on the cursor: where the last pick-up came from if that's still empty, else the first empty slot
	 * (the menu's own first, then yours), else onto the same item with room. Then the server is asked for the menu, and
	 * it ends with the stop's reason once that shows an empty cursor: an answer to an earlier click arriving after the
	 * put-down would otherwise show the stack back on the cursor, with nothing to correct it, or hide one still there.
	 */
	private boolean putDown(MenuSync sync) {
		if (check != null) {
			if (!check.isDone()) return false;
			check = null;
			confirmed = true;
		}
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			if (confirmed) return fail(stopping);
			check = sync.refresh(menu);
			return false;
		}
		confirmed = false;
		if (Ticks.current() - lastClickAt <= SlotsInFlight.roundTrip()) return false;
		if (putDowns >= PUT_DOWNS) return fail(stopping + "; something stayed on the cursor");
		int to = planner instanceof Arranger a ? a.vacatedSlot() : -1;
		for (int i = 0; i < menu.slots.size() && to < 0; i++) if (menu.slots.get(i).getItem().isEmpty() && menu.slots.get(i).mayPlace(carried)) to = i;
		for (int i = 0; i < menu.slots.size() && to < 0; i++) {
			ItemStack there = menu.slots.get(i).getItem();
			if (ItemStack.isSameItemSameComponents(there, carried) && there.getCount() < there.getMaxStackSize()) to = i;
		}
		if (to < 0) return fail(stopping + "; something stayed on the cursor (no room for it)");
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) || !Myriad.inventory().prepareClick()) return false;
		click(to, 0, ContainerInput.PICKUP);
		putDowns++;
		return false;
	}

	private void click(int slot, int button, ContainerInput input) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
		lastClickAt = Ticks.current();
	}

	@Override
	public void abort(String why) {
		result.completeExceptionally(new IllegalStateException(why));
	}

	private boolean fail(String why) {
		abort(why);
		return true;
	}
}
