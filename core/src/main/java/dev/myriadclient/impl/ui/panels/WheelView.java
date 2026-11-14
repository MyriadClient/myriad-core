package dev.myriadclient.impl.ui.panels;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.IconPicker;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One wheel's window: a little picture of the wheel as it will show, a search that adds modules, macros, control panels,
 * actions and panels or a typed command, the slots in order as cards (clockwise from the top; drag one to move it, unfold
 * it for its own name and icon), and its settings folded at the bottom.
 */
final class WheelView extends WidgetPanel {
	private final WheelImpl wheel;
	private boolean editing;
	/** Made once and kept: what's typed, the keyboard, each card's fold and a drag all carry on as slots change. */
	private final Preview preview = new Preview();
	private final SlotList slots = new SlotList();
	private final AddSearch<WheelSlot> search = new AddSearch<>("Add: search, .command, /command", this::find, this::toggle);
	private Collapsible settingsSection;
	/** Slots unfolded, by what they run. */
	private final Set<String> unfolded = new HashSet<>();
	/** The slot pointed at, on the picture or its card, drawn lit on the other. */
	private String pointed;

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
		return wheel.keybind.get().isSet() ? "Hold " + wheel.keybind.get().displayName() : "No bind";
	}

	@Override
	public String icon() {
		return wheel.icon();
	}

	@Override
	public void tick() {
		slots.sync();
		// Deleted: nothing left to show.
		if (!MyriadImpl.get().wheelManager().all().contains(wheel)) {
			Window w = window();
			if (w != null) w.close();
		}
	}

	@Override
	protected void build(VBox content) {
		slots.sync();
		content.add(preview);
		content.add(search);
		content.add(slots);
		content.add(settingsSection());
	}

	private static String key(WheelSlot s) {
		return s.kind() + ":" + s.target();
	}

	/** What a search finds, each marked if it's on the wheel. */
	private List<AddSearch.Result<WheelSlot>> find(String query) {
		List<WheelSlot> on = wheel.slots();
		List<AddSearch.Result<WheelSlot>> out = new ArrayList<>();
		for (WheelSlot s : WheelSlot.candidates(query)) out.add(new AddSearch.Result<>(s, s.icon(), s.label(), s.hint(), on.stream().anyMatch(s::sameTarget)));
		return out;
	}

	/** Puts it on, or takes it off if it's there already. */
	private void toggle(WheelSlot s) {
		WheelSlot there = wheel.slots().stream().filter(s::sameTarget).findFirst().orElse(null);
		if (there != null) wheel.remove(there);
		else wheel.add(s);
		slots.sync();
	}

	private Collapsible settingsSection() {
		if (settingsSection != null) return settingsSection;
		VBox edit = new VBox(3, 0);
		edit.add(SettingsView.rows(wheel.settings, s -> true));
		HBox actions = new HBox(4);
		actions.addWeighted(Spacer.gap(0), 1);
		actions.addFixed(new Button("Delete", () -> Myriad.ui().confirm("Delete " + wheel.name(), "Delete this wheel and what's on it?", "Delete",
			() -> MyriadImpl.get().wheelManager().delete(wheel), null)).danger(), 44);
		edit.add(actions);
		settingsSection = new Collapsible(() -> Icons.COG + "  Settings", edit, () -> editing, v -> editing = v).indent(8);
		return settingsSection;
	}

	/** The cards in the wheel's order, keeping each card (and so its fold, and a drag under way) as the order changes. */
	private final class SlotList extends VBox {
		private final Map<String, SlotCard> cards = new HashMap<>();
		private SlotCard dragging;

		SlotList() {
			super(2, 0);
		}

		void sync() {
			List<Widget> next = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			for (WheelSlot s : wheel.slots()) {
				String k = key(s);
				keys.add(k);
				next.add(cards.computeIfAbsent(k, x -> new SlotCard(s)));
			}
			cards.keySet().retainAll(keys);
			if (next.equals(children())) return;
			clear();
			next.forEach(this::add);
		}

		/** The card held is pointed at height {@code my}: it moves to where that is among the others. */
		void dragTo(SlotCard card, float my) {
			int from = children().indexOf(card);
			if (from < 0) return;
			int to = from;
			while (to > 0 && my < children().get(to - 1).y() + children().get(to - 1).height() / 2) to--;
			while (to < children().size() - 1 && my > children().get(to + 1).y() + children().get(to + 1).height() / 2) to++;
			if (to == from) return;
			for (int i = Math.abs(to - from); i > 0; i--) wheel.move(card.slot(), to > from ? 1 : -1);
			sync();
		}
	}

	/** A slot: what it runs, unfolding to its own name and icon, moving and removing. Dragged by its header, it moves. */
	private final class SlotCard extends CardEntry {
		private static final float DRAG_START = 3;
		private final String key;
		private final WheelSlot first;
		/** Where the header was pressed (NaN: it isn't held). */
		private float pressY = Float.NaN;
		private boolean moved;

		SlotCard(WheelSlot slot) {
			super(slot.isOn(), unfolded.contains(key(slot)));
			this.first = slot;
			this.key = key(slot);
		}

		/** The slot as it is now (its icon or name may have changed since this was made). */
		WheelSlot slot() {
			for (WheelSlot s : wheel.slots()) if (key(s).equals(key)) return s;
			return first;
		}

		@Override
		protected String title() {
			return slot().label();
		}

		@Override
		protected String icon() {
			return slot().icon();
		}

		@Override
		protected boolean isOn() {
			return slot().isOn() || key.equals(pointed) || slots.dragging == this;
		}

		@Override
		protected int accent() {
			return slot().exists() ? theme().accent.argb() : theme().red.argb();
		}

		@Override
		protected boolean isExpanded() {
			return unfolded.contains(key);
		}

		@Override
		protected void setExpanded(boolean expanded) {
			if (expanded) unfolded.add(key);
			else unfolded.remove(key);
		}

		@Override
		protected float drawBadges(Canvas c, float right, float cy) {
			WheelSlot s = slot();
			return badge(c, s.hint(), right, cy, s.exists() ? theme().textDim.argb() : theme().red.argb(), !s.exists()) - 4;
		}

		@Override
		protected void buildBody(VBox body) {
			HBox name = new HBox(4);
			name.addFixed(new Label("Name").dim(), 34);
			name.addWeighted(new TextField(() -> slot().customName()).placeholder(first.defaultLabel())
				.onChange(s -> wheel.replace(slot(), slot().withName(s))), 1);
			body.add(name);
			body.add(new IconPicker(() -> slot().customIcon(), g -> wheel.replace(slot(), slot().withIcon(g)))
				.fallback(() -> slot().defaultIcon()).named("Icon", 38));
			HBox actions = new HBox(3);
			Button up = actions.addFixed(new Button("", () -> move(-1)), 18);
			Button down = actions.addFixed(new Button("", () -> move(1)), 18);
			up.tooltip("Move anticlockwise");
			down.tooltip("Move clockwise");
			actions.addWeighted(Spacer.gap(0), 1);
			actions.addFixed(new Button("Remove", () -> {
				wheel.remove(slot());
				slots.sync();
			}).danger(), 48);
			body.add(actions);
		}

		private void move(int by) {
			wheel.move(slot(), by);
			slots.sync();
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			super.render(c, mx, my);
			if (mx >= x && my >= y && mx < x + width && my < y + ROW + 2) pointed = key;
			else if (key.equals(pointed) && !preview.isOver(mx, my)) pointed = null;
		}

		@Override
		public boolean mouseClicked(float mx, float my, int button) {
			boolean header = mx >= x && my >= y && mx < x + width && my < y + ROW + 2;
			if (button != InputConstants.MOUSE_BUTTON_LEFT || !header) return super.mouseClicked(mx, my, button);
			pressY = my;
			moved = false;
			return true;
		}

		@Override
		public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
			if (Float.isNaN(pressY)) return false;
			if (!moved && Math.abs(my - pressY) < DRAG_START) return true;
			moved = true;
			slots.dragging = this;
			slots.dragTo(this, my);
			return true;
		}

		@Override
		public boolean mouseReleased(float mx, float my, int button) {
			if (Float.isNaN(pressY)) return false;
			pressY = Float.NaN;
			slots.dragging = null;
			if (!moved) setExpanded(!isExpanded());
			return true;
		}
	}

	/**
	 * The wheel in small, as it shows in game: the slots round a disc, the first at the top, the middle naming the one
	 * pointed at. Clicking one unfolds its card. It's there with nothing on the wheel too, so the first slot added doesn't
	 * push the window about.
	 */
	private final class Preview extends Widget {
		/** Tallest it gets; narrower windows make it smaller. */
		private static final float MAX = 118;
		private int over = -1;

		boolean isOver(float mx, float my) {
			return mx >= x && my >= y && mx < x + width && my < y + height;
		}

		private float outer() {
			return Math.min(width, MAX) / 2 - 1;
		}

		private float button(int count) {
			float outer = outer();
			float wanted = outer * 0.2f;
			float ring = outer - wanted - 3;
			return count < 3 ? wanted : Math.min(wanted, ring * (float) Math.sin(Math.PI / count) * 0.85f);
		}

		private float ringRadius(int count) {
			return outer() - button(count) - 3;
		}

		private float slotX(int i, int count) {
			return x + width / 2 + (float) Math.cos(angle(i, count)) * ringRadius(count);
		}

		private float slotY(int i, int count) {
			return y + outer() + 1 + (float) Math.sin(angle(i, count)) * ringRadius(count);
		}

		private double angle(int i, int count) {
			return -Math.PI / 2 + i * 2 * Math.PI / Math.max(1, count);
		}

		@Override
		protected float measure(Canvas c, float width) {
			return Math.min(width, MAX);
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			List<WheelSlot> list = wheel.slots();
			int n = list.size();
			float outer = outer(), cx = x + width / 2, cy = y + outer + 1;
			float b = button(n), ring = ringRadius(n), inner = ring - b - 3;
			c.circle(cx, cy, outer, theme().surface.argb());
			c.circle(cx, cy, Math.max(0, inner), theme().surface.argb());
			over = -1;
			for (int i = 0; i < n; i++) {
				float dx = mx - slotX(i, n), dy = my - slotY(i, n);
				if (dx * dx + dy * dy <= b * b) over = i;
			}
			if (over >= 0) pointed = key(list.get(over));
			else if (isOver(mx, my)) pointed = null;
			float size = c.defaultFontSize();
			float iconSize = Math.min(size * 1.05f, b * 1.25f);
			WheelSlot lit = null;
			for (int i = 0; i < n; i++) {
				WheelSlot s = list.get(i);
				boolean here = key(s).equals(pointed);
				if (here) lit = s;
				float sx = slotX(i, n), sy = slotY(i, n);
				int fill = here ? theme().accent.argb() : s.isOn() ? ColorUtil.withAlpha(theme().accent.argb(), 70) : theme().surfaceHover.argb();
				c.circle(sx, sy, b, fill);
				if (s.isOn() && !here) c.outline(sx - b, sy - b, b * 2, b * 2, b, 1, theme().accent.argb());
				String icon = s.icon();
				int color = here ? theme().windowBackground.argb() | 0xFF000000 : !s.exists() ? theme().textDim.argb() : s.isOn() ? theme().accent.argb() : theme().text.argb();
				c.text(FontFamily.MONO, iconSize, icon, sx - c.textWidth(FontFamily.MONO, iconSize, icon) / 2, sy - c.textHeight(FontFamily.MONO, iconSize) / 2, color);
			}
			// The middle: the wheel, or the slot pointed at.
			float maxW = Math.max(0, inner * 2 - 6);
			float ts = size * 0.9f;
			if (lit != null) {
				String t = c.ellipsize(FontFamily.SANS, ts, lit.label(), maxW);
				c.text(FontFamily.SANS, ts, t, cx - c.textWidth(FontFamily.SANS, ts, t) / 2, cy - c.textHeight(FontFamily.SANS, ts) / 2, theme().text.argb());
			} else {
				float is = size * 1.3f;
				String g = wheel.icon();
				float ih = c.textHeight(FontFamily.MONO, is);
				// Empty, and room to say so: that under the icon, the two centred together.
				float hs = size * 0.8f, lh = c.textHeight(FontFamily.SANS, hs) + 1;
				List<String> say = n == 0 ? Label.wrap(c, FontFamily.SANS, hs, "Nothing on it yet", Math.max(1, maxW)) : List.of();
				if (say.size() > 2) say = List.of();
				float top = cy - (ih + say.size() * lh) / 2;
				c.text(FontFamily.MONO, is, g, cx - c.textWidth(FontFamily.MONO, is, g) / 2, top, n == 0 ? theme().textDim.argb() : theme().accent.argb());
				float ly = top + ih;
				for (String line : say) {
					c.text(FontFamily.SANS, hs, line, cx - c.textWidth(FontFamily.SANS, hs, line) / 2, ly, theme().textDim.argb());
					ly += lh;
				}
			}
		}

		@Override
		public boolean mouseClicked(float mx, float my, int button) {
			if (button != InputConstants.MOUSE_BUTTON_LEFT || over < 0 || over >= wheel.slots().size()) return false;
			String k = key(wheel.slots().get(over));
			if (!unfolded.remove(k)) unfolded.add(k);
			return true;
		}
	}
}
