package dev.myriadclient.impl.compat;

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
 * frame. Found reflectively, so Myriad needn't depend on the mod.
 */
public final class EntityCullingCompat {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/EntityCulling");
	private static final MethodHandle KEEP_VISIBLE = find();
	/** Whether the mod is installed and its hook was found. */
	public static final boolean ACTIVE = KEEP_VISIBLE != null;

	private EntityCullingCompat() {
	}

	private static MethodHandle find() {
		if (!FabricLoader.getInstance().isModLoaded("entityculling")) return null;
		try {
			Class<?> cullable = Class.forName("dev.tr7zw.entityculling.access.Cullable", false, EntityCullingCompat.class.getClassLoader());
			return MethodHandles.publicLookup().findVirtual(cullable, "setTimeout", MethodType.methodType(void.class))
				.asType(MethodType.methodType(void.class, Entity.class));
		} catch (ReflectiveOperationException | LinkageError e) {
			LOG.warn("EntityCulling is installed but its API changed; through-walls highlights may miss entities it hides", e);
			return null;
		}
	}

	/** Keeps {@code entity} drawn even if EntityCulling thinks it's hidden. */
	public static void keepVisible(Entity entity) {
		if (KEEP_VISIBLE == null) return;
		try {
			KEEP_VISIBLE.invokeExact(entity);
		} catch (Throwable ignored) {
			// Not every entity has to be Cullable; nothing to keep then.
		}
	}
}
