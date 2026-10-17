package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlManager;
import dev.myriadclient.impl.ui.WindowManager;

import java.util.List;

/** Every control panel of the profile: switch one, open its window, or make a new one. */
final class ControlPanelsPanel extends WidgetPanel {
	private String newName = "";
	private List<ControlPanel> built = List.of();

	@Override
	public String title() {
		return "Control Panels";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void tick() {
		if (!built.equals(Myriad.controls().all())) rebuild();
	}

	@Override
	protected void build(VBox content) {
		ControlManager controls = MyriadImpl.get().controlManager();
		built = Myriad.controls().all();
		content.add(new Label("A control panel switches a set of modules together with one bind, each with the settings it remembers for it.").dim());
		for (ControlPanel c : built) {
			HBox row = new HBox(4);
			row.addWeighted(new Label(() -> c.name() + "  " + c.modules().size() + (c.keybind().isSet() ? "  · " + c.keybind().displayName() : "")), 1);
			row.addFixed(new Button("Open", () -> open(c)), 40);
			row.addFixed(new Toggle(c::isActive, c::setActive), 22);
			content.add(row);
		}
		if (built.isEmpty()) content.add(new Label("None yet.").dim());
		content.add(Spacer.divider());
		HBox create = new HBox(4);
		create.addWeighted(new TextField(() -> newName).placeholder(controls.freshName()).onChange(s -> newName = s), 1);
		create.addFixed(new Button("Create", () -> {
			ControlPanel c = Myriad.controls().create(newName.isBlank() ? controls.freshName() : newName);
			newName = "";
			open(c);
		}).accent(), 50);
		content.add(create);
		if (controls.anyDeleted()) content.add(new Button("Bring back the addons' control panels", controls::restoreDeleted));
	}

	static void open(ControlPanel c) {
		JsonObject args = new JsonObject();
		args.addProperty("id", c.id().toString());
		((WindowManager) Myriad.ui()).openPanel(CorePanels.CONTROL, args);
	}
}
