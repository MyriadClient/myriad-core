package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.EmptyState;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.FuzzyMatch;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlPanelImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One control panel's window, like a category window: a search that adds modules, its modules as cards (each toggles on
 * its own and unfolds its settings, where the panel's saved ones are kept), its switch in the title bar, and, folded at
 * the bottom, what it is: name, description, bind.
 */
final class ControlPanelView extends WidgetPanel {
	private final ControlPanelImpl control;
	private final Set<Module> unfolded = new HashSet<>();
	/** Which of the modules' settings groups are open in this window (others showing them fold them on their own). */
	private final Map<SettingGroup, Boolean> groupsOpen = new HashMap<>();
	private final List<TitleControl> titleControls;
	private boolean editing;
	private final AddSearch<Module> search;
	private final VBox cards = new VBox(2, 0);
	/** The modules last built, to rebuild when they change. */
	private Set<MyriadId> built = Set.of();
	/**
	 * The Settings section, made once: the window rebuilds as modules are picked (for their cards), and a new one
	 * would close the module picker after every pick, losing its search and scroll.
	 */
	private Collapsible settingsSection;

	ControlPanelView(ControlPanelImpl control) {
		this.control = control;
		this.search = new AddSearch<>("Add modules…", this::find, control.members::toggle);
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
		return control.icon();
	}

	@Override
	public List<TitleControl> titleControls() {
		return titleControls;
	}

	@Override
	public void tick() {
		if (!built.equals(control.members.get())) fillCards();
		// Deleted: nothing left to show.
		if (!MyriadImpl.get().controlManager().impls().contains(control)) {
			Window w = window();
			if (w != null) w.close();
		}
	}

	/** Every module a search finds, those in the panel first as they're what's likely being looked for. */
	private List<AddSearch.Result<Module>> find(String query) {
		List<AddSearch.Result<Module>> on = new ArrayList<>(), rest = new ArrayList<>();
		for (Module m : Myriad.modules()) {
			if (m.isMirror() || !FuzzyMatch.matches(m.name(), query.strip())) continue;
			boolean in = control.members.contains(m);
			(in ? on : rest).add(new AddSearch.Result<>(m, m.tag().icon(), m.name(), m.group().name(), in));
		}
		on.addAll(rest);
		return on;
	}

	private Collapsible settingsSection() {
		if (settingsSection != null) return settingsSection;
		VBox edit = new VBox(3, 0);
		edit.add(SettingsView.rows(control.settings, s -> s != control.members));
		HBox actions = new HBox(4);
		if (control.isPreset()) {
			actions.addFixed(new Button("Reset", () -> MyriadImpl.get().controlManager().reset(control.id()))
				.tooltip("Back to the addon's version: its modules, saved settings and bind."), 40);
		}
		actions.addWeighted(Spacer.gap(0), 1);
		actions.addFixed(new Button("Delete", () -> Myriad.ui().confirm("Delete " + control.name(), "Delete this control panel? Its modules stay as they are.", "Delete",
			() -> Myriad.controls().delete(control), null)).danger(), 44);
		edit.add(actions);
		settingsSection = new Collapsible(() -> Icons.COG + "  Settings", edit, () -> editing, v -> editing = v).indent(8);
		return settingsSection;
	}

	@Override
	protected void build(VBox content) {
		fillCards();
		content.add(search);
		content.add(new EmptyState(Icons.SLIDERS, "No modules yet", "Search above for the modules it switches together.")).visible(() -> control.members.get().isEmpty());
		content.add(cards);
		content.add(settingsSection());
	}

	/** The modules' cards, on their own: the rest of the window (what's typed, the keyboard) stays as modules come and go. */
	private void fillCards() {
		built = Set.copyOf(control.members.get());
		cards.clear();
		for (Module m : control.modules()) cards.add(new ControlMemberEntry(m, unfolded, groupsOpen, control));
	}
}
