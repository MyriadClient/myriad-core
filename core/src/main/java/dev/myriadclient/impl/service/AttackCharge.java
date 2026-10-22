package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The server's attack charge counter (for {@link InventoryManager#attackCharge()}): ticks since your last hit, or since
 * the item in your hand (the one the server holds) changed to a different one, which starts it over (as vanilla's player
 * tick does on both sides).
 */
final class AttackCharge {
	private final Minecraft mc = Minecraft.getInstance();
	private final InventoryManager inventory;
	private int attackTicks;
	private ItemStack chargedItem = ItemStack.EMPTY;

	/** {@link #attackDelay} answers the same until the items or your attack speed modifiers change; asked every tick. */
	private ItemStack delayItem, delayCharged, delayMain;
	private int delayModifiers = -1;
	private float delay;

	AttackCharge(InventoryManager inventory) {
		this.inventory = inventory;
	}

	/** Lowest priority: what actually goes out, after other handlers have changed it. */
	@Subscribe(priority = Priority.LOWEST, packets = ServerboundAttackPacket.class)
	private void onAttack(PacketEvent.Send e) {
		if (!e.isCancelled()) attackTicks = 0;
	}

	/** First thing each tick, before any module attacks: counts the charge on, for the item the server now holds. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 100)
	private void countCharge(TickEvent.Pre e) {
		if (mc.player == null) return;
		attackTicks++;
		ItemStack held = mc.player.getInventory().getItem(inventory.serverSlot());
		if (!ItemStack.isSameItem(chargedItem, held)) {
			attackTicks = 0;
			chargedItem = held.copy();
		}
	}

	float charge() {
		if (mc.player == null) return 0;
		return Mth.clamp((attackTicks + 0.5f) / cachedAttackDelay(mc.player.getInventory().getItem(inventory.serverSlot())), 0, 1);
	}

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
}
