package dev.myriadclient.impl.keybind;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.impl.CoreAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

/** One place that maps raw input to module toggles and key actions, outside of screens. */
public final class KeybindManager {
	@Subscribe(priority = Priority.HIGH)
	private void onKey(KeyEvent e) {
		if (e.action() == GLFW.GLFW_REPEAT) return;
		if (e.inScreen()) {
			// The menu can also be opened from the title screen.
			if (e.isPress() && Minecraft.getInstance().gui.screen() instanceof TitleScreen) {
				for (KeyAction a : Myriad.keyActions()) {
					if (a.id().equals(CoreAddon.OPEN_MENU) && a.bind().matchesKey(e.key(), e.modifiers())) {
						// Consume it, or the freshly opened menu receives the same press and closes again.
						e.cancel();
						a.run();
					}
				}
			}
			return;
		}
		if (handle(e.key(), false, e.modifiers(), e.isPress())) e.cancel();
	}

	@Subscribe(priority = Priority.HIGH)
	private void onMouse(MouseButtonEvent e) {
		if (e.inScreen()) return;
		if (handle(e.button(), true, e.modifiers(), e.isPress())) e.cancel();
	}

	/** Returns true if a key action ran (its input is then consumed so nothing else sees it). */
	private boolean handle(int code, boolean mouse, int mods, boolean press) {
		for (Module m : Myriad.modules()) {
			// A mirror's key is handled by the mod it mirrors; acting on it here too would toggle twice.
			if (m.isMirror()) continue;
			Keybind bind = m.keybind.get();
			if (!bind.isSet() || bind.mouse() != mouse || bind.code() != code) continue;
			if (m.holdMode.get()) {
				m.setEnabled(press);
			} else if (press && matches(bind, code, mouse, mods)) {
				m.toggle();
			}
		}
		if (!press) return false;
		boolean ran = false;
		for (KeyAction a : Myriad.keyActions()) {
			if (matches(a.bind(), code, mouse, mods)) {
				a.run();
				ran = true;
			}
		}
		return ran;
	}

	private static boolean matches(Keybind bind, int code, boolean mouse, int mods) {
		return mouse ? bind.matchesMouse(code, mods) : bind.matchesKey(code, mods);
	}
}
