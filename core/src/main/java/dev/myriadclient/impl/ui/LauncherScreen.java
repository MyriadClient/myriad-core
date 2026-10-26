package dev.myriadclient.impl.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The launcher on its own (mod+Space without the menu open), over the game or over the screen it was opened from (the
 * title screen), which keeps drawing behind it; it goes back there as soon as the launcher closes.
 */
final class LauncherScreen extends Screen {
	private final WindowManager wm;
	private final @Nullable Screen parent;

	LauncherScreen(WindowManager wm, @Nullable Screen parent) {
		super(Component.literal("Myriad launcher"));
		this.wm = wm;
		this.parent = parent;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		if (parent != null) {
			parent.extractBackground(context, -1, -1, delta);
			parent.extractRenderState(context, -1, -1, delta);
		}
		wm.painter().renderLauncher(context, mouseX, mouseY);
	}

	@Override
	protected void init() {
		if (parent != null) parent.init(width, height);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return wm.launcherOpen() && closeIfDone(wm.input().mouseClicked(event.x(), event.y(), event.button(), event.hasShiftDown()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		return wm.launcherOpen() && wm.input().mouseScrolled(mouseX, mouseY, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return wm.launcherOpen() && closeIfDone(wm.input().keyPressed(event.key(), event.scancode(), event.modifiers()));
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!wm.launcherOpen()) return false;
		boolean handled = false;
		for (char chr : Character.toChars(event.codepoint())) handled |= wm.input().charTyped(chr, 0);
		return handled;
	}

	@Override
	public void tick() {
		if (parent != null) parent.tick();
		closeIfDone(false);
	}

	/**
	 * Closed by Escape, a click outside, or running something that didn't need the menu: back to the game at once (what
	 * needed the menu has replaced this screen with it already). The desktop's own keys never reach a hidden desktop.
	 */
	private boolean closeIfDone(boolean handled) {
		if (!wm.launcherOpen() && minecraft.gui.screen() == this) onClose();
		return handled;
	}

	@Override
	public void removed() {
		wm.onScreenRemoved();
	}
}
