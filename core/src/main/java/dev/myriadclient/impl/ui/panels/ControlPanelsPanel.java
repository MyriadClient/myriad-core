package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlManager;
import dev.myriadclient.impl.ui.WindowManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every control panel of the profile, as cards: click one to switch it, unfold it for what it switches, open its window
 * from there. A name field at the bottom makes a new one.
 */
final class ControlPanelsPanel extends WidgetPanel {
	private String newName = "";
	private List<ControlPanel> built = List.of();
	private final Set<MyriadId> unfolded = new HashSet<>();
	private final List<TitleControl> titleControls;

	ControlPanelsPanel() {
		ControlManager controls = MyriadImpl.get().controlManager();
		titleControls = List.of(TitleControl.button(() -> "", controls::restoreDeleted, () -> "Bring back the addons' control panels you deleted")
			.visible(controls::anyDeleted));
	}

	@Override
	public String title() {
		return "Control Panels";
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
		if (!built.equals(Myriad.controls().all())) rebuild();
	}

	@Override
	protected void build(VBox content) {
		ControlManager controls = MyriadImpl.get().controlManager();
		built = Myriad.controls().all();
		for (ControlPanel c : built) content.add(new Card(c));
		if (built.isEmpty()) content.add(new Label("Switch several modules with one bind, each with its own settings for it: name one below.").dim());
		HBox create = new HBox(4);
		create.addWeighted(new TextField(() -> newName).placeholder(controls.freshName()).onChange(s -> newName = s), 1);
		create.addFixed(new Button("New", () -> {
			ControlPanel c = Myriad.controls().create(newName.isBlank() ? controls.freshName() : newName);
			newName = "";
			open(c);
		}).accent(), 36);
		content.add(create);
	}

	static void open(ControlPanel c) {
		JsonObject args = new JsonObject();
		args.addProperty("id", c.id().toString());
		((WindowManager) Myriad.ui()).openPanel(CorePanels.CONTROL, args);
	}

	/** A control panel: click switches it, unfolding lists its modules and opens its window. */
	private final class Card extends CardEntry {
		private final ControlPanel control;

		Card(ControlPanel control) {
			super(control.isActive(), unfolded.contains(control.id()));
			this.control = control;
			this.tooltip = control.isPreset() ? "From " + AddonNames.of(control.id().namespace()) : null;
		}

		@Override
		protected String title() {
			return control.name();
		}

		@Override
		protected boolean isOn() {
			return control.isActive();
		}

		@Override
		protected int accent() {
			return theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(control.id());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(control.id());
			else unfolded.remove(control.id());
		}

		@Override
		protected void primary() {
			control.toggle();
		}

		@Override
		protected void shiftSecondary() {
			open(control);
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			if (control.keybind().isSet()) right = badge(c, control.keybind().displayName(), right, cy, theme().textDim.argb(), false) - 4;
			return badge(c, control.modules().size() + "", right, cy, theme().textDim.argb(), false) - 4;
		}

		@Override
		protected void buildBody(VBox body) {
			List<Module> modules = control.modules();
			body.add(new Label(modules.isEmpty() ? "No modules yet." : String.join(", ", modules.stream().map(Module::name).toList())).dim());
			body.add(new Button(" Open", () -> open(control)));
		}
	}
}
