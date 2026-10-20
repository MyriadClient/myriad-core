package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.util.Packets;
import dev.myriadclient.impl.network.ActionTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Leaving a server in mid-glide with No Durability's chestplate on would have you rejoin falling in a chestplate. So
 * just before the client leaves ({@link #beforeLeaving}), the elytra goes back on and the glide is started on the
 * server, which saves both with you: you rejoin gliding. Where nothing could be sent first (the connection dropped, a
 * kick) or the start didn't take, the next world catches it: falling in the air with no glide, the elytra goes on and
 * the glide opens, as a dropped glide is caught in flight. Flights (pilots) open their own glides and are left to it.
 */
public final class LeavingGlide {
	private static final LeavingGlide INSTANCE = new LeavingGlide();
	/** Falling at least this fast (blocks a tick), with more than this far to fall, is worth a glide. */
	private static final double FALLING = -0.3, HARMLESS_FALL = 4;
	/** Ticks between tries at opening the glide (jump has to be let go in between, as Grim's ElytraB expects). */
	private static final int RETRY_TICKS = 4;

	/** You left gliding, and haven't landed or glided since. */
	private static volatile boolean leftGliding;
	private int sinceTry = RETRY_TICKS;

	private LeavingGlide() {
	}

	public static LeavingGlide get() {
		return INSTANCE;
	}

	/**
	 * Called on the render thread just before the client closes the connection itself (a module leaving, the pause
	 * menu, the server's kick being handled). Gliding with the chestplate on, the elytra goes back on: a server still
	 * gliding you keeps the glide. One that has stopped it (its stop has come in) gets the glide started again, after
	 * the held ping answers so Grim sees the stop first; a start while it glides would stop the glide instead. Sent at
	 * once even after this tick's movement: nothing will come after.
	 */
	public static void beforeLeaving() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || p.onGround() || p.isInWater() || !(p.isFallFlying() || ElytraManager.holdsGlide())) return;
		leftGliding = true;
		if (!ChestSwapper.hasGlider()) return;
		boolean stopped = GlideHolder.stopCame();
		GlideHolder.releaseAll();
		Myriad.limits().urgent(() -> {
			if (!ChestSwapper.elytraWorn() && !ChestSwapper.swap()) return;
			if (stopped) Packets.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		});
		ActionTiming.get().flushNow();
	}

	/** Called when the connection ends, however it ended: whether you were gliding then. */
	public static void left() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && !p.onGround() && !p.isInWater() && (p.isFallFlying() || ElytraManager.holdsGlide())) leftGliding = true;
	}

	/** The catch, after rejoining: before the tick's actions, so a swap goes out ahead of this tick's movement. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 5, inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (!leftGliding) return;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p.onGround() || p.isInWater() || p.isFallFlying() || p.getAbilities().flying) {
			// Landed, or gliding again (the server kept the glide, or something opened it): done.
			leftGliding = false;
			return;
		}
		sinceTry++;
		// Not falling yet (a queue holds you still), harmless from here, or a flight that opens its own glide.
		if (p.getDeltaMovement().y > FALLING || ElytraManager.piloted()) return;
		double above = p.getY() - p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
		if (above < HARMLESS_FALL || !ChestSwapper.hasGlider() || sinceTry < RETRY_TICKS || !ChestSwapper.settled()) return;
		sinceTry = 0;
		if (!ChestSwapper.elytraWorn()) {
			Myriad.limits().urgent(ChestSwapper::swap);
			return;
		}
		// Jump pressed in the air with the elytra on: vanilla opens the glide.
		Subscription[] once = new Subscription[1];
		once[0] = Myriad.events().listen(InputEvent.class, Priority.LOWEST, in -> {
			in.jump = true;
			once[0].unsubscribe();
		});
	}
}
