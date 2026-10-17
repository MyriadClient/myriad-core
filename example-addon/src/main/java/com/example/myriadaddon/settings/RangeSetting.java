package com.example.myriadaddon.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.setting.Setting;

import java.util.function.Supplier;

/**
 * A custom setting type: a min/max pair. Addons can add setting types by extending {@link Setting} and registering
 * a widget for the class (see {@code ExampleAddon#initialize}).
 */
public class RangeSetting extends Setting<RangeSetting.Range> {
	public record Range(double min, double max) {
	}

	private final double lower, upper;

	public RangeSetting(String name, String description, Range defaultValue, double lower, double upper) {
		super(name, description, defaultValue, (Supplier<Boolean>) null);
		this.lower = lower;
		this.upper = upper;
	}

	public double lower() {
		return lower;
	}

	public double upper() {
		return upper;
	}

	@Override
	protected Range validate(Range v) {
		if (v == null) return defaultValue;
		double a = Math.clamp(v.min(), lower, upper), b = Math.clamp(v.max(), lower, upper);
		return new Range(Math.min(a, b), Math.max(a, b));
	}

	@Override
	public JsonElement toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("min", value.min());
		o.addProperty("max", value.max());
		return o;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json.isJsonObject()) set(new Range(json.getAsJsonObject().get("min").getAsDouble(), json.getAsJsonObject().get("max").getAsDouble()));
	}

	@Override
	public boolean parse(String input) {
		String[] p = input.trim().split("[\\s,-]+");
		if (p.length != 2) return false;
		try {
			set(new Range(Double.parseDouble(p[0]), Double.parseDouble(p[1])));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public String valueString() {
		return String.format("%.1f – %.1f", value.min(), value.max());
	}
}
