package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A list of whole numbers: hotbar slots, entity ids, y levels. Edited and typed as a comma-separated list
 * ({@code 1, 2, 9}); {@code clear} empties it.
 */
public class IntListSetting extends Setting<List<Integer>> {
	private final int min, max;

	public IntListSetting(String name, String description, List<Integer> defaultValue, Supplier<Boolean> visible, int min, int max) {
		super(name, description, defaultValue, visible);
		this.min = min;
		this.max = max;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public boolean contains(int v) {
		return value.contains(v);
	}

	@Override
	protected List<Integer> copy(List<Integer> v) {
		return v == null ? new ArrayList<>() : new ArrayList<>(v);
	}

	@Override
	protected List<Integer> validate(List<Integer> v) {
		List<Integer> out = new ArrayList<>();
		if (v != null) for (Integer i : v) if (i != null && !out.contains(i)) out.add(Math.clamp(i, min, max));
		return out;
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		value.forEach(a::add);
		return a;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json == null || !json.isJsonArray()) return;
		List<Integer> list = new ArrayList<>();
		for (JsonElement e : json.getAsJsonArray()) if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) list.add(e.getAsInt());
		set(list);
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		if (in.isEmpty() || in.equalsIgnoreCase("clear") || in.equalsIgnoreCase("none")) {
			set(new ArrayList<>());
			return true;
		}
		List<Integer> list = new ArrayList<>();
		for (String part : in.split("[,\\s]+")) {
			if (part.isBlank()) continue;
			try {
				list.add(Integer.parseInt(part.trim()));
			} catch (NumberFormatException e) {
				return false;
			}
		}
		set(list);
		return true;
	}

	@Override
	public String valueString() {
		if (value.isEmpty()) return "none";
		StringBuilder sb = new StringBuilder();
		for (int i : value) sb.append(sb.isEmpty() ? "" : ", ").append(i);
		return sb.toString();
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.IntListSetting.Builder, List<Integer>, IntListSetting> {
		private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;

		public Builder(String name) {
			super(name, new ArrayList<>());
		}

		public Builder defaultValue(int... values) {
			List<Integer> list = new ArrayList<>(values.length);
			for (int v : values) list.add(v);
			this.defaultValue = list;
			return this;
		}

		/** Values outside are clamped. */
		public Builder range(int min, int max) {
			this.min = min;
			this.max = max;
			return this;
		}

		@Override
		protected IntListSetting create() {
			return new IntListSetting(name, description, defaultValue, visible, min, max);
		}
	}
}
