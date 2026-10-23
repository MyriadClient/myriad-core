package dev.myriadclient.impl.render.preview;

import dev.myriadclient.api.ui.widget.Preview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The entities previews show, made once per world (never added to it), and each subject's box in preview coordinates:
 * an entity's hitbox with its feet at the origin, a block's cube from the origin.
 */
final class PreviewSubjects {
	private static final Map<EntityType<?>, Entity> entities = new HashMap<>();
	private static @Nullable ClientLevel level;
	private static int nextId = -1_000_000;

	private PreviewSubjects() {
	}

	/** The entity a subject shows, or null (no world, or a type that can't be made on its own). */
	static @Nullable Entity entity(Preview.Subject subject) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		return switch (subject) {
			case Preview.PlayerSubject p -> mc.player;
			case Preview.EntitySubject e -> {
				if (level != mc.level) {
					entities.clear();
					level = mc.level;
				}
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
