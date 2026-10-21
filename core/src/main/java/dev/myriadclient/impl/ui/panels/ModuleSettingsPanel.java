package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;

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
		content.add(SettingsView.build(module.settings));
	}
}
