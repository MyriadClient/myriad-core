package com.example.myriadaddon.stats;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks this play session (since you last joined a world). It isn't a module: it should always run, so the addon
 * subscribes it to the event bus once with {@code ctx.events().subscribe(stats)}. Any object with {@code @Subscribe}
 * methods works; Myriad attributes its errors to this addon.
 */
public final class SessionStats {
	private long joinedAt = System.currentTimeMillis();
	private double distance;
	private double topSpeed;
	private int deaths;
	private Vec3 last;

	@Subscribe
	private void onJoin(WorldEvent.Join e) {
		joinedAt = System.currentTimeMillis();
		distance = topSpeed = 0;
		deaths = 0;
		last = null;
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		Vec3 pos = player.position();
		if (last != null) {
			double moved = pos.distanceTo(last);
			// Ignore teleports and respawns.
			if (moved < 10) {
				distance += moved;
				topSpeed = Math.max(topSpeed, moved * 20);
			}
		}
		last = pos;
	}

	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof DeathScreen) deaths++;
	}

	public long sessionMillis() {
		return System.currentTimeMillis() - joinedAt;
	}

	public double distance() {
		return distance;
	}

	/** Blocks per second. */
	public double topSpeed() {
		return topSpeed;
	}

	public int deaths() {
		return deaths;
	}
}
