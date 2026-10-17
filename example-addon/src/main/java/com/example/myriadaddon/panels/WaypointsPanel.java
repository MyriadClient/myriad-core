package com.example.myriadaddon.panels;

import com.example.myriadaddon.waypoints.Waypoint;
import com.example.myriadaddon.waypoints.WaypointStore;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.Toggle;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.Format;

import java.util.List;
import java.util.Objects;

/**
 * A window for managing waypoints, built from widgets. Open it from the launcher ({@code mod+Space}, "Waypoints") or
 * with the addon's key action.
 *
 * <p>{@link WidgetPanel} calls {@link #build} once; widgets read live values through suppliers, so they stay current
 * without rebuilding. When the <i>structure</i> changes (a waypoint added or removed), call {@link #rebuild()}: here
 * {@link #tick()} compares the store's version and the world, so changes from the command or key action show up too.
 */
public final class WaypointsPanel extends WidgetPanel {
	private final WaypointStore store;
	private int builtVersion = -1;
	private String builtWorld;
	private String newName = "";

	public WaypointsPanel(WaypointStore store) {
		this.store = store;
	}

	@Override
	public String title() {
		return "Waypoints";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void tick() {
		if (builtVersion != store.version() || !Objects.equals(builtWorld, WaypointStore.worldKey())) rebuild();
	}

	@Override
	protected void build(VBox content) {
		builtVersion = store.version();
		builtWorld = WaypointStore.worldKey();
		if (builtWorld == null) {
			content.add(new Label("Join a world to see and add its waypoints.").dim());
			return;
		}

		// Adding: type a name, press Enter or the button.
		HBox add = new HBox(4);
		add.addWeighted(new TextField(() -> newName).onChange(s -> newName = s).onSubmit(s -> addHere()).placeholder("New waypoint name"), 1);
		add.addFixed(new Button(" Add Here", this::addHere), 64).tooltip("Adds a waypoint at your feet.");
		content.add(add);

		List<Waypoint> all = store.here();
		if (all.isEmpty()) {
			content.add(new Label("No waypoints yet.").dim());
			return;
		}

		// One row per waypoint: show/hide, name and details, delete. Grouped so other dimensions fold away.
		String dimension = WaypointStore.currentDimension();
		VBox here = new VBox(2, 0), elsewhere = new VBox(2, 0);
		for (Waypoint w : all) (w.dimension().equals(dimension) ? here : elsewhere).add(row(w));
		content.add(new Collapsible("This Dimension", here).indent(10).hint(() -> String.valueOf(here.children().size())));
		if (!elsewhere.children().isEmpty()) {
			content.add(new Collapsible("Other Dimensions", elsewhere).indent(10).hint(() -> String.valueOf(elsewhere.children().size())));
		}
	}

	private HBox row(Waypoint w) {
		HBox row = new HBox(4);
		row.addFixed(new Toggle(() -> store.find(w.name()).map(Waypoint::visible).orElse(false), v -> store.setVisible(w.name(), v)), 18)
			.tooltip("Show in the world");
		row.addWeighted(new Label(w.name()), 1);
		row.addWeighted(new Label(() -> details(w)).dim(), 1);
		row.addFixed(new Button("", () -> store.remove(w.name())), 18).tooltip("Delete " + w.name());
		return row;
	}

	private String details(Waypoint w) {
		if (mc.player == null || !w.dimension().equals(WaypointStore.currentDimension())) return w.coords() + "  " + w.dimension().replace("minecraft:", "");
		return w.coords() + "  " + Format.distance(w.center().distanceTo(mc.player.position()));
	}

	private void addHere() {
		if (mc.player == null) return;
		String name = newName.isBlank() ? store.nextName("Waypoint") : newName.trim();
		if (store.put(name, mc.player.blockPosition())) {
			Myriad.notifications().success("Waypoints", "Added " + name);
			newName = "";
		}
	}
}
