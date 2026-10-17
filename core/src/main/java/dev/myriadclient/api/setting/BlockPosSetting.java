package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/** A block position: an anchor, a schematic origin, a home. The editor also offers "set to where I'm standing". */
public class BlockPosSetting extends Setting<BlockPos> {
	public BlockPosSetting(String name, String description, BlockPos defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	@Override
	protected BlockPos validate(BlockPos v) {
		return v == null ? defaultValue : v.immutable();
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		a.add(value.getX());
		a.add(value.getY());
		a.add(value.getZ());
		return a;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonArray() && json.getAsJsonArray().size() == 3) {
			JsonArray a = json.getAsJsonArray();
			set(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
		}
	}

	/** "x y z" (commas also work). */
	@Override
	public boolean parse(String input) {
		String[] p = input.trim().split("[\\s,]+");
		if (p.length != 3) return false;
		try {
			set(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public String valueString() {
		return value.getX() + " " + value.getY() + " " + value.getZ();
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.BlockPosSetting.Builder, BlockPos, BlockPosSetting> {
		public Builder(String name) {
			super(name, BlockPos.ZERO);
		}

		@Override
		protected BlockPosSetting create() {
			return new BlockPosSetting(name, description, defaultValue, visible);
		}
	}
}
