package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A screen is about to open, or the open one is about to close ({@link #screen()} null, back to the game or, with no
 * world, to the title screen). Posted on the render thread, for every {@code setScreen}. Cancel it to keep the current
 * screen (or none) as it is; {@link #setScreen} opens another screen instead, which is then not posted again.
 */
public final class ScreenOpenEvent extends Cancellable {
	private @Nullable Screen screen;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public ScreenOpenEvent(@Nullable Screen screen) {
		this.screen = screen;
	}

	/** The screen about to open (or what an earlier handler set), or null for closing the current one. */
	public @Nullable Screen screen() {
		return screen;
	}

	/** Opens {@code screen} instead (null: close to the game). */
	public void setScreen(@Nullable Screen screen) {
		this.screen = screen;
	}
}
