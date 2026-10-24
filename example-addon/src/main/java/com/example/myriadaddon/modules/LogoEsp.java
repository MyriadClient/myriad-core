package com.example.myriadaddon.modules;

import com.example.myriadaddon.ExampleAddon;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Highlights living things with an outline and a fill of Myriad logos, through walls.
 *
 * <p>Shows:
 * <ul>
 *   <li>{@link RenderEvent}: asking for an entity each frame; core draws its exact shape (armour and held items
 *       included) as a shader highlight, or its hitbox as a box, whichever the player picked in the Render window;</li>
 *   <li>an addon's own fill: {@link ExampleAddon#LOGO_FILL}, a fragment shader in this addon's assets
 *       ({@code shaders/fill/logo_grid.fsh}) registered with {@link HighlightStyle.Fill#custom}. It's listed in every
 *       module's Fill option, not just this one's;</li>
 *   <li>{@link RenderSettings} starting from that fill: the standard Render options, the pattern size being the
 *       logos' spacing.</li>
 * </ul>
 */
public final class LogoEsp extends Module {
	private final DoubleSetting range = sgGeneral.decimal("Range").defaultValue(48).range(4, 128).decimals(0).build();

	private final SettingGroup sgRender = settings.group("Render");
	private final ColorSetting color = sgRender.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	// The fill reads the pattern spacing as the logos' (see logo_grid.fsh).
	private final RenderSettings look = RenderSettings.builder(sgRender)
		.defaults(HighlightStyle.OUTLINE.withFill(ExampleAddon.LOGO_FILL).withFillOpacity(0.8f).withDots(4, 1).withGlow(4))
		.build();

	public LogoEsp() {
		super(Tags.RENDER, "Logo ESP", "Outlines living things and fills them with Myriad logos (a custom highlight fill).");
	}

	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		double r = range.get() * range.get();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || living == mc.player || living instanceof ArmorStand) continue;
			if (living.distanceToSqr(mc.player) <= r) e.entity(living, look, color.argb());
		}
	}
}
