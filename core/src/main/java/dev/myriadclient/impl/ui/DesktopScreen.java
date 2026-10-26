package dev.myriadclient.impl.ui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** The screen that hosts the desktop; all logic lives in {@link WindowManager}. */
public final class DesktopScreen extends Screen {
	private final WindowManager wm;

	public DesktopScreen(WindowManager wm) {
		super(Component.literal("Myriad"));
		this.wm = wm;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		wm.painter().renderDesktop(context, mouseX, mouseY);
		CursorType cursor = wm.input().cursor(wm.mouseX, wm.mouseY);
		if (cursor != null) context.requestCursor(cursor);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean isPauseScreen() {
		return wm.theme().pauseGame.get();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return wm.input().mouseClicked(event.x(), event.y(), event.button(), event.hasShiftDown());
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		wm.input().mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return wm.input().mouseReleased(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		return wm.input().mouseDragged(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		return wm.input().mouseScrolled(mouseX, mouseY, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return wm.input().keyPressed(event.key(), event.scancode(), event.modifiers());
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		boolean handled = false;
		for (char chr : Character.toChars(event.codepoint())) handled |= wm.input().charTyped(chr, 0);
		return handled;
	}

	@Override
	public void removed() {
		wm.onScreenRemoved();
	}
}
