package dev.myriadclient.api.config;

import org.jetbrains.annotations.ApiStatus;

import java.nio.file.Path;
import java.util.List;

/**
 * Profiles and persistence. Module state and UI layout belong to the active profile; friends and global options
 * do not. Changes are saved automatically shortly after they happen.
 * <p>
 * Everything lives under {@link #root()}: global options (the active profile, the command prefix, key action binds)
 * in {@code myriad.json}, and each profile in {@code profiles/<name>/} (modules, addon settings, control panels and
 * the UI's windows). Settings and module toggles mark the config dirty themselves; call {@link #markDirty()} only for
 * state of your own that the profile saves. Addon data that isn't settings belongs in
 * {@link dev.myriadclient.api.addon.AddonStorage}.
 *
 * <pre>{@code
 * Myriad.config().switchProfile("pvp");
 * String prefix = Myriad.config().commandPrefix();
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface ConfigManager {
	/** Myriad's folder, {@code .minecraft/myriad/}. */
	Path root();

	/**
	 * Asks for a save: the profile is written about two seconds after the first change since the last save, so a burst
	 * of changes costs one write. Ignored while config is loading. The JSON is built on the render thread and written on
	 * a background thread.
	 */
	void markDirty();

	/** Saves everything right away, on the calling thread, waiting for the disk. */
	void saveNow();

	/** The name of the profile in use ({@code "default"} on a fresh install). */
	String activeProfile();

	/** The names of every saved profile, sorted, with the active one even before it's first saved; a copy. */
	List<String> profiles();

	/**
	 * Saves the current profile and loads {@code name}, creating it from the current state if it doesn't exist.
	 * Characters other than letters, digits, {@code _} and {@code -} become {@code _}; an empty name, or the active
	 * profile's, does nothing.
	 */
	void switchProfile(String name);

	/**
	 * Reloads the active profile from disk, dropping changes that haven't been saved (e.g. after editing files by hand).
	 * Global options in {@code myriad.json} aren't read again.
	 */
	void reload();

	/**
	 * Deletes the profile {@code name} and its files. False if it's the active profile, doesn't exist, or couldn't be
	 * deleted.
	 */
	boolean deleteProfile(String name);

	/** What starts a Myriad command in chat, {@code "."} by default. */
	String commandPrefix();

	/** Sets the command prefix, trimmed, and saves it; a blank one is ignored. */
	void setCommandPrefix(String prefix);
}
