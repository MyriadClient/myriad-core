package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.IconPicker;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelSlot;

import java.util.List;

/**
 * One wheel's window: its slots in order (clockwise from the top), each movable and removable, and given an icon and a
 * name of its own from the button at its left; a search below that adds modules, macros, control panels, actions and
 * panels, or a typed command; and, folded at the bottom, its name, bind, icon and Delete.
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
	/** The slot whose icon and name are being edited (by place on the wheel), or -1. */
	private int editingSlot = -1;

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
		return wheel.icon();
	}

	@Override
	public void tick() {
		// A slot's new icon or name shows live; only adding, removing or moving rebuilds.
		if (!sameSlots(built, wheel.slots())) rebuild();
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
		if (editingSlot >= built.size()) editingSlot = -1;
		for (int i = 0; i < built.size(); i++) {
			content.add(row(i, built.size()));
			if (i == editingSlot) content.add(slotEditor(i));
		}
		content.add(picker());
		content.add(settingsSection());
		if (refocus) {
			refocus = false;
			search.requestFocus();
		}
	}

	private static boolean sameSlots(List<WheelSlot> a, List<WheelSlot> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) if (!a.get(i).sameTarget(b.get(i))) return false;
		return true;
	}

	/** Slot {@code index} as it is now (its icon or name may have changed since the window was built). */
	private WheelSlot slot(int index) {
		List<WheelSlot> now = wheel.slots();
		return index < now.size() ? now.get(index) : built.get(index);
	}

	private HBox row(int index, int count) {
		HBox row = new HBox(3);
		row.addFixed(new Button(() -> slot(index).icon(), () -> {
			editingSlot = editingSlot == index ? -1 : index;
			rebuild();
		}), 16).tooltip("Its icon and name on the wheel");
		Label name = new Label(() -> slot(index).label());
		name.color(() -> slot(index).exists() ? Myriad.ui().theme().text.argb() : Myriad.ui().theme().textDim.argb());
		row.addWeighted(name, 1);
		row.addFixed(new Label(() -> slot(index).hint()).dim(), 70);
		Button up = row.addFixed(new Button("\uf062", () -> wheel.move(slot(index), -1)), 16);
		Button down = row.addFixed(new Button("\uf063", () -> wheel.move(slot(index), 1)), 16);
		row.addFixed(new Button("\uf00d", () -> wheel.remove(slot(index))), 16);
		up.tooltip(index == 0 ? "Already first (at the top)" : "Move anticlockwise");
		down.tooltip(index == count - 1 ? "Already last" : "Move clockwise");
		return row;
	}

	/** Under a slot: its own name (blank for what it runs) and icon (Default for what it runs). */
	private VBox slotEditor(int index) {
		VBox box = new VBox(2, 0);
		box.add(new TextField(() -> slot(index).customName()).placeholder(slot(index).defaultLabel())
			.onChange(s -> wheel.replace(slot(index), slot(index).withName(s))));
		box.add(new IconPicker(() -> slot(index).customIcon(), g -> wheel.replace(slot(index), slot(index).withIcon(g))));
		return box;
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
			if (on.stream().anyMatch(s::sameTarget)) continue;
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
