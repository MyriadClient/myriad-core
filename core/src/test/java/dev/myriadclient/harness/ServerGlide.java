package dev.myriadclient.harness;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's side of the player's glide, as vanilla's server runs it, for tests of what the client's flight does
 * to it. Packets are taken in the order sent, then the server's tick: a glide start while not gliding starts one (if
 * the player could glide), one while gliding stops it (vanilla's answer to a start it can't make); a tick on which the
 * player can't glide (on the ground, no usable elytra worn) stops it; and every 20th tick of one unbroken glide wears
 * the elytra by one. The chest slot is the server's own copy ({@link ServerInventory}); effects are the client's.
 */
public final class ServerGlide {
	private final ServerInventory inventory;
	private boolean gliding, onGround;
	/** The flag changed during this tick (even if back again): vanilla sends it at the tick's end then. */
	private boolean dirty;
	/** A glide started this tick: one that's stopped again in it lasted 0 ticks. */
	private boolean startedThisTick;
	private int glideTicks, wear, longestGlide, starts;
	private final List<Integer> glides = new ArrayList<>();

	ServerGlide(LocalPlayer player) {
		inventory = new ServerInventory(player);
	}

	/** The server's copy of the player's inventory. */
	public ServerInventory inventory() {
		return inventory;
	}

	/** Whether the server is gliding the player now (as its last tick left it). */
	public boolean gliding() {
		return gliding;
	}

	/** Durability the server has taken off the elytra by gliding. */
	public int wear() {
		return wear;
	}

	/** How many glides the server started. */
	public int starts() {
		return starts;
	}

	/** How many server ticks each glide that ended lasted, in order (0 for one stopped in the tick it started). */
	public List<Integer> glides() {
		return List.copyOf(glides);
	}

	/** The most server ticks the player glided in one go. */
	public int longestGlide() {
		return longestGlide;
	}

	void receive(Packet<?> packet, LocalPlayer player) {
		inventory.receive(packet);
		if (packet instanceof ServerboundMovePlayerPacket move) onGround = move.isOnGround();
		if (packet instanceof ServerboundPlayerCommandPacket command && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
			boolean was = gliding;
			gliding = !gliding && canGlide(player);
			dirty |= was != gliding;
			if (gliding) {
				starts++;
				startedThisTick = true;
			}
		}
	}

	/** The server's tick; true if the glide's flag changed during it (even back again), so the player's flags go out. */
	boolean tick(LocalPlayer player) {
		if (gliding && !canGlide(player)) {
			gliding = false;
			dirty = true;
		}
		if (gliding) {
			glideTicks++;
			if (glideTicks % 20 == 0) wear++;
			longestGlide = Math.max(longestGlide, glideTicks);
		} else {
			if (glideTicks > 0 || startedThisTick) glides.add(glideTicks);
			glideTicks = 0;
		}
		startedThisTick = false;
		boolean send = dirty;
		dirty = false;
		return send;
	}

	private boolean canGlide(LocalPlayer player) {
		return !onGround && !player.isPassenger() && !player.hasEffect(MobEffects.LEVITATION)
			&& LivingEntity.canGlideUsing(inventory.chest(), EquipmentSlot.CHEST);
	}
}
