package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.impl.mixin.LocalPlayerAccessor;
import dev.myriadclient.impl.network.ActionTiming;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;

/**
 * When a click may go (for {@link InventoryManager#safeToClick()} and {@link InventoryManager#prepareClick()}): Grim
 * (MultiActionsC) cancels a click while you sprint, and, depending on the version the server sees you as and the server
 * ({@link ClickRules}), while the last input packet had a movement key or jump down, or sneak. A click at the start of a
 * tick goes out before that tick's input packet, so the one that counts is the last one sent. Sprinting alone doesn't
 * stop a click from going in this tick: see {@link #prepareClick()}.
 */
final class ClickGate {
	private final Minecraft mc = Minecraft.getInstance();

	/**
	 * What the server last heard of your movement: the keys of the last input packet (vanilla only sends one when they
	 * change) and whether you're sprinting. Grim judges a click by these, not by what you press now.
	 */
	private volatile Input sentInput = Input.EMPTY;
	private volatile boolean sentSprinting;

	/** A still tick is wanted: the next input releases sprint, and what else {@link #rules()} counts. */
	private boolean stillRequested;
	/** Sprinting was stopped for a still tick; it's pressed again once you move forward. */
	private boolean resumeSprint;
	/** The player tick a still one was last asked for (see stillWanted). */
	private int stillWantedAt = Integer.MIN_VALUE / 2;
	/** A module needs your keys this tick (see keepKeys): the still tick waits. */
	private boolean keysKept;
	/** The still tick's sprint stays off until the player has moved. */
	private boolean sprintBlocked;
	/** The player tick the last block placement went out in (see beforeTravel). */
	private int placedAt = Integer.MIN_VALUE / 2;

	/** Lowest priority: what actually goes out, after other handlers have changed it. */
	@Subscribe(priority = Priority.LOWEST, packets = {ServerboundPlayerInputPacket.class, ServerboundPlayerCommandPacket.class, ServerboundUseItemOnPacket.class})
	private void onSend(PacketEvent.Send e) {
		if (e.isCancelled()) return;
		switch (e.packet()) {
			case ServerboundUseItemOnPacket p -> {
				if (mc.player != null) placedAt = mc.player.tickCount;
			}
			case ServerboundPlayerInputPacket p -> sentInput = p.input();
			case ServerboundPlayerCommandPacket p when p.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING -> sentSprinting = true;
			case ServerboundPlayerCommandPacket p when p.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING -> sentSprinting = false;
			default -> {
			}
		}
	}

	/** A respawn starts with no keys and no sprint. */
	@Subscribe(packets = {ClientboundRespawnPacket.class, ClientboundLoginPacket.class})
	private void onReceive(PacketEvent.Receive e) {
		sentInput = Input.EMPTY;
		sentSprinting = false;
	}

	boolean safeToClick() {
		if (mc.player == null) return false;
		if (!Inventory.CLICK_READY.hooked()) return coreSafeToClick();
		Input sent = sentInput;
		boolean keys = sent.forward() || sent.backward() || sent.left() || sent.right() || sent.jump();
		Boolean ready = Inventory.CLICK_READY.call(null, new Inventory.ClickMoment(sentSprinting, keys, sent.shift()), m -> coreSafeToClick());
		return ready != null ? ready : coreSafeToClick();
	}

	private boolean coreSafeToClick() {
		// Only anti-cheats that refuse clicks while you move (Grim) need a still moment.
		if (!Myriad.antiCheat().profile().clicksOnlyWhileStill()) return true;
		return !rules().refusesKeys(sentInput) && (!sentSprinting || canBracket());
	}

	/** What a click has to wait for, for the version this connection joined as and the server. */
	ClickRules rules() {
		return ClickRules.forServer(JoinedVersion.get().protocol(), Myriad.server().address());
	}

	/** Whether the keys the server last heard would get a click refused now (on a server that refuses clicks while you move). */
	boolean keysRefuse() {
		return Myriad.antiCheat().profile().clicksOnlyWhileStill() && rules().refusesKeys(sentInput);
	}

	/**
	 * Whether a click can go between a sprint stop and start in this tick: on the game thread, before this tick's
	 * movement packet. After it, the stop would reach Grim after the movement (its Post check), so the click waits for
	 * the next tick's start.
	 */
	private boolean canBracket() {
		return mc.isSameThread() && mc.getConnection() != null && !ActionTiming.get().isLate();
	}

	/**
	 * The sprint bracket: Grim takes your sprint state from the sprint packets as they arrive, so a stop sent now lets
	 * this tick's clicks through, and vanilla sends the start again just before the movement packet (the player still
	 * sprints, and it's told the server has a stop). You never slow down. True if the server has the stop.
	 */
	private boolean stopSprintForClicks() {
		mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
		((LocalPlayerAccessor) mc.player).myriad$setWasSprinting(false);
		return !sentSprinting;
	}

	/**
	 * True if a click can go now: the keys the server last heard don't refuse it, and sprinting, if the server has it,
	 * is stopped for this tick's clicks ({@link #stopSprintForClicks()}). Otherwise what a still tick needs is released
	 * for the next tick; with only sprint in the way after this tick's movement, nothing is released: the next tick's
	 * clicks get their stop.
	 */
	boolean prepareClick() {
		if (mc.player == null) return false;
		if (!Myriad.antiCheat().profile().clicksOnlyWhileStill()) return true;
		if (!rules().refusesKeys(sentInput)) {
			if (!sentSprinting) return true;
			if (canBracket()) return stopSprintForClicks();
			return false;
		}
		stillWantedAt = mc.player.tickCount;
		// Letting go of sneak on an edge, with what's left of your speed, would walk you off it: wait for it to settle.
		if (rules().sneak() && mc.player.isShiftKeyDown() && mc.player.onGround() && wouldStepOffEdge()) return false;
		stillRequested = true;
		return false;
	}

	/** Whether a still tick was asked for lately (only clicks that need one: sprint alone is got past in the tick). */
	boolean stillWanted() {
		return mc.player != null && mc.player.tickCount - stillWantedAt <= 2;
	}

	void keepKeys() {
		keysKept = true;
	}

	/** Whether this tick's motion, without sneak's edge guard, would take you off the block you stand on. */
	private boolean wouldStepOffEdge() {
		var p = mc.player;
		var v = p.getDeltaMovement();
		var box = p.getBoundingBox();
		return p.level().noCollision(p, new AABB(box.minX + 1e-7 + v.x, box.minY - p.maxUpStep() - 1e-7, box.minZ + 1e-7 + v.z,
			box.maxX - 1e-7 + v.x, box.minY, box.maxZ - 1e-7 + v.z));
	}

	/**
	 * Last, so it has the final say over what's sent: Grim takes these keys as what you were doing at the click. Only
	 * what counts for this version is let go: through ViaFabricPlus as 1.20.4, that's sprint alone.
	 */
	@Subscribe(priority = Priority.LOWEST)
	private void onInput(InputEvent e) {
		if (mc.player == null) return;
		boolean kept = keysKept;
		keysKept = false;
		// A module needs the keys this tick: the still tick comes later (asked for again meanwhile).
		if (stillRequested && !kept) {
			stillRequested = false;
			ClickRules rules = rules();
			if (rules.input()) e.forward = e.backward = e.left = e.right = e.jump = false;
			if (rules.sneak()) e.sneak = false;
			e.sprint = false;
			sprintBlocked = true;
			if (mc.player.isSprinting()) {
				mc.player.setSprinting(false);
				resumeSprint = true;
			}
		} else if (resumeSprint) {
			resumeSprint = false;
			if (e.forward) e.sprint = true;
		}
	}

	/**
	 * Just before the player moves: a still tick stays without sprint even if it started again since its input
	 * (ViaFabricPlus, joined as an older version, starts it from the held sprint key as those did), so the movement and
	 * the sprint state the server gets agree.
	 * <p>
	 * A sprint start the server doesn't have yet waits a tick after a block placement, too: Grim predicts the movement
	 * of a start sent just after a placement at walking speed, and on a diagonal (the movement fix turning forward into
	 * forward and a strafe while the look is turned) sprinting goes 0.03 further, a Simulation flag that then decays.
	 * Every such start flagged in the printer's sprinting and jumping passes, and none did once it waited the tick.
	 */
	void beforeTravel() {
		if (!sprintBlocked && !sentSprinting && mc.player.isSprinting() && mc.player.tickCount - placedAt <= 1) sprintBlocked = true;
		if (!sprintBlocked) return;
		sprintBlocked = false;
		if (mc.player.isSprinting()) {
			mc.player.setSprinting(false);
			resumeSprint = true;
		}
	}
}
