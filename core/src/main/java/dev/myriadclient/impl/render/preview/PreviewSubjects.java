package dev.myriadclient.impl.render.preview;

import dev.myriadclient.api.ui.widget.Preview;
import net.minecraft.client.Minecraft;
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

	/** A player looking as you do now: your profile (so your skin), skin layers, main arm and what you wear and hold. */
	private static RemotePlayer standIn(LocalPlayer you) {
		if (standIn == null) {
			standIn = new RemotePlayer(level, you.getGameProfile());
			standIn.setId(nextId--);
			// Equipping makes a sound.
			standIn.setSilent(true);
		}
		standIn.getEntityData().set(Avatar.DATA_PLAYER_MODE_CUSTOMISATION, you.getEntityData().get(Avatar.DATA_PLAYER_MODE_CUSTOMISATION));
		standIn.setMainArm(you.getMainArm());
		for (EquipmentSlot slot : WORN) {
			ItemStack worn = you.getItemBySlot(slot);
			if (!ItemStack.matches(standIn.getItemBySlot(slot), worn)) standIn.setItemSlot(slot, worn.copy());
		}
		return standIn;
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
