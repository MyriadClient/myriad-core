package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.KeybindButton;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.DesktopBinds;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.wheel.WheelImpl;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Every binding in one place, to set them all from here: Global (Minecraft's own controls, other mods' included, saved
 * to its options), Myriad (its actions, the menu's shortcuts, core's modules and the wheels) and one section per addon
 * (its actions and modules). The search at the top filters by name; a section's name shows all of it, and a key's name
 * shows what's bound to it.
 */
public final class KeybindsPanel extends WidgetPanel {
	private final WindowManager wm;
	private String query = "";
	/** The sections opened, by path; while searching, every section with a match is open. */
	private final Set<String> open = new HashSet<>();

	public KeybindsPanel(WindowManager wm) {
		this.wm = wm;
	}

	@Override
	public String title() {
		return "Keybinds";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	protected void build(VBox content) {
		content.add(new TextField(() -> query).placeholder("Search binds or keys…").onChange(s -> query = s.trim().toLowerCase(Locale.ROOT)));
		content.add(global().widget());
		content.add(myriad().widget());
		for (Addon addon : Myriad.addons()) {
			if (addon.isCore()) continue;
			Section s = new Section(null, AddonNames.of(addon.id()));
			actions(s, addon.id());
			modules(s, addon.id());
			if (!s.isEmpty()) content.add(s.widget());
		}
	}

	/** Minecraft's controls, by its categories (Myriad's own two, shown under Myriad, left out). */
	private Section global() {
		Section global = new Section(null, "Global");
		Map<KeyMapping.Category, Section> categories = new LinkedHashMap<>();
		for (KeyMapping k : mc.options.keyMappings) {
			if (k.getCategory().id().getNamespace().equals(Myriad.MOD_ID)) continue;
			Section c = categories.computeIfAbsent(k.getCategory(), cat -> global.child(cat.label().getString()));
			c.row(Component.translatable(k.getName()).getString(), () -> bindOf(k), b -> {
				// Minecraft's binds hold one key: modifiers are dropped.
				k.setKey(!b.isSet() ? InputConstants.UNKNOWN : (b.mouse() ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM).getOrCreate(b.code()));
				KeyMapping.resetMapping();
				mc.options.save();
			});
		}
		return global;
	}

	private static Keybind bindOf(KeyMapping k) {
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(k);
		if (key.equals(InputConstants.UNKNOWN)) return Keybind.NONE;
		return switch (key.getType()) {
			case MOUSE -> Keybind.mouse(key.getValue());
			case KEYSYM -> Keybind.key(key.getValue());
			case SCANCODE -> Keybind.NONE;
		};
	}

	private Section myriad() {
		Section myriad = new Section(null, "Myriad");
		actions(myriad, Myriad.MOD_ID);
		Section menu = myriad.child("Menu");
		menu.body.add(new Label("Pressed together with the mod key. Workspaces 1–9, the HUD and the launcher also work in game, without the menu open.").dim());
		// The mod key and vim keys: binds, so here rather than among the theme's preferences (where they're kept).
		for (Setting<?> s : List.of(wm.theme().modKey, wm.theme().vimKeys)) {
			Widget row = Myriad.ui().settingWidgets().create(s);
			if (row != null) menu.body.add(row);
		}
		for (DesktopBinds.Bind b : wm.binds().values()) {
			menu.row(b.name, () -> b.bind, k -> {
				b.bind = k;
				Myriad.config().markDirty();
			});
		}
		modules(myriad, Myriad.MOD_ID);
		List<WheelImpl> wheels = MyriadImpl.get().wheelManager().all();
		if (!wheels.isEmpty()) {
			Section list = myriad.child("Wheels");
			for (WheelImpl w : wheels) list.row(w.name(), w.keybind::get, w.keybind::set);
		}
		return myriad;
	}

	private void actions(Section into, String addon) {
		Section actions = null;
		for (KeyAction a : Myriad.keyActions()) {
			if (!addon.equals(Myriad.keyActions().ownerOf(a))) continue;
			if (actions == null) actions = into.child("Actions");
			actions.row(a.name(), a::bind, b -> {
				a.setBind(b);
				Myriad.config().markDirty();
			});
		}
	}

	private void modules(Section into, String addon) {
		Section modules = null;
		for (Module m : Myriad.modules()) {
			if (!addon.equals(Myriad.modules().ownerOf(m))) continue;
			if (modules == null) modules = into.child("Modules");
			modules.row(m.name(), m.keybind::get, m.keybind::set);
		}
	}

	@Override
	public void save(JsonObject out) {
		JsonArray a = new JsonArray();
		open.forEach(a::add);
		out.add("open", a);
	}

	@Override
	public void load(JsonObject in) {
		open.clear();
		if (in.has("open")) for (JsonElement e : in.getAsJsonArray("open")) open.add(e.getAsString());
	}

	/**
	 * A titled, folding group of binds: hidden while a search matches nothing in it, open while it matches something.
	 * Its hint counts the binds in it.
	 */
	private final class Section {
		final Section parent;
		final String title, path;
		final VBox body = new VBox(2, 0);
		final List<Supplier<Boolean>> shown = new ArrayList<>();
		int count;

		Section(Section parent, String title) {
			this.parent = parent;
			this.title = title;
			this.path = parent == null ? title : parent.path + "/" + title;
		}

		/** A section inside this one, open until it's folded. */
		Section child(String title) {
			Section c = new Section(this, title);
			if (!open.contains(c.path + "!")) open.add(c.path);
			body.add(c.widget());
			shown.add(c::anyShown);
			return c;
		}

		void row(String name, Supplier<Keybind> get, Consumer<Keybind> set) {
			String lower = name.toLowerCase(Locale.ROOT);
			Supplier<Boolean> visible = () -> query.isEmpty() || titleMatches() || lower.contains(query)
				|| get.get().displayName().toLowerCase(Locale.ROOT).equals(query);
			HBox row = new HBox(4);
			row.addWeighted(new Label(name), 1);
			row.addFixed(new KeybindButton(get, set), 90);
			row.visible(visible);
			body.add(row);
			shown.add(visible);
			for (Section s = this; s != null; s = s.parent) s.count++;
		}

		/** A search for a section ("essentials", "movement") shows all of it; from three letters, so "a" isn't everything. */
		boolean titleMatches() {
			if (query.length() < 3) return false;
			for (Section s = this; s != null; s = s.parent) if (s.title.toLowerCase(Locale.ROOT).startsWith(query)) return true;
			return false;
		}

		boolean anyShown() {
			for (Supplier<Boolean> s : shown) if (s.get()) return true;
			return false;
		}

		boolean isEmpty() {
			return shown.isEmpty();
		}

		Collapsible widget() {
			// Sub-sections start open (folding one is remembered as "path!"); top-level ones start folded.
			Collapsible c = new Collapsible(() -> title, body, () -> !query.isEmpty() || open.contains(path), v -> {
				if (v) {
					open.add(path);
					open.remove(path + "!");
				} else {
					open.remove(path);
					if (parent != null) open.add(path + "!");
				}
			}).hint(() -> String.valueOf(count)).indent(8);
			c.visible(() -> query.isEmpty() || anyShown());
			return c;
		}
	}
}
