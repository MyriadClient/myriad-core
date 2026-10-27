package dev.myriadclient.impl.ui;

import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The logo as one of Minecraft's icon buttons, opening Myriad's menu: on its title screen and the pause screen. */
public final class MenuButton {
	/** The logo in the brand's mint (assets/myriad/textures/gui/sprites/icon/myriad.png). */
	private static final Identifier SPRITE = Identifier.fromNamespaceAndPath("myriad", "icon/myriad");

	private MenuButton() {
	}

	public static SpriteIconButton create() {
		return SpriteIconButton.builder(Component.literal("Myriad"), b -> MyriadImpl.get().windowManager().open(), true)
			.width(20).sprite(SPRITE, 15, 15).withTootip().build();
	}
}
