package dev.myriadclient.api.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Questions about item stacks that features keep asking: enchantments, food, contents, durability, gear kind, armour. */
public final class ItemInfo {
	private ItemInfo() {
	}

	/** The level of {@code enchantment} on the stack, 0 if it doesn't have it. */
	public static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		for (Object2IntMap.Entry<Holder<Enchantment>> e : stack.getEnchantments().entrySet()) {
			if (e.getKey().is(enchantment)) return e.getIntValue();
		}
		return 0;
	}

	public static boolean hasEnchantment(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		return enchantmentLevel(stack, enchantment) > 0;
	}

	public static boolean isFood(ItemStack stack) {
		return stack.has(DataComponents.FOOD);
	}

	/** Hunger and saturation, or null if it isn't food. */
	public static @Nullable FoodProperties food(ItemStack stack) {
		return stack.get(DataComponents.FOOD);
	}

	public static boolean isShulkerBox(ItemStack stack) {
		return stack.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock;
	}

	/**
	 * What's stored in a container item (a shulker box, or any item carrying contents), slot by slot; empty if it has
	 * none.
	 */
	public static List<ItemStack> contents(ItemStack stack) {
		ItemContainerContents c = stack.get(DataComponents.CONTAINER);
		List<ItemStack> out = new ArrayList<>();
		if (c != null) c.allItemsCopyStream().forEach(out::add);
		return out;
	}

	/** Uses left before it breaks; {@link Integer#MAX_VALUE} for items that don't wear out. */
	public static int durability(ItemStack stack) {
		return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
	}

	/** Durability left as 0..1 (1 for items that don't wear out). */
	public static float durabilityFraction(ItemStack stack) {
		return stack.isDamageableItem() ? 1f - stack.getDamageValue() / (float) stack.getMaxDamage() : 1f;
	}

	public static boolean isWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.TRIDENT) || stack.is(Items.MACE);
	}

	public static boolean isTool(ItemStack stack) {
		return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
			|| stack.is(Items.SHEARS);
	}

	/** The armour slot it's worn in (head, chest, legs, feet; elytra is chest), or null if it isn't armour. */
	public static @Nullable EquipmentSlot armorSlot(ItemStack stack) {
		Equippable e = stack.get(DataComponents.EQUIPPABLE);
		return e != null && e.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? e.slot() : null;
	}

	/** Armour points it gives worn in {@code slot} (a diamond chestplate gives 8); 0 for anything that isn't armour there. */
	public static double armor(ItemStack stack, EquipmentSlot slot) {
		return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).compute(Attributes.ARMOR, 0, slot);
	}

	/** Armour toughness it gives worn in {@code slot} (diamond 2, netherite 3). */
	public static double toughness(ItemStack stack, EquipmentSlot slot) {
		return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
	}

	/** Whether it's something to glide with (an elytra), worn out or not. */
	public static boolean isGlider(ItemStack stack) {
		return stack.has(DataComponents.GLIDER);
	}

	/** Whether you could glide with it worn on your chest now: a glider that isn't about to break. */
	public static boolean canGlide(ItemStack stack) {
		return !stack.isEmpty() && LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST);
	}

	/** Whether it's armour for the chest that isn't a glider: a chestplate. */
	public static boolean isChestplate(ItemStack stack) {
		Equippable e = stack.get(DataComponents.EQUIPPABLE);
		return e != null && e.slot() == EquipmentSlot.CHEST && !isGlider(stack);
	}

	/** Whether it has Curse of Binding (or anything else that keeps worn armour from being taken off). */
	public static boolean isBound(ItemStack stack) {
		return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
	}

	/** The inventory index ({@link Slots#HEAD} etc.) for an armour slot. */
	public static int inventoryIndex(EquipmentSlot armorSlot) {
		return switch (armorSlot) {
			case HEAD -> Slots.HEAD;
			case CHEST -> Slots.CHEST;
			case LEGS -> Slots.LEGS;
			case FEET -> Slots.FEET;
			case OFFHAND -> Slots.OFF_HAND;
			default -> throw new IllegalArgumentException("Not an armour slot: " + armorSlot);
		};
	}
}
