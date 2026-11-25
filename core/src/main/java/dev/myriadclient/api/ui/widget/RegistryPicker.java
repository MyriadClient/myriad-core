package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.ui.draw.Canvas;
import org.jetbrains.annotations.ApiStatus;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inline searchable multi-select over a vanilla registry, instead of a separate picker screen. Selected
 * entries sort first. Shut, it's a row naming what's picked (with a count); click it (or Enter) to
 * open a search box and a scrolling list, where clicking an entry picks or unpicks it. The search matches names and
 * ids. The order is taken when it opens or the search changes, so entries don't jump while you pick.
 *
 * @param <T> the registry's entry type
 */
public class RegistryPicker<T> extends Widget {
	private static final float LIST_HEIGHT = 120, ENTRY = 13, PAD = 6;

	/** What the picker lists and how picking works: a multi-select list, a single choice, or anything else. */
	public interface Source<T> {
		/** Every entry there is (a registry), in its own order. */
		Iterable<T> all();

		/** Whether {@code t} may be listed at all (a setting's filter). */
		boolean accepts(T t);

		/** Whether {@code t} is picked now. */
		boolean contains(T t);

		/** A click on {@code t}: pick or unpick it (or, for a single choice, pick it). */
		void toggle(T t);

		/** How many are selected (any non-negative number for single choices). */
		int size();

		/** The collapsed row's text, e.g. "3 selected". */
		String label();

		/** Matched by the search box besides the name. */
		String id(T t);

		/** What {@code t} is called on screen. */
		String name(T t);

		/**
		 * Whether several can be picked (a list) rather than one (a single choice): shut, a list's row names what's picked
		 * with a count, a single choice's shows {@link #label}. True unless overridden.
		 */
		@ApiStatus.AvailableSince("2.6")
		default boolean multiple() {
			return true;
		}

		/**
		 * An icon for {@code t}: blocks and items their item, anything else {@link ItemStack#EMPTY} (none). The picker no
		 * longer draws it (its rows are checkboxes or radio dots); kept for sources that use it elsewhere.
		 */
		default ItemStack icon(T t) {
			if (t instanceof Block b && b.asItem() != Items.AIR) return new ItemStack(b.asItem());
			if (t instanceof Item i) return new ItemStack(i);
			return ItemStack.EMPTY;
		}
	}

	private final Source<T> setting;
	private final TextField search;
	private boolean expanded;
	private String query = "";
	private List<T> filtered = new ArrayList<>();
	private float scroll;
	private int lastSize = -1;
	/** What's picked, named, for the shut row; worked out again when the count changes, or now and then. */
	private String summary = "";
	private int summarySize = -1, summaryAge;
	/** Where the shut row's "Clear" was drawn (x, width), or width 0 for none. */
	private float clearX, clearW;

	/** A multi-select over {@code setting}'s registry, picking into the setting; the row says how many are picked. */
	public RegistryPicker(RegistryListSetting<T> setting) {
		this(new Source<>() {
			@Override
			public Iterable<T> all() {
				return setting.registry();
			}

			@Override
			public boolean accepts(T t) {
				return setting.accepts(t);
			}

			@Override
			public boolean contains(T t) {
				return setting.contains(t);
			}

			@Override
			public void toggle(T t) {
				setting.toggle(t);
			}

			@Override
			public int size() {
				return setting.get().size();
			}

			@Override
			public String label() {
				return setting.get().size() + " selected";
			}

			@Override
			public String id(T t) {
				return String.valueOf(setting.registry().getKey(t));
			}

			@Override
			public String name(T t) {
				return registryName(setting.registry(), t);
			}
		});
	}

	/** One entry of a registry (a single item or block setting): picking an entry selects it. */
	public static <T> RegistryPicker<T> single(RegistrySetting<T> setting) {
		return new RegistryPicker<>(new Source<>() {
			@Override
			public Iterable<T> all() {
				return setting.registry();
			}

			@Override
			public boolean accepts(T t) {
				return setting.accepts(t);
			}

			@Override
			public boolean contains(T t) {
				return setting.get() == t;
			}

			@Override
			public void toggle(T t) {
				setting.set(t);
			}

			@Override
			public int size() {
				return 1;
			}

			@Override
			public boolean multiple() {
				return false;
			}

			@Override
			public String label() {
				return registryName(setting.registry(), setting.get());
			}

			@Override
			public String id(T t) {
				return String.valueOf(setting.registry().getKey(t));
			}

			@Override
			public String name(T t) {
				return registryName(setting.registry(), t);
			}
		});
	}

	/** A picker over any {@link Source}. */
	public RegistryPicker(Source<T> setting) {
		this.setting = setting;
		this.search = new TextField(() -> query).search().placeholder("Search…").onChange(s -> {
			query = s;
			refilter();
		});
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		search.attach(root, this);
	}

	private void refilter() {
		String q = query.toLowerCase(Locale.ROOT).trim();
		List<T> sel = new ArrayList<>(), rest = new ArrayList<>();
		for (T t : setting.all()) {
			if (!setting.accepts(t)) continue;
			if (!q.isEmpty() && !setting.name(t).toLowerCase(Locale.ROOT).contains(q) && !setting.id(t).contains(q)) continue;
			(setting.contains(t) ? sel : rest).add(t);
		}
		sel.addAll(rest);
		filtered = sel;
		scroll = 0;
		lastSize = setting.size();
	}

	/** A registry entry's display name (blocks, items, entities and effects), falling back to its id. */
	public static <T> String registryName(Registry<T> registry, T t) {
		if (t instanceof Block b) return b.getName().getString();
		if (t instanceof Item i) return i.getName(i.getDefaultInstance()).getString();
		if (t instanceof EntityType<?> e) return e.getDescription().getString();
		if (t instanceof MobEffect s) return s.getDisplayName().getString();
		Identifier id = registry.getKey(t);
		return id == null ? String.valueOf(t) : id.getPath();
	}

	@Override
	public float minWidth(Canvas canvas) {
		return 44;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		if (!expanded) return ROW;
		// Inset, inside the field's outline.
		search.layout(canvas, x + 3, y + ROW + 3, width - 6);
		return ROW + 3 + ROW + 3 + LIST_HEIGHT;
	}

	/** The names of what's picked, in the registry's order, for the shut row; "None" for nothing. */
	private String summary() {
		int size = setting.size();
		if (size != summarySize || ++summaryAge > 40) {
			StringBuilder b = new StringBuilder();
			int n = 0;
			if (size > 0) for (T t : setting.all()) {
				if (!setting.contains(t)) continue;
				if (n++ > 0) b.append(", ");
				b.append(setting.name(t));
				if (n >= 12) break;
			}
			// Picked but not listed (a module of an addon that isn't loaded): said, not hidden.
			int missing = size - Math.min(n, size);
			if (n > 0 && missing > 0 && n < 12) b.append(" +").append(missing).append(" not loaded");
			summary = n == 0 ? size > 0 ? size + " not loaded" : "None" : b.toString();
			summarySize = size;
			summaryAge = 0;
		}
		return summary;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		Dropdown.field(c, x, y, width, height, ROW, hover, expanded, expanded ? 1 : 0);
		float ty = y + (ROW - c.textHeight()) / 2;
		float right = x + width - 16;
		clearW = 0;
		if (setting.multiple()) {
			int size = setting.size();
			if (size > 0) {
				// The count as a pill, and while open a way to unpick them all.
				String count = String.valueOf(size);
				float pw = c.textWidth(count) + 7;
				right -= pw;
				c.roundRect(right, y + 3, pw, ROW - 6, (ROW - 6) / 2, ColorUtil.withAlpha(theme().accent.argb(), 60));
				c.text(count, right + 3.5f, ty, theme().accent.argb());
				right -= 4;
				if (expanded) {
					String clear = "Clear";
					clearW = c.textWidth(clear) + 6;
					clearX = right - clearW;
					boolean ch = mx >= clearX && mx < clearX + clearW && my >= y && my < y + ROW;
					c.text(clear, clearX + 3, ty, ch ? theme().red.argb() : theme().textDim.argb());
					right = clearX - 2;
				}
			}
			String text = summary();
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), text, right - x - PAD), x + PAD, ty, size > 0 ? theme().text.argb() : theme().textDim.argb());
		} else {
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), setting.label(), right - x - PAD), x + PAD, ty, theme().text.argb());
		}
		Dropdown.chevron(c, x, y, width, ROW, expanded);
		if (!expanded) return;
		if (lastSize < 0) refilter();
		search.render(c, mx, my);
		float ly = y + ROW * 2 + 6;
		c.push();
		c.clip(x, ly, width, LIST_HEIGHT);
		int first = (int) (scroll / ENTRY);
		int count = (int) (LIST_HEIGHT / ENTRY) + 2;
		float box = 8;
		for (int i = first; i < Math.min(filtered.size(), first + count); i++) {
			T t = filtered.get(i);
			float ey = ly + i * ENTRY - scroll;
			boolean sel = setting.contains(t);
			boolean h = mx >= x && mx < x + width && my >= ey && my < ey + ENTRY && my >= ly && my < ly + LIST_HEIGHT;
			if (h) c.roundRect(x + 2, ey, width - 4, ENTRY, 3, theme().surfaceHover.argb());
			// A tick box (a dot for a single choice), then the name.
			float bx = x + PAD, by = ey + (ENTRY - box) / 2;
			if (setting.multiple()) {
				if (sel) {
					c.roundRect(bx, by, box, box, 2, theme().accent.argb());
				} else {
					c.outline(bx, by, box, box, 2, 1, ColorUtil.withAlpha(theme().textDim.argb(), h ? 200 : 120));
				}
			} else {
				c.outline(bx, by, box, box, box / 2, 1, ColorUtil.withAlpha(sel ? theme().accent.argb() : theme().textDim.argb(), sel ? 255 : 120));
				if (sel) c.circle(bx + box / 2, by + box / 2, 2, theme().accent.argb());
			}
			// (No item icons: items don't draw inside UI windows yet, and a column for them stood empty.)
			float tx = bx + box + 5;
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), setting.name(t), x + width - tx - PAD), tx, ey + (ENTRY - c.textHeight()) / 2,
				sel ? theme().text.argb() : h ? theme().text.argb() : theme().textDim.argb());
		}
		c.pop();
		// A thin bar says where in a long list you are.
		float total = filtered.size() * ENTRY;
		if (total > LIST_HEIGHT) {
			float bh = Math.max(12, LIST_HEIGHT * LIST_HEIGHT / total);
			float by = ly + (LIST_HEIGHT - bh) * (scroll / (total - LIST_HEIGHT));
			c.roundRect(x + width - 4, by, 2, bh, 1, ColorUtil.withAlpha(theme().textDim.argb(), 110));
		}
		if (filtered.isEmpty()) c.text("Nothing matches", x + PAD, ly + 4, theme().textDim.argb());
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
			if (clearW > 0 && mx >= clearX && mx < clearX + clearW) {
				// Unpick them all (the list keeps its order till it's searched again).
				List<T> picked = new ArrayList<>();
				for (T t : setting.all()) if (setting.contains(t)) picked.add(t);
				for (T t : picked) setting.toggle(t);
				return true;
			}
			expanded = !expanded;
			if (expanded) refilter();
			return true;
		}
		if (search.mouseClicked(mx, my, button)) return true;
		float ly = y + ROW * 2 + 6;
		if (my >= ly && my < ly + LIST_HEIGHT) {
			int i = (int) ((my - ly + scroll) / ENTRY);
			if (i >= 0 && i < filtered.size()) setting.toggle(filtered.get(i));
		}
		return true;
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		expanded = !expanded;
		if (expanded) {
			refilter();
			search.requestFocus();
		}
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		if (!expanded) return false;
		float max = Math.max(0, filtered.size() * ENTRY - LIST_HEIGHT);
		float next = Math.clamp(scroll - amount * ENTRY * 3, 0, max);
		if (next == scroll) return false;
		scroll = next;
		return true;
	}
}
