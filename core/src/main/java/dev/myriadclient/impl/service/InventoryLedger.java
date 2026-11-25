package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.mixin.AbstractContainerMenuAccessor;
import dev.myriadclient.impl.service.SlotLedger.Verdict;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Follows the open menu in a {@link SlotLedger}, watching only: every click's changes are recorded as it's made
 * (whoever makes it), changes made some other way (a block placed, food eaten) at the next packet or tick end, and
 * every packet the server sends about the menu is weighed against them. Nothing it learns is acted on yet. At each
 * tick end it compares the menu as shown with what the ledger holds, and counts (and with logging on, logs) where they
 * differ: the slot is showing a value the server sent before it took clicks still on their way.
 * <p>
 * With {@code -Dmyriad.ledgerLog=true} it logs what it finds (at most {@link #LINES_PER_SECOND} lines a second, and a
 * summary every {@link #SUMMARY_TICKS}).
 */
public final class InventoryLedger {
	/** Ticks between summaries in the log. */
	private static final int SUMMARY_TICKS = 6000;
	private static final int LINES_PER_SECOND = 5;
	/** Clicks that must be left in the budget for a check to go: it never makes a module's wait. */
	private static final int CHECK_HEADROOM = 12;

	private final Minecraft mc = Minecraft.getInstance();
	private final boolean log = Boolean.getBoolean("myriad.ledgerLog");
	/**
	 * Whether the ledger runs the menu (the default): the menu always shows its view, and checks go out by themselves;
	 * with {@code -Dmyriad.ledger=shadow} it only watches.
	 */
	private final boolean enforce = !"shadow".equals(System.getProperty("myriad.ledger"));
	/** The tick the last click was made in, and the last one a packet about the menu came in. */
	private long clickedAt = Long.MIN_VALUE / 2, heardAt = Long.MIN_VALUE / 2;
	/** Lines logged in the current second, and those left out for going over. */
	private int lines;
	private long linesSecond, linesDropped;
	private final SlotLedger<ItemStack> ledger = new SlotLedger<>(ItemStack::matches, ItemStack.EMPTY);

	/**
	 * The menu followed, and each of its slots (then the cursor) as last seen: after a click, a packet or a tick end.
	 * Only the slots the server knows: {@code seen} has as many as its whole-menu packets (as the menu had when it opened
	 * until one comes); slots a mod adds after those, filled on this side only, are left alone.
	 */
	private @Nullable AbstractContainerMenu menu;
	private final List<ItemStack> seen = new ArrayList<>();
	private ItemStack seenCursor = ItemStack.EMPTY;
	/** The menu's slots (then the cursor) just before the click being made, or null. */
	private @Nullable List<ItemStack> beforeClick;
	/** Slots showing something other than the ledger's view at the last tick end. */
	private final Set<Integer> differing = new HashSet<>();

	private long clicks, steps, local, echoes, stale, contradicts, server, differTicks, differEpisodes;

	/**
	 * A check on its way: one click that changes nothing, saying what the client holds in every slot; the server answers
	 * with the slots it holds something else in, then the cursor (claimed wrong on purpose, so it always comes, last).
	 * {@code upTo} is the last change made before it, {@code claimed} what each slot was vouched to hold.
	 */
	private record Check(AbstractContainerMenu menu, long upTo, List<@Nullable ItemStack> claimed, long sentTick) {}
	private @Nullable Check check;
	/** Slots the server spoke of since the check went out (its corrections). */
	private final Set<Integer> answered = new HashSet<>();
	private long checks, corrected, abandoned;
	/** Checks dropped in a row: each doubles the wait before the next (whole menus keep coming while a module works). */
	private int dropStreak;
	private long summaryAt;

	/** Counts so far, for the dev console. */
	public String summary() {
		long oldest = ledger.oldestPendingTick();
		return String.format(Locale.ROOT, "menu %s: %d clicks (%d steps), %d other changes; server: %d echoes, %d stale, %d contradicting, %d its own; "
				+ "%d steps pending (oldest %s); shown differing: %d times, %d slot-ticks; %d checks (%d dropped), %d slots corrected",
			menu == null ? "none" : menu.containerId, clicks, steps, local, echoes, stale, contradicts, server, ledger.pendingSteps(),
			oldest < 0 ? "-" : Ticks.since(oldest) + " ticks", differEpisodes, differTicks, checks, abandoned, corrected);
	}

	/** Whether the ledger runs the menu, rather than only watching (see {@link #enforce}). */
	public boolean enforcing() {
		return enforce;
	}

	/** Sends a check of every slot of the open menu (dev console {@code ledger check}). */
	public String check() {
		if (mc.player == null || mc.getConnection() == null) return "not in a world";
		absorb();
		if (menu == null) return "no menu";
		if (check != null) return "a check is already on its way";
		if (!Myriad.inventory().prepareClick()) return "not a moment to click";
		sendCheck(true);
		return "check sent for menu " + menu.containerId + " (" + ledger.pendingSteps() + " steps pending)";
	}

	/**
	 * Sends a check: its answer settles every change made before it. {@code all} vouches for every slot (finding any
	 * difference at all), else only for those with changes pending (a server that never agrees on some items, as 2b2t
	 * with boxes, answers each of those it's asked about). The click is a shift-click on no slot, which the server does
	 * nothing with, whatever the cursor holds.
	 */
	private void sendCheck(boolean all) {
		var hashes = mc.getConnection().decoratedHashOpsGenenerator();
		Int2ObjectMap<HashedStack> claims = new Int2ObjectOpenHashMap<>();
		List<@Nullable ItemStack> claimed = new ArrayList<>(seen.size());
		for (int i = 0; i < seen.size(); i++) {
			if (!all && !ledger.needsCheck(i)) {
				claimed.add(null);
				continue;
			}
			ItemStack logical = ledger.logical(i).copy();
			claimed.add(logical);
			claims.put(i, HashedStack.create(logical, hashes));
		}
		// The cursor claimed to hold what it can't: the server always answers that, after every slot.
		ItemStack cursor = ledger.logical(SlotLedger.CURSOR);
		ItemStack wrong = cursor.is(Items.BARRIER) ? ItemStack.EMPTY : new ItemStack(Items.BARRIER);
		long upTo = ledger.nextChange();
		MyriadImpl.get().packetLimiter().recordClick();
		mc.getConnection().send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) -1, (byte) 0,
			ContainerInput.QUICK_MOVE, claims, HashedStack.create(wrong, hashes)));
		MyriadImpl.get().packetLimiter().clickDone();
		answered.clear();
		check = new Check(menu, upTo, claimed, Ticks.current());
		checks++;
	}

	/**
	 * The check's answer is in: every slot the server didn't correct holds what it was vouched to, and a corrected one
	 * what the correction said, whatever the changes made before the check expected.
	 */
	private void checked(ItemStack cursor) {
		Check c = check;
		check = null;
		int vouched = 0;
		for (int i = 0; i < c.claimed().size(); i++) {
			if (c.claimed().get(i) == null && !answered.contains(i)) continue;
			if (answered.contains(i)) {
				ledger.settle(i, ledger.confirmed(i), c.upTo());
				continue;
			}
			if (ledger.isPending(i)) vouched++;
			ledger.settle(i, c.claimed().get(i), c.upTo());
		}
		ledger.settle(SlotLedger.CURSOR, cursor.copy(), c.upTo());
		corrected += answered.size();
		dropStreak = 0;
		if (enforce) showAll();
		line("check answered in {} ticks: {} slots corrected {}, {} pending ones confirmed", Ticks.since(c.sentTick()), answered.size(),
			answered, vouched);
	}

	/**
	 * Follows the player's open menu, starting over from what it shows when that's another one. Not in creative: the
	 * inventory is yours to set there (the server takes each slot as sent, no clicks), and the creative screen's own menu
	 * has your inventory's id with slots numbered nothing like it, so the server's slots would land in its grid.
	 */
	private boolean follow() {
		if (mc.player == null) return false;
		AbstractContainerMenu open = mc.player.containerMenu;
		if (mc.player.isCreative() || open instanceof CreativeModeInventoryScreen.ItemPickerMenu) {
			if (menu != null) {
				menu = null;
				seen.clear();
				beforeClick = null;
				differing.clear();
				check = null;
			}
			return false;
		}
		if (open == menu) return true;
		menu = open;
		List<ItemStack> now = new ArrayList<>(open.slots.size());
		seen.clear();
		// Copies: the game changes stacks in place (a stack moved out of a slot can be emptied), which would change what
		// the ledger holds with them.
		for (Slot s : open.slots) {
			now.add(s.getItem().copy());
			seen.add(s.getItem().copy());
		}
		seenCursor = open.getCarried().copy();
		ledger.reset(now, seenCursor);
		beforeClick = null;
		differing.clear();
		check = null;
		return true;
	}

	/** Records changes made since the menu was last seen that no click made (from the game's own predictions). */
	private void absorb() {
		if (!follow()) return;
		boolean started = false;
		for (int i = 0; i < seen.size() && i < menu.slots.size(); i++) {
			ItemStack now = menu.slots.get(i).getItem();
			if (ItemStack.matches(now, seen.get(i))) continue;
			seen.set(i, now.copy());
			ItemStack logical = ledger.logical(i);
			if (ItemStack.matches(now, logical)) continue;
			if (!started) ledger.nextChange();
			started = true;
			ledger.predict(i, logical, now.copy(), Ticks.current());
			local++;
		}
		ItemStack cursor = menu.getCarried();
		if (!ItemStack.matches(cursor, seenCursor)) {
			seenCursor = cursor.copy();
			if (!ItemStack.matches(cursor, ledger.logical(SlotLedger.CURSOR))) {
				if (!started) ledger.nextChange();
				ledger.predict(SlotLedger.CURSOR, ledger.logical(SlotLedger.CURSOR), seenCursor, Ticks.current());
				local++;
			}
		}
	}

	/** A click is about to be made in menu {@code containerId} (MultiPlayerGameModeMixin, before vanilla). */
	public void beforeClick(int containerId) {
		absorb();
		if (menu == null || menu.containerId != containerId) {
			beforeClick = null;
			return;
		}
		List<ItemStack> before = new ArrayList<>(menu.slots.size() + 1);
		for (Slot s : menu.slots) before.add(s.getItem().copy());
		before.add(menu.getCarried().copy());
		beforeClick = before;
	}

	/** The click was made (MultiPlayerGameModeMixin, after vanilla sent it): its changes are recorded. */
	public void afterClick(int containerId, int slotNum, int button, ContainerInput input) {
		List<ItemStack> before = beforeClick;
		beforeClick = null;
		if (before == null || menu == null || mc.player == null || mc.player.containerMenu != menu || menu.containerId != containerId) return;
		clicks++;
		clickedAt = Ticks.current();
		ledger.nextChange();
		long tick = Ticks.current();
		int n = Math.min(Math.min(seen.size(), menu.slots.size()), before.size() - 1);
		for (int i = 0; i < n; i++) {
			ItemStack now = menu.slots.get(i).getItem();
			if (ItemStack.matches(before.get(i), now)) continue;
			ledger.predict(i, before.get(i), now.copy(), tick);
			seen.set(i, now.copy());
			steps++;
		}
		ItemStack cursor = menu.getCarried();
		if (!ItemStack.matches(before.getLast(), cursor)) {
			ledger.predict(SlotLedger.CURSOR, before.getLast(), cursor.copy(), tick);
			seenCursor = cursor.copy();
			steps++;
		}
	}

	/** A packet about the menu is about to be applied: changes made since it was last seen are recorded first. */
	public void beforeServer() {
		absorb();
	}

	/**
	 * Ticks within which a slot the server sent may have crossed a click on the way (a round trip): only which of the
	 * two is shown until a check answers depends on it, never what's settled.
	 */
	private static long crossing() {
		return SlotsInFlight.roundTrip() + 1L;
	}

	/** Whether a packet for {@code containerId} is about the menu followed. */
	private boolean about(int containerId) {
		return menu != null && mc.player != null && mc.player.containerMenu == menu && menu.containerId == containerId;
	}

	/** The server set one slot (after vanilla applied it). */
	public void onSlot(int containerId, int slot, ItemStack item) {
		if (!about(containerId) || slot < 0 || slot >= seen.size()) return;
		heardAt = Ticks.current();
		ItemStack before = ledger.logical(slot);
		if (check != null) answered.add(slot);
		count(ledger.onSlot(slot, item.copy(), Ticks.current(), crossing()), slot, before, item);
		if (enforce) show(slot);
		seen.set(slot, menu.slots.get(slot).getItem().copy());
	}

	/** The server sent the whole menu (after vanilla applied it). */
	public void onContents(int containerId, List<ItemStack> items, ItemStack cursor) {
		if (!about(containerId)) return;
		heardAt = Ticks.current();
		// The server's slots are as many as it sends.
		while (seen.size() > items.size()) seen.removeLast();
		while (seen.size() < items.size() && seen.size() < menu.slots.size()) seen.add(menu.slots.get(seen.size()).getItem().copy());
		// The check went out with a state id already old (the server's answers to earlier clicks were on their way), so
		// this may be its answer rather than the slots and cursor it asked for: it's dropped, and another goes out once
		// things are quiet. The menu is weighed as any.
		if (check != null) {
			check = null;
			abandoned++;
			dropStreak++;
			line("check dropped: a whole menu came before its answer");
		}
		List<ItemStack> before = new ArrayList<>(items.size() + 1);
		List<ItemStack> copies = new ArrayList<>(items.size());
		for (int i = 0; i < items.size(); i++) {
			before.add(ledger.logical(i));
			copies.add(items.get(i).copy());
		}
		before.add(ledger.logical(SlotLedger.CURSOR));
		Verdict[] verdicts = ledger.onContents(copies, cursor.copy());
		for (int i = 0; i < verdicts.length; i++) {
			int slot = i == items.size() ? SlotLedger.CURSOR : i;
			count(verdicts[i], slot, before.get(i), slot == SlotLedger.CURSOR ? cursor : items.get(i));
		}
		if (enforce) showAll();
		for (int i = 0; i < seen.size() && i < menu.slots.size(); i++) seen.set(i, menu.slots.get(i).getItem().copy());
		seenCursor = menu.getCarried().copy();
	}

	/** The server set the cursor (after vanilla applied it). */
	public void onCursor(ItemStack item) {
		if (menu == null || mc.player == null || mc.player.containerMenu != menu) return;
		if (check != null && check.menu() == menu) {
			checked(item);
			seenCursor = menu.getCarried().copy();
			return;
		}
		ItemStack before = ledger.logical(SlotLedger.CURSOR);
		count(ledger.onCursor(item.copy(), Ticks.current(), crossing()), SlotLedger.CURSOR, before, item);
		if (enforce) show(SlotLedger.CURSOR);
		seenCursor = menu.getCarried().copy();
	}

	/** The server set a slot of your inventory by its index there (after vanilla applied it). */
	public void onInventorySlot(int index, ItemStack item) {
		if (menu == null || mc.player == null || mc.player.containerMenu != menu) return;
		Inventory inventory = mc.player.getInventory();
		for (Slot s : menu.slots) {
			if (s.container == inventory && s.getContainerSlot() == index && s.index < seen.size()) {
				ItemStack before = ledger.logical(s.index);
				if (check != null) answered.add(s.index);
				count(ledger.onSlot(s.index, item.copy(), Ticks.current(), crossing()), s.index, before, item);
				if (enforce) show(s.index);
				seen.set(s.index, s.getItem().copy());
				return;
			}
		}
	}

	/**
	 * The slot shows the ledger's view: a value the server sent before it took clicks still on their way is put back to
	 * what they make, before anything is drawn or decided on it.
	 */
	private void show(int slot) {
		ItemStack logical = ledger.logical(slot);
		if (slot == SlotLedger.CURSOR) {
			if (!ItemStack.matches(menu.getCarried(), logical)) menu.setCarried(logical.copy());
		} else if (slot >= 0 && slot < seen.size() && slot < menu.slots.size() && !ItemStack.matches(menu.slots.get(slot).getItem(), logical)) {
			menu.slots.get(slot).set(logical.copy());
		}
	}

	private void showAll() {
		for (int i = -1; i < seen.size(); i++) show(i);
	}

	private void count(Verdict v, int slot, ItemStack before, ItemStack value) {
		switch (v) {
			case SERVER -> server++;
			case ECHO -> echoes++;
			case STALE -> stale++;
			case CONTRADICTS -> {
				contradicts++;
				line("menu {} slot {}: server says {}, the ledger had {}", menu.containerId, slot, describe(value), describe(before));
			}
		}
	}

	/** Late, after the tick's clicks and other changes. */
	@Subscribe(priority = -900)
	private void onTick(TickEvent.Post e) {
		if (mc.player == null) return;
		absorb();
		if (menu == null) return;
		if (enforce) checkWhenDone();
		for (int i = -1; i < seen.size() && i < menu.slots.size(); i++) {
			ItemStack shown = i < 0 ? menu.getCarried() : menu.slots.get(i).getItem();
			ItemStack logical = ledger.logical(i);
			if (ItemStack.matches(shown, logical)) {
				differing.remove(i);
				continue;
			}
			differTicks++;
			if (differing.add(i)) {
				differEpisodes++;
				{
					long oldest = ledger.steps(i).isEmpty() ? -1 : ledger.steps(i).getFirst().tick();
					line("menu {} slot {} shows {} while clicks on their way make it {} ({} steps, oldest {} ticks)", menu.containerId, i,
						describe(shown), describe(logical), ledger.steps(i).size(), oldest < 0 ? "-" : Ticks.since(oldest));
				}
			}
		}
		if (log && Ticks.current() >= summaryAt) {
			summaryAt = Ticks.current() + SUMMARY_TICKS;
			if (clicks + local + echoes + stale + contradicts > 0) MyriadImpl.LOG.info("[ledger] {} ({} lines left out)", summary(), linesDropped);
		}
	}

	/**
	 * Once clicking stops and the server's answers to it are in, with changes still unanswered, a check settles them: a
	 * server answers a change it made as predicted with nothing, and one an anti-cheat dropped likewise. Not mid-drag,
	 * which any click would cut short ({@link #dragging}).
	 */
	private void checkWhenDone() {
		// A check carries the menu's state id: sent before the answers to the last clicks are in, it's out of date, and the
		// server answers it with the whole menu instead (dropped then, see onContents). So it waits a round trip after the
		// last click, and for the answers to stop. This only says when to check; what's true is the answer's to say.
		if (check != null || !ledger.anyNeedsCheck() || mc.getConnection() == null) return;
		long wait = (SlotsInFlight.roundTrip() + 1L) << Math.min(dropStreak, 5);
		if (Ticks.current() - clickedAt <= wait || Ticks.current() - heardAt < 2 + Math.min(dropStreak, 5) * 4L) return;
		if (dragging(menu)) return;
		// A check can wait: not while a build runs (it asks for the inventory once it's done), unless your inventory or a
		// container is on screen (a build kept up, as Printer's, runs all the while it's on, and what you're clicking at
		// would stay wrong until it ended); never when clicks are short (a module's would wait behind it; a server like
		// 2b2t counts every one), and only at a moment already safe to click (sprint stopped for the tick at most, keys
		// never let go).
		if (!Myriad.building().running().isEmpty() && !(mc.gui.screen() instanceof AbstractContainerScreen<?>)) return;
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, CHECK_HEADROOM)) return;
		if (!Myriad.inventory().safeToClick() || !Myriad.inventory().prepareClick()) return;
		sendCheck(false);
	}

	/**
	 * Whether a drag (spreading the cursor's stack over slots) is under way in {@code menu}: any other click would end
	 * it, on the server too. Not merely something on the cursor: one the client shows there wrongly is what a check
	 * puts right.
	 */
	static boolean dragging(AbstractContainerMenu menu) {
		return ((AbstractContainerMenuAccessor) menu).myriad$quickcraftStatus() != 0;
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		menu = null;
		check = null;
		seen.clear();
		beforeClick = null;
		differing.clear();
	}

	/** A line in the log, while this second's allowance lasts. */
	private void line(String format, Object... args) {
		if (!log) return;
		long second = Ticks.current() / 20;
		if (second != linesSecond) {
			linesSecond = second;
			lines = 0;
		}
		if (lines++ < LINES_PER_SECOND) MyriadImpl.LOG.info("[ledger] " + format, args);
		else linesDropped++;
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + " " + stack.getItem();
	}
}
