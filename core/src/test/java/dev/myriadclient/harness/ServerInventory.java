package dev.myriadclient.harness;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The server's copy of the player's inventory, changed by the packets in the order they arrive: the held slot, swap
 * clicks in the player's own menu, and armour put on by using it. What it doesn't model it refuses loudly, so a test
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

	public ItemStack chest() {
		return slots[CHEST];
	}

	void receive(Packet<?> packet) {
		switch (packet) {
			case ServerboundSetCarriedItemPacket p -> held = p.getSlot();
			case ServerboundContainerClickPacket p when p.containerId() == 0 -> click(p);
			case ServerboundUseItemPacket p -> use(p.getHand() == InteractionHand.MAIN_HAND ? HOTBAR + held : OFF_HAND);
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
