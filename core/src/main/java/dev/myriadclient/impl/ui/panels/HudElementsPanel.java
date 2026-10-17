package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.FuzzyMatch;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.ui.WindowImpl;
import dev.myriadclient.impl.ui.WindowManager;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The HUD window, laid out like a category window: every HUD element is an entry. Click one to put it on the HUD
 * or take it off; unfold it for its options.
 */
public final class HudElementsPanel extends WidgetPanel {
	private final WindowManager wm;
	private final Set<MyriadId> unfolded = new HashSet<>();
	private String filter = "";

	public HudElementsPanel(WindowManager wm) {
		this.wm = wm;
	}

	@Override
	public String title() {
		return "HUD";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void save(JsonObject out) {
		JsonArray a = new JsonArray();
		for (MyriadId id : unfolded) a.add(id.toString());
		out.add("unfolded", a);
	}

	@Override
	public void load(JsonObject in) {
		if (!in.has("unfolded")) return;
		for (var e : in.getAsJsonArray("unfolded")) unfolded.add(MyriadId.parse(e.getAsString()));
		rebuild();
	}

	@Override
	protected void build(VBox content) {
		content.add(new TextField(() -> filter).placeholder("  Filter HUD elements…").onChange(s -> filter = s));
		boolean any = false;
		for (PanelType type : Myriad.panels()) {
			if (!type.isHud()) continue;
			any = true;
			content.add(new HudEntry(type)).visible(() -> filter.isBlank() || FuzzyMatch.matches(type.name(), filter));
		}
		if (!any) content.add(new Label("No HUD elements installed. Addons such as myriad-essentials provide them.").dim());
		else content.add(new Label("Drag elements on the HUD workspace to move them; they snap to edges and the centre.").dim());
	}

	private final class HudEntry extends CardEntry {
		private final PanelType type;

		HudEntry(PanelType type) {
			super(wm.hudElement(type).isPresent(), unfolded.contains(type.id()));
			this.type = type;
		}

		private Optional<WindowImpl> element() {
			return wm.hudElement(type);
		}

		@Override
		protected String title() {
			return type.name();
		}

		@Override
		protected boolean isOn() {
			return element().isPresent();
		}

		@Override
		protected int accent() {
			return theme().accent.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(type.id());
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(type.id());
			else unfolded.remove(type.id());
		}

		/** The options belong to the element on the HUD, so the card is rebuilt when it's added or removed. */
		@Override
		protected Object bodyKey() {
			return element().map(w -> w.uid()).orElse(-1);
		}

		@Override
		protected void buildBody(VBox body) {
			Optional<WindowImpl> w = element();
			if (w.isEmpty()) {
				body.add(new Label("Not on the HUD. Click the name to add it; its options show up here.").dim());
				return;
			}
			if (w.get().panel().settings.all().isEmpty()) {
				body.add(new Label("No options.").dim());
				return;
			}
			body.add(SettingsView.build(w.get().panel().settings));
		}

		@Override
		protected void primary() {
			if (element().isPresent()) wm.removeHudElement(type);
			else wm.addHudElement(type);
		}

		@Override
		protected String tooltipText() {
			String owner = Myriad.panels().ownerOf(type);
			return (isOn() ? "On the HUD. Click to remove." : "Click to add to the HUD.") + "\n"
				+ badgeLine(" " + AddonNames.of(owner == null ? "myriad" : owner) + "@" + String.format("%08X", accent()));
		}
	}
}
