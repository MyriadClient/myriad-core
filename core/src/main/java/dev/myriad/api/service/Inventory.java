package dev.myriad.api.service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Hotbar helpers that track the slot the server thinks is selected. */
@ApiStatus.NonExtendable
public interface Inventory {
	/** The hotbar slot (0-8) last sent to the server. */
	int serverSlot();

	/** Selects a hotbar slot (client and server). */
	void select(int hotbarSlot);

	/**
	 * Silently switches the server-side slot to {@code hotbarSlot} and runs {@code action} with it in hand (the client
	 * holds it too while the action runs, so what it predicts is with that item; the visible slot doesn't change). The
	 * server goes back to your visible slot, or a {@link #hold}'s, first thing next tick: switching back right after an
	 * action is what Grim flags. Sends nothing when the server already holds that slot, so swapping to the same slot
	 * tick after tick costs one switch each way.
	 */
	void silentSwap(int hotbarSlot, Runnable action);

	/**
	 * Holds the server-side slot on {@code hotbarSlot} across ticks without changing the visible slot, until
	 * {@link #release} or {@code maxTicks} pass. One module holds at a time; returns false if another owner holds.
	 * Calling it again as the same owner refreshes the timeout (and moves the hold to the new slot).
	 * <p>
	 * Your own right clicks (using an item, placing, eating) use the slot you see: the hold steps aside for them and
	 * comes back once you're done (it returns false meanwhile). Your left clicks attack and mine with the held item.
	 */
	boolean hold(Object owner, int hotbarSlot, int maxTicks);

	/**
	 * Like {@link #hold}, but any other module's {@link #hold} takes it over: for holding something just in case (a weapon
	 * charging before a target is in reach) without keeping others from what they need now.
	 */
	boolean holdWeakly(Object owner, int hotbarSlot, int maxTicks);

	/** Ends {@code owner}'s hold and syncs the server back to the visible slot. */
	void release(Object owner);

	/** Whether any module is holding a slot. */
	boolean isHolding();

	/**
	 * The item the server thinks is in your main hand: the held slot's item while a module {@link #hold holds} one,
	 * otherwise the selected item. Mining speed and tool checks use it, so holding a pickaxe mines at its speed.
	 */
	ItemStack serverItem();

	/**
	 * The attack charge (0-1) of the item the server holds in your main hand, as the server counts it: from your last
	 * hit, or from when that item came into your hand (switching to a different item starts the charge over). With a
	 * {@link #hold} the client's own charge follows the item you see instead, so a hit with a held weapon waits for
	 * this one. A full charge (1) hits for full damage.
	 */
	float attackCharge();

	/** First hotbar slot matching, or -1. */
	int findInHotbar(Predicate<ItemStack> predicate);

	/** First main-inventory slot (9-35) matching, or -1. */
	int findInInventory(Predicate<ItemStack> predicate);

	/** The hotbar slot whose item scores highest; items scoring 0 or less don't count, nor {@link #spare spared} ones. -1 if none. */
	int bestInHotbar(ToDoubleFunction<ItemStack> score);

	/** Like {@link #bestInHotbar} over the hotbar and main inventory (0-35). */
	int bestInInventory(ToDoubleFunction<ItemStack> score);

	/**
	 * Asks for items {@code spared} accepts to be left alone, e.g. gear about to break that's been put away to be
	 * mended later: {@link #bestInHotbar}, {@link #bestInInventory} and the tools breaks pick (see
	 * {@link dev.myriad.api.util.Mining#fastestSlot}) pass them over, and modules choosing gear should too
	 * ({@link #isSpared}). One predicate per {@code owner}; null withdraws it.
	 */
	void spare(Object owner, @Nullable Predicate<ItemStack> spared);

	/** Whether a module asked for {@code stack} to be left alone ({@link #spare}). */
	boolean isSpared(ItemStack stack);

	/** Total item count matching across the hotbar, main inventory and off hand. */
	int count(Predicate<ItemStack> predicate);

	/**
	 * Swaps the item at {@code inventoryIndex} (0-35, see {@link dev.myriad.api.util.Slots}) into hotbar slot
	 * {@code hotbarSlot}, as pressing a number key over it would. Only works while no container is open; returns
	 * false otherwise.
	 */
	boolean moveToHotbar(int inventoryIndex, int hotbarSlot);

	/**
	 * The hotbar slot holding a matching item, moving one in from the main inventory if needed: into
	 * {@code preferredSlot}, or the first empty hotbar slot when that's -1 (falling back to the selected slot).
	 * Returns -1 if you have none or it couldn't be moved.
	 */
	int ensureInHotbar(Predicate<ItemStack> predicate, int preferredSlot);

	/**
	 * Whether an inventory click now would pass anti-cheats that refuse clicks while you move. Grim cancels a click
	 * while you sprint and, depending on the version the server sees you as (ViaFabricPlus's, if it translates), when
	 * the last movement keys it heard had a direction or jump down (from 1.21.2), or sneak (from 1.21.9). 2b2t's Grim
	 * only judges sprint and sneak. Sprinting doesn't make this false while this tick's movement hasn't gone out:
	 * {@link #prepareClick()} gets a click past it at no cost.
	 * <p>
	 * Moves that can wait (refills, tools, building material) should wait for this. A click made anyway isn't lost:
	 * core holds it back until a tick the server takes it on, and the client shows its result meanwhile. Always true
	 * where the server doesn't check this ({@link AntiCheat#isStrict()} is false).
	 */
	boolean safeToClick();

	/**
	 * Call it just before clicking: true if a click can go now. While you sprint, the server is told you stopped just
	 * before the click, and you sprint on: vanilla tells it you started again with this tick's movement, so you never
	 * slow down. The keys the server judges (movement and jump from 1.21.2, sneak from 1.21.9, not on 2b2t) need a still
	 * tick: they're released for the next tick and it returns false, so asking again then gets a click. While sneaking
	 * at an edge it waits instead (letting go would walk you off). Releasing keys costs a moment of slowing down, so
	 * moves that can wait (refills) should check {@link #safeToClick()} first.
	 */
	boolean prepareClick();

	/**
	 * Whether a click (yours from a screen, or a module's) is waiting for a still tick: the keys the server judges were
	 * held (see {@link #prepareClick()}; sprint alone never makes a click wait). A module that holds keys for you can let
	 * go of them for a tick where that costs it least, so the click goes then.
	 */
	boolean clickWaiting();

	/**
	 * Keeps your keys as they are this tick although a click is waiting ({@link #prepareClick()} would let go of them):
	 * for a module that needs them this tick (a jump it can't miss) and gives the click a still tick itself soon (see
	 * {@link #clickWaiting()}). Call it each tick it needs them, before the keyboard is read.
	 */
	void keepKeys();

	/**
	 * Brings the item at {@code inventoryIndex} (9-35) into the hotbar to be used, keeping out of the way of what you
	 * use: into an empty slot, else over an item {@code replaceable} accepts (they swap places, so it lands where this
	 * one was), else over ordinary building blocks. Never the selected slot or one a module holds. One click; while you
	 * move, it first releases your keys for a tick ({@link #prepareClick()}). Returns the hotbar slot it went to, or -1
	 * (ask again next tick).
	 */
	int pullToHotbar(int inventoryIndex, Predicate<ItemStack> replaceable);

	/**
	 * Borrows the item at {@code inventoryIndex} for {@code owner}: like {@link #pullToHotbar}, but the item goes back
	 * to that inventory slot (and whatever it made room for back to the hotbar) once you're done with it, so using a tool
	 * or weapon from the inventory leaves the hotbar as it was. Meanwhile the hotbar slot keeps showing what was there.
	 * <p>
	 * Ask again every tick you still want it: pass the hotbar slot it went to (any index 0-8 is fine; one that isn't
	 * borrowed is just returned) and it stays. It also stays while the server holds that slot (a hold or a silent
	 * swap). Once nothing has wanted it for half a second, it's put back with one more click, waiting for a moment
	 * you're not moving if one comes soon, otherwise releasing your keys for a tick as {@link #prepareClick()} does. If
	 * you selected it meanwhile, the slot you had selected is selected again. If the items have moved since, it's left
	 * where it is. Returns the hotbar slot, or -1 (ask again next tick).
	 */
	int borrow(Object owner, int inventoryIndex, Predicate<ItemStack> replaceable);

	/** Puts back what {@code owner} borrowed as soon as a click can go, instead of waiting. */
	void giveBack(Object owner);

	/**
	 * What hotbar slot {@code hotbarSlot} should show: what was there before an item was borrowed into it (while it's not
	 * selected), otherwise its item.
	 */
	ItemStack shownInHotbar(int hotbarSlot);

	/**
	 * Keeps the slots {@code inventoryIndexes} looking as they do now while {@code owner} moves items among them, for
	 * a swap the player needn't see (a chestplate in and out of the chest slot several times a second, say). Each keeps
	 * showing the item it held, wherever among them that item is now (its own stack, so wear and counts stay true):
	 * in the hotbar, the hands, the item name, the armour bar, inventory screens and your player model. Anything else
	 * that turns up in one of them (a pickup) shows as it is, as does a slot whose item has gone.
	 * <p>
	 * Mask before the first move. Call again to keep it (adding slots is fine; each shows what it held when first
	 * masked); it lapses {@code holdMs} after the last call, or at {@link #unmask}, and the slots show what they hold.
	 * Indexes as for {@link #move}.
	 */
	void mask(Object owner, long holdMs, int... inventoryIndexes);

	/** Ends {@code owner}'s mask: its slots show what they hold. */
	void unmask(Object owner);

	/**
	 * What inventory slot {@code inventoryIndex} shows: its item, or what a {@link #mask} or {@link #borrow} keeps it
	 * showing. Indexes as for {@link #move}.
	 */
	ItemStack shown(int inventoryIndex);

	/** The armour points to show: yours, or while a {@link #mask} covers armour slots, what they were when masked. */
	int shownArmor();

	// The moves below click in your own inventory screen, which works without opening it (as vanilla's number keys
	// do). They need no container to be open, and return false otherwise, for bad slots, or when the packet budget
	// is spent (see PacketLimits; wrap must-happen moves in Myriad.limits().urgent). Indexes are inventory indexes
	// (see Slots): 0-8 hotbar, 9-35 main, 36-39 armour (feet..head), 40 off hand.

	/**
	 * Moves the stack at {@code from} to {@code to}, swapping with whatever is there (merging if they stack). One click
	 * with the hotbar involved, or for armour into its own empty slot; otherwise two or three.
	 */
	boolean move(int from, int to);

	/**
	 * Tops up the stack at {@code to} from the stack at {@code from} when they stack together: as much as fits moves
	 * over and the rest stays at {@code from}. Returns false (and does nothing) if they don't stack.
	 */
	boolean merge(int from, int to);

	/**
	 * Swaps {@code inventoryIndex} with the off hand (like pressing F over it), e.g. to put a totem there. From the
	 * hotbar while a click would have to wait ({@link #safeToClick()}), it's done with the swap-hands key and a silent
	 * swap instead, which isn't a click: no waiting while you move.
	 */
	boolean swapWithOffhand(int inventoryIndex);

	/** Shift-clicks {@code inventoryIndex}: armour goes onto you, other items between the hotbar and main inventory. */
	boolean quickMove(int inventoryIndex);

	/** Throws out the stack at {@code inventoryIndex} (or one item). */
	boolean drop(int inventoryIndex, boolean wholeStack);

	/**
	 * Asks the server for your whole inventory (and the open container's contents, if one is open) and completes once
	 * it's here, so the client shows what the server has. The server only answers a click with the slots that came out
	 * differently from what the client predicted, and a click an anti-cheat drops gets no answer at all: the client
	 * then shows items where the server has none. For a module that's lost track of what moved.
	 * <p>
	 * The ask is one click that changes nothing, which the server answers with everything; like any click it waits for
	 * a tick the server takes one on ({@link #safeToClick()}), releasing your keys for one if none comes within a
	 * second, and for the cursor to be empty. It fails if the menu closes meanwhile or no answer comes. Servers older
	 * than 1.17 (through ViaFabricPlus) can't be asked: it completes at once.
	 */
	CompletableFuture<Void> refresh();
}
