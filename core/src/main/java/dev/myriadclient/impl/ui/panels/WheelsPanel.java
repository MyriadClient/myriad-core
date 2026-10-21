package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelManager;
import dev.myriadclient.impl.wheel.WheelSlot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every wheel of the profile, as cards: click one to open its window, unfold it for what's on it. A name field at the
 * bottom makes a new one.
 */
final class WheelsPanel extends WidgetPanel {
	static final String ICON = Icons.DOT_CIRCLE;

	private String newName = "";
	private List<WheelImpl> built = List.of();
	private final Set<WheelImpl> unfolded = new HashSet<>();

	@Override
	public String title() {
		return "Wheels";
	}

	@Override
	public String icon() {
		return ICON;
	}

	@Override
	public void tick() {
		if (!built.equals(MyriadImpl.get().wheelManager().all())) rebuild();
	}

	@Override
	protected void build(VBox content) {
		WheelManager wheels = MyriadImpl.get().wheelManager();
		built = wheels.all();
		for (WheelImpl w : built) content.add(new Card(w));
		if (built.isEmpty()) {
			content.add(new Label("Hold a bind to pick from a wheel of modules, macros, control panels, actions or commands: name one below.").dim());
		}
		HBox create = new HBox(4);
		create.addWeighted(new TextField(() -> newName).placeholder(wheels.freshName()).onChange(s -> newName = s), 1);
		create.addFixed(new Button("New", () -> {
			WheelImpl w = wheels.create(newName);
			newName = "";
			open(w);
		}).accent(), 36);
		content.add(create);
	}

	static void open(WheelImpl wheel) {
		JsonObject args = new JsonObject();
		args.addProperty("id", MyriadImpl.get().wheelManager().id(wheel));
		((WindowManager) Myriad.ui()).openPanel(CorePanels.WHEEL, args);
	}

	/** A wheel: click opens its window, unfolding lists what's on it. */
	private final class Card extends CardEntry {
		private final WheelImpl wheel;

		Card(WheelImpl wheel) {
			super(false, unfolded.contains(wheel));
			this.wheel = wheel;
		}

		@Override
		protected String title() {
			return wheel.icon() + "  " + wheel.name();
		}

		@Override
		protected boolean isOn() {
			return false;
		}

		@Override
		protected int accent() {
			return theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(wheel);
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(wheel);
			else unfolded.remove(wheel);
		}

		@Override
		protected void primary() {
			open(wheel);
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			if (wheel.keybind.get().isSet()) right = badge(c, wheel.keybind.get().displayName(), right, cy, theme().textDim.argb(), false) - 4;
			return badge(c, wheel.slots().size() + "", right, cy, theme().textDim.argb(), false) - 4;
		}

		@Override
		protected void buildBody(VBox body) {
			List<WheelSlot> slots = wheel.slots();
			body.add(new Label(slots.isEmpty() ? "Nothing on it yet." : String.join(", ", slots.stream().map(WheelSlot::label).toList())).dim());
			body.add(new Button("\uf2d0 Open", () -> open(wheel)));
		}
	}
}
