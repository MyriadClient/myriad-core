package dev.myriadclient.impl.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.system.MemoryStack;

import java.util.List;

/**
 * Addons' fills' own options ({@link HighlightStyle.Fill#options}) for their shaders: a small buffer per fill of
 * {@link HighlightStyle.Fill#MAX_OPTIONS} vec4s, written from the settings each time the fill's pass runs.
 */
final class FillOptions {
	private static final int SIZE = HighlightStyle.Fill.MAX_OPTIONS * 16;
	private static final Int2ObjectOpenHashMap<GpuBuffer> BUFFERS = new Int2ObjectOpenHashMap<>();

	private FillOptions() {
	}

	/** The options of fill {@code shaderId}, written to its buffer now. */
	static GpuBuffer buffer(CommandEncoder encoder, int shaderId) {
		GpuBuffer buffer = BUFFERS.computeIfAbsent(shaderId,
			id -> RenderSystem.getDevice().createBuffer(() -> "Myriad fill options " + id, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SIZE));
		SettingGroup group = null;
		for (HighlightStyle.Fill f : HighlightStyle.Fill.all()) {
			if (f.shaderId() == shaderId) group = f.options();
		}
		List<Setting<?>> settings = group == null ? List.of() : group.settings();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder out = Std140Builder.onStack(stack, SIZE);
			for (int i = 0; i < HighlightStyle.Fill.MAX_OPTIONS; i++) {
				float[] v = i < settings.size() ? value(settings.get(i)) : new float[4];
				out.putVec4(v[0], v[1], v[2], v[3]);
			}
			encoder.writeToBuffer(buffer.slice(), out.get());
		}
		return buffer;
	}

	/** A setting as the shader reads it: a number in x, a switch as 1 or 0, a colour as RGBA, a choice as its index. */
	private static float[] value(Setting<?> s) {
		if (s instanceof ColorSetting c) {
			int argb = c.argb();
			return new float[]{(argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f};
		}
		Object v = s.get();
		float x = switch (v) {
			case Boolean b -> b ? 1 : 0;
			case Number n -> n.floatValue();
			case Enum<?> e -> e.ordinal();
			default -> s instanceof ChoiceSetting c ? Math.max(0, c.options().indexOf(c.get())) : 0;
		};
		return new float[]{x, 0, 0, 0};
	}
}
