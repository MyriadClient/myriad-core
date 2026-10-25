package dev.myriadclient.impl.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen sits and puts its labels, for drawing it in the theme (see ThemedScreens). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int myriad$leftPos();

	@Accessor("topPos")
	int myriad$topPos();

	@Accessor("imageWidth")
	int myriad$imageWidth();

	@Accessor("imageHeight")
	int myriad$imageHeight();

	@Accessor("titleLabelX")
	int myriad$titleLabelX();

	@Accessor("titleLabelY")
	int myriad$titleLabelY();

	@Accessor("inventoryLabelX")
	int myriad$inventoryLabelX();

	@Accessor("inventoryLabelY")
	int myriad$inventoryLabelY();

	@Accessor("playerInventoryTitle")
	Component myriad$playerInventoryTitle();
}
