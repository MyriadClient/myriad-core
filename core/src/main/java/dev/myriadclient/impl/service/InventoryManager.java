package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.util.Slots;
import dev.myriadclient.api.util.Threads;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.network.ActionTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The inventory service: the slot the server holds, holds between modules, and silent swaps with what their actions
 * leave to undo. The rest is in its parts ({@link ClickGate}, {@link ScreenClicks}, {@link AttackCharge},
 * {@link InventoryClicks}, {@link Borrows}, {@link Masks}), each subscribed beside it.
 */
// size: the whole Inventory API is implemented here, about 200 lines of it one-line delegations to the parts; what's
// left is the server slot's state, which holds, silent swaps and their undos each read and change together.
public final class InventoryManager implements Inventory {
	private final Minecraft mc = Minecraft.getInstance();
	private volatile int serverSlot;
	private Object holder;
	private int holdTicks, holdSlot;
	/** The hold gives way to anyone else's (see holdWeakly). */
	private boolean weakHold;
	/** Set while vanilla handles one of your own right clicks (using an item, placing, interacting). */
	private boolean userClick;
	/** The hold stepped aside for your own click: the server has your visible slot until you're done. */
	private boolean yielded;
	/**
	 * The last hold refused because someone else held: when that hold ends right after, it's handed straight over, so
	 * the server doesn't get your visible slot and then this one again in the same tick.
	 */
	private Object waitingOwner;
	private int waitingSlot, waitingTicks;
	private boolean waitingWeak;
	private long waitingAt;
	/** Set while vanilla handles your own attack or mining. */
	private boolean userAttack;

	/**
	 * A silent swap left the server on {@code swapSlot}: it goes back at the start of tick {@code restoreAt}, before
	 * anything else is sent, as Grim (PacketOrderE) flags a slot change after an attack or use in the same tick.
	 */
	private boolean restorePending;
	private int swapSlot;
	private long restoreAt;
	/**
	 * What swaps' actions left to undo (an air placement's off hand swap), each with the slot it was made with and the
	 * tick it may go out: never in the tick of its click, as Grim (PacketOrderG) cancels an off hand swap sent after a
	 * use in the same tick.
	 */
	private final List<Undo> undos = new ArrayList<>();
	private record Undo(int slot, long made, long at, Runnable action) {
	}
	/** The slot the server holds for the silent swap action running now. */
	private int actionSlot = -1;
	private boolean restoring;

	/**
	 * An attack, use, mining or pick went out since this tick's movement: Grim (PacketOrderG) cancels an off hand swap
	 * after one of those in the same tick.
	 */
	private boolean actedThisTick;

	// The parts: each subscribed beside this (see parts()).
	private final ClickGate gate = new ClickGate();
	private final ScreenClicks screenClicks = new ScreenClicks(gate);
	private final AttackCharge charge = new AttackCharge(this);
	private final InventoryClicks clicks = new InventoryClicks(this, gate);
	private final Borrows borrows = new Borrows(this, clicks, gate);
	private final SlotsInFlight inFlight = new SlotsInFlight();
	private final HotbarRestore restore = new HotbarRestore(this, clicks, gate, inFlight);
	private final Masks masks = new Masks(borrows, restore);
	private final OffHandWatch offHand = new OffHandWatch(this, clicks, masks);

	/** What has handlers of its own besides this: subscribed with it. */
	public List<Object> parts() {
		return List.of(gate, screenClicks, charge, borrows, masks, restore, offHand);
	}

	/** Lowest priority: what actually goes out, after other handlers have changed it. */
	@Subscribe(priority = Priority.LOWEST, packets = {ServerboundSetCarriedItemPacket.class, ServerboundAttackPacket.class,
		ServerboundInteractPacket.class, ServerboundUseItemPacket.class, ServerboundUseItemOnPacket.class,
		ServerboundPlayerActionPacket.class, ServerboundPickItemFromBlockPacket.class, ServerboundPickItemFromEntityPacket.class,
		ServerboundMovePlayerPacket.class, ServerboundClientTickEndPacket.class})
	private void onSend(PacketEvent.Send e) {
		if (e.isCancelled()) return;
		switch (e.packet()) {
			case ServerboundSetCarriedItemPacket p -> serverSlot = p.getSlot();
			case ServerboundAttackPacket p -> actedThisTick = true;
			case ServerboundPlayerActionPacket p -> {
				var a = p.getAction();
				if (a == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND && !clicks.swapping()) offHand.yours();
				if (a != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND && a != ServerboundPlayerActionPacket.Action.DROP_ITEM
					&& a != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) actedThisTick = true;
			}
			case ServerboundMovePlayerPacket p -> actedThisTick = false;
			case ServerboundClientTickEndPacket p -> actedThisTick = false;
			case ServerboundInteractPacket p -> actedThisTick = true;
			case ServerboundUseItemPacket p -> actedThisTick = true;
			case ServerboundUseItemOnPacket p -> actedThisTick = true;
			case ServerboundPickItemFromBlockPacket p -> actedThisTick = true;
			case ServerboundPickItemFromEntityPacket p -> actedThisTick = true;
			default -> {
			}
		}
	}

	/**
	 * First in line for anything that has to follow a silent swap's way back: an action going out on the tick it's due
	 * (from a handler that runs before the tick start's own, say) takes it with it, ahead of itself.
	 */
	@Subscribe(priority = Priority.HIGHEST + 100, packets = {ServerboundAttackPacket.class, ServerboundInteractPacket.class, ServerboundUseItemPacket.class,
		ServerboundUseItemOnPacket.class, ServerboundPlayerActionPacket.class, ServerboundPlayerCommandPacket.class, ServerboundSwingPacket.class,
		ServerboundPlayerInputPacket.class})
	private void beforeAction(PacketEvent.Send e) {
		if (due() && !restoring && mc.player != null && mc.isSameThread()) restore();
	}

	/** The server can set the slot itself (on join, or by a plugin). */
	@Subscribe(packets = ClientboundSetHeldSlotPacket.class)
	private void onReceive(PacketEvent.Receive e) {
		serverSlot = ((ClientboundSetHeldSlotPacket) e.packet()).slot();
	}

	@Override
	public float attackCharge() {
		return charge.charge();
	}

	/**
	 * Expires holds at the start of the tick, so the slot change back goes out before movement, like vanilla's; and takes
	 * the held slot back once you're done with a click it stepped aside for.
	 */
	@Subscribe(priority = 950)
	private void onTick(TickEvent.Pre e) {
		if (holder == null) {
			yielded = false;
			return;
		}
		if (mc.player == null || --holdTicks <= 0) {
			release(holder);
			return;
		}
		if (yielded && !userBusy()) {
			yielded = false;
			if (serverSlot != holdSlot) mc.getConnection().send(new ServerboundSetCarriedItemPacket(holdSlot));
		}
	}

	/**
	 * Takes back what a silent swap left on the last tick, before actions held over from it go out (they were made
	 * with your visible slot) and before anything this tick does.
	 */
	@Subscribe(priority = ActionTiming.FLUSH_PRIORITY + 50)
	private void restoreSwap(TickEvent.Pre e) {
		if (!restorePending && undos.isEmpty()) return;
		if (mc.player == null || mc.getConnection() == null) {
			restorePending = false;
			undos.clear();
		} else if (due()) restore();
	}

	/** Whether the silent swap's way back, or an undo, may go out now. */
	private boolean due() {
		long now = Ticks.current();
		return restorePending && now >= restoreAt || !undos.isEmpty() && now >= undos.getFirst().at;
	}

	/** The slot the server should hold when no silent swap is under way: a hold's, or the one you see. */
	private int wanted() {
		return holder != null && !yielded ? holdSlot : mc.player.getInventory().getSelectedSlot();
	}

	/** Puts the server back on {@link #wanted()} after a silent swap, undoing what its action left first. */
	private void restore() {
		settle();
		int visible = mc.player.getInventory().getSelectedSlot();
		int target = wanted();
		if (serverSlot != target) sendSlot(target);
		// Vanilla's own record of the slot it sent: your visible one (a hold keeps the server elsewhere on purpose).
		mc.gameMode.carriedIndex = visible;
	}

	/** Whether a silent swap is waiting to go back (first thing next tick, or {@link #restoreNow}). */
	public boolean restorePending() {
		return restorePending;
	}

	/**
	 * Sends a silent swap back now, in the tick it was made, instead of first thing next tick: for swaps whose action
	 * was only digging, which Grim's PacketOrderE doesn't mind a slot change after.
	 */
	public void restoreNow() {
		if (restorePending && mc.player != null && mc.getConnection() != null) restore();
	}

	/**
	 * Ends a silent swap before the server's slot changes: runs the undos that may go out now. The server stays on
	 * the last one's slot; whoever settles sends the slot it wants next.
	 */
	private void settle() {
		restorePending = false;
		undo(-1);
	}

	/**
	 * Runs the undos due now, and those for {@code slot} (whose next action needs that hand back) even if early, each
	 * with the client and the server on the slot it was made with: the server is put back there first if something
	 * moved it since. The rest wait for their tick.
	 */
	private void undo(int slot) {
		if (undos.isEmpty() || mc.player == null || mc.getConnection() == null) return;
		long now = Ticks.current();
		var inv = mc.player.getInventory();
		int visible = inv.getSelectedSlot();
		restoring = true;
		try {
			for (var it = undos.iterator(); it.hasNext(); ) {
				Undo u = it.next();
				if (now < u.at && u.slot != slot) continue;
				it.remove();
				if (serverSlot != u.slot) sendSlot(u.slot);
				inv.setSelectedSlot(u.slot);
				mc.gameMode.carriedIndex = u.slot;
				u.action.run();
			}
		} finally {
			inv.setSelectedSlot(visible);
			mc.gameMode.carriedIndex = visible;
			restoring = false;
		}
	}

	/**
	 * For a silent swap's action: runs {@code undo} when the swap goes back (first thing next tick, or the tick after
	 * for an action held over to the next one), with the client and the server on the action's slot. Air placement
	 * swaps the block back out of the off hand this way.
	 */
	public void afterSwap(Runnable undo) {
		if (actionSlot < 0) throw new IllegalStateException("afterSwap outside a silent swap's action");
		long now = Ticks.current();
		undos.add(new Undo(actionSlot, now, now + (ActionTiming.get().isLate() ? 2 : 1), undo));
	}

	/** Whether an undo (an air placement's off hand swap) is still to go out: the off hand isn't what it was. */
	public boolean undoPending() {
		return !undos.isEmpty();
	}

	/**
	 * The hotbar slot an undo was made for this tick, or -1: its block is in the off hand for the rest of the tick, so
	 * more placements of it can go from there without another swap.
	 */
	/** Whether an action (a click, an attack, a use) went out since this tick's movement packet. */
	public boolean actedThisTick() {
		return actedThisTick;
	}

	/** The slot of the first undo still to go out, or -1. */
	public int undoSlot() {
		return undos.isEmpty() ? -1 : undos.getFirst().slot;
	}

	public int undoSlotThisTick() {
		long now = Ticks.current();
		for (Undo u : undos) if (u.made == now) return u.slot;
		return -1;
	}

	private void sendSlot(int slot) {
		mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
	}

	/** Whether you're still using your hand: eating or drawing a bow with it, or holding right click. */
	private boolean userBusy() {
		var p = mc.player;
		return p.isUsingItem() && p.getUsedItemHand() == InteractionHand.MAIN_HAND || mc.options.keyUse.isDown();
	}

	/** Vanilla is handling one of your own right clicks (from the mixin on {@code Minecraft}). */
	public void userClick(boolean active) {
		userClick = active;
	}

	/** Vanilla is handling your own attack or mining (from the mixin on {@code Minecraft}). */
	public void userAttack(boolean active) {
		userAttack = active;
	}

	/**
	 * Vanilla makes sure the server holds your visible slot before an action. For your own right click while a module
	 * holds another slot, the hold steps aside: the server gets your visible slot (what you see is what you use) until
	 * you're done ({@link #userBusy()}), then the held slot again. Left clicks keep the hold: attacking and mining with
	 * what a module holds is what Auto Tool and Kill Aura hold for. A silent swap still waiting to go back goes back
	 * now for either, or when vanilla is about to send a slot you newly selected.
	 */
	public void beforeCarriedSync() {
		if (mc.player == null || restoring) return;
		int visible = mc.player.getInventory().getSelectedSlot();
		boolean own = userClick || userAttack;
		boolean settled = restorePending && (own || visible != mc.gameMode.carriedIndex);
		if (settled) settle();
		// Your own click needs its hand back now; a slot you newly selected leaves undos to go out on their own slot.
		if (own && undoPending()) {
			undo(visible);
			settled = true;
		}
		if (settled && own) {
			int target = userAttack && !userClick ? wanted() : visible;
			if (serverSlot != target) sendSlot(target);
		}
		if (!userClick || holder == null) return;
		if (serverSlot != visible) sendSlot(visible);
		yielded = true;
	}

	@Override
	public boolean hold(Object owner, int hotbarSlot, int maxTicks) {
		return hold(owner, hotbarSlot, maxTicks, false);
	}

	@Override
	public boolean holdWeakly(Object owner, int hotbarSlot, int maxTicks) {
		return hold(owner, hotbarSlot, maxTicks, true);
	}

	private boolean hold(Object owner, int hotbarSlot, int maxTicks, boolean weak) {
		if (mc.player == null || hotbarSlot < 0 || hotbarSlot > 8) return false;
		if (holder != null && holder != owner && (weak || !weakHold)) {
			waitingOwner = owner;
			waitingSlot = hotbarSlot;
			waitingTicks = maxTicks;
			waitingWeak = weak;
			waitingAt = Ticks.current();
			return false;
		}
		if (waitingOwner == owner) waitingOwner = null;
		holder = owner;
		weakHold = weak;
		holdTicks = Math.max(1, maxTicks);
		holdSlot = hotbarSlot;
		// Stepped aside for your own click: taken back once you're done.
		if (yielded) return false;
		settle();
		if (serverSlot != hotbarSlot) sendSlot(hotbarSlot);
		return true;
	}

	@Override
	public void release(Object owner) {
		if (waitingOwner == owner) waitingOwner = null;
		if (holder == null || holder != owner) return;
		holder = null;
		holdTicks = 0;
		yielded = false;
		if (handOver()) return;
		if (mc.player != null && mc.getConnection() != null) {
			settle();
			int visible = mc.player.getInventory().getSelectedSlot();
			if (serverSlot != visible) sendSlot(visible);
		}
	}

	/**
	 * Gives the hold to whoever asked for it this tick or last and was refused, for a couple of ticks (asking again
	 * keeps it, as with any hold).
	 */
	private boolean handOver() {
		Object next = waitingOwner;
		waitingOwner = null;
		if (next == null || Ticks.current() - waitingAt > 1 || mc.player == null || mc.getConnection() == null) return false;
		holder = next;
		weakHold = waitingWeak;
		holdTicks = Math.clamp(waitingTicks, 1, 2);
		holdSlot = waitingSlot;
		settle();
		if (serverSlot != holdSlot) sendSlot(holdSlot);
		return true;
	}

	@Override
	public boolean isHolding() {
		return holder != null;
	}

	@Override
	public ItemStack serverItem() {
		if (mc.player == null) return ItemStack.EMPTY;
		var inv = mc.player.getInventory();
		return holder != null && !yielded || restorePending ? inv.getItem(serverSlot) : inv.getSelectedItem();
	}

	@Override
	public int serverSlot() {
		return serverSlot;
	}

	@Override
	public void select(int hotbarSlot) {
		Threads.assertRender();
		if (mc.player == null || hotbarSlot < 0 || hotbarSlot > 8) return;
		mc.player.getInventory().setSelectedSlot(hotbarSlot);
		mc.gameMode.ensureHasSentCarriedItem();
	}

	/**
	 * The server keeps the slot until the start of the next tick (see {@link #restoreSwap}): switching back in the same
	 * tick, after the action, is what Grim's PacketOrderE flags. Another swap to the same slot meanwhile sends nothing.
	 */
	@Override
	public void silentSwap(int hotbarSlot, Runnable action) {
		Threads.assertRender();
		if (mc.player == null || mc.getConnection() == null || hotbarSlot < 0 || hotbarSlot > 8) return;
		// The last swap ends before the server moves to another slot (its undos go out if they may).
		if (restorePending && swapSlot != hotbarSlot) settle();
		// This slot's own undo first: the action needs its hand back (even in the tick it was made).
		undo(hotbarSlot);
		var inv = mc.player.getInventory();
		int visible = inv.getSelectedSlot();
		if (serverSlot != hotbarSlot) sendSlot(hotbarSlot);
		// The client holds it too for the action, so what it predicts (a placement, an item used) is with that item.
		inv.setSelectedSlot(hotbarSlot);
		mc.gameMode.carriedIndex = hotbarSlot;
		int outer = actionSlot;
		actionSlot = hotbarSlot;
		try {
			action.run();
		} finally {
			actionSlot = outer;
			inv.setSelectedSlot(visible);
			mc.gameMode.carriedIndex = visible;
			if (serverSlot != wanted()) {
				restorePending = true;
				swapSlot = hotbarSlot;
				// Sent after this tick's movement, it goes out with what ActionTiming holds next tick: back the tick after.
				restoreAt = Ticks.current() + (ActionTiming.get().isLate() ? 2 : 1);
			}
		}
	}

	@Override
	public int findInHotbar(Predicate<ItemStack> predicate) {
		return clicks.findInHotbar(predicate);
	}

	@Override
	public int findInInventory(Predicate<ItemStack> predicate) {
		return clicks.findInInventory(predicate);
	}

	@Override
	public void spare(Object owner, @Nullable Predicate<ItemStack> spared) {
		clicks.spare(owner, spared);
	}

	@Override
	public boolean isSpared(ItemStack stack) {
		return clicks.isSpared(stack);
	}

	@Override
	public int bestInHotbar(ToDoubleFunction<ItemStack> score) {
		return clicks.bestInHotbar(score);
	}

	@Override
	public int bestInInventory(ToDoubleFunction<ItemStack> score) {
		return clicks.bestInInventory(score);
	}

	@Override
	public int count(Predicate<ItemStack> predicate) {
		return clicks.count(predicate);
	}

	@Override
	public boolean moveToHotbar(int inventoryIndex, int hotbarSlot) {
		return clicks.moveToHotbar(inventoryIndex, hotbarSlot);
	}

	@Override
	public int ensureInHotbar(Predicate<ItemStack> predicate, int preferredSlot) {
		Threads.assertRender();
		return clicks.ensureInHotbar(predicate, preferredSlot);
	}

	/** See {@link ClickGate}. */
	@Override
	public boolean safeToClick() {
		return gate.safeToClick();
	}

	/** What a click has to wait for, for the version this connection joined as and the server. */
	public ClickRules rules() {
		return gate.rules();
	}

	/** See {@link ClickGate#prepareClick()}. */
	@Override
	public boolean prepareClick() {
		return gate.prepareClick();
	}

	/** Only clicks that need a still tick: sprint alone is got past in the tick (see {@link #prepareClick()}). */
	@Override
	public boolean clickWaiting() {
		return gate.stillWanted() || screenClicks.hasWaiting() && gate.keysRefuse();
	}

	@Override
	public void keepKeys() {
		gate.keepKeys();
	}

	/** Just before the player moves (from the mixin): see {@link ClickGate#beforeTravel()}. */
	public void beforeTravel() {
		gate.beforeTravel();
	}

	/** Your own click in a container screen (from the mixin on it): true if it has to wait, and was kept for later. */
	public boolean holdScreenClick(int containerId, int slotId, int button, ContainerInput input) {
		return screenClicks.hold(containerId, slotId, button, input);
	}

	@Override
	public int pullToHotbar(int inventoryIndex, Predicate<ItemStack> replaceable) {
		if (mc.player == null || inventoryIndex < 9 || inventoryIndex >= 36 || !prepareClick()) return -1;
		int to = borrows.roomFor(replaceable);
		return to >= 0 && moveToHotbar(inventoryIndex, to) ? to : -1;
	}

	@Override
	public int borrow(Object owner, int inventoryIndex, Predicate<ItemStack> replaceable) {
		return borrows.borrow(owner, inventoryIndex, replaceable);
	}

	@Override
	public void giveBack(Object owner) {
		borrows.giveBack(owner);
	}

	/**
	 * Every click made, from the mixin on the game's click handler (ViaFabricPlus sends some itself, past the
	 * connection): the hotbar slots it changes are in flight for a round trip (see {@link SlotsInFlight}).
	 */
	public void clickMade(int containerId, int slotNum, int button, ContainerInput input) {
		if (mc.player == null) return;
		// A swap's other end: the hotbar slot (or the off hand, 40) its button names.
		if (input == ContainerInput.SWAP) inFlight.changed(button);
		var menu = mc.player.containerMenu;
		if (menu.containerId != containerId || slotNum < 0 || slotNum >= menu.slots.size()) return;
		var slot = menu.slots.get(slotNum);
		if (slot.container == mc.player.getInventory()) inFlight.changed(slot.getContainerSlot());
	}

	/** Something was placed or used from hotbar {@code slot}: see {@link SlotsInFlight#used}. */
	public void slotUsed(int slot) {
		inFlight.used(slot);
	}

	/** See {@link SlotsInFlight#settled}. */
	public boolean settled(int index) {
		return inFlight.settled(index);
	}

	/** See {@link SlotsInFlight#swappable}. */
	boolean swappable(int slot) {
		return inFlight.swappable(slot);
	}

	/** A build moves a material into {@code hotbarSlot}: see {@link HotbarRestore#begin}. */
	void buildMoves(int hotbarSlot) {
		restore.begin(hotbarSlot);
	}

	/** An air placement is about to swap hotbar slot {@code hotbarSlot}'s block into the off hand: see {@link OffHandWatch}. */
	void airSwapping(int hotbarSlot) {
		offHand.swapping(hotbarSlot);
	}

	/** Whether a module borrowed something into hotbar slot {@code slot} that hasn't gone back yet. */
	boolean lent(int slot) {
		return borrows.at(slot) != null;
	}

	/** Whether a build moved something into hotbar slot {@code slot} that hasn't gone back yet. */
	boolean buildUses(int slot) {
		return restore.uses(slot);
	}

	/** No build is running: what they moved into the hotbar goes back (see {@link HotbarRestore}). */
	void buildsDone() {
		restore.finish();
	}

	@Override
	public ItemStack shownInHotbar(int hotbarSlot) {
		return masks.shown(hotbarSlot);
	}

	@Override
	public void mask(Object owner, long holdMs, int... inventoryIndexes) {
		masks.mask(owner, holdMs, inventoryIndexes);
	}

	@Override
	public void unmask(Object owner) {
		masks.unmask(owner);
	}

	/** Whether a mask covers inventory slot {@code index} right now. */
	public boolean masked(int index) {
		return masks.masked(index);
	}

	@Override
	public ItemStack shown(int index) {
		return masks.shown(index);
	}

	@Override
	public int shownArmor() {
		return masks.shownArmor();
	}

	/** What the local player's {@code slot} shows (see Inventory.mask): for the hands, the selected slot or the off hand. */
	public ItemStack shownEquipment(EquipmentSlot slot) {
		return masks.shownEquipment(slot);
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		restorePending = false;
		undos.clear();
	}

	@Override
	public boolean move(int from, int to) {
		Threads.assertRender();
		return clicks.move(from, to);
	}

	@Override
	public boolean merge(int from, int to) {
		return clicks.merge(from, to);
	}

	@Override
	public boolean swapWithOffhand(int inventoryIndex) {
		return clicks.swapWithOffhand(inventoryIndex);
	}

	/** Swaps the main and off hand items, as the swap-hands key does, showing it at once. */
	public void swapHands() {
		clicks.swapHands();
	}

	@Override
	public boolean quickMove(int inventoryIndex) {
		return clicks.quickMove(inventoryIndex);
	}

	@Override
	public boolean drop(int inventoryIndex, boolean wholeStack) {
		return clicks.drop(inventoryIndex, wholeStack);
	}

	@Override
	public CompletableFuture<Void> refresh() {
		if (mc.player == null) return CompletableFuture.failedFuture(new IllegalStateException("Not in a world"));
		return MyriadImpl.get().menuSync().refresh(mc.player.containerMenu);
	}

}
