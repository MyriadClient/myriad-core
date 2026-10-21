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
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
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

	/** A bind that matches this press, and what it does. */
	private record Hit(Keybind bind, Runnable run, boolean consumes) {
	}

	/**
	 * Presses run the binds that match the held modifiers, but only the most specific of them: with T and Ctrl+T both
	 * bound, Ctrl+T runs only the Ctrl+T one, while Shift+T (nothing bound to it) still runs T. Releases let go of
	 * every hold bind on the key, whatever modifiers are held by then.
	 * Returns true if a control panel or key action ran (its input is then consumed so nothing else sees it).
	 */
	private boolean handle(int code, boolean mouse, int mods, boolean press) {
		if (!press) return release(code, mouse);
		List<Hit> hits = new ArrayList<>();
		for (Module m : Myriad.modules()) {
			// A mirror's key is handled by the mod it mirrors; acting on it here too would toggle twice.
			if (m.isMirror() || !matches(m.keybind.get(), code, mouse, mods)) continue;
			// A plain key also stays the game's (R still reloads), but a combination (Ctrl+Alt+T) is the module's alone:
			// T doesn't open chat as well.
			hits.add(new Hit(m.keybind.get(), m.holdMode.get() ? () -> m.setEnabled(true) : m::toggle, m.keybind.get().modifiers() != 0));
		}
		for (ControlPanelImpl c : controls()) {
			if (!matches(c.keybind.get(), code, mouse, mods)) continue;
			hits.add(new Hit(c.keybind.get(), c.hold.get() ? () -> c.setActive(true) : c::toggle, true));
		}
		for (KeyAction a : Myriad.keyActions()) {
			if (matches(a.bind(), code, mouse, mods)) hits.add(new Hit(a.bind(), a::run, true));
		}
		// A wheel shows while its bind is held (it watches for the release itself).
		WheelManager wheels = MyriadImpl.get().wheelManager();
		for (WheelImpl w : wheels.all()) {
			Keybind bind = w.keybind.get();
			if (matches(bind, code, mouse, mods)) hits.add(new Hit(bind, () -> wheels.show(w, bind), true));
		}
		int most = 0;
		for (Hit h : hits) most = Math.max(most, Integer.bitCount(h.bind.modifiers()));
		boolean consumed = false;
		for (Hit h : hits) {
			if (Integer.bitCount(h.bind.modifiers()) < most) continue;
			h.run.run();
			consumed |= h.consumes;
		}
		return consumed;
	}

	/** The key or button let go: hold-bound modules and control panels on it turn off. */
	private boolean release(int code, boolean mouse) {
		boolean ran = false;
		for (Module m : Myriad.modules()) {
			Keybind bind = m.keybind.get();
			if (!m.isMirror() && m.holdMode.get() && bind.isSet() && bind.mouse() == mouse && bind.code() == code) m.setEnabled(false);
		}
		for (ControlPanelImpl c : controls()) {
			Keybind bind = c.keybind.get();
			if (c.hold.get() && bind.isSet() && bind.mouse() == mouse && bind.code() == code) {
				c.setActive(false);
				ran = true;
			}
		}
		return ran;
	}

	private static boolean matches(Keybind bind, int code, boolean mouse, int mods) {
		return mouse ? bind.matchesMouse(code, mods) : bind.matchesKey(code, mods);
	}
}
