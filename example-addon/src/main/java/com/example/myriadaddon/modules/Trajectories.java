package com.example.myriadaddon.modules;

import dev.myriadclient.api.combat.Trajectory;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.TickCached;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws where the item in your hand would land, and where every arrow and thrown thing in the air is going.
 *
 * <p>Shows:
 * <ul>
 *   <li>{@link Trajectory#launch} and {@link Trajectory#simulate}: the path of a bow (at its current draw), crossbow,
 *       trident, pearl, snowball, potion or wind charge, with the block or entity it hits;</li>
 *   <li>the same physics applied to projectiles already flying, from their current velocity;</li>
 *   <li>{@link TickCached}: the flying projectiles' paths are simulated once per tick, not once per frame, since a
 *       path costs about a hundred raycasts and a render handler runs far more often than the world changes.</li>
 * </ul>
 */
public final class Trajectories extends Module {
	private final BoolSetting held = sgGeneral.bool("Held Item").description("The path of the item in your hand.").defaultValue(true).build();
	private final BoolSetting flying = sgGeneral.bool("Flying Projectiles").description("Where arrows and thrown items in the air are going.").defaultValue(true).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	private final ColorSetting hitColor = sgGeneral.color("Hit Color").defaultValue(SettingColor.role(SettingColor.Mode.RED)).build();

	/** Paths of everything in flight, simulated once per tick. */
	private final TickCached<List<Trajectory.Path>> inFlight = TickCached.of(() -> {
		List<Trajectory.Path> out = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Projectile p) || p.getDeltaMovement().lengthSqr() < 1e-6) continue;
			Trajectory.Ballistics b = e instanceof AbstractArrow ? Trajectory.Ballistics.ARROW : Trajectory.Ballistics.THROWN;
			out.add(Trajectory.simulate(new Trajectory.Launch(p.position(), p.getDeltaMovement(), b), 200, p));
		}
		return out;
	});

	public Trajectories() {
		super(Categories.RENDER, "Trajectories", "Shows where projectiles will land.");
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		ShapeBuilder shapes = e.shapes();
		if (held.get()) {
			// The hand's own aim, with the pitch the launch applies (potions throw 20° higher than you look).
			Trajectory.Launch launch = Trajectory.launch(mc.player, mc.player.getMainHandItem(), mc.player.getYRot(), mc.player.getXRot());
			if (launch == null) launch = Trajectory.launch(mc.player, mc.player.getOffhandItem(), mc.player.getYRot(), mc.player.getXRot());
			if (launch != null) draw(shapes, Trajectory.simulate(launch, 300, mc.player));
		}
		if (flying.get()) for (Trajectory.Path path : inFlight.get()) draw(shapes, path);
	}

	private void draw(ShapeBuilder shapes, Trajectory.Path path) {
		int c = color.argb();
		List<Vec3> points = path.points();
		for (int i = 1; i < points.size(); i++) shapes.line(points.get(i - 1), points.get(i), c, true);
		Vec3 end = path.end();
		int hit = path.hit() != null ? hitColor.argb() : c;
		shapes.box(new AABB(end.x - 0.15, end.y - 0.15, end.z - 0.15, end.x + 0.15, end.y + 0.15, end.z + 0.15), ColorUtil.withAlpha(hit, 60), hit, Renderer3D.ShapeMode.BOTH, true);
	}
}
