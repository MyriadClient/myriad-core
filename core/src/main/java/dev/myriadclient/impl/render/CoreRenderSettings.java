package dev.myriadclient.impl.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.util.Keybind;

/** Core's Render settings, saved with the profile: the render mode every module draws in. */
public final class CoreRenderSettings {
	private static AddonSettings settings;
	private static EnumSetting<RenderMode> mode;

	private CoreRenderSettings() {
	}

	public static void register(AddonContext ctx) {
		settings = ctx.settings("Render");
		mode = settings.group("General").oneOf("Mode", RenderMode.SHADER)
			.description("Shader: outlines, glow and fills traced around the exact shape of things. Box: boxes of lines and faces, lighter on a weak GPU. Every module draws this way.")
			.build();
		ctx.registerKeyAction("Switch Render Mode", Keybind.NONE, () -> {
			set(mode() == RenderMode.SHADER ? RenderMode.BOX : RenderMode.SHADER);
			Myriad.notifications().toast("Render", (mode() == RenderMode.SHADER ? "Shader" : "Box") + " mode").replacing("render-mode").show();
		});
	}

	public static RenderMode mode() {
		return mode == null ? RenderMode.SHADER : mode.get();
	}

	public static void set(RenderMode value) {
		mode.edit(value);
		Myriad.config().markDirty();
	}

	public static AddonSettings settings() {
		return settings;
	}
}
