package dev.myriadclient.harness;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/** The game's GUI as far as the harness needs it: it remembers the screen it was last asked to show. */
final class FakeGui extends Gui {
	@Nullable Screen screen;

	@SuppressWarnings("unused")
	private FakeGui() {
		super(null, null, null);
	}

	static FakeGui create() {
		return Fields.allocate(FakeGui.class);
	}

	@Override
	public void setScreen(@Nullable Screen screen) {
		this.screen = screen;
	}
}
