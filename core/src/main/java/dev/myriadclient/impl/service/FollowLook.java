package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.service.Placement.Click;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.impl.network.ActionTiming;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The look {@link PlacementManager} sends in the movement packet after a solved click, and its copy of Grim's
 * RotationPlace buffer, which decides whether a click may be aimed (looking at the block only in that next packet).
 */
final class FollowLook {
	/** How far a solved click's look is sent off the one asked for, at random, in degrees (DuplicateRotPlace, as for points). */
	static final double LOOK_JITTER = 0.25;

	/**
	 * The look to send in this tick's movement packet after a solved click (NaN: none): the same look again, so a server
	 * that applies a click a packet late (Paper over a real connection, now and then) places it the same; or for an
	 * aimed click, the look onto the block Grim's RotationPlace wants.
	 */
	private float followYaw = Float.NaN, followPitch = Float.NaN, sentFollowYaw = Float.NaN, sentFollowPitch = Float.NaN;
	private boolean followAimed;
	/** An aimed click went out and the movement packet after it hasn't yet. */
	private boolean aimedInFlight;
	/**
	 * A copy of Grim's RotationPlace buffer: once an aimed click was decided without its next look on the block, it checks
	 * every placement's own look until about ten have passed, and an aimed click's own look never is (see {@link #aimedAllowed()}).
	 */
	private double rotationPlaceBuffer;

	/** Whether the look after a solved click is still to go out this tick: a break's turn now would take its place. */
	boolean pending() {
		return !Float.isNaN(followYaw);
	}

	/** Whether the look after a solved click went out this tick: it isn't to be moved. */
	boolean sent() {
		return !Float.isNaN(sentFollowYaw);
	}

	/** Whether an aimed click is waiting for the movement packet after it: no other click goes before it. */
	boolean inFlight() {
		return aimedInFlight;
	}

	/** Whether a solved click may be aimed: not while Grim would check its own look. */
	boolean aimedAllowed() {
		return rotationPlaceBuffer <= 0;
	}

	/** At the start of the tick: asks for the look after last tick's solved click, if there is one. True if it did. */
	boolean request(Object owner) {
		if (Float.isNaN(followYaw)) return false;
		Myriad.rotations().request(owner, followYaw, followPitch, Rotations.PRIORITY_HIGH, PlacementManager.MOVE_FIX, null);
		sentFollowYaw = followYaw;
		sentFollowPitch = followPitch;
		followYaw = followPitch = Float.NaN;
		return true;
	}

	/** At click time, with the look the server has: did the look after the last solved click get there? If an aimed one's didn't, Grim's buffer is up. */
	void check(float yaw, float pitch) {
		if (Float.isNaN(sentFollowYaw)) return;
		if (followAimed && !PlacementManager.near(yaw, pitch, sentFollowYaw, sentFollowPitch)) rotationPlaceBuffer = 1;
		sentFollowYaw = sentFollowPitch = Float.NaN;
	}

	/** After solved click {@code c} went out with the look it was solved with: the look for the next movement packet. */
	void after(Click c, float sendYaw, float sendPitch, CompletableFuture<Boolean> result) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		followAimed = c.aimed();
		followYaw = c.aimed() ? c.aimYaw() + (float) r.nextDouble(-LOOK_JITTER, LOOK_JITTER) : sendYaw;
		followPitch = c.aimed() ? Math.clamp(c.aimPitch() + (float) r.nextDouble(-LOOK_JITTER, LOOK_JITTER), -89.9f, 89.9f) : sendPitch;
		if (!c.aimed()) rotationPlaceBuffer = Math.max(0, rotationPlaceBuffer - 0.1);
		else {
			// Grim decides it at the next movement packet, by that packet's look, unless a slot change gets there first.
			aimedInFlight = true;
			ActionTiming.get().holdRestOfTick();
			// Refused: Grim's buffer is likely up, raised by something this copy doesn't see (another placement, against air).
			result.thenAccept(placed -> {
				if (!placed) rotationPlaceBuffer = 1;
			});
		}
	}

	/**
	 * Between an aimed click and the movement packet after it, a slot change or a reply to one of Grim's pings makes Grim
	 * decide the placement at once, with only the click's own look, which isn't on the block: its buffer is up. Slot
	 * changes are held meanwhile (see {@link ActionTiming#holdRestOfTick()}); this catches what still gets out.
	 */
	@Subscribe(priority = Priority.LOWEST)
	private void onSendAfterAimed(PacketEvent.Send e) {
		if (!aimedInFlight || e.isCancelled()) return;
		Packet<?> p = e.packet();
		if (p instanceof ServerboundMovePlayerPacket) aimedInFlight = false;
		else if (p instanceof ServerboundPongPacket || p instanceof ServerboundSetCarriedItemPacket && !ActionTiming.get().isLate()) rotationPlaceBuffer = 1;
	}

	void clear() {
		followYaw = followPitch = sentFollowYaw = sentFollowPitch = Float.NaN;
		rotationPlaceBuffer = 0;
		aimedInFlight = false;
	}
}
