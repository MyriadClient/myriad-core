package dev.myriadclient.impl.network;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps actions in the part of the tick where vanilla sends them: before the movement packet. Vanilla clicks, breaks,
 * places, attacks and switches slots while handling input, before the player moves; Grim (2b2t) flags any of those
 * that arrive after a tick's movement (or, without one, its tick-end) packet when it gets a reply to one of its pings
 * before the next (its "Post" check). So once a tick's movement has gone out, those packets are held and sent, in order,
 * at the start of the next tick, before anything else. The server then still has the rotation they were aimed with.
 * <p>
 * Core's own services act at the start of the tick and never wait here; this catches anything else (an addon acting at
 * the end of a tick, or in a callback). The one exception is {@link #holdRestOfTick()}, after a click whose look reaches
 * the server only in the movement packet after it.
 */
public final class ActionTiming {
	/** Runs before every other tick-start handler, so held actions go out before anything new. */
	public static final int FLUSH_PRIORITY = 1000;

	private record Held(Connection connection, Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush) {
	}

	private static final ActionTiming INSTANCE = new ActionTiming();
	private static final ThreadLocal<Boolean> FLUSHING = ThreadLocal.withInitial(() -> false);
	private final Deque<Held> held = new ArrayDeque<>();
	private volatile boolean afterMovement;
	private volatile boolean inItemUse;

	private ActionTiming() {
	}

	public static ActionTiming get() {
		return INSTANCE;
	}

	/** True while held packets are being sent, so the connection hook lets them through untouched. */
	public static boolean isFlushing() {
		return FLUSHING.get();
	}

	/** The packets Grim expects before the movement packet, and swings, which have to stay right after their attacks. */
	public static boolean isAction(Packet<?> p) {
		if (p instanceof ServerboundSwingPacket) return true;
		return p instanceof ServerboundUseItemOnPacket || p instanceof ServerboundUseItemPacket || p instanceof ServerboundAttackPacket
			|| p instanceof ServerboundInteractPacket || p instanceof ServerboundSetCarriedItemPacket || p instanceof ServerboundPlayerAbilitiesPacket
			|| p instanceof ServerboundPlayerCommandPacket || p instanceof ServerboundPlayerActionPacket;
	}

	/** Whether an action sent now is held for the next tick: this tick's movement has gone out, or something asked. */
	public boolean isLate() {
		return afterMovement;
	}

	/**
	 * Holds this tick's remaining actions for the next tick, as if the movement packet had gone out. For a placement
	 * aimed by the look in the movement packet after it: Grim (RotationPlace) decides a placement at the next movement
	 * packet with that packet's look, but a slot change before it makes Grim decide at once, with only the look the
	 * server had at the click.
	 */
	public void holdRestOfTick() {
		afterMovement = true;
	}

	/** Called around {@code MultiPlayerGameMode.useItem}: true while it runs. */
	public void usingItem(boolean using) {
		inItemUse = using;
	}

	/**
	 * Called for every packet that went out: a movement or tick-end packet closes this tick's window for actions. Not
	 * one sent during an item use: joined as 1.17 to 1.20.5, ViaFabricPlus sends your position and look just before the
	 * use packet, as those clients did, and the server and Grim take it as part of the use. Closing the window there
	 * held the use a tick, so it landed after the movement it was made before (a water bucket under a fall, after the
	 * landing).
	 */
	public void sent(Packet<?> p) {
		if (inItemUse && p instanceof ServerboundMovePlayerPacket) return;
		if (p instanceof ServerboundMovePlayerPacket || p instanceof ServerboundClientTickEndPacket) afterMovement = true;
	}

	/** Holds {@code packet} until the next tick if this tick's movement has gone out; true if it was held. */
	public boolean holdIfLate(Connection connection, Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush) {
		if (!afterMovement || !isAction(packet)) return false;
		synchronized (held) {
			held.add(new Held(connection, packet, callbacks, flush));
		}
		return true;
	}

	@Subscribe(priority = FLUSH_PRIORITY)
	private void onTickStart(TickEvent.Pre e) {
		afterMovement = false;
		flushNow();
	}

	/** Sends what's held now, in order: for the moment before leaving, when there's no next tick to wait for. */
	public void flushNow() {
		Deque<Held> out;
		synchronized (held) {
			if (held.isEmpty()) return;
			out = new ArrayDeque<>(held);
			held.clear();
		}
		FLUSHING.set(true);
		try {
			for (Held h : out) if (h.connection.isConnected()) h.connection.send(h.packet, h.callbacks, h.flush);
		} finally {
			FLUSHING.set(false);
		}
	}

	/** A new connection starts clean. */
	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		reset();
	}

	public void reset() {
		afterMovement = false;
		synchronized (held) {
			held.clear();
		}
	}
}
