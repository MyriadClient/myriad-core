package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A whole number, set with a slider or typed into a box ({@link Builder#typed()}). Values are clamped to
 * {@link #min()}..{@link #max()}; the slider covers {@link #sliderMin()}..{@link #sliderMax()}, which may be narrower.
 */
public class IntSetting extends Setting<Integer> {
	private final int min, max, sliderMin, sliderMax;
	private boolean typed;

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#integer(String)}, or call this from a
	 * subclass.
	 */
	public IntSetting(String name, @Nullable String description, int defaultValue, @Nullable Supplier<Boolean> visible, int min, int max, int sliderMin, int sliderMax) {
		super(name, description, defaultValue, visible);
		this.min = min;
		this.max = max;
		this.sliderMin = sliderMin;
		this.sliderMax = sliderMax;
	}

	/** The lowest value it takes; anything lower is raised to it. */
	public int min() {
		return min;
	}

	/** The highest value it takes; anything higher is lowered to it. */
	public int max() {
		return max;
	}

	/** The slider's left end. */
	public int sliderMin() {
		return sliderMin;
	}

	/** The slider's right end. */
	public int sliderMax() {
		return sliderMax;
	}

	/** Whether it's typed into a box rather than set with a slider (see {@link Builder#typed()}). */
	public boolean typed() {
		return typed;
	}

	@Override
	protected Integer validate(@Nullable Integer v) {
		return v == null ? defaultValue : Math.clamp(v, min, max);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsInt());
	}

	@Override
	public boolean parse(String input) {
		try {
			set(Integer.parseInt(input.trim()));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Builds an {@link IntSetting}; 0 and unbounded by default. */
	public static class Builder extends Setting.Builder<IntSetting.Builder, Integer, IntSetting> {
		private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
		private @Nullable Integer sliderMin, sliderMax;
		private boolean typed;

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#integer(String)}, which adds it to
		 * the group.
		 */
		public Builder(String name) {
			super(name, 0);
		}

		/**
		 * Hard bounds, inclusive. Also used as slider bounds unless {@link #sliderRange} is set; without either, the slider
		 * runs from 0 to twice the default (at least 10).
		 */
		public IntSetting.Builder range(int min, int max) {
			this.min = min;
			this.max = max;
			return this;
		}

		/**
		 * A box to type the number in instead of a slider: for coordinates, seeds and other numbers no slider range
		 * suits.
		 */
		public IntSetting.Builder typed() {
			this.typed = true;
			return this;
		}

		/** Slider bounds; typed values may still go up to {@link #range}. */
		public IntSetting.Builder sliderRange(int min, int max) {
			this.sliderMin = min;
			this.sliderMax = max;
			return this;
		}

		@Override
		protected IntSetting create() {
			int smin = sliderMin != null ? sliderMin : (min == Integer.MIN_VALUE ? 0 : min);
			int smax = sliderMax != null ? sliderMax : (max == Integer.MAX_VALUE ? Math.max(10, defaultValue * 2) : max);
			IntSetting s = new IntSetting(name, description, defaultValue, visible, min, max, smin, smax);
			s.typed = typed;
			return s;
		}
	}
}
