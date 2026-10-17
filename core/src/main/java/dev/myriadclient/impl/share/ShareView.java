package dev.myriadclient.impl.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.BlockPosSetting;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntListSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.ModuleListSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.StringListSetting;
import dev.myriadclient.api.ui.ThemeSettings;

/**
 * How things looked in the menu of whoever shared them, for the website to draw without knowing the addons: a
 * module's name, category (with its icon and colour) and its settings as rows (name, kind, shown value). Values come
 * from the player's own settings; protected ones say only that they're there.
 */
final class ShareView {
	private ShareView() {
	}

	static JsonObject module(Module m, boolean binds) {
		JsonObject v = new JsonObject();
		v.addProperty("name", m.name());
		v.addProperty("description", m.description());
		String addon = m.id().namespace();
		v.addProperty("addon", addon);
		v.addProperty("addonName", Myriad.addons().stream().filter(a -> a.id().equals(addon)).map(Addon::name).findFirst().orElse(addon));
		JsonObject category = new JsonObject();
		category.addProperty("name", m.category().name());
		category.addProperty("icon", m.category().icon());
		category.addProperty("color", hex(m.category().color()));
		v.add("category", category);
		JsonArray groups = new JsonArray();
		for (SettingGroup g : m.settings.groups()) {
			if (!binds && ShareManager.isModuleGroup(g)) continue;
			JsonArray rows = new JsonArray();
			for (Setting<?> s : g.settings()) {
				if (s instanceof ActionSetting || !visible(s)) continue;
				rows.add(setting(s));
			}
			if (rows.isEmpty()) continue;
			JsonObject group = new JsonObject();
			group.addProperty("name", g.name());
			group.add("settings", rows);
			groups.add(group);
		}
		v.add("groups", groups);
		return v;
	}

	private static boolean visible(Setting<?> s) {
		try {
			return s.isVisible();
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** One row: {name, description, kind, shown, (value | protected), default, min/max, options, color}. */
	static JsonObject setting(Setting<?> s) {
		JsonObject r = new JsonObject();
		r.addProperty("name", s.name());
		if (!s.description().isEmpty()) r.addProperty("description", s.description());
		r.addProperty("kind", kind(s));
		if (s.isProtected()) {
			r.addProperty("protected", true);
			return r;
		}
		r.addProperty("default", s.isDefault());
		r.addProperty("shown", shown(s));
		switch (s) {
			case BoolSetting b -> r.addProperty("value", b.playerValue());
			case IntSetting i -> {
				r.addProperty("value", i.playerValue());
				r.addProperty("min", i.min());
				r.addProperty("max", i.max());
			}
			case DoubleSetting d -> {
				r.addProperty("value", d.playerValue());
				r.addProperty("min", d.min());
				r.addProperty("max", d.max());
			}
			case ColorSetting c -> r.addProperty("color", hex(c.playerValue().argb()));
			case EnumSetting<?> e -> {
				r.addProperty("shown", pretty(e.playerValue().name()));
				JsonArray options = new JsonArray();
				for (Enum<?> o : e.values()) options.add(pretty(o.name()));
				r.add("options", options);
			}
			case ChoiceSetting c -> {
				JsonArray options = new JsonArray();
				for (String o : c.options()) options.add(o);
				r.add("options", options);
			}
			default -> {
			}
		}
		return r;
	}

	private static String kind(Setting<?> s) {
		return switch (s) {
			case BoolSetting b -> "toggle";
			case IntSetting i -> "number";
			case DoubleSetting d -> "number";
			case ColorSetting c -> "color";
			case EnumSetting<?> e -> "choice";
			case ChoiceSetting c -> "choice";
			case KeybindSetting k -> "keybind";
			case BlockPosSetting p -> "position";
			case StringListSetting l -> "list";
			case IntListSetting l -> "list";
			case ModuleListSetting l -> "list";
			case RegistryListSetting<?> l -> "list";
			case EnumSetSetting<?> l -> "list";
			default -> "text";
		};
	}

	/** The value as the menu shows it (the player's own, not one a control panel lays over it). */
	private static String shown(Setting<?> s) {
		try {
			return s.isOverridden() ? display(s) : s.valueString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static <T> String display(Setting<T> s) {
		return String.valueOf(s.playerValue());
	}

	/** The theme's main colours and shape, for drawing the share in the sharer's look. */
	static JsonObject theme(ThemeSettings t) {
		JsonObject v = new JsonObject();
		v.addProperty("accent", hex(t.accent.argb()));
		v.addProperty("secondary", hex(t.secondary.argb()));
		v.addProperty("window", hex(t.windowBackground.argb()));
		v.addProperty("title", hex(t.titleBackground.argb()));
		v.addProperty("surface", hex(t.surface.argb()));
		v.addProperty("text", hex(t.text.argb()));
		v.addProperty("textDim", hex(t.textDim.argb()));
		v.addProperty("border", hex(t.inactiveBorder.argb()));
		v.addProperty("rounding", t.rounding.get());
		return v;
	}

	/** {@code #RRGGBBAA}, as CSS reads it. */
	static String hex(int argb) {
		return String.format("#%06X%02X", argb & 0xFFFFFF, argb >>> 24);
	}

	/** {@code BLAST_LEGS} → {@code Blast Legs}. */
	private static String pretty(String name) {
		StringBuilder b = new StringBuilder();
		for (String w : name.toLowerCase().split("_")) {
			if (w.isEmpty()) continue;
			if (!b.isEmpty()) b.append(' ');
			b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return b.toString();
	}
}
