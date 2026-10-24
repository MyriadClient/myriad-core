package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;

public final class ModuleSettingsPanel extends WidgetPanel {
	private final Module module;

	public ModuleSettingsPanel(Module module) {
		this.module = module;
	}

	public Module module() {
		return module;
	}

	@Override
	public String title() {
		return module.name();
	}

	@Override
	public String icon() {
		return module.tag().icon();
	}

	@Override
	protected void build(VBox content) {
		HBox header = new HBox(4);
		header.addWeighted(new Label(() -> module.isEnabled() ? "Enabled" : "Disabled").bold(), 1);
		header.addFixed(new Toggle(module::isEnabled, module::setEnabled), 24);
		content.add(header);
		if (!module.description().isEmpty()) content.add(new Label(module.description()).dim());
		content.add(new Label(module.group().name() + " · " + AddonNames.of(module)).dim());
		Widget preview = module.preview();
		if (preview != null) {
			content.add(preview);
			// A preview shows what the modules that are on draw, this one included.
			content.add(new Label("Turn it on to see it on the preview.").dim().visible(() -> !module.isEnabled()));
		}
		content.add(SettingsView.build(module.settings));
		content.add(resetButton(module));
	}

	/**
	 * Puts every setting of {@code module} back to its default, after asking (it's one click from the settings, and
	 * can't be undone). The keybind and hold mode stay: they're how the player reaches the module, not how it behaves.
	 */
	static Widget resetButton(Module module) {
		return new Button("Reset to Default", () -> Myriad.ui().confirm("Reset " + module.name(),
			"Put every setting of " + module.name() + " back to its default? Its keybind stays.", "Reset", () -> {
				for (Setting<?> s : module.settings.all()) if (s != module.keybind && s != module.holdMode) s.reset();
			}, null));
	}
}
