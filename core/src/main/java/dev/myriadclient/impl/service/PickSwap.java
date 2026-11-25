package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Borrowing an item into the hotbar by the server's pick-from-block (what middle click sends) instead of a click, when a
 * block of that item is in reach: the server swaps it from the main inventory into the hotbar exactly as the click would,
 * so everything that tracks clicks stays as it is (the click is made, and its packet swapped for the pick as it goes
 * out), but it's no inventory click: it isn't counted against the click budget (2b2t takes 79 clicks and 10 picks in one
 * go) and goes out whatever the anti-cheat thinks of clicks while moving.
 * <p>
 * The server also selects the slot it put the item in, and telling it your slot again before its own word arrives is a
 * change to the slot Grim still has you on (BadPacketsA). So the slot is held first ({@link InventoryManager#hold}): one
 * change to it, Grim's way, after which the server picks into it (the first empty slot from the one it holds) and its
 * selection changes nothing. That selection never reaches the hotbar you see; the hold is let go two ticks after it
 * (once Grim has it too), and the slot you see goes back to the server as any slot change does.
 * <p>
 * Only from 1.21.4 on (older versions have no such packet, and a translated one is dropped), in survival, with no
 * container open, no other hold and not gliding (No Durability can hold back the answers Grim waits for), when the
 * hotbar has no stack of the item (the server would only select it), this is the first stack of it, and the slot it
 * goes to is empty. {@code -Dmyriad.noPickSwap} turns it off.
 */
final class PickSwap {
	/** The hold's owner. */
	private static final Object OWNER = new Object();
	/** Ticks after the server's selection came back before letting go, and the most to wait for it. */
	private static final int SETTLE_TICKS = 2, TIMEOUT_TICKS = 40;
	/** The first version with the packet (1.21.4). */
	private static final int PROTOCOL = 769;
	private static final boolean OFF = Boolean.getBoolean("myriad.noPickSwap");

	private record Pick(int index, int slot, int visible, BlockPos pos) {
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final InventoryManager inventory;
	/** A pick ready to take the next click's place; the one out, waiting for the server's selection. */
	private @Nullable Pick armed, out;
	private long sentAt;
	private volatile long answeredAt = -1;
	/** Where a block of each item was last found. */
	private final Map<Item, BlockPos> lastFound = new HashMap<>();
	private int picks;

	PickSwap(InventoryManager inventory) {
		this.inventory = inventory;
	}

	/**
	 * Readies a pick to stand in for the click moving inventory slot {@code index} (9-35) to hotbar slot {@code slot},
	 * if it can; then make the click at once ({@link #sent} after it, {@link #disarm} if it didn't go).
	 */
	boolean arm(int index, int slot) {
		var p = mc.player;
		if (OFF || armed != null || out != null || p == null || mc.level == null || mc.getConnection() == null) return false;
		if (index < 9 || index >= 36 || slot < 0 || slot > 8 || p.isCreative() || p.isSpectator() || p.isFallFlying()) return false;
		// Natively joined only: translated, ViaFabricPlus sends the clicks itself, past where the pick takes the click's place.
		if (p.containerMenu != p.inventoryMenu || inventory.isHolding() || JoinedVersion.get().protocol() < PROTOCOL || JoinedVersion.get().translated()) return false;
		Inventory inv = p.getInventory();
		ItemStack stack = inv.getItem(index);
		if (stack.isEmpty() || !inv.getItem(slot).isEmpty()) return false;
		// The server takes the first stack of it there is: none before this one, and none in the hotbar.
		for (int i = 0; i < index; i++) if (ItemStack.isSameItemSameComponents(inv.getItem(i), stack)) return false;
		BlockPos pos = find(stack);
		if (pos == null) return false;
		// The slot it goes to held first (one slot change, Grim's way): the server picks into the first empty slot from
		// the one it holds, which is then this one, whatever's in your hand.
		if (!inventory.hold(OWNER, slot, SETTLE_TICKS + TIMEOUT_TICKS)) return false;
		armed = new Pick(index, slot, inv.getSelectedSlot(), pos);
		return true;
	}

	/** The click didn't go: nothing to pick, and the slot not held. */
	void disarm() {
		armed = null;
		if (out == null) inventory.release(OWNER);
	}

	/** The click went out: if the pick didn't take its place, the hold goes. */
	void sent() {
		armed = null;
		if (out == null || sentAt != Ticks.current()) inventory.release(OWNER);
	}

	/** Whether a pick is out and the server's selection not settled yet. */
	boolean busy() {
		return out != null;
	}

	/** A block in reach whose picked item is {@code stack}'s, nearest first; the last one found if it still is. */
	private @Nullable BlockPos find(ItemStack stack) {
		double range = Reach.blockRange();
		BlockPos last = lastFound.get(stack.getItem());
		if (last != null && picks(last, stack, range)) return last;
		Vec3 eyes = Reach.eyes();
		BlockPos center = BlockPos.containing(eyes);
		int r = (int) Math.ceil(range) + 1;
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			double d = Vec3.atCenterOf(pos).distanceToSqr(eyes);
			if (d >= bestDist || !picks(pos, stack, range)) continue;
			best = pos.immutable();
			bestDist = d;
		}
		if (best != null) lastFound.put(stack.getItem(), best);
		return best;
	}

	/** Whether a pick of the block at {@code pos} gives {@code stack}'s item (with its components), and it's in reach. */
	private boolean picks(BlockPos pos, ItemStack stack, double range) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !Reach.canReach(pos, range)) return false;
		return ItemStack.isSameItemSameComponents(state.getCloneItemStack(mc.level, pos, false), stack);
	}

	/** The click's packet, as it goes out: the pick in its place, uncounted against the click budget. */
	@Subscribe(priority = Priority.HIGHEST, packets = ServerboundContainerClickPacket.class)
	private void onSend(PacketEvent.Send e) {
		Pick a = armed;
		if (a == null || e.isCancelled() || !(e.packet() instanceof ServerboundContainerClickPacket click)) return;
		armed = null;
		if (click.containerId() != 0 || click.slotNum() != a.index() || click.buttonNum() != a.slot() || click.containerInput() != ContainerInput.SWAP) return;
		e.setPacket(new ServerboundPickItemFromBlockPacket(a.pos(), false));
		MyriadImpl.get().packetLimiter().uncount(PacketLimits.Kind.INVENTORY);
		out = a;
		sentAt = Ticks.current();
		answeredAt = -1;
		picks++;
	}

	/**
	 * The server's selection, after the pick: kept from the hotbar you see (the slot is held instead, see the class
	 * comment). After InventoryManager's own handler, which takes it as the slot the server holds. Network thread.
	 */
	@Subscribe(priority = Priority.LOW, packets = ClientboundSetHeldSlotPacket.class)
	private void onReceive(PacketEvent.Receive e) {
		Pick o = out;
		if (o == null || answeredAt >= 0 || !(e.packet() instanceof ClientboundSetHeldSlotPacket held) || held.slot() != o.slot()) return;
		answeredAt = Ticks.current();
		e.setPacket(new ClientboundSetHeldSlotPacket(o.visible()));
	}

	@Subscribe(priority = 950)
	private void tick(TickEvent.Pre e) {
		Pick o = out;
		if (o == null) return;
		long now = Ticks.current();
		boolean settled = answeredAt >= 0 && now - answeredAt >= SETTLE_TICKS;
		if (settled || now - sentAt > TIMEOUT_TICKS) {
			out = null;
			inventory.release(OWNER);
			return;
		}
		inventory.hold(OWNER, o.slot(), SETTLE_TICKS + 1);
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		armed = null;
		out = null;
		lastFound.clear();
	}

	/** How many picks took a click's place, for the record. */
	int picks() {
		return picks;
	}
}
