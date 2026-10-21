package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The menu's group windows: what a window's args say it shows, and how layouts saved before groups (one window per
 * addon + category) find theirs.
 */
public final class MenuGroups {
	private MenuGroups() {
	}

	/** Every group with modules, in menu order. */
	public static List<ModuleGroup> all() {
		return Myriad.modules().groups();
	}

	public static List<Module> modules(ModuleGroup group) {
		return Myriad.modules().inGroup(group);
	}

	/** The args of a group window showing {@code groups}. */
	public static JsonObject args(List<ModuleGroup> groups) {
		JsonArray a = new JsonArray();
		for (ModuleGroup g : groups) a.add(g.id().toString());
		JsonObject o = new JsonObject();
		o.add("groups", a);
		return o;
	}

	/** Whether {@code args} name groups the way {@link #args} writes them (not a layout saved before groups). */
	public static boolean isCurrent(JsonObject args) {
		if (!args.has("groups")) return false;
		for (JsonElement e : args.getAsJsonArray("groups")) if (e.getAsString().indexOf('|') >= 0) return false;
		return true;
	}

	/**
	 * The groups a window's args name, skipping groups with no modules right now (an addon not installed, or not
	 * registered yet). Layouts saved before groups named an addon + category ({@code "essentials|myriad:combat"}), or
	 * a category alone; those find the group that took their place: the addon's group named like the category, or the
	 * one with most of the addon's modules with that tag.
	 */
	public static List<ModuleGroup> fromArgs(JsonObject args) {
		return fromArgs(args, Set.of());
	}

	/**
	 * Like {@link #fromArgs(JsonObject)}, with the groups other windows already show: an old window then finds the best
	 * group no other has taken.
	 */
	public static List<ModuleGroup> fromArgs(JsonObject args, Set<ModuleGroup> taken) {
		List<ModuleGroup> out = new ArrayList<>();
		if (args.has("groups")) {
			for (JsonElement e : args.getAsJsonArray("groups")) {
				String key = e.getAsString();
				int bar = key.indexOf('|');
				if (bar < 0) {
					MyriadId id = id(key);
					for (ModuleGroup g : all()) if (g.id().equals(id) && !out.contains(g)) out.add(g);
					continue;
				}
				MyriadId tag = id(key.substring(bar + 1));
				ModuleGroup g = tag == null ? null : replacing(key.substring(0, bar), tag, taken);
				if (g != null && !out.contains(g)) out.add(g);
			}
		} else if (args.has("category")) {
			MyriadId tag = id(args.get("category").getAsString());
			for (ModuleGroup g : all()) {
				if (tag != null && g.equals(replacing(g.addon(), tag, taken)) && !out.contains(g)) out.add(g);
			}
		}
		return out;
	}

	/** The group of {@code addon}'s that replaced its window for tag {@code tagId}, other than {@code taken}, or null. */
	private static ModuleGroup replacing(String addon, MyriadId tagId, Set<ModuleGroup> taken) {
		Tag tag = Myriad.tags().get(tagId).orElse(null);
		if (tag == null) return null;
		ModuleGroup best = null;
		int most = 0;
		for (ModuleGroup g : all()) {
			if (!g.addon().equals(addon) || taken.contains(g)) continue;
			if (g.name().equalsIgnoreCase(tag.name())) return g;
			int n = 0;
			for (Module m : modules(g)) if (m.hasTag(tag)) n++;
			if (n > most) {
				most = n;
				best = g;
			}
		}
		return best;
	}

	private static MyriadId id(String s) {
		try {
			return MyriadId.parse(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
