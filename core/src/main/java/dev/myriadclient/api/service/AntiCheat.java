package dev.myriadclient.api.service;

import dev.myriadclient.api.Myriad;

import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * What the server checks, so a feature can be careful where it has to be and quick where it doesn't, without every
 * module asking the player "is this server strict?".
 * <p>
 * The player picks the profile in the Profiles panel (or with {@code .anticheat}); the default, {@link Mode#AUTO},
 * works it out per server. The services follow it through their {@code forServer()} presets
 * ({@link Placement.Options#forServer()}, {@link Breaking.Options#forServer()}, {@link Rotations.Options#forServer()},
 * {@link Building.Options#forServer()}), and the inventory service only waits for a safe moment to click
 * ({@link Inventory#safeToClick()}) where it matters. Use the presets, or ask {@link #isStrict()} yourself:
 *
 * <pre>{@code
 * Myriad.placement().place(this, pos, slot, Placement.Options.forServer());
 * if (Myriad.antiCheat().isStrict()) delay = Math.max(delay, 1);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface AntiCheat {
	/** How strictly the server checks what the client does. */
	enum Profile {
		/**
		 * Only the vanilla server's own limits: instant rotations, any face, breaking as soon as the server accepts it,
		 * inventory clicks at any time.
		 */
		VANILLA,
		/**
		 * Grim, or another anti-cheat that simulates movement and checks every action against the rotation the server
		 * last saw (they work alike): rotate before acting, click visible faces, move along the sent rotation, break at
		 * the times it allows, and click in the inventory only while standing still.
		 */
		GRIM
	}

	/** The player's choice. */
	enum Mode {
		/** {@link #detected()} for the server you're on. */
		AUTO,
		VANILLA,
		GRIM
	}

	/** The profile in effect: the player's choice, or with {@link Mode#AUTO} what this server looks like. */
	Profile profile();

	/** Whether the profile in effect is {@link Profile#GRIM}. */
	default boolean isStrict() {
		return profile() == Profile.GRIM;
	}

	/**
	 * What this server looks like: {@link Profile#GRIM} once it sends the steady pings ("transactions") anti-cheats that
	 * simulate the player use to keep in step with the client (vanilla servers never send them in game), or at once for
	 * servers known to run one (2b2t). {@link Profile#VANILLA} in singleplayer and until then.
	 */
	Profile detected();

	Mode mode();

	void setMode(Mode mode);

	/** Hosts known to run Grim, for {@link Mode#AUTO} to take as {@link Profile#GRIM} from the moment you join (subdomains included). */
	Set<String> knownServers();

	/** Adds a host ("2b2t.org") to {@link #knownServers()}; saved globally. */
	boolean addKnownServer(String host);

	boolean removeKnownServer(String host);

	/**
	 * {@link #isStrict()} from anywhere, including before Myriad has started (then true: when in doubt, be careful).
	 */
	static boolean strict() {
		return !Myriad.isReady() || Myriad.antiCheat().isStrict();
	}
}
