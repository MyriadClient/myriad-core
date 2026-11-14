package dev.myriadclient.api.world;

import com.mojang.authlib.GameProfile;
import dev.myriadclient.api.util.Client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Client-side dummy players for testing combat and render features in singleplayer: they stand where you do, wear
 * a copy of your gear, and count as players for targeting, damage maths and rendering. Only you can see them; the
 * server knows nothing about them. They're removed when you leave the world. Also {@code .fakeplayer}.
 */
public final class FakePlayers {
	private static final List<RemotePlayer> players = new ArrayList<>();
	/** Negative ids can't collide with real entities. */
	private static int nextId = -1_000_000;

	private FakePlayers() {
	}

	/**
	 * Spawns a fake player named {@code name} at your position, facing your way, with {@code health} and, if
	 * {@code copyInventory}, a copy of your inventory and gear. Null outside a world.
	 */
	public static @Nullable Player spawn(String name, float health, boolean copyInventory) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return null;
		RemotePlayer fake = new RemotePlayer(mc.level, new GameProfile(UUID.nameUUIDFromBytes(("fake:" + name).getBytes()), name));
		fake.copyPosition(mc.player);
		fake.setYHeadRot(mc.player.getYHeadRot());
		fake.setYBodyRot(mc.player.getVisualRotationYInDegrees());
		if (copyInventory) fake.getInventory().replaceWith(mc.player.getInventory());
		fake.setHealth(health);
		fake.setId(nextId--);
		mc.level.addEntity(fake);
		players.add(fake);
		return fake;
	}

	/** Removes the first fake player called {@code name} (case ignored); false if there's none. */
	public static boolean remove(String name) {
		prune();
		for (RemotePlayer p : List.copyOf(players)) {
			if (p.getGameProfile().name().equalsIgnoreCase(name)) {
				despawn(p);
				return true;
			}
		}
		return false;
	}

	/** Removes every fake player. */
	public static void clear() {
		prune();
		for (RemotePlayer p : List.copyOf(players)) despawn(p);
	}

	private static void despawn(RemotePlayer p) {
		players.remove(p);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) mc.level.removeEntity(p.getId(), Entity.RemovalReason.DISCARDED);
	}

	/**
	 * The fake players in the world you're in, oldest first: a read-only live view, so copy it before spawning or
	 * removing while iterating.
	 */
	public static List<Player> all() {
		prune();
		return Collections.unmodifiableList(players);
	}

	/** Whether {@code entity} is one of these fake players. */
	public static boolean isFake(Entity entity) {
		return entity instanceof RemotePlayer p && players.contains(p);
	}

	/** Fake players belong to the world they were spawned in; forget them once it's gone. */
	private static void prune() {
		var world = Minecraft.getInstance().level;
		players.removeIf(p -> p.level() != world);
	}
}
