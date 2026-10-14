package dev.myriad.impl.service;

import dev.myriad.api.Myriad;
import dev.myriad.api.build.Target;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.event.events.WorldEvent;
import dev.myriad.api.service.Inventory;
import dev.myriad.api.service.PacketLimits;
import dev.myriad.api.util.Slots;
import dev.myriad.api.util.Ticks;
import dev.myriad.impl.network.ActionTiming;
import dev.myriad.impl.network.JoinedVersion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
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
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

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
	/** What a swap's action left to undo when it goes back (an off hand swap), done with its slot still held. */
	private final List<Runnable> beforeRestore = new ArrayList<>();
	private boolean restoring;

	/**
	 * An attack, use, mining or pick went out since this tick's movement: Grim (PacketOrderG) cancels an off hand swap
	 * after one of those in the same tick.
	 */
	private boolean actedThisTick;

	/**
	 * What the server last heard of your movement: the keys of the last input packet (vanilla only sends one when they
	 * change) and whether you're sprinting. Grim judges a click by these, not by what you press now.
	 */
	private volatile Input sentInput = Input.EMPTY;
	private volatile boolean sentSprinting;

	/**
	 * The server's attack charge counter: ticks since your last hit, or since the item in your hand (the one the server
	 * holds) changed to a different one, which starts it over (as vanilla's player tick does on both sides).
	 */
	private int attackTicks;
	private ItemStack chargedItem = ItemStack.EMPTY;

	/** Lowest priority: what actually goes out, after other handlers have changed it. */
	@Subscribe(priority = Priority.LOWEST, packets = {ServerboundSetCarriedItemPacket.class, ServerboundAttackPacket.class, ServerboundPlayerInputPacket.class,
		ServerboundPlayerCommandPacket.class, ServerboundInteractPacket.class, ServerboundUseItemPacket.class, ServerboundUseItemOnPacket.class,
		ServerboundPlayerActionPacket.class, ServerboundPickItemFromBlockPacket.class, ServerboundPickItemFromEntityPacket.class,
		ServerboundMovePlayerPacket.class, ServerboundClientTickEndPacket.class})
	private void onSend(PacketEvent.Send e) {
		if (e.isCancelled()) return;
		switch (e.packet()) {
			case ServerboundSetCarriedItemPacket p -> serverSlot = p.getSlot();
			case ServerboundAttackPacket p -> {
				attackTicks = 0;
				actedThisTick = true;
			}
			case ServerboundPlayerInputPacket p -> sentInput = p.input();
			case ServerboundPlayerCommandPacket p when p.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING -> sentSprinting = true;
			case ServerboundPlayerCommandPacket p when p.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING -> sentSprinting = false;
			case ServerboundPlayerActionPacket p -> {
				var a = p.getAction();
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
		if (restorePending && !restoring && Ticks.current() >= restoreAt && mc.player != null && mc.isSameThread()) restore();
	}

	/** The server can set the slot itself (on join, or by a plugin); a respawn starts with no keys and no sprint. */
	@Subscribe(packets = {ClientboundSetHeldSlotPacket.class, ClientboundRespawnPacket.class, ClientboundLoginPacket.class})
	private void onReceive(PacketEvent.Receive e) {
		if (e.packet() instanceof ClientboundSetHeldSlotPacket p) serverSlot = p.slot();
		if (e.packet() instanceof ClientboundRespawnPacket || e.packet() instanceof ClientboundLoginPacket) {
			sentInput = Input.EMPTY;
			sentSprinting = false;
		}
	}

	/** First thing each tick, before any module attacks: counts the charge on, for the item the server now holds. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 100)
	private void countCharge(TickEvent.Pre e) {
		if (mc.player == null) return;
		attackTicks++;
		ItemStack held = mc.player.getInventory().getItem(serverSlot);
		if (!ItemStack.isSameItem(chargedItem, held)) {
			attackTicks = 0;
			chargedItem = held.copy();
		}
	}

	@Override
	public float attackCharge() {
		if (mc.player == null) return 0;
		return Mth.clamp((attackTicks + 0.5f) / cachedAttackDelay(mc.player.getInventory().getItem(serverSlot)), 0, 1);
	}

	/** {@link #attackDelay} answers the same until the items or your attack speed modifiers change; asked every tick. */
	private ItemStack delayItem, delayCharged, delayMain;
	private int delayModifiers = -1;
	private float delay;

	private float cachedAttackDelay(ItemStack item) {
		AttributeInstance speed = mc.player.getAttribute(Attributes.ATTACK_SPEED);
		int modifiers = speed == null ? -1 : speed.getModifiers().size();
		ItemStack main = mc.player.getMainHandItem();
		if (item != delayItem || chargedItem != delayCharged || main != delayMain || modifiers != delayModifiers) {
			delay = attackDelay(item);
			delayItem = item;
			delayCharged = chargedItem;
			delayMain = main;
			delayModifiers = modifiers;
		}
		return delay;
	}

	/**
	 * Ticks a full charge takes with {@code item} in hand: your attack speed with its modifiers in place of those of the
	 * item your attributes were last worked out with (effects like Haste stay).
	 */
	private float attackDelay(ItemStack item) {
		AttributeInstance speed = mc.player.getAttribute(Attributes.ATTACK_SPEED);
		if (speed == null) return mc.player.getCurrentItemAttackStrengthDelay();
		Set<Identifier> fromItems = new HashSet<>();
		List<AttributeModifier> held = new ArrayList<>();
		for (ItemStack stack : new ItemStack[]{mc.player.getMainHandItem(), item, chargedItem}) {
			stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).forEach(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
				if (!attribute.equals(Attributes.ATTACK_SPEED)) return;
				fromItems.add(modifier.id());
				if (stack == item) held.add(modifier);
			});
		}
		AttributeInstance withItem = new AttributeInstance(Attributes.ATTACK_SPEED, i -> {
		});
		withItem.setBaseValue(speed.getBaseValue());
		for (AttributeModifier modifier : speed.getModifiers()) if (!fromItems.contains(modifier.id())) withItem.addTransientModifier(modifier);
		for (AttributeModifier modifier : held) withItem.addOrUpdateTransientModifier(modifier);
		return (float) (1 / Math.max(withItem.getValue(), 0.05) * 20);
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
		if (!restorePending) return;
		if (mc.player == null || mc.getConnection() == null) {
			restorePending = false;
			beforeRestore.clear();
		} else if (Ticks.current() >= restoreAt) restore();
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
	 * Undoes what a silent swap's action left for the next tick, with the client holding that slot again as it did; the
	 * server stays where it is. Anything that changes the server's slot settles first.
	 */
	private void settle() {
		if (!restorePending) return;
		restorePending = false;
		if (beforeRestore.isEmpty()) return;
		List<Runnable> undo = List.copyOf(beforeRestore);
		beforeRestore.clear();
		// Something moved the server off that slot without settling first: undoing it now would hit another item.
		if (serverSlot != swapSlot || mc.player == null) return;
		var inv = mc.player.getInventory();
		int visible = inv.getSelectedSlot();
		restoring = true;
		inv.setSelectedSlot(swapSlot);
		mc.gameMode.carriedIndex = swapSlot;
		try {
			for (Runnable r : undo) r.run();
		} finally {
			inv.setSelectedSlot(visible);
			mc.gameMode.carriedIndex = visible;
			restoring = false;
		}
	}

	/**
	 * For a silent swap's action: runs {@code undo} when the swap goes back (first thing next tick), with its slot still
	 * held at the server and in the client's hand. Air placement swaps the block back out of the off hand this way.
	 */
	public void afterSwap(Runnable undo) {
		beforeRestore.add(undo);
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
		if (restorePending && (userClick || userAttack || visible != mc.gameMode.carriedIndex)) {
			settle();
			if (userClick || userAttack) {
				int target = userAttack && !userClick ? wanted() : visible;
				if (serverSlot != target) sendSlot(target);
			}
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
		if (mc.player == null || mc.getConnection() == null || hotbarSlot < 0 || hotbarSlot > 8) return;
		// What the last swap left to undo goes first, from its own slot.
		if (restorePending && (swapSlot != hotbarSlot || !beforeRestore.isEmpty())) settle();
		var inv = mc.player.getInventory();
		int visible = inv.getSelectedSlot();
		if (serverSlot != hotbarSlot) sendSlot(hotbarSlot);
		// The client holds it too for the action, so what it predicts (a placement, an item used) is with that item.
		inv.setSelectedSlot(hotbarSlot);
		mc.gameMode.carriedIndex = hotbarSlot;
		try {
			action.run();
		} finally {
			inv.setSelectedSlot(visible);
			mc.gameMode.carriedIndex = visible;
			if (serverSlot != wanted() || !beforeRestore.isEmpty()) {
				restorePending = true;
				swapSlot = hotbarSlot;
				// Sent after this tick's movement, it goes out with what ActionTiming holds next tick: back the tick after.
				restoreAt = Ticks.current() + (ActionTiming.get().isLate() ? 2 : 1);
			}
		}
	}

	@Override
	public int findInHotbar(Predicate<ItemStack> predicate) {
		return find(predicate, 0, 9);
	}

	@Override
	public int findInInventory(Predicate<ItemStack> predicate) {
		return find(predicate, 9, 36);
	}

	/** What modules asked to be left alone, by owner. */
	private final Map<Object, Predicate<ItemStack>> spared = new IdentityHashMap<>();

	@Override
	public void spare(Object owner, @Nullable Predicate<ItemStack> spared) {
		if (spared == null) this.spared.remove(owner);
		else this.spared.put(owner, spared);
	}

	@Override
	public boolean isSpared(ItemStack stack) {
		if (spared.isEmpty() || stack.isEmpty()) return false;
		for (Predicate<ItemStack> p : spared.values()) if (p.test(stack)) return true;
		return false;
	}

	@Override
	public int bestInHotbar(ToDoubleFunction<ItemStack> score) {
		return best(score, 0, 9);
	}

	@Override
	public int bestInInventory(ToDoubleFunction<ItemStack> score) {
		return best(score, 0, 36);
	}

	private int best(ToDoubleFunction<ItemStack> score, int from, int to) {
		if (mc.player == null) return -1;
		int best = -1;
		double bestScore = 0;
		for (int i = from; i < to; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (isSpared(stack)) continue;
			double s = score.applyAsDouble(stack);
			if (s > bestScore) {
				bestScore = s;
				best = i;
			}
		}
		return best;
	}

	@Override
	public int count(Predicate<ItemStack> predicate) {
		if (mc.player == null) return 0;
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (predicate.test(inv.getItem(i))) n += inv.getItem(i).getCount();
		if (predicate.test(inv.getItem(Slots.OFF_HAND))) n += inv.getItem(Slots.OFF_HAND).getCount();
		return n;
	}

	@Override
	public boolean moveToHotbar(int inventoryIndex, int hotbarSlot) {
		if (mc.player == null || hotbarSlot < 0 || hotbarSlot > 8 || inventoryIndex < 0 || inventoryIndex >= 36) return false;
		if (inventoryIndex == hotbarSlot) return true;
		if (!canClick(1)) return false;
		return clickSwap(inventoryIndex, hotbarSlot);
	}

	@Override
	public int ensureInHotbar(Predicate<ItemStack> predicate, int preferredSlot) {
		int slot = findInHotbar(predicate);
		if (slot != -1) return slot;
		int from = findInInventory(predicate);
		if (from == -1) return -1;
		int to = preferredSlot;
		if (to < 0 || to > 8) {
			to = findInHotbar(ItemStack::isEmpty);
			if (to == -1) to = mc.player.getInventory().getSelectedSlot();
		}
		return moveToHotbar(from, to) ? to : -1;
	}

	/**
	 * Grim (MultiActionsC) cancels a click while you sprint, and, depending on the version the server sees you as
	 * ({@link ClickRules}), while the last input packet had a movement key or jump down, or sneak. A click at the start of
	 * a tick goes out before that tick's input packet, so the one that counts is the last one sent.
	 */
	@Override
	public boolean safeToClick() {
		if (mc.player == null) return false;
		// Only anti-cheats that simulate movement (Grim) refuse clicks while you move.
		if (!Myriad.antiCheat().isStrict()) return true;
		return !rules().refuses(sentInput, sentSprinting);
	}

	/** What a click has to wait for, for the version this connection joined as. */
	public ClickRules rules() {
		return ClickRules.forProtocol(JoinedVersion.get().protocol());
	}

	/** A still tick is wanted: the next input releases sprint, and what else {@link #rules()} counts. */
	private boolean stillRequested;
	/** Sprinting was stopped for a still tick; it's pressed again once you move forward. */
	private boolean resumeSprint;

	@Override
	public boolean prepareClick() {
		if (safeToClick()) return true;
		if (mc.player == null) return false;
		stillWantedAt = mc.player.tickCount;
		// Letting go of sneak on an edge, with what's left of your speed, would walk you off it: wait for it to settle.
		if (rules().sneak() && mc.player.isShiftKeyDown() && mc.player.onGround() && wouldStepOffEdge()) return false;
		stillRequested = true;
		return false;
	}

	/** The player tick a still one was last asked for (see clickWaiting). */
	private int stillWantedAt = Integer.MIN_VALUE / 2;
	/** A module needs your keys this tick (see keepKeys): the still tick waits. */
	private boolean keysKept;

	@Override
	public boolean clickWaiting() {
		return mc.player != null && (!waitingClicks.isEmpty() || mc.player.tickCount - stillWantedAt <= 2);
	}

	@Override
	public void keepKeys() {
		keysKept = true;
	}

	/** Whether this tick's motion, without sneak's edge guard, would take you off the block you stand on. */
	private boolean wouldStepOffEdge() {
		var p = mc.player;
		var v = p.getDeltaMovement();
		var box = p.getBoundingBox();
		return p.level().noCollision(p, new AABB(box.minX + 1e-7 + v.x, box.minY - p.maxUpStep() - 1e-7, box.minZ + 1e-7 + v.z,
			box.maxX - 1e-7 + v.x, box.minY, box.maxZ - 1e-7 + v.z));
	}

	/**
	 * Last, so it has the final say over what's sent: Grim takes these keys as what you were doing at the click. Only
	 * what counts for this version is let go: through ViaFabricPlus as 1.20.4, that's sprint alone.
	 */
	@Subscribe(priority = Priority.LOWEST)
	private void onInput(InputEvent e) {
		if (mc.player == null) return;
		boolean kept = keysKept;
		keysKept = false;
		// A module needs the keys this tick: the still tick comes later (asked for again meanwhile).
		if (stillRequested && !kept) {
			stillRequested = false;
			ClickRules rules = rules();
			if (rules.input()) e.forward = e.backward = e.left = e.right = e.jump = false;
			if (rules.sneak()) e.sneak = false;
			e.sprint = false;
			sprintBlocked = true;
			if (mc.player.isSprinting()) {
				mc.player.setSprinting(false);
				resumeSprint = true;
			}
		} else if (resumeSprint) {
			resumeSprint = false;
			if (e.forward) e.sprint = true;
		}
	}

	/** The still tick's sprint stays off until the player has moved. */
	private boolean sprintBlocked;

	/**
	 * Just before the player moves: a still tick stays without sprint even if it started again since its input
	 * (ViaFabricPlus, joined as an older version, starts it from the held sprint key as those did), so the movement and
	 * the sprint state the server gets agree.
	 */
	public void beforeTravel() {
		if (!sprintBlocked) return;
		sprintBlocked = false;
		if (mc.player.isSprinting()) {
			mc.player.setSprinting(false);
			resumeSprint = true;
		}
	}

	// ---- your own clicks that have to wait --------------------------------------------------------------------

	/**
	 * Your own clicks in a screen made while the server would refuse them (walking with Inventory Move, say), in order:
	 * Grim cancels such a click without telling the client, which then shows items where the server has none. They're
	 * made at the start of the first tick after a still one ({@link #prepareClick()}) instead. Modules' clicks don't
	 * come here: core's click methods wait for a safe tick themselves ({@link #canClick}).
	 */
	private record ScreenClick(int containerId, int slotId, int button, ContainerInput input) {
	}

	private final ArrayDeque<ScreenClick> waitingClicks = new ArrayDeque<>();
	private boolean replaying;
	private int waitedTicks;
	/** Ticks a click waits for a still moment (sneaking at an edge never lets go) before it's made anyway. */
	private static final int MAX_CLICK_WAIT = 40;

	/** Your own click in a container screen (from the mixin on it): true if it has to wait, and was kept for later. */
	public boolean holdScreenClick(int containerId, int slotId, int button, ContainerInput input) {
		if (replaying || mc.player == null || mc.gameMode == null) return false;
		if (safeToClick()) {
			// A still moment came between ticks: what waited goes first.
			if (!waitingClicks.isEmpty()) replayWaitingClicks();
			return false;
		}
		waitingClicks.add(new ScreenClick(containerId, slotId, button, input));
		prepareClick();
		return true;
	}

	/** Early in the tick, before anything else clicks, so what waited stays first. */
	@Subscribe(priority = 960)
	private void clickWaitingClicks(TickEvent.Pre e) {
		if (waitingClicks.isEmpty()) return;
		if (mc.player == null || mc.gameMode == null) {
			waitingClicks.clear();
			return;
		}
		if (!prepareClick() && ++waitedTicks < MAX_CLICK_WAIT) return;
		replayWaitingClicks();
	}

	/** Closing the screen: what waited is clicked first, while it's still open. */
	@Subscribe(priority = Priority.HIGHEST, packets = ServerboundContainerClosePacket.class)
	private void beforeClose(PacketEvent.Send e) {
		if (!waitingClicks.isEmpty() && mc.isSameThread() && mc.player != null) replayWaitingClicks();
	}

	private void replayWaitingClicks() {
		waitedTicks = 0;
		replaying = true;
		try {
			while (!waitingClicks.isEmpty()) {
				ScreenClick c = waitingClicks.poll();
				// Clicks for a screen that has closed since are dropped: the client never showed them.
				if (mc.player.containerMenu.containerId == c.containerId) mc.gameMode.handleContainerInput(c.containerId, c.slotId, c.button, c.input, mc.player);
			}
		} finally {
			replaying = false;
		}
	}

	@Override
	public int pullToHotbar(int inventoryIndex, Predicate<ItemStack> replaceable) {
		if (mc.player == null || inventoryIndex < 9 || inventoryIndex >= 36 || !prepareClick()) return -1;
		int to = roomFor(replaceable);
		return to >= 0 && moveToHotbar(inventoryIndex, to) ? to : -1;
	}

	/**
	 * The hotbar slot to bring an item into: an empty one, else one {@code replaceable} accepts, else building blocks.
	 * Never the selected slot, the one the server holds, or one with something borrowed in it. -1 if none.
	 */
	private int roomFor(Predicate<ItemStack> replaceable) {
		var inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot(), to = -1, blocks = -1;
		for (int i = 0; i < 9; i++) {
			if (i == selected || i == serverSlot || borrowAt(i) != null) continue;
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) return i;
			if (to < 0 && replaceable.test(s)) to = i;
			if (blocks < 0 && Target.solid().preference(s) >= 0) blocks = i;
		}
		return to >= 0 ? to : blocks;
	}

	// ---- borrowing ----------------------------------------------------------------------------------------------

	/** An item borrowed from {@code home} (9-35) into hotbar slot {@code slot}, to go back once it's no longer wanted. */
	private static final class Borrow {
		final Object owner;
		final int home, slot, selectedBefore;
		/** What was borrowed, and what it made room for (now at home; null if the hotbar slot was empty). */
		final Item item, displaced;
		int lastWanted, waited, outOfPlace;
		boolean giveBack;

		Borrow(Object owner, int home, int slot, int selectedBefore, Item item, Item displaced, int now) {
			this.owner = owner;
			this.home = home;
			this.slot = slot;
			this.selectedBefore = selectedBefore;
			this.item = item;
			this.displaced = displaced;
			this.lastWanted = now;
		}
	}

	/** A borrowed item goes back once nothing has wanted it for this long. */
	private static final int KEEP_TICKS = 10;
	/** Ticks a return waits for a moment you aren't moving before releasing your keys for one. */
	private static final int WAIT_FOR_STILL = 40;
	/**
	 * Ticks the items may look out of place before a borrow is forgotten: an inventory update the server sent before it
	 * had the borrowing click can arrive after it, showing the old places until the next one.
	 */
	private static final int OUT_OF_PLACE_TICKS = 20;

	private final List<Borrow> borrows = new ArrayList<>();
	private int ticks;

	@Override
	public int borrow(Object owner, int inventoryIndex, Predicate<ItemStack> replaceable) {
		if (mc.player == null || inventoryIndex < 0 || inventoryIndex >= 36) return -1;
		if (inventoryIndex < 9) {
			Borrow b = borrowAt(inventoryIndex);
			if (b != null) {
				b.lastWanted = ticks;
				b.giveBack = false;
			}
			return inventoryIndex;
		}
		if (!prepareClick()) return -1;
		int to = roomFor(replaceable == null ? s -> false : replaceable);
		if (to < 0) return -1;
		var inv = mc.player.getInventory();
		Item item = inv.getItem(inventoryIndex).getItem();
		ItemStack displaced = inv.getItem(to);
		Item displacedItem = displaced.isEmpty() ? null : displaced.getItem();
		if (!moveToHotbar(inventoryIndex, to)) return -1;
		borrows.add(new Borrow(owner, inventoryIndex, to, inv.getSelectedSlot(), item, displacedItem, ticks));
		return to;
	}

	@Override
	public void giveBack(Object owner) {
		for (Borrow b : borrows) if (b.owner == owner) b.giveBack = true;
	}

	@Override
	public ItemStack shownInHotbar(int hotbarSlot) {
		return shown(hotbarSlot);
	}

	/** Display masks (see Inventory.mask), by owner: what each masked slot held when masked, and until when. */
	private final Map<Object, Mask> masks = new IdentityHashMap<>();

	private static final class Mask {
		final Map<Integer, ItemStack> held = new java.util.HashMap<>();
		/**
		 * What each slot shows: a copy, the same one for as long as the item is unchanged (the hand would take a new
		 * stack for a new item), and kept while the item is in none of the slots (a swap's two slot updates can come a
		 * tick apart). Never the live stack: moving an item empties the stack it leaves.
		 */
		final Map<Integer, ItemStack> last = new java.util.HashMap<>();
		long untilMs;
		int armor;

		/** Whether {@code stack} is one of the masked items (by kind: its wear may have changed since). */
		boolean has(ItemStack stack) {
			for (ItemStack h : held.values()) if (!h.isEmpty() && ItemStack.isSameItem(h, stack)) return true;
			return false;
		}
	}

	@Override
	public void mask(Object owner, long holdMs, int... inventoryIndexes) {
		if (mc.player == null) return;
		var inv = mc.player.getInventory();
		Mask m = masks.get(owner);
		if (m == null || System.currentTimeMillis() > m.untilMs) {
			m = new Mask();
			m.armor = mc.player.getArmorValue();
			masks.put(owner, m);
		}
		for (int i : inventoryIndexes) if (valid(i)) m.held.putIfAbsent(i, inv.getItem(i).copy());
		m.untilMs = System.currentTimeMillis() + holdMs;
	}

	@Override
	public void unmask(Object owner) {
		masks.remove(owner);
	}

	/** The live mask covering {@code index}, if any. */
	private Mask maskAt(int index) {
		if (masks.isEmpty()) return null;
		long now = System.currentTimeMillis();
		for (Mask m : masks.values()) if (now <= m.untilMs && m.held.containsKey(index)) return m;
		return null;
	}

	/** Whether a mask covers inventory slot {@code index} right now. */
	public boolean masked(int index) {
		return maskAt(index) != null;
	}

	@Override
	public ItemStack shown(int index) {
		if (mc.player == null || !valid(index)) return ItemStack.EMPTY;
		var inv = mc.player.getInventory();
		ItemStack real = inv.getItem(index);
		Mask m = maskAt(index);
		if (m != null) {
			// Something from outside the group turned up here (a pickup): it shows as it is.
			if (!real.isEmpty() && !m.has(real)) return real;
			ItemStack was = m.held.get(index);
			if (was.isEmpty()) return ItemStack.EMPTY;
			ItemStack live = ItemStack.isSameItem(real, was) ? real : null;
			for (int i : m.held.keySet()) {
				if (live != null) break;
				ItemStack there = inv.getItem(i);
				if (ItemStack.isSameItem(there, was)) live = there;
			}
			ItemStack last = m.last.get(index);
			if (live == null) return last != null ? last : real;
			if (last == null || !ItemStack.matches(last, live)) m.last.put(index, last = live.copy());
			return last;
		}
		if (!Slots.isHotbar(index)) return real;
		Borrow b = borrowAt(index);
		if (b == null || inv.getSelectedSlot() == index || !intact(b)) return real;
		return inv.getItem(b.home);
	}

	@Override
	public int shownArmor() {
		if (mc.player == null) return 0;
		for (int i = Slots.FEET; i <= Slots.HEAD; i++) {
			Mask m = maskAt(i);
			if (m != null) return m.armor;
		}
		return mc.player.getArmorValue();
	}

	/** What the local player's {@code slot} shows (see Inventory.mask): for the hands, the selected slot or the off hand. */
	public ItemStack shownEquipment(EquipmentSlot slot) {
		if (mc.player == null) return ItemStack.EMPTY;
		return switch (slot) {
			case MAINHAND -> shown(mc.player.getInventory().getSelectedSlot());
			case OFFHAND -> shown(Slots.OFF_HAND);
			case FEET, LEGS, CHEST, HEAD -> shown(Slots.FEET + slot.getIndex());
			default -> mc.player.getItemBySlot(slot);
		};
	}

	private Borrow borrowAt(int hotbarSlot) {
		for (Borrow b : borrows) if (b.slot == hotbarSlot) return b;
		return null;
	}

	/** The borrowed item (or nothing, if it was used up) is still in its slot, and what it displaced at home. */
	private boolean intact(Borrow b) {
		var inv = mc.player.getInventory();
		ItemStack here = inv.getItem(b.slot), home = inv.getItem(b.home);
		return (here.isEmpty() || here.is(b.item)) && (b.displaced == null ? home.isEmpty() : home.is(b.displaced));
	}

	/**
	 * Puts back borrowed items nothing wants any more, one click a tick. A borrow stays while the server holds its slot
	 * (a hold, or a silent swap) or you're using it; one whose items have moved is forgotten where it is.
	 */
	@Subscribe(priority = 940)
	private void tickBorrows(TickEvent.Pre e) {
		ticks++;
		if (!masks.isEmpty()) {
			long now = System.currentTimeMillis();
			masks.values().removeIf(m -> now > m.untilMs);
		}
		if (borrows.isEmpty() || mc.player == null) return;
		var inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot();
		for (Borrow b : new ArrayList<>(borrows)) {
			if (!intact(b)) {
				if (++b.outOfPlace > OUT_OF_PLACE_TICKS) borrows.remove(b);
				continue;
			}
			b.outOfPlace = 0;
			boolean held = serverSlot == b.slot && (holder != null || serverSlot != selected);
			if (held || selected == b.slot && mc.player.isUsingItem()) b.lastWanted = ticks;
			if (held || !b.giveBack && ticks - b.lastWanted < KEEP_TICKS) continue;
			// A moment you aren't moving if one comes soon; otherwise your keys are released for a tick.
			if (!safeToClick() && !(++b.waited > WAIT_FOR_STILL && prepareClick())) continue;
			if (!canClick(1)) continue;
			if (selected == b.slot && b.selectedBefore != b.slot) select(b.selectedBefore);
			clickSwap(b.home, b.slot);
			borrows.remove(b);
			return;
		}
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		borrows.clear();
		masks.clear();
		waitingClicks.clear();
		restorePending = false;
		beforeRestore.clear();
	}

	/**
	 * True if the player's own screen is the one open to clicks, the packet budget has room for {@code clicks}, and the
	 * server would take a click now; if that's all that's missing, your keys are released for the next tick
	 * ({@link #prepareClick()}), so asking again then works. A refused click would leave the client showing items
	 * where the server has none.
	 */
	private boolean canClick(int clicks) {
		return mc.player != null && mc.gameMode != null && mc.player.containerMenu == mc.player.inventoryMenu
			&& Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, clicks) && prepareClick();
	}

	private static boolean valid(int index) {
		return index >= 0 && index <= Slots.OFF_HAND;
	}

	private void click(int index, int button, ContainerInput action) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, Slots.playerScreen(index), button, action, mc.player);
	}

	@Override
	public boolean move(int from, int to) {
		if (!canClick(3) || !valid(from) || !valid(to)) return false;
		if (from == to) return true;
		if (Slots.isHotbar(to)) return clickSwap(from, to);
		if (Slots.isHotbar(from)) return clickSwap(to, from);
		// Armour into its own empty slot: a shift click puts it there.
		if (to >= Slots.FEET && to <= Slots.HEAD && from < Slots.MAIN_END && quickMovesTo(from, to)) {
			click(from, 0, ContainerInput.QUICK_MOVE);
			return true;
		}
		// Pick up, put down (swapping with what's there), then put back whatever ended up on the cursor.
		click(from, 0, ContainerInput.PICKUP);
		click(to, 0, ContainerInput.PICKUP);
		if (!mc.player.containerMenu.getCarried().isEmpty()) click(from, 0, ContainerInput.PICKUP);
		return true;
	}

	@Override
	public boolean merge(int from, int to) {
		if (!valid(from) || !valid(to) || from == to) return false;
		var inv = mc.player == null ? null : mc.player.getInventory();
		if (inv == null || !ItemStack.isSameItemSameComponents(inv.getItem(from), inv.getItem(to)) || inv.getItem(from).isEmpty()) return false;
		if (!canClick(3)) return false;
		// Pick the source up, drop it on the target (they combine), and put any remainder back.
		click(from, 0, ContainerInput.PICKUP);
		click(to, 0, ContainerInput.PICKUP);
		if (!mc.player.containerMenu.getCarried().isEmpty()) click(from, 0, ContainerInput.PICKUP);
		return true;
	}

	private boolean clickSwap(int index, int hotbarSlot) {
		click(index, hotbarSlot, ContainerInput.SWAP);
		return true;
	}

	/** Whether a shift click on {@code from} (outside the armour slots) lands the item in armour slot {@code to}. */
	private boolean quickMovesTo(int from, int to) {
		var inv = mc.player.getInventory();
		ItemStack stack = inv.getItem(from);
		if (stack.isEmpty() || !inv.getItem(to).isEmpty()) return false;
		EquipmentSlot slot = mc.player.getEquipmentSlotForItem(stack);
		return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && Slots.FEET + slot.getIndex() == to;
	}

	@Override
	public boolean swapWithOffhand(int inventoryIndex) {
		if (mc.player == null || !valid(inventoryIndex) || inventoryIndex == Slots.OFF_HAND) return false;
		// From the hotbar while a click would have to wait: the swap-hands key with that slot held, which isn't a click.
		// Not after an attack or use this tick, which Grim (PacketOrderG) cancels it after.
		if (Slots.isHotbar(inventoryIndex) && !safeToClick() && !actedThisTick && mc.getConnection() != null && !mc.player.isSpectator()) {
			silentSwap(inventoryIndex, this::swapHands);
			return true;
		}
		if (!canClick(1)) return false;
		// Button 40 is the off hand swap key.
		click(inventoryIndex, 40, ContainerInput.SWAP);
		return true;
	}

	/** Swaps the main and off hand items, as the swap-hands key does, showing it at once. */
	public void swapHands() {
		var p = mc.player;
		ItemStack off = p.getOffhandItem();
		p.setItemInHand(InteractionHand.OFF_HAND, p.getMainHandItem());
		p.setItemInHand(InteractionHand.MAIN_HAND, off);
		mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
	}

	@Override
	public boolean quickMove(int inventoryIndex) {
		if (!canClick(1) || !valid(inventoryIndex)) return false;
		click(inventoryIndex, 0, ContainerInput.QUICK_MOVE);
		return true;
	}

	@Override
	public boolean drop(int inventoryIndex, boolean wholeStack) {
		if (!canClick(1) || !valid(inventoryIndex)) return false;
		click(inventoryIndex, wholeStack ? 1 : 0, ContainerInput.THROW);
		return true;
	}

	private int find(Predicate<ItemStack> predicate, int from, int to) {
		if (mc.player == null) return -1;
		for (int i = from; i < to; i++) if (predicate.test(mc.player.getInventory().getItem(i))) return i;
		return -1;
	}
}
