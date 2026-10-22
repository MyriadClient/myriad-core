package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.function.Supplier;

public class DoubleSetting extends Setting<Double> {
	private final double min, max, sliderMin, sliderMax;
	private final int decimals;
	private boolean typed;

	public DoubleSetting(String name, String description, double defaultValue, Supplier<Boolean> visible,
						 double min, double max, double sliderMin, double sliderMax, int decimals) {
		super(name, description, defaultValue, visible);
		this.min = min;
		this.max = max;
		this.sliderMin = sliderMin;
		this.sliderMax = sliderMax;
		this.decimals = decimals;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double sliderMin() {
		return sliderMin;
	}

	public double sliderMax() {
		return sliderMax;
	}

	public int decimals() {
		return decimals;
	}

	/** Whether it's typed into a box rather than set with a slider (see {@link Builder#typed()}). */
	public boolean typed() {
		return typed;
	}

	public float getFloat() {
		return get().floatValue();
	}

	@Override
	protected Double validate(Double v) {
		if (v == null || v.isNaN()) return defaultValue;
		double scale = Math.pow(10, decimals);
		return Math.clamp(Math.round(v * scale) / scale, min, max);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsDouble());
	}

	@Override
	public boolean parse(String input) {
		try {
			set(Double.parseDouble(input.trim()));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public String valueString() {
		// The same in every locale, so what's shown can be typed back.
		return String.format(Locale.ROOT, "%." + decimals + "f", get());
	}

	public static class Builder extends Setting.Builder<DoubleSetting.Builder, Double, DoubleSetting> {
		private double min = -Double.MAX_VALUE, max = Double.MAX_VALUE;
		private Double sliderMin, sliderMax;
		private int decimals = 2;
		private boolean typed;

		public Builder(String name) {
			super(name, 0.0);
		}

		/** Accepts int literals too, e.g. {@code defaultValue(45)}. */
		public DoubleSetting.Builder defaultValue(double value) {
			return defaultValue(Double.valueOf(value));
		}

		public DoubleSetting.Builder range(double min, double max) {
			this.min = min;
			this.max = max;
			return this;
		}

		public DoubleSetting.Builder sliderRange(double min, double max) {
			this.sliderMin = min;
			this.sliderMax = max;
			return this;
		}

		/** A box to type the number in instead of a slider: for coordinates and other numbers no slider range suits. */
		public DoubleSetting.Builder typed() {
			this.typed = true;
			return this;
		}

		public DoubleSetting.Builder decimals(int decimals) {
			this.decimals = decimals;
			return this;
		}

		@Override
		protected DoubleSetting create() {
			double smin = sliderMin != null ? sliderMin : (min == -Double.MAX_VALUE ? 0 : min);
			double smax = sliderMax != null ? sliderMax : (max == Double.MAX_VALUE ? Math.max(10, defaultValue * 2) : max);
			DoubleSetting s = new DoubleSetting(name, description, defaultValue, visible, min, max, smin, smax, decimals);
			s.typed = typed;
			return s;
		}
	}
}
