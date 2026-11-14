package dev.myriadclient.impl.ui;

import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A Myriad button among Minecraft's own, opening Myriad's menu: on its title screen and the pause screen. */
public final class MenuButton {
	private static final Component NAME = Component.literal("Myriad");
	private static final Identifier ICON = Identifier.fromNamespaceAndPath("myriad", "icon/menu");

	private MenuButton() {
	}

	/** A "Myriad" button {@code width} wide, as the buttons beside it are; placed by whoever adds it. */
	public static Button create(int width) {
		return Button.builder(NAME, b -> open()).width(width).build();
	}

	/** The logo as a 20 by 20 icon button, as Minecraft's language and accessibility ones are; placed by whoever adds it. */
	public static Button icon() {
		SpriteIconButton button = SpriteIconButton.builder(NAME, b -> open(), true).width(20).sprite(ICON, 15, 15).build();
		button.setTooltip(Tooltip.create(NAME));
		return button;
	}

	private static void open() {
		MyriadImpl.get().windowManager().open();
	}
}
