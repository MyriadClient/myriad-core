package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlPanelImpl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One control panel's window, like a category window: its modules as cards (each toggles on its own and unfolds its
 * settings, where the panel's saved ones are kept), its switch in the title bar, and, folded at the bottom, what it is:
 * name, description, modules, bind.
 */
final class ControlPanelView extends WidgetPanel {
	private final ControlPanelImpl control;
	private final Set<Module> unfolded = new HashSet<>();
	/** Which of the modules' settings groups are open in this window (others showing them fold them on their own). */
	private final Map<SettingGroup, Boolean> groupsOpen = new HashMap<>();
	private final List<TitleControl> titleControls;
	private boolean editing;
	/** The modules last built, to rebuild when they change. */
	private Set<MyriadId> built = Set.of();
	/**
	 * The Settings section, made once: the window rebuilds as modules are picked (for their cards), and a new one
	 * would close the module picker after every pick, losing its search and scroll.
	 */
	private Collapsible settingsSection;

	ControlPanelView(ControlPanelImpl control) {
		this.control = control;
		this.editing = control.members.get().isEmpty();
		this.titleControls = List.of(TitleControl.toggle(control::isActive, control::setActive,
			() -> (control.isActive() ? "Turn off " : "Turn on ") + control.name() + (control.keybind().isSet() ? " (" + control.keybind().displayName() + ")" : "")));
	}

	@Override
	public String title() {
		return control.name();
	}

	/** The description the player gave it, or the addon it came with. */
	@Override
	public String subtitle() {
		String d = control.description.get();
		if (!d.isBlank()) return d;
		return control.isPreset() ? AddonNames.of(control.id().namespace()) : null;
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public List<TitleControl> titleControls() {
		return titleControls;
	}

	@Override
	public void tick() {
		if (!built.equals(control.members.get())) rebuild();
		// Deleted: nothing left to show.
		if (!MyriadImpl.get().controlManager().impls().contains(control)) {
			Window w = window();
			if (w != null) w.close();
		}
	}

	private Collapsible settingsSection() {
		if (settingsSection != null) return settingsSection;
		VBox edit = new VBox(2, 0);
		edit.add(SettingsView.build(control.settings));
		HBox actions = new HBox(4);
		if (control.isPreset()) {
			actions.addFixed(new Button("Reset", () -> MyriadImpl.get().controlManager().reset(control.id()))
				.tooltip("Back to the addon's version: its modules, saved settings and bind."), 40);
		}
		actions.addWeighted(new Label(""), 1);
		actions.addFixed(new Button("Delete", () -> Myriad.controls().delete(control)).tooltip("Delete " + control.name() + "."), 40);
		edit.add(actions);
		settingsSection = new Collapsible(() -> "  Settings", edit, () -> editing, v -> editing = v);
		return settingsSection;
	}

	@Override
	protected void build(VBox content) {
		built = Set.copyOf(control.members.get());
		List<Module> modules = control.modules();
		if (modules.isEmpty()) content.add(new Label("No modules yet: pick some under Settings below.").dim());
		for (Module m : modules) content.add(new ControlMemberEntry(m, unfolded, groupsOpen, control));

		content.add(settingsSection());
	}
}
