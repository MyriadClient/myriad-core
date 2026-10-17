package dev.myriadclient.api.service;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;

@ApiStatus.NonExtendable
public interface Friends {
	boolean isFriend(String name);

	default boolean isFriend(Player player) {
		return isFriend(player.getGameProfile().name());
	}

	boolean add(String name);

	boolean remove(String name);

	Collection<String> all();
}
