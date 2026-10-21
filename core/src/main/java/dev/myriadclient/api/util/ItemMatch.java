package dev.myriadclient.api.util;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Set;

/** When two stacks count as the same item for a layout (a kit, a sort): stacks that stack together always do. */
public enum ItemMatch {
	/** Same item, whatever its enchantments, name or wear. */
	ITEM,
	/** Same item and the same components, except wear, the anvil cost and the name: a kit's sword with its enchantments, worn or not. */
	LOOSE,
	/** Same item and components: what stacks together. */
	EXACT;

	/** Components {@link #LOOSE} ignores. */
	private static final Set<DataComponentType<?>> LOOSE_IGNORED = Set.of(DataComponents.DAMAGE, DataComponents.REPAIR_COST, DataComponents.CUSTOM_NAME);

	public boolean test(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
		if (!a.is(b.getItem())) return false;
		return switch (this) {
			case ITEM -> true;
			case EXACT -> ItemStack.isSameItemSameComponents(a, b);
			case LOOSE -> ItemStack.isSameItemSameComponents(a, b) || sameIgnoring(a.getComponents(), b.getComponents());
		};
	}

	private static boolean sameIgnoring(DataComponentMap a, DataComponentMap b) {
		for (TypedDataComponent<?> c : a) {
			if (!LOOSE_IGNORED.contains(c.type()) && !Objects.equals(c.value(), b.get(c.type()))) return false;
		}
		for (TypedDataComponent<?> c : b) {
			if (!LOOSE_IGNORED.contains(c.type()) && !a.has(c.type())) return false;
		}
		return true;
	}
}
