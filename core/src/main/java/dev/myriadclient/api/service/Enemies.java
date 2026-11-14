package dev.myriadclient.api.service;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;

/**
 * The players you've marked as enemies ({@code .enemy add <name>}), from {@link dev.myriadclient.api.Myriad#enemies()}:
 * kept as {@link Friends} are, in {@code myriad/enemies.json}, for modules that pick them out (a colour of their own in
 * the player list, targets first). Safe to use from any thread; {@link #add} and {@link #remove} write the file on the
 * calling thread.
 */
@ApiStatus.NonExtendable
@ApiStatus.AvailableSince("1.6")
public interface Enemies {
	/** Whether {@code name} is an enemy, ignoring case. */
	boolean isEnemy(String name);

	/** Whether {@code player} is an enemy, by their profile name. */
	default boolean isEnemy(Player player) {
		return isEnemy(player.getGameProfile().name());
	}

	/** Adds {@code name} (trimmed) and saves the list; false if they were an enemy already. */
	boolean add(String name);

	/** Removes {@code name} (trimmed, any case) and saves the list; false if they weren't an enemy. */
	boolean remove(String name);

	/** Every enemy, sorted ignoring case; a copy. */
	Collection<String> all();
}
