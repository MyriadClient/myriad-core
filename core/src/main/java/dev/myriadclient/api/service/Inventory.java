package dev.myriadclient.api.service;

import dev.myriadclient.api.annotation.RenderThread;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Replacement;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.item.Slots;
import dev.myriadclient.api.player.Mining;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The player's one inventory, shared between every module that wants it: which hotbar slot the server holds, which
 * items sit in the hotbar, and the clicks that move them. Modules ask here instead of setting the selected slot or
 * clicking themselves, so that two of them acting in the same tick don't undo each other and nothing goes out that an
 * anti-cheat refuses.
 *
 * <h2>The slot the server holds</h2>
 * The visible slot ({@link #select}) is yours. A module that needs another item in hand changes only the server's slot
 * ({@link #serverSlot()}, {@link #serverItem()}):
 * <ul>
 * <li>{@link #silentSwap} for one action this tick; the server goes back to the slot it should hold first thing next
 * tick.</li>
 * <li>{@link #hold} across ticks, one owner at a time, until {@link #release} or its time runs out
 * ({@link #holdWeakly} for a hold anyone else's takes over).</li>
 * </ul>
 *
 * <h2>Borrowing and masking</h2>
 * {@link #borrow} brings an item from the main inventory into the hotbar while it's wanted and puts it back, and what
 * it displaced, once nobody has asked for it for half a second: a tool used from the inventory leaves the hotbar as it
 * was, and the hotbar slot keeps showing what was there ({@link #shownInHotbar}). {@link #mask} goes further, keeping
 * a set of slots looking as they did while an owner moves items among them, so a swap needn't be seen at all
 * ({@link #shown}, {@link #shownArmor()}). Both lapse on their own once they stop being renewed. {@link #spare} keeps
 * items out of the "best item" searches, and lasts until its owner withdraws it.
 *
 * <h2>Clicks</h2>
 * Moves ({@link #move}, {@link #merge}, {@link #quickMove}, {@link #gather}, {@link #drop}, {@link #moveToHotbar},
 * {@link #swapWithOffhand}, {@link #pullToHotbar}, {@link #borrow}) are clicks in your own inventory's menu, which
 * the server takes without its screen being open, as vanilla's number keys do. Each one returns false (or -1) instead
 * of clicking when it can't go now, so ask again next tick:
 * <ul>
 * <li>A container is open ({@link #containerOpen()} says so for one with its screen up): the server takes clicks only
 * in the menu it has open. One held open ({@link Containers#hold}) is the exception: it takes the click on its own
 * slot for the same item.</li>
 * <li>The click budget is spent: {@link PacketLimits.Kind#INVENTORY}, sized for the strictest servers measured (72 in
 * any 5 seconds; 2b2t disconnects on the 80th click in about 4). A sort or refill of up to that many clicks goes in
 * one tick. Wrap a move that must happen whatever the cost (a totem) in {@link PacketLimits#urgent}.</li>
 * <li>The server hasn't answered the last click on one of the slots yet: with lag, a click made on what the client
 * still shows would undo what the server already did.</li>
 * <li>The server would refuse a click now. Grim refuses inventory clicks while you sprint or (depending on the version
 * it sees you as) hold movement keys; each move calls {@link #prepareClick()} for you, which gets past sprint at once
 * and lets go of your keys for a tick otherwise.</li>
 * </ul>
 * Three methods tell those moments apart:
 * <ul>
 * <li>{@link #safeToClick()}: whether a click would go now without changing anything about how you move. Moves that
 * can wait (refills, tools) wait for it.</li>
 * <li>{@link #prepareClick()}: call just before clicking; true if a click can go now, and if not, your keys are let go
 * for the next tick so asking again then works. For moves that can't wait (a totem).</li>
 * <li>{@link #containerOpen()}: whether a container's screen is up, which none of the moves click past.</li>
 * </ul>
 *
 * <pre>{@code
 * // A refill that waits for a moment that costs nothing: the moves return false until then.
 * int slot = Myriad.inventory().findInInventory(s -> s.is(Items.GOLDEN_APPLE));
 * if (slot != -1 && !Myriad.inventory().containerOpen() && Myriad.inventory().safeToClick()) {
 *     Myriad.inventory().move(slot, 8);
 * }
 *
 * // A pickaxe from the inventory, held at the server for this tick's mining, back where it was afterwards.
 * int pick = Myriad.inventory().bestInInventory(s -> s.is(ItemTags.PICKAXES) ? 1 : 0);
 * int hotbar = pick == -1 ? -1 : Myriad.inventory().borrow(this, pick, s -> false);
 * if (hotbar != -1) Myriad.inventory().hold(this, hotbar, 2);
 * }</pre>
 *
 * Inventory indexes are {@link Slots}': 0-8 the hotbar, 9-35 the main inventory, 36-39 armour (feet to head), 40 the
 * off hand. Owners are as everywhere: pass your module.
 */
@ApiStatus.NonExtendable
@RenderThread
public interface Inventory {
	/**
	 * The hotbar slot (0-8) the server holds: the last one sent, by anyone, or set by the server itself (on join, or by
	 * a plugin). It differs from the visible slot during a {@link #silentSwap} or a {@link #hold}. Safe to read from any
	 * thread.
	 */
	int serverSlot();

	/**
	 * Selects hotbar slot {@code hotbarSlot} (0-8) as pressing its number key would: the visible slot changes and the
	 * server is told at once. Does nothing outside a world or for a slot outside 0-8.
	 */
	void select(int hotbarSlot);

	/**
	 * Silently switches the server-side slot to {@code hotbarSlot} and runs {@code action} with it in hand (the client
	 * holds it too while the action runs, so what it predicts is with that item; the visible slot doesn't change). The
	 * server goes back to your visible slot, or a {@link #hold}'s, first thing next tick: switching back right after an
	 * action is what Grim flags. Sends nothing when the server already holds that slot, so swapping to the same slot
	 * tick after tick costs one switch each way. Where the server doesn't check the order of packets in a tick
	 * ({@link AntiCheat.Profile#checksPacketOrder()} false, 2b2t) it goes back straight after the action instead, so
	 * the item never shows in your hand. Does nothing (and doesn't run {@code action}) outside a world or for a slot
	 * outside 0-8.
	 */
	void silentSwap(int hotbarSlot, Runnable action);

	/**
	 * Holds the server-side slot on {@code hotbarSlot} across ticks without changing the visible slot, until
	 * {@link #release} or {@code maxTicks} ticks pass (at least one). One module holds at a time; returns false if
	 * another owner holds, outside a world, or for a slot outside 0-8. Calling it again as the same owner refreshes the
	 * timeout (and moves the hold to the new slot), so a module that holds while it works asks every tick with a short
	 * timeout and the hold ends by itself once it stops asking. A hold refused because someone else holds is handed to
	 * you straight away if theirs ends within a tick of your asking, for a tick or two (ask again to keep it).
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

	/**
	 * Ends {@code owner}'s hold and puts the server back on the visible slot (or straight on the slot of a module whose
	 * hold was refused in the last tick, see {@link #hold}). Does nothing if {@code owner} doesn't hold.
	 */
	void release(Object owner);

	/** Whether any module is holding a slot. */
	boolean isHolding();

	/**
	 * The item the server thinks is in your main hand: the held slot's item while a module {@link #hold holds} one or a
	 * {@link #silentSwap} hasn't gone back yet, otherwise the selected item; empty outside a world. Mining speed and
	 * tool checks use it, so holding a pickaxe mines at its speed.
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
	 * {@link Mining#fastestSlot}) pass them over, and modules choosing gear should too
	 * ({@link #isSpared}). One predicate per {@code owner}; null withdraws it. It isn't withdrawn when a module turns
	 * off: withdraw it yourself.
	 */
	void spare(Object owner, @Nullable Predicate<ItemStack> spared);

	/** Whether a module asked for {@code stack} to be left alone ({@link #spare}). */
	boolean isSpared(ItemStack stack);

	/**
	 * Total item count matching across the hotbar, main inventory and off hand (armour isn't counted); 0 outside a
	 * world.
	 */
	int count(Predicate<ItemStack> predicate);

	/**
	 * Swaps the item at {@code inventoryIndex} (0-35, see {@link Slots}) into hotbar slot {@code hotbarSlot}, as
	 * pressing a number key over it would: one click. True at once when they're the same slot. False, with nothing
	 * done, while a container is open, for bad slots, or when the click can't go now (see the type's notes on clicks).
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
	 * where the server doesn't check this ({@link AntiCheat.Profile#clicksOnlyWhileStill()} is false).
	 */
	boolean safeToClick();

	/**
	 * Whether a container's screen is open (a chest, a furnace, a villager's trades), so a click meant for your own
	 * inventory would land in it: modules that click on their own (totems, armour) wait until it's closed. Your own
	 * inventory's screen isn't one, and nor is a container held open without its screen (clicks go past it).
	 */
	@ApiStatus.AvailableSince("2.0")
	boolean containerOpen();

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

	/**
	 * Puts back what {@code owner} borrowed as soon as a click can go, instead of waiting; with a container you opened
	 * yourself, through its menu (which shows your inventory too), so it needn't close first. In a container a module
	 * opened ({@link Containers.View#byHand()} false) it waits for that to close, so it never clicks in the middle of
	 * that module's work.
	 */
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
	// do). They return false, with nothing done, while a container is open (one held open without its screen takes
	// them), for bad slots, when the packet budget is spent (see PacketLimits; wrap must-happen moves in
	// Myriad.limits().urgent), while the server hasn't answered the last click on one of the slots, or when the server
	// would refuse a click now (prepareClick() is called for you, so asking again next tick works). Indexes are
	// inventory indexes (see Slots): 0-8 hotbar, 9-35 main, 36-39 armour (feet..head), 40 off hand.

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

	/**
	 * Fills the stack at {@code inventoryIndex} (hotbar or main inventory) from the main inventory's stacks of the same
	 * item with a double click: picked up, gathered, put back, three clicks for any number of stacks. False (and nothing
	 * done) unless the main inventory holds enough to fill it and nothing else would be gathered too: no other stack of it
	 * short of full in the hotbar or the off hand (a double click takes those first).
	 */
	@ApiStatus.AvailableSince("1.8")
	boolean gather(int inventoryIndex);

	/**
	 * Throws the item at {@code inventoryIndex} on the ground, the whole stack or one of it, as the drop key does: one
	 * click.
	 */
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

	/**
	 * Arranges your own inventory as {@link Containers.View#arrange} does a container's: sorting it, say, with your
	 * inventory screen open or no screen at all. Slot ids are your inventory menu's: 9-35 the main inventory, 36-44 the
	 * hotbar, 45 the off hand. Completes with whether the layout was reached. Fails if another menu opens meanwhile, or
	 * once you click in it yourself (what the arrangement holds on the cursor is put down first).
	 *
	 * <pre>{@code
	 * int[] main = IntStream.range(9, 36).toArray();
	 * Myriad.inventory().arrange(Containers.Arrangement.of(main, sortedLayout));
	 * }</pre>
	 */
	CompletableFuture<Boolean> arrange(Containers.Arrangement arrangement);

	/**
	 * The last movement the server saw, for {@link #CLICK_READY}: whether it had sprint on, a movement key or jump
	 * down, and sneak.
	 *
	 * @param sprinting whether the server last heard you sprinting
	 * @param keys whether a movement key or jump was down in the last input the server heard
	 * @param sneaking whether sneak was down in the last input the server heard
	 */
	record ClickMoment(boolean sprinting, boolean keys, boolean sneaking) {
	}

	/**
	 * Whether an inventory click may go out now ({@link #safeToClick()}). Core: always, except where the anti-cheat
	 * refuses clicks while you move (Grim), then only once the input the server last saw allows it.
	 */
	Hook<ClickMoment, Boolean> CLICK_READY = Hook.of("myriad:click_ready", "Click ready",
		"Whether an inventory click may be sent now.");

	/**
	 * The base for a replacement of this service ({@link Service#INVENTORY}): hands every call
	 * to the service it wraps (core's), so override only what you do differently. While it's in use it gets Myriad's
	 * events and {@link #activated()}/{@link #deactivated()}.
	 */
	abstract class Forwarding implements Inventory, Replacement {
		/** The service this one wraps: core's own. */
		protected final Inventory core;

		/** Wraps {@code core}, the service handed to your replacement's factory. */
		protected Forwarding(Inventory core) {
			this.core = Objects.requireNonNull(core);
		}

		@Override
		public int serverSlot() {
			return core.serverSlot();
		}

		@Override
		public void select(int hotbarSlot) {
			core.select(hotbarSlot);
		}

		@Override
		public void silentSwap(int hotbarSlot, Runnable action) {
			core.silentSwap(hotbarSlot, action);
		}

		@Override
		public boolean hold(Object owner, int hotbarSlot, int maxTicks) {
			return core.hold(owner, hotbarSlot, maxTicks);
		}

		@Override
		public boolean holdWeakly(Object owner, int hotbarSlot, int maxTicks) {
			return core.holdWeakly(owner, hotbarSlot, maxTicks);
		}

		@Override
		public void release(Object owner) {
			core.release(owner);
		}

		@Override
		public boolean isHolding() {
			return core.isHolding();
		}

		@Override
		public ItemStack serverItem() {
			return core.serverItem();
		}

		@Override
		public float attackCharge() {
			return core.attackCharge();
		}

		@Override
		public int findInHotbar(Predicate<ItemStack> predicate) {
			return core.findInHotbar(predicate);
		}

		@Override
		public int findInInventory(Predicate<ItemStack> predicate) {
			return core.findInInventory(predicate);
		}

		@Override
		public int bestInHotbar(ToDoubleFunction<ItemStack> score) {
			return core.bestInHotbar(score);
		}

		@Override
		public int bestInInventory(ToDoubleFunction<ItemStack> score) {
			return core.bestInInventory(score);
		}

		@Override
		public void spare(Object owner, @Nullable Predicate<ItemStack> spared) {
			core.spare(owner, spared);
		}

		@Override
		public boolean isSpared(ItemStack stack) {
			return core.isSpared(stack);
		}

		@Override
		public int count(Predicate<ItemStack> predicate) {
			return core.count(predicate);
		}

		@Override
		public boolean moveToHotbar(int inventoryIndex, int hotbarSlot) {
			return core.moveToHotbar(inventoryIndex, hotbarSlot);
		}

		@Override
		public int ensureInHotbar(Predicate<ItemStack> predicate, int preferredSlot) {
			return core.ensureInHotbar(predicate, preferredSlot);
		}

		@Override
		public boolean safeToClick() {
			return core.safeToClick();
		}

		@Override
		public boolean containerOpen() {
			return core.containerOpen();
		}

		@Override
		public boolean prepareClick() {
			return core.prepareClick();
		}

		@Override
		public boolean clickWaiting() {
			return core.clickWaiting();
		}

		@Override
		public void keepKeys() {
			core.keepKeys();
		}

		@Override
		public int pullToHotbar(int inventoryIndex, Predicate<ItemStack> replaceable) {
			return core.pullToHotbar(inventoryIndex, replaceable);
		}

		@Override
		public int borrow(Object owner, int inventoryIndex, Predicate<ItemStack> replaceable) {
			return core.borrow(owner, inventoryIndex, replaceable);
		}

		@Override
		public void giveBack(Object owner) {
			core.giveBack(owner);
		}

		@Override
		public ItemStack shownInHotbar(int hotbarSlot) {
			return core.shownInHotbar(hotbarSlot);
		}

		@Override
		public void mask(Object owner, long holdMs, int... inventoryIndexes) {
			core.mask(owner, holdMs, inventoryIndexes);
		}

		@Override
		public void unmask(Object owner) {
			core.unmask(owner);
		}

		@Override
		public ItemStack shown(int inventoryIndex) {
			return core.shown(inventoryIndex);
		}

		@Override
		public int shownArmor() {
			return core.shownArmor();
		}

		@Override
		public boolean move(int from, int to) {
			return core.move(from, to);
		}

		@Override
		public boolean merge(int from, int to) {
			return core.merge(from, to);
		}

		@Override
		public boolean swapWithOffhand(int inventoryIndex) {
			return core.swapWithOffhand(inventoryIndex);
		}

		@Override
		public boolean quickMove(int inventoryIndex) {
			return core.quickMove(inventoryIndex);
		}

		@Override
		public boolean gather(int inventoryIndex) {
			return core.gather(inventoryIndex);
		}

		@Override
		public boolean drop(int inventoryIndex, boolean wholeStack) {
			return core.drop(inventoryIndex, wholeStack);
		}

		@Override
		public CompletableFuture<Void> refresh() {
			return core.refresh();
		}

		@Override
		public CompletableFuture<Boolean> arrange(Containers.Arrangement arrangement) {
			return core.arrange(arrangement);
		}
	}
}
