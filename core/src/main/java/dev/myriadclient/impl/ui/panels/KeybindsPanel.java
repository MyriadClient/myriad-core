package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.KeybindButton;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.ui.WindowManager;

/** Every binding in one place: global actions, desktop shortcuts (with the mod key) and module toggles. */
public final class KeybindsPanel extends WidgetPanel {
	private final WindowManager wm;

	public KeybindsPanel(WindowManager wm) {
		this.wm = wm;
	}

	@Override
	public String title() {
		return "Keybinds";
	}

	@Override
	public String icon() {
		return "";
	}

	private static HBox row(String name, KeybindButton button) {
		HBox row = new HBox(4);
		row.addWeighted(new Label(name), 1);
		row.addFixed(button, 90);
		return row;
	}

	@Override
	protected void build(VBox content) {
		VBox global = new VBox(2, 0);
		for (KeyAction a : Myriad.keyActions()) {
			global.add(row(a.name(), new KeybindButton(a::bind, b -> {
				a.setBind(b);
				Myriad.config().markDirty();
			})));
		}
		content.add(new Collapsible("Global", global));

		VBox desktop = new VBox(2, 0);
		desktop.add(new Label(() -> "These are pressed together with the mod key (" + wm.theme().modKey.get().name().toLowerCase() + ", change it in Theme → Preferences). Workspaces 1–9, the HUD and the launcher also work in game, without the menu open.").dim());
		for (WindowManager.WmBind b : wm.binds().values()) {
			desktop.add(row(b.name, new KeybindButton(() -> b.bind, k -> {
				b.bind = k;
				Myriad.config().markDirty();
			})));
		}
		content.add(new Collapsible("Menu", desktop));

		VBox modules = new VBox(2, 0);
		for (Module m : Myriad.modules()) {
			modules.add(row(m.name(), new KeybindButton(m.keybind::get, m.keybind::set)));
		}
		if (Myriad.modules().size() > 0) content.add(new Collapsible("Modules", modules));
	}
}
