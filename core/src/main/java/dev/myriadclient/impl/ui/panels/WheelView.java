package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelSlot;

import java.util.List;

/**
 * One wheel's window: its slots in order (clockwise from the top), each movable and removable; a search below that adds
 * modules, macros, control panels, actions and panels, or a typed command; and, folded at the bottom, its name, bind
 * and Delete.
 */
final class WheelView extends WidgetPanel {
	/** Search results shown at once. */
	private static final int RESULTS = 8;

	private final WheelImpl wheel;
	private List<WheelSlot> built = List.of();
	private boolean editing;
	private String query = "";
	/**
	 * The search and the settings, made once: the window rebuilds as slots are added, and new ones would lose what's
	 * typed (and which field has the keyboard).
	 */
	private VBox picker;
	private TextField search;
	/** Give the search the keyboard again after the rebuild an add causes, to add several in a row. */
	private boolean refocus;
	private final VBox results = new VBox(2, 0);
	private Collapsible settingsSection;

	WheelView(WheelImpl wheel) {
		this.wheel = wheel;
		this.editing = !wheel.keybind.get().isSet();
	}

	@Override
	public String title() {
		return wheel.name();
	}

	@Override
	public String subtitle() {
		return wheel.keybind.get().isSet() ? "Hold " + wheel.keybind.get().displayName() : "No bind yet: set one under Settings";
	}

	@Override
	public String icon() {
		return WheelsPanel.ICON;
	}

	@Override
	public void tick() {
		if (!built.equals(wheel.slots())) rebuild();
		// Deleted: nothing left to show.
		if (!MyriadImpl.get().wheelManager().all().contains(wheel)) {
			Window w = window();
			if (w != null) w.close();
		}
	}

	@Override
	protected void build(VBox content) {
		built = wheel.slots();
		if (built.isEmpty()) content.add(new Label("Nothing on it yet: search below for what to put on it.").dim());
		for (int i = 0; i < built.size(); i++) content.add(row(built.get(i), i, built.size()));
		content.add(picker());
		content.add(settingsSection());
		if (refocus) {
			refocus = false;
			search.requestFocus();
		}
	}

	private HBox row(WheelSlot slot, int index, int count) {
		HBox row = new HBox(3);
		Label name = new Label(slot.icon() + "  " + slot.label());
		if (!slot.exists()) name.dim();
		row.addWeighted(name, 1);
		row.addFixed(new Label(slot.hint()).dim(), 70);
		Button up = row.addFixed(new Button("", () -> wheel.move(slot, -1)), 16);
		Button down = row.addFixed(new Button("", () -> wheel.move(slot, 1)), 16);
		row.addFixed(new Button("", () -> wheel.remove(slot)), 16);
		up.tooltip(index == 0 ? "Already first (at the top)" : "Move anticlockwise");
		down.tooltip(index == count - 1 ? "Already last" : "Move clockwise");
		return row;
	}

	private VBox picker() {
		if (picker != null) return picker;
		picker = new VBox(2, 0);
		search = picker.add(new TextField(() -> query).placeholder("Add: search, or type .command or /command")
			.onChange(s -> {
				query = s;
				fillResults();
			})
			.onSubmit(s -> {
				List<WheelSlot> found = WheelSlot.candidates(s);
				if (!found.isEmpty()) add(found.getFirst());
			}));
		picker.add(results);
		fillResults();
		return picker;
	}

	private void fillResults() {
		results.clear();
		if (query.isBlank()) return;
		List<WheelSlot> on = wheel.slots();
		int shown = 0;
		for (WheelSlot s : WheelSlot.candidates(query)) {
			if (on.contains(s)) continue;
			if (shown++ == RESULTS) break;
			results.add(new Button(s.icon() + "  " + s.label() + "   · " + s.hint(), () -> add(s)));
		}
		if (shown == 0) results.add(new Label("Nothing matches.").dim());
	}

	private void add(WheelSlot slot) {
		wheel.add(slot);
		query = "";
		search.setText("");
		refocus = true;
		fillResults();
	}

	private Collapsible settingsSection() {
		if (settingsSection != null) return settingsSection;
		VBox edit = new VBox(2, 0);
		edit.add(SettingsView.build(wheel.settings));
		HBox actions = new HBox(4);
		actions.addWeighted(new Label(""), 1);
		actions.addFixed(new Button("Delete", () -> MyriadImpl.get().wheelManager().delete(wheel)).tooltip("Delete " + wheel.name() + "."), 40);
		edit.add(actions);
		settingsSection = new Collapsible(() -> "\uf013  Settings", edit, () -> editing, v -> editing = v);
		return settingsSection;
	}
}
