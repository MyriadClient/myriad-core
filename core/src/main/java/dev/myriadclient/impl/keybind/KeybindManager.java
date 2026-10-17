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
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlPanelImpl;
import dev.myriadclient.impl.ui.MyriadTitleScreen;
import dev.myriadclient.impl.ui.WindowManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** One place that maps raw input to module toggles and key actions, outside of screens. */
public final class KeybindManager {
	@Subscribe(priority = Priority.HIGH)
	private void onKey(KeyEvent e) {
		if (e.action() == GLFW.GLFW_REPEAT) return;
		var screen = Minecraft.getInstance().gui.screen();
		boolean titleScreen = screen instanceof TitleScreen || screen instanceof MyriadTitleScreen;
		// The desktop's shortcuts that open it (mod+1..9, mod+S, mod+Space), in game and on the title screen. Consumed,
		// so mod+3 doesn't also pick hotbar slot 3 and mod+Space doesn't jump.
		if (e.isPress() && (!e.inScreen() || titleScreen) && ((WindowManager) Myriad.ui()).globalKey(e.key(), e.modifiers())) {
			e.cancel();
			return;
		}
		if (e.inScreen()) {
			// A hold bind let go behind a screen (a portal's loading screen, the disconnect screen) still lets go.
			if (!e.isPress()) releaseHolds(e.key(), false);
			// The menu can also be opened from the title screen.
			if (e.isPress() && titleScreen) {
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
		if (e.inScreen()) {
			if (!e.isPress()) releaseHolds(e.button(), true);
			return;
		}
		if (handle(e.button(), true, e.modifiers(), e.isPress())) e.cancel();
	}

	/** Turns off the hold-bound modules and control panels on this key or button: its release, wherever it happened. */
	private static void releaseHolds(int code, boolean mouse) {
		for (Module m : Myriad.modules()) {
			if (m.isMirror() || !m.holdMode.get()) continue;
			Keybind bind = m.keybind.get();
			if (bind.isSet() && bind.mouse() == mouse && bind.code() == code && m.isEnabled()) m.setEnabled(false);
		}
		for (ControlPanelImpl c : controls()) {
			Keybind bind = c.keybind.get();
			if (c.hold.get() && bind.isSet() && bind.mouse() == mouse && bind.code() == code && c.isActive()) c.setActive(false);
		}
	}

	private static List<ControlPanelImpl> controls() {
		return MyriadImpl.get().controlManager().impls();
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
		boolean ran = false;
		for (ControlPanelImpl c : controls()) {
			Keybind bind = c.keybind.get();
			if (!bind.isSet() || bind.mouse() != mouse || bind.code() != code) continue;
			if (c.hold.get()) {
				c.setActive(press);
				ran = true;
			} else if (press && matches(bind, code, mouse, mods)) {
				c.toggle();
				ran = true;
			}
		}
		if (!press) return ran;
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
