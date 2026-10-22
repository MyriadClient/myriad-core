package dev.myriadclient.harness;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.List;

/**
 * The server's copy of the player's inventory, changed by the packets in the order they arrive: the held slot, swap
 * clicks in the player's own menu, the off-hand swap key, and armour put on by using it. What it doesn't model it refuses loudly, so a test
 * never passes on a guess. Slots are numbered as in the player's menu: 5-8 armour (6 the chest), 9-35 the main
 * inventory, 36-44 the hotbar, 45 the off hand.
 */
public final class ServerInventory {
	private static final int CHEST = 6, HOTBAR = 36, OFF_HAND = 45;
	private final ItemStack[] slots = new ItemStack[46];
	private int held;

	ServerInventory(LocalPlayer player) {
		for (int i = 0; i < slots.length; i++) slots[i] = player.inventoryMenu.getSlot(i).getItem().copy();
		held = player.getInventory().getSelectedSlot();
	}

	/** The server's copy as the client's inventory is now; {@link #apply} what's sent from here. */
	public static ServerInventory of(LocalPlayer player) {
		return new ServerInventory(player);
	}

	/** Takes {@code sent} in order, as the server would. */
	public ServerInventory apply(List<Harness.Sent> sent) {
		for (Harness.Sent s : sent) receive(s.packet());
		return this;
	}

	public ItemStack chest() {
		return slots[CHEST];
	}

	/** What the server has in hotbar slot {@code index} (0-8). */
	public ItemStack hotbar(int index) {
		return slots[HOTBAR + index];
	}

	public ItemStack offHand() {
		return slots[OFF_HAND];
	}

	/** The hotbar slot the server has selected. */
	public int held() {
		return held;
	}

	void receive(Packet<?> packet) {
		switch (packet) {
			case ServerboundSetCarriedItemPacket p -> held = p.getSlot();
			case ServerboundContainerClickPacket p when p.containerId() == 0 -> click(p);
			case ServerboundUseItemPacket p -> use(p.getHand() == InteractionHand.MAIN_HAND ? HOTBAR + held : OFF_HAND);
			case ServerboundPlayerActionPacket p when p.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND -> {
				ItemStack s = slots[HOTBAR + held];
				slots[HOTBAR + held] = slots[OFF_HAND];
				slots[OFF_HAND] = s;
			}
			default -> {
			}
		}
	}

	private void click(ServerboundContainerClickPacket p) {
		if (p.containerInput() != ContainerInput.SWAP) throw new UnsupportedOperationException("ServerInventory doesn't model " + p.containerInput() + " clicks");
		int a = p.slotNum(), b = p.buttonNum() == 40 ? OFF_HAND : HOTBAR + p.buttonNum();
		ItemStack s = slots[a];
		slots[a] = slots[b];
		slots[b] = s;
	}

	/** Using armour in hand puts it on, swapping it with what's worn there, as vanilla's equippable items do. */
	private void use(int slot) {
		Equippable equippable = slots[slot].get(DataComponents.EQUIPPABLE);
		if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) return;
		ItemStack worn = slots[CHEST];
		slots[CHEST] = slots[slot];
		slots[slot] = worn;
	}
}
