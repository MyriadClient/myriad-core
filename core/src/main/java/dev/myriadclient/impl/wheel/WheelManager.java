package dev.myriadclient.impl.wheel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.impl.ui.WheelScreen;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The active profile's wheels, saved per profile in {@code wheels.json}. */
public final class WheelManager {
	private final List<WheelImpl> wheels = new CopyOnWriteArrayList<>();

	public List<WheelImpl> all() {
		return List.copyOf(wheels);
	}

	public @Nullable WheelImpl get(String id) {
		for (WheelImpl w : wheels) if (w.id.equals(id)) return w;
		return null;
	}

	public String id(WheelImpl wheel) {
		return wheel.id;
	}

	public WheelImpl create(String name) {
		int n = 1;
		while (get("wheel_" + n) != null) n++;
		WheelImpl w = new WheelImpl("wheel_" + n, name.isBlank() ? freshName() : name.strip());
		wheels.add(w);
		Myriad.config().markDirty();
		return w;
	}

	public void delete(WheelImpl wheel) {
		if (wheels.remove(wheel)) Myriad.config().markDirty();
	}

	/** A name not used yet: "Wheel 2" and so on. */
	public String freshName() {
		int n = wheels.size() + 1;
		while (byName("Wheel " + n) != null) n++;
		return "Wheel " + n;
	}

	private @Nullable WheelImpl byName(String name) {
		for (WheelImpl w : wheels) if (w.name().equalsIgnoreCase(name)) return w;
		return null;
	}

	/** Shows {@code wheel} over the game until {@code bind} is let go (nothing if a screen is already open). */
	public void show(WheelImpl wheel, Keybind bind) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.screen() != null) return;
		mc.gui.setScreen(new WheelScreen(wheel, bind));
	}

	public JsonObject save() {
		JsonObject o = new JsonObject();
		JsonArray list = new JsonArray();
		for (WheelImpl w : wheels) list.add(w.toJson());
		o.add("wheels", list);
		return o;
	}

	public void load(@Nullable JsonObject o) {
		wheels.clear();
		if (o == null || !o.has("wheels") || !o.get("wheels").isJsonArray()) return;
		for (JsonElement e : o.getAsJsonArray("wheels")) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
			JsonObject j = e.getAsJsonObject();
			WheelImpl w = new WheelImpl(j.get("id").getAsString(), "");
			w.fromJson(j);
			wheels.add(w);
		}
	}
}
