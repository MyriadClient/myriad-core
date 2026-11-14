package dev.myriadclient.api.service;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;

/**
 * The players you've marked as friends ({@code .friend add <name>}, or the Friends window), from
 * {@link dev.myriadclient.api.Myriad#friends()}. Kept across profiles and sessions in {@code myriad/friends.json}; names
 * compare ignoring case. Combat modules leave friends alone (see {@link dev.myriadclient.api.combat.Targets}). Safe
 * to use from any thread; {@link #add} and {@link #remove} write the file on the calling thread.
 *
 * <pre>{@code
 * if (Myriad.friends().isFriend(player)) return; // never attack a friend
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Friends {
	/** Whether {@code name} is a friend, ignoring case. */
	boolean isFriend(String name);

	/** Whether {@code player} is a friend, by their profile name. */
	default boolean isFriend(Player player) {
		return isFriend(player.getGameProfile().name());
	}

	/** Adds {@code name} (trimmed) and saves the list; false if they were a friend already. */
	boolean add(String name);

	/** Removes {@code name} (trimmed, any case) and saves the list; false if they weren't a friend. */
	boolean remove(String name);

	/** Every friend, sorted ignoring case; a copy, so it can be kept and iterated freely. */
	Collection<String> all();
}
