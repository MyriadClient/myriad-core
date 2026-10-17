package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Inline searchable multi-select over a vanilla registry, instead of a separate picker screen. Selected
 * entries sort first; items and blocks show their icon.
 */
public class RegistryPicker<T> extends Widget {
	private static final float LIST_HEIGHT = 120, ENTRY = 12;

	/** What the picker lists and how picking works: a multi-select list, a single choice, or anything else. */
	public interface Source<T> {
		Iterable<T> all();

		boolean accepts(T t);

		boolean contains(T t);

		void toggle(T t);

		/** How many are selected (any non-negative number for single choices). */
		int size();

		/** The collapsed row's text, e.g. "3 selected". */
		String label();

		/** Matched by the search box besides the name. */
		String id(T t);

		String name(T t);

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

	public RegistryPicker(Source<T> setting) {
		this.setting = setting;
		this.search = new TextField(() -> query).placeholder("Search…").onChange(s -> {
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
	protected float measure(Canvas canvas, float width) {
		if (!expanded) return ROW;
		search.layout(canvas, x, y + ROW + 3, width);
		return ROW + 3 + ROW + 3 + LIST_HEIGHT;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		c.roundRect(x, y, width, ROW, 4, hover ? theme().surfaceHover.argb() : theme().surface.argb());
		String label = setting.label();
		c.text(label, x + 4, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());
		c.text(expanded ? "" : "", x + width - 10, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());
		if (!expanded) return;
		if (lastSize < 0) refilter();
		search.render(c, mx, my);
		float ly = y + ROW * 2 + 6;
		c.roundRect(x, ly, width, LIST_HEIGHT, 4, ColorUtil.withAlpha(theme().surface.argb(), 60));
		c.push();
		c.clip(x, ly, width, LIST_HEIGHT);
		int first = (int) (scroll / ENTRY);
		int count = (int) (LIST_HEIGHT / ENTRY) + 2;
		for (int i = first; i < Math.min(filtered.size(), first + count); i++) {
			T t = filtered.get(i);
			float ey = ly + i * ENTRY - scroll;
			boolean sel = setting.contains(t);
			boolean h = mx >= x && mx < x + width && my >= ey && my < ey + ENTRY && my >= ly && my < ly + LIST_HEIGHT;
			if (h) c.rect(x, ey, width, ENTRY, theme().surfaceHover.argb());
			c.roundRect(x + 3, ey + 3, 6, 6, 2, sel ? theme().accent.argb() : ColorUtil.withAlpha(theme().textDim.argb(), 80));
			ItemStack stack = setting.icon(t);
			float tx = x + 13;
			if (!stack.isEmpty()) {
				c.item(stack, tx, ey + 1, 10);
				tx += 12;
			}
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize() * 0.9f, setting.name(t), width - (tx - x) - 4) , tx, ey + (ENTRY - c.textHeight()) / 2, sel ? theme().text.argb() : theme().textDim.argb());
		}
		c.pop();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
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
		scroll = Math.clamp(scroll - amount * ENTRY * 3, 0, max);
		return true;
	}
}
