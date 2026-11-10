package dev.myriadclient.impl.mixin;

import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The disconnect screen's column of title, reason and buttons, for Themed Screens' window behind it. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
	@Accessor("layout")
	LinearLayout myriad$layout();
}
