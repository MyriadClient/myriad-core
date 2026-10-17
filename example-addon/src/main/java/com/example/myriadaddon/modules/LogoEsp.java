package com.example.myriadaddon.modules;

import com.example.myriadaddon.ExampleAddon;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SettingColor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Highlights living things with an outline and a fill of Myriad logos, through walls.
 *
 * <p>Shows:
 * <ul>
 *   <li>{@link HighlightEvent.Entity}: core asks about each entity as it's drawn; calling {@code highlight} outlines
 *       its exact shape (armour and held items included), with nothing to draw yourself;</li>
 *   <li>an addon's own fill: {@link ExampleAddon#LOGO_FILL}, a fragment shader in this addon's assets
 *       ({@code shaders/fill/logo_grid.fsh}) registered with {@link HighlightStyle.Fill#custom};</li>
 *   <li>a {@link HighlightStyle} built from settings: the style is immutable, so it's rebuilt only when a setting
 *       changes, not per entity.</li>
 * </ul>
 * For the standard highlight options (glow, gradient, dots...) as settings, see {@code HighlightSettings}.
 */
public final class LogoEsp extends Module {
	private final DoubleSetting range = sgGeneral.doubleSetting("Range").defaultValue(48).range(4, 128).decimals(0).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	private final DoubleSetting size = sgGeneral.doubleSetting("Logo Size").description("Grid spacing of the logos, in pixels.")
		.defaultValue(4).range(2, 12).decimals(1).onChanged(v -> rebuild()).build();
	private final DoubleSetting opacity = sgGeneral.doubleSetting("Fill Opacity").defaultValue(0.8).range(0, 1).decimals(2).onChanged(v -> rebuild()).build();

	private HighlightStyle style;

	public LogoEsp() {
		super(Categories.RENDER, "Logo ESP", "Outlines living things and fills them with Myriad logos (a custom highlight fill).");
		rebuild();
	}

	private void rebuild() {
		// The fill reads the style's dot spacing as its scale (see logo_grid.fsh).
		style = HighlightStyle.OUTLINE
			.withFill(ExampleAddon.LOGO_FILL)
			.withFillOpacity(opacity.getFloat())
			.withDots(size.getFloat(), 1)
			.withGlow(4);
	}

	@Subscribe(inGame = true)
	private void onHighlight(HighlightEvent.Entity e) {
		if (!(e.entity() instanceof LivingEntity living) || living == mc.player || living instanceof ArmorStand) return;
		if (living.distanceToSqr(mc.player) > range.get() * range.get()) return;
		e.highlight(style, color.argb());
	}
}
