package dev.myriadclient.impl.ui;

import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A "Myriad" button among Minecraft's own, opening Myriad's menu: on its title screen and the pause screen. */
public final class MenuButton {
	private MenuButton() {
	}

	/** {@code width} wide, as the buttons beside it are; placed by whoever adds it. */
	public static Button create(int width) {
		return Button.builder(Component.literal("Myriad"), b -> MyriadImpl.get().windowManager().open()).width(width).build();
	}
}
