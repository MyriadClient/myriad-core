package dev.myriadclient.impl.wheel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.IconSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.setting.StringSetting;
import dev.myriadclient.api.ui.Icons;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A wheel: its name, bind and icon (as settings, edited like any other), and its slots in order, clockwise from the top. */
public final class WheelImpl {
	final String id;
	public final Settings settings = new Settings();
	private final SettingGroup sg = settings.group("Wheel");
	public final StringSetting name = sg.string("Name").build();
	public final KeybindSetting keybind = sg.keybind("Keybind").description("Hold it in game to show the wheel; let go over a slot to run it.").build();
	public final IconSetting icon = sg.icon("Icon").description("Its icon: in the middle of the wheel, on its window and in the launcher.").build();
	private final List<WheelSlot> slots = new CopyOnWriteArrayList<>();

	WheelImpl(String id, String name) {
		this.id = id;
		this.name.set(name);
	}

	public String name() {
		return name.get().isBlank() ? "Wheel" : name.get();
	}

	/** Its icon, or the wheel glyph without one. */
	public String icon() {
		return icon.getOr(Icons.DOT_CIRCLE);
	}

	public List<WheelSlot> slots() {
		return List.copyOf(slots);
	}

	/** Adds {@code slot} last, unless something on the wheel already runs the same. */
	public void add(WheelSlot slot) {
		if (slots.stream().anyMatch(slot::sameTarget)) return;
		slots.add(slot);
		Myriad.config().markDirty();
	}

	/** Puts {@code with} where {@code slot} is (the same slot with a new icon or name). */
	public void replace(WheelSlot slot, WheelSlot with) {
		int i = slots.indexOf(slot);
		if (i < 0) return;
		slots.set(i, with);
		Myriad.config().markDirty();
	}

	public void remove(WheelSlot slot) {
		if (slots.remove(slot)) Myriad.config().markDirty();
	}

	/** Moves {@code slot} one place round the wheel ({@code by} -1 is anticlockwise). */
	public void move(WheelSlot slot, int by) {
		int i = slots.indexOf(slot), j = i + by;
		if (i < 0 || j < 0 || j >= slots.size()) return;
		slots.set(i, slots.get(j));
		slots.set(j, slot);
		Myriad.config().markDirty();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.add("settings", settings.toJson());
		JsonArray list = new JsonArray();
		for (WheelSlot s : slots) list.add(s.toJson());
		o.add("slots", list);
		return o;
	}

	void fromJson(JsonObject o) {
		if (o.has("settings") && o.get("settings").isJsonObject()) settings.fromJson(o.getAsJsonObject("settings"));
		slots.clear();
		if (o.has("slots") && o.get("slots").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("slots")) {
				WheelSlot s = e.isJsonObject() ? WheelSlot.fromJson(e.getAsJsonObject()) : null;
				if (s != null) slots.add(s);
			}
		}
	}
}
