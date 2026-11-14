package dev.myriadclient.impl.compat;

import dev.myriadclient.impl.util.Clock;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The EntityCulling mod skips drawing entities it traces as hidden behind blocks, before Myriad sees them. Its
 * {@code Cullable.setTimeout()} (on every entity) keeps one drawn for a second; through-walls highlights call it every
 * frame, and while one draws an entity it's also kept ticking whole. Nothing else is touched: with no such highlight on,
 * the mod culls drawing and ticks as it would without Myriad. Found reflectively, so Myriad needn't depend on the mod.
 */
public final class EntityCullingCompat {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/EntityCulling");
	private static final MethodHandle KEEP_VISIBLE = find("setTimeout", MethodType.methodType(void.class));
	private static final MethodHandle SET_CULLED = find("setCulled", MethodType.methodType(void.class, boolean.class));
	/** Whether the mod is installed and its hook was found. */
	public static final boolean ACTIVE = KEEP_VISIBLE != null;
	/** How long after a highlight last drew an entity it's still kept ticking whole (frames can be slower than ticks). */
	private static final long TICK_KEEP_MS = 250;
	/**
	 * Entities a through-walls highlight drew, and when it last did. Not the mod's own forced-visible time: it sets that
	 * for every entity that comes back into view, and un-culling renews it, so every entity seen once would stay ticking.
	 */
	private static final Reference2LongOpenHashMap<Entity> DRAWN = new Reference2LongOpenHashMap<>();
	private static long prunedAt;

	private EntityCullingCompat() {
	}

	/** {@code Cullable.name}, taking the entity first. */
	private static MethodHandle find(String name, MethodType type) {
		if (!FabricLoader.getInstance().isModLoaded("entityculling")) return null;
		try {
			Class<?> cullable = Class.forName("dev.tr7zw.entityculling.access.Cullable", false, EntityCullingCompat.class.getClassLoader());
			return MethodHandles.publicLookup().findVirtual(cullable, name, type).asType(type.insertParameterTypes(0, Entity.class));
		} catch (ReflectiveOperationException | LinkageError e) {
			LOG.warn("EntityCulling is installed but its API changed ({}); through-walls highlights may miss or stutter entities it hides", name, e);
			return null;
		}
	}

	/**
	 * Before {@code entity} ticks: drawn by a through-walls highlight just now ({@link #keepVisible}), it's marked not
	 * culled, so EntityCulling's tick culling gives it its whole tick (see ClientLevelEntityCullingMixin).
	 */
	public static void keepTicking(Entity entity) {
		if (SET_CULLED == null || DRAWN.isEmpty()) return;
		long drawn = DRAWN.getLong(entity);
		if (drawn == 0) return;
		if (Clock.millis() - drawn > TICK_KEEP_MS) {
			DRAWN.removeLong(entity);
			return;
		}
		try {
			SET_CULLED.invokeExact(entity, false);
		} catch (Throwable ignored) {
			// Not every entity has to be Cullable.
		}
	}

	/** Keeps {@code entity} drawn even if EntityCulling thinks it's hidden. */
	public static void keepVisible(Entity entity) {
		if (KEEP_VISIBLE == null) return;
		try {
			KEEP_VISIBLE.invokeExact(entity);
		} catch (Throwable ignored) {
			// Not every entity has to be Cullable; nothing to keep then.
			return;
		}
		long now = Clock.millis();
		DRAWN.put(entity, now);
		// Entities that left (and so never tick again) don't stay in the map.
		if (now - prunedAt > 5000) {
			prunedAt = now;
			DRAWN.reference2LongEntrySet().removeIf(e -> now - e.getLongValue() > TICK_KEEP_MS);
		}
	}
}
