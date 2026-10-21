package com.example.myriadaddon.modules;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * Digs a tunnel the way you face.
 *
 * <p>Shows:
 * <ul>
 *   <li>the planner ({@code Myriad.building()}): the module only describes the shape, a box of {@link Target#air()},
 *       and core works out what to break, in which order, with which tool, and confirms each break with the server;</li>
 *   <li>a blueprint far bigger than reach costing only what's around you;</li>
 *   <li>drawing the plan from {@link Build#steps()}, which says what's happening at each position or why it's stuck;</li>
 *   <li>no anti-cheat options at all: {@code Building.Options.forServer()} rotates and mines at vanilla speed where the
 *       server checks (the player's anti-cheat profile, detected per server), and goes fast where it doesn't.</li>
 * </ul>
 */
public final class Tunnel extends Module {
	// Public so the addon's "Digging" control panel can set them, typed (see ExampleAddon).
	public final IntSetting width = sgGeneral.intSetting("Width").defaultValue(1).range(1, 5).build();
	public final IntSetting height = sgGeneral.intSetting("Height").defaultValue(2).range(2, 4).build();
	private final IntSetting length = sgGeneral.intSetting("Length").description("Blocks ahead to dig.").defaultValue(64).range(1, 10_000).sliderRange(1, 256).build();
	private final BoolSetting showPlan = sgGeneral.bool("Show Plan").description("Outline what's left to dig and anything in the way.").defaultValue(true).build();

	private Build build;

	public Tunnel() {
		super(Tags.WORLD, "Tunnel", "Digs a tunnel the way you face.");
	}

	@Override
	protected void onEnable() {
		if (!inGame()) {
			disable();
			return;
		}
		Direction forward = mc.player.getDirection();
		Direction right = forward.getClockWise();
		BlockPos feet = mc.player.blockPosition();
		int half = (width.get() - 1) / 2;
		BlockPos a = feet.relative(forward).relative(right, -half);
		BlockPos b = feet.relative(forward, length.get()).relative(right, width.get() - 1 - half).above(height.get() - 1);

		// Grim or not, decided per server; change a preset with its with-methods (withPriority, withKeepUp, ...).
		Building.Options options = Building.Options.forServer();
		build = Myriad.building().start(this, Blueprint.box(a, b, Target.air()), options);
		build.finished().thenAccept(done -> {
			if (!done) return;
			info("Tunnel finished");
			disable();
		});
	}

	@Override
	public String hudInfo() {
		return build == null || build.remaining() < 0 ? null : String.valueOf(build.remaining());
	}

	@Subscribe
	private void onRender(Render3DEvent e) {
		if (build == null || !showPlan.get()) return;
		for (Build.Step step : build.steps()) {
			int color = switch (step.status()) {
				case DONE -> 0;
				case BREAK, WAITING, PLACE -> 0xFF89B4FA;
				case OUT_OF_RANGE -> 0x6089B4FA;
				default -> 0xFFF38BA8; // stuck: unbreakable, fluid, the block you stand on, ...
			};
			if (color != 0) Renderer3D.box(new AABB(step.pos()), color & 0x30FFFFFF, color, Renderer3D.ShapeMode.LINES, false);
		}
	}
}
