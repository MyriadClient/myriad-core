package dev.myriadclient.impl.render.preview;

import com.mojang.authlib.GameProfile;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.impl.mixin.WalkAnimationStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The entities previews show, made once per world (never added to it), and each subject's box in preview coordinates:
 * an entity's hitbox with its feet at the origin, a block's cube from the origin. You are shown by a stand-in player with
 * your look (skin layers, armour, what you hold, main arm): modules draw on it as on any other player, where you
 * yourself are left out of most of what they draw.
 */
final class PreviewSubjects {
	private static final Map<EntityType<?>, Entity> entities = new HashMap<>();
	private static @Nullable ClientLevel level;
	private static int nextId = -1_000_000;
	private static final EquipmentSlot[] WORN = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
		EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static @Nullable RemotePlayer standIn;

	private PreviewSubjects() {
	}

	/** The entity a subject shows, or null (no world, or a type that can't be made on its own). */
	static @Nullable Entity entity(Preview.Subject subject) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		return switch (subject) {
			case Preview.PlayerSubject p -> {
				newWorld(mc.level);
				yield mc.player == null ? null : standIn(mc.player);
			}
			case Preview.EntitySubject e -> {
				newWorld(mc.level);
				yield entities.computeIfAbsent(e.type(), t -> {
					Entity made = t.create(mc.level, EntitySpawnReason.LOAD);
					// An id of its own (rendering reads it), below any the server hands out.
					if (made != null) made.setId(nextId--);
					return made;
				});
			}
			default -> null;
		};
	}

	private static void newWorld(ClientLevel now) {
		if (level == now) return;
		entities.clear();
		standIn = null;
		level = now;
	}

	/**
	 * A player looking and moving as you do now: your profile (so your skin), skin layers, main arm and what you wear and
	 * hold, and what ticking gives you (moved).
	 */
	private static RemotePlayer standIn(LocalPlayer you) {
		if (standIn == null) {
			standIn = new StandIn(level, you.getGameProfile());
			standIn.setId(nextId--);
			// Equipping makes a sound.
			standIn.setSilent(true);
		}
		moved(standIn, you);
		standIn.getEntityData().set(Avatar.DATA_PLAYER_MODE_CUSTOMISATION, you.getEntityData().get(Avatar.DATA_PLAYER_MODE_CUSTOMISATION));
		standIn.setMainArm(you.getMainArm());
		for (EquipmentSlot slot : WORN) {
			ItemStack worn = you.getItemBySlot(slot);
			if (!ItemStack.matches(standIn.getItemBySlot(slot), worn)) standIn.setItemSlot(slot, worn.copy());
		}
		return standIn;
	}

	/**
	 * {@code standIn} as ticking has left {@code you}, as it's never ticked itself: where you are and were, on the ground
	 * or falling, moving, how old, crouching, your walk and (StandIn) your cape's swing and bob. Resource packs that
	 * animate players (Entity Model Features') go by all of these, and on an entity that's never ticked played a fall or
	 * landing over and over, its cape up; vanilla's inventory draws you yourself, so they look right there. It faces the
	 * preview's way, as the inventory turns you before drawing you.
	 */
	private static void moved(RemotePlayer standIn, LocalPlayer you) {
		standIn.setPos(you.position());
		standIn.xo = you.xo;
		standIn.yo = you.yo;
		standIn.zo = you.zo;
		standIn.setOnGround(you.onGround());
		standIn.setDeltaMovement(you.getDeltaMovement());
		standIn.fallDistance = you.fallDistance;
		standIn.horizontalCollision = you.horizontalCollision;
		standIn.verticalCollision = you.verticalCollision;
		standIn.tickCount = you.tickCount;
		standIn.setShiftKeyDown(you.isShiftKeyDown());
		standIn.setPose(you.getPose());
		WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) standIn.walkAnimation;
		walk.myriad$setSpeedOld(((WalkAnimationStateAccessor) you.walkAnimation).myriad$speedOld());
		walk.myriad$setSpeed(you.walkAnimation.speed());
		walk.myriad$setPosition(you.walkAnimation.position());
		standIn.yBodyRot = standIn.yBodyRotO = standIn.yHeadRot = standIn.yHeadRotO = standIn.yRotO = 180;
		standIn.setYRot(180);
		standIn.setXRot(0);
		standIn.xRotO = 0;
	}

	/** The stand-in: its cape swings and bobs as yours do (your avatar state, which ticking keeps). */
	private static final class StandIn extends RemotePlayer {
		StandIn(ClientLevel level, GameProfile profile) {
			super(level, profile);
		}

		@Override
		public ClientAvatarState avatarState() {
			LocalPlayer you = Minecraft.getInstance().player;
			return you != null ? you.avatarState() : super.avatarState();
		}
	}

	/** The subject's box, in its own coordinates. */
	static AABB box(Preview.Subject subject) {
		Entity entity = entity(subject);
		if (entity != null) {
			double w = entity.getBbWidth() / 2, h = entity.getBbHeight();
			return new AABB(-w, 0, -w, w, h, w);
		}
		return new AABB(0, 0, 0, 1, 1, 1);
	}
}
