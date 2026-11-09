package dev.myriadclient.api.service;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;

/** The players you've marked as enemies, kept as {@link Friends} are. */
@ApiStatus.NonExtendable
@ApiStatus.AvailableSince("1.6")
public interface Enemies {
	boolean isEnemy(String name);

	default boolean isEnemy(Player player) {
		return isEnemy(player.getGameProfile().name());
	}

	boolean add(String name);

	boolean remove(String name);

	Collection<String> all();
}
