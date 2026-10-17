package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.Supplier;

public class IntSetting extends Setting<Integer> {
	private final int min, max, sliderMin, sliderMax;

	public IntSetting(String name, String description, int defaultValue, Supplier<Boolean> visible, int min, int max, int sliderMin, int sliderMax) {
		super(name, description, defaultValue, visible);
		this.min = min;
		this.max = max;
		this.sliderMin = sliderMin;
		this.sliderMax = sliderMax;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public int sliderMin() {
		return sliderMin;
	}

	public int sliderMax() {
		return sliderMax;
	}

	@Override
	protected Integer validate(Integer v) {
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

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.IntSetting.Builder, Integer, IntSetting> {
		private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
		private Integer sliderMin, sliderMax;

		public Builder(String name) {
			super(name, 0);
		}

		/** Hard bounds. Also used as slider bounds unless {@link #sliderRange} is set. */
		public dev.myriadclient.api.setting.IntSetting.Builder range(int min, int max) {
			this.min = min;
			this.max = max;
			return this;
		}

		/** Slider bounds; typed values may still go up to {@link #range}. */
		public dev.myriadclient.api.setting.IntSetting.Builder sliderRange(int min, int max) {
			this.sliderMin = min;
			this.sliderMax = max;
			return this;
		}

		@Override
		protected IntSetting create() {
			int smin = sliderMin != null ? sliderMin : (min == Integer.MIN_VALUE ? 0 : min);
			int smax = sliderMax != null ? sliderMax : (max == Integer.MAX_VALUE ? Math.max(10, defaultValue * 2) : max);
			return new IntSetting(name, description, defaultValue, visible, min, max, smin, smax);
		}
	}
}
