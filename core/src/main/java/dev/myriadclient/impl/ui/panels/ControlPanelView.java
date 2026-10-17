package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlPanelImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One control panel's window: its switch, its modules (each toggles on its own, and unfolds its settings, as in a
 * category window) with the settings the panel remembers for them, and, folded away, what it is: name, modules, bind.
 */
final class ControlPanelView extends WidgetPanel {
	private final ControlPanelImpl control;
	private final Set<Module> unfolded = new HashSet<>();
	private boolean editing;
	/** The modules last built, to rebuild when they change. */
	private Set<MyriadId> built = Set.of();

	ControlPanelView(ControlPanelImpl control) {
		this.control = control;
		this.editing = control.members.get().isEmpty();
	}

	@Override
	public String title() {
		return control.name();
	}

	@Override
	public String subtitle() {
		return control.isPreset() ? AddonNames.of(control.id().namespace()) : "Control panel";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void tick() {
		if (!built.equals(control.members.get())) rebuild();
		// Deleted: the window has nothing left to show.
		if (!MyriadImpl.get().controlManager().impls().contains(control)) {
			Window w = window();
			if (w != null) w.close();
		}
	}

	@Override
	protected void build(VBox content) {
		built = Set.copyOf(control.members.get());
		HBox head = new HBox(4);
		head.addWeighted(new Label(() -> control.isActive() ? "On: its modules run with the settings below" : "Off"), 1);
		head.addFixed(new Toggle(control::isActive, control::setActive), 22);
		content.add(head);

		List<Module> modules = control.modules();
		if (modules.isEmpty()) content.add(new Label("No modules yet: add some under Edit.").dim());
		for (Module m : modules) {
			content.add(new ModuleEntry(m, unfolded));
			HBox row = new HBox(4);
			row.addWeighted(new Label(() -> {
				List<String> keys = control.rememberedKeys(m);
				return keys.isEmpty() ? "   Its own settings" : "   Remembers " + keys.size() + " setting" + (keys.size() == 1 ? "" : "s");
			}).dim(), 1);
			row.addFixed(new Button("Remember", () -> {
				control.remember(m);
				info(m.name() + ": settings remembered for " + control.name());
			}).tooltip("Remember " + m.name() + "'s settings as they are now: it takes them whenever " + control.name() + " turns on, and gets its own back when it turns off."), 56);
			row.addFixed(new Button("Forget", () -> control.forget(m)).tooltip("Run " + m.name() + " with its own settings."), 44)
				.visible(() -> control.rememberedCount(m) > 0);
			content.add(row);
		}

		content.add(Spacer.divider());
		VBox edit = new VBox(2, 0);
		edit.add(SettingsView.build(control.settings));
		HBox actions = new HBox(4);
		if (control.isPreset()) {
			actions.addFixed(new Button("Reset", () -> MyriadImpl.get().controlManager().reset(control.id()))
				.tooltip("Back to the addon's version: its modules, settings and bind."), 44);
		}
		actions.addWeighted(new Label(""), 1);
		actions.addFixed(new Button("Delete", () -> Myriad.controls().delete(control)), 44);
		edit.add(actions);
		content.add(new Collapsible(() -> "Edit", edit, () -> editing, v -> editing = v));
	}

	private static void info(String text) {
		Myriad.notifications().send("Control panel", text, dev.myriadclient.api.service.Notifications.Level.SUCCESS, 2000, "control-remember");
	}
}
