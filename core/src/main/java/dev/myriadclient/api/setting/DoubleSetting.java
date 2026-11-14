package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * A number with decimals, set with a slider or typed into a box ({@link Builder#typed()}). Values are rounded to
 * {@link #decimals()} places and clamped to {@link #min()}..{@link #max()}; NaN gives the default. The slider covers
 * {@link #sliderMin()}..{@link #sliderMax()}, which may be narrower.
 */
public class DoubleSetting extends Setting<Double> {
	private final double min, max, sliderMin, sliderMax;
	private final int decimals;
	private boolean typed;

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#decimal(String)}, or call this from a
	 * subclass.
	 */
	public DoubleSetting(String name, @Nullable String description, double defaultValue, @Nullable Supplier<Boolean> visible,
						 double min, double max, double sliderMin, double sliderMax, int decimals) {
		super(name, description, defaultValue, visible);
		this.min = min;
		this.max = max;
		this.sliderMin = sliderMin;
		this.sliderMax = sliderMax;
		this.decimals = decimals;
	}

	/** The lowest value it takes; anything lower is raised to it. */
	public double min() {
		return min;
	}

	/** The highest value it takes; anything higher is lowered to it. */
	public double max() {
		return max;
	}

	/** The slider's left end. */
	public double sliderMin() {
		return sliderMin;
	}

	/** The slider's right end. */
	public double sliderMax() {
		return sliderMax;
	}

	/** How many decimal places values are rounded to and shown with. */
	public int decimals() {
		return decimals;
	}

	/** Whether it's typed into a box rather than set with a slider (see {@link Builder#typed()}). */
	public boolean typed() {
		return typed;
	}

	/** {@link #get()} as a float. */
	public float getFloat() {
		return get().floatValue();
	}

	@Override
	protected Double validate(@Nullable Double v) {
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

	/** Builds a {@link DoubleSetting}; 0, unbounded and 2 decimals by default. */
	public static class Builder extends Setting.Builder<DoubleSetting.Builder, Double, DoubleSetting> {
		private double min = -Double.MAX_VALUE, max = Double.MAX_VALUE;
		private @Nullable Double sliderMin, sliderMax;
		private int decimals = 2;
		private boolean typed;

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#decimal(String)}, which adds it to
		 * the group.
		 */
		public Builder(String name) {
			super(name, 0.0);
		}

		/** Accepts int literals too, e.g. {@code defaultValue(45)}. */
		public DoubleSetting.Builder defaultValue(double value) {
			return defaultValue(Double.valueOf(value));
		}

		/**
		 * Hard bounds, inclusive. Also used as slider bounds unless {@link #sliderRange} is set; without either, the slider
		 * runs from 0 to twice the default (at least 10).
		 */
		public DoubleSetting.Builder range(double min, double max) {
			this.min = min;
			this.max = max;
			return this;
		}

		/** Slider bounds; typed values may still go up to {@link #range}. */
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

		/** How many decimal places to round to and show; 2 by default. */
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
