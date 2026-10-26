package dev.myriadclient.api.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * What the server checks, so a feature can be careful where it has to be and quick where it doesn't, without every
 * module asking the player "is this server strict?".
 * <p>
 * A {@link Profile} is a set of answers: does the server simulate movement, check the look of every click, accept
 * only faces you can see, refuse inventory clicks while you move, simulate the elytra glide, time breaks, want a swing
 * with each click, check the order of packets in a tick. Grim is the best-known bundle; {@link Profile#VANILLA},
 * {@link Profile#GRIM}, {@link Profile#GRIM_2B2T} and {@link Profile#NCP} come with Myriad, and
 * an addon for a server of its own registers its profile ({@code ctx.registerAntiCheatProfile}) and can recognise the
 * server ({@link #DETECT}).
 * <p>
 * The player picks the profile in the Profiles panel (or with {@code .anticheat}); by default it's worked out per
 * server ({@link #detected()}). The services follow it through their {@code forServer()} presets
 * ({@link Placement.Options#forServer()}, {@link Breaking.Options#forServer()}, {@link Rotations.Options#forServer()},
 * {@link Building.Options#forServer()}), and the inventory service only waits for a safe moment to click
 * ({@link Inventory#safeToClick()}) where it matters. Use the presets, or ask the profile yourself:
 *
 * <pre>{@code
 * Myriad.placement().place(this, pos, slot, Placement.Options.forServer());
 * if (AntiCheat.inEffect().simulatesGlide()) return; // this flight would be set back
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface AntiCheat {
	/**
	 * Works out what the server checks, when you join and as it shows itself. Core's answer comes first as the
	 * fallback: {@link Profile#GRIM_2B2T} on 2b2t, {@link Profile#GRIM} on a host known to run it or once the server
	 * sends the steady pings anti-cheats that simulate the player use, {@link Profile#NCP} on 6b6t,
	 * {@link Profile#VANILLA} otherwise. Answer with your own profile for your server; return null to leave core's.
	 */
	Hook<ServerSeen, Profile> DETECT = Hook.of("myriad:detect_anticheat", "Detect anti-cheat",
		"What the server checks, worked out from the server you joined and how it behaves.");

	/**
	 * What detection sees: the host you joined (lower case, no port; null in singleplayer), how many of the steady
	 * pings ("transactions") the server has sent since you joined (counted up to 100; the vanilla server never sends
	 * them in game), and the protocol number of the version the server sees you as ({@link #joinedProtocol()}).
	 */
	record ServerSeen(@Nullable String host, boolean singleplayer, int pings, int protocol) {
	}

	/** How a server times block breaking. */
	enum BreakTiming {
		/** Only the vanilla server's rule: a break may finish once 70% of its time has passed. */
		SERVER,
		/**
		 * The anti-cheat times each break from its start (Grim's FastBreak): the full time, with small allowances for
		 * an early finish and a quick next start. Finishes before it are refused and the block comes back.
		 */
		CHECKED,
		/**
		 * Timed as {@link #CHECKED}, by a build of it that a start for the same spot far above the world fools: it then
		 * times air, which breaks at once, so a break may finish at 70% of its time ({@link Breaking.Mode#FAST_GRIM}),
		 * and a second block can be mined meanwhile. 2b2t's Grim.
		 */
		@ApiStatus.AvailableSince("1.2")
		DECOYABLE
	}

	/**
	 * What a server checks, as the answers the services act on. Start from a preset and change what differs, under an
	 * id and name of your own:
	 *
	 * <pre>{@code
	 * // A Grim build that doesn't simulate the glide.
	 * Profile mine = AntiCheat.Profile.GRIM.named("my-addon:my_server", "My server").withSimulatesGlide(false);
	 * ctx.registerAntiCheatProfile(mine);
	 * }</pre>
	 *
	 * Two profiles are the same profile when their ids are ({@code Match.on(profile)} matches by id).
	 */
	final class Profile implements Identified {
		/**
		 * Only the vanilla server's own limits: instant rotations, any face, breaking as soon as the server accepts it,
		 * inventory clicks at any time.
		 */
		public static final Profile VANILLA = new Profile(MyriadId.of("myriad", "vanilla"), "Vanilla", false, false, false, false, false, BreakTiming.SERVER, 9,
			true, true);
		/**
		 * Grim, or another anti-cheat that simulates movement and checks every action against the rotation the server
		 * last saw (they work alike): rotate before acting, click visible faces, move along the sent rotation, break at
		 * the times it allows, click in the inventory only while standing still, and glide as vanilla does.
		 */
		public static final Profile GRIM = new Profile(MyriadId.of("myriad", "grim"), "Grim", true, true, true, true, true, BreakTiming.CHECKED, 9,
			true, true);
		/**
		 * 2b2t's Grim: Grim's answers, but it lets placements and item uses go without a swing, and a slot swapped in for
		 * one go back in the same tick, so your hand never shows the item (live captures, 2026-10-24: no setback, nothing
		 * refused); and its break timing is fooled by a decoy start ({@link BreakTiming#DECOYABLE}). Picked for 2b2t.org.
		 */
		@ApiStatus.AvailableSince("1.2")
		public static final Profile GRIM_2B2T = GRIM.named("myriad:2b2t", "2b2t").withChecksSwings(false).withChecksPacketOrder(false)
			.withBreakTiming(BreakTiming.DECOYABLE);
		/**
		 * NoCheatPlus (6b6t runs a fork of it). It checks the glide only against loose limits rather than simulating it,
		 * which is what lets Elytra Fly's Control work there; everything else is treated as on a vanilla server, as it
		 * was before this profile existed.
		 */
		public static final Profile NCP = new Profile(MyriadId.of("myriad", "ncp"), "NoCheatPlus", false, false, false, false, false, BreakTiming.SERVER, 9,
			true, true);

		private final MyriadId id;
		private final String name;
		private final boolean simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide;
		private final BreakTiming breakTiming;
		private final int interactionBudget;
		private final boolean checksSwings, checksPacketOrder;

		private Profile(MyriadId id, String name, boolean simulatesMovement, boolean checksLookOnActions, boolean visibleFacesOnly,
		                boolean clicksOnlyWhileStill, boolean simulatesGlide, BreakTiming breakTiming, int interactionBudget,
		                boolean checksSwings, boolean checksPacketOrder) {
			this.id = Objects.requireNonNull(id);
			this.name = Objects.requireNonNull(name);
			this.simulatesMovement = simulatesMovement;
			this.checksLookOnActions = checksLookOnActions;
			this.visibleFacesOnly = visibleFacesOnly;
			this.clicksOnlyWhileStill = clicksOnlyWhileStill;
			this.simulatesGlide = simulatesGlide;
			this.breakTiming = Objects.requireNonNull(breakTiming);
			this.interactionBudget = Math.max(1, interactionBudget);
			this.checksSwings = checksSwings;
			this.checksPacketOrder = checksPacketOrder;
		}

		/** This profile's answers under another id ({@code "my-addon:my_server"}) and name, to register as a profile of its own. */
		public Profile named(String id, String name) {
			return new Profile(MyriadId.parse(id), name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill,
				simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		@Override
		public MyriadId id() {
			return id;
		}

		/** For people: "Grim", "NoCheatPlus". */
		public String name() {
			return name;
		}

		/**
		 * The anti-cheat runs the player's movement itself and compares (Grim's Simulation): movement has to follow the
		 * rotation the server last got, so {@link Rotations.Options#forServer()} turns with the move fix. This is what
		 * {@link AntiCheat#isStrict()} reports.
		 */
		public boolean simulatesMovement() {
			return simulatesMovement;
		}

		/**
		 * Every click is checked against the rotation the server last got, so placements turn first
		 * ({@link Placement.Options#forServer()} rotates; a building also turns for its breaks and places one block a
		 * tick, one turn each).
		 */
		public boolean checksLookOnActions() {
			return checksLookOnActions;
		}

		/** A placement has to click a face you can see. */
		public boolean visibleFacesOnly() {
			return visibleFacesOnly;
		}

		/**
		 * Inventory clicks are refused while you move or sprint (Grim's MultiActions), so clicks that can wait, wait for
		 * {@link Inventory#safeToClick()}.
		 */
		public boolean clicksOnlyWhileStill() {
			return clicksOnlyWhileStill;
		}

		/**
		 * The elytra glide is simulated: flight that isn't vanilla's glide is set back, and a glide can't be restarted
		 * mid-air to spare the elytra (No Durability swaps a chestplate on instead).
		 */
		public boolean simulatesGlide() {
			return simulatesGlide;
		}

		/** How breaks are timed; {@link Breaking.Options#forServer()} mines by it. */
		public BreakTiming breakTiming() {
			return breakTiming;
		}

		/**
		 * How many uses, placements and attacks fit in 300 ms ({@link PacketLimits.Kind#INTERACT}). Paper drops the
		 * tenth inside its spam limiter's window, without a flag, so the presets allow 9.
		 */
		public int interactionBudget() {
			return interactionBudget;
		}

		/**
		 * The server wants a swing with each placement and item use (Grim, NoCheatPlus: a click without one is flagged).
		 * Where it doesn't, the placement and inventory services leave the swing out: your arm doesn't move. Kept with
		 * breaks and attacks whatever this says. The vanilla server doesn't check, but its profile is also what a server
		 * nothing was recognised on gets, so it keeps them; so does this answer's other profile, NoCheatPlus.
		 */
		@ApiStatus.AvailableSince("1.2")
		public boolean checksSwings() {
			return checksSwings;
		}

		/**
		 * The server checks the order of packets within a tick (Grim's PacketOrder checks): a held slot swapped in for
		 * an action goes back at the start of the next tick, an off-hand swap for an air placement too. Where it doesn't,
		 * they go back in the same tick, so the item never shows in your hand to others and a tick can place blocks of
		 * several kinds from the off hand. True for every preset but {@link #GRIM_2B2T}: a server nothing was
		 * recognised on gets the vanilla profile, and might run a check of it.
		 */
		@ApiStatus.AvailableSince("1.2")
		public boolean checksPacketOrder() {
			return checksPacketOrder;
		}

		@ApiStatus.AvailableSince("1.2")
		public Profile withChecksSwings(boolean checksSwings) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		@ApiStatus.AvailableSince("1.2")
		public Profile withChecksPacketOrder(boolean checksPacketOrder) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withSimulatesMovement(boolean simulatesMovement) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withChecksLookOnActions(boolean checksLookOnActions) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withVisibleFacesOnly(boolean visibleFacesOnly) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withClicksOnlyWhileStill(boolean clicksOnlyWhileStill) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withSimulatesGlide(boolean simulatesGlide) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withBreakTiming(BreakTiming breakTiming) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		public Profile withInteractionBudget(int interactionBudget) {
			return new Profile(id, name, simulatesMovement, checksLookOnActions, visibleFacesOnly, clicksOnlyWhileStill, simulatesGlide, breakTiming, interactionBudget, checksSwings, checksPacketOrder);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Profile p && p.id.equals(id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** The profile in effect: the player's choice, or what this server looks like ({@link #detected()}). */
	Profile profile();

	/** Whether the profile in effect simulates movement ({@link Profile#simulatesMovement()}), as Grim does. */
	default boolean isStrict() {
		return profile().simulatesMovement();
	}

	/** What this server looks like ({@link #DETECT}); {@link Profile#VANILLA} in singleplayer and before anything shows. */
	Profile detected();

	/** The profile the player chose, or null for Auto ({@link #detected()}). */
	@Nullable Profile choice();

	/** Chooses the profile, or null for Auto. Saved globally. */
	void choose(@Nullable Profile profile);

	/** Every profile there is: Myriad's and the ones addons registered. */
	Registry<Profile> profiles();

	/** Hosts known to run Grim, for detection to take as {@link Profile#GRIM} from the moment you join (subdomains included). */
	Set<String> knownServers();

	/** Adds a host ("2b2t.org") to {@link #knownServers()}; saved globally. */
	boolean addKnownServer(String host);

	boolean removeKnownServer(String host);

	/**
	 * The protocol number of the version the server sees you as: the one ViaFabricPlus translates to when it does
	 * (1.20.4 is 765), otherwise this game's own; -1 if it can't be told. Grim judges some things by it
	 * (which clicks it refuses while you move, some placements), so version-specific rules can gate on it.
	 */
	int joinedProtocol();

	/** That version as players name it: "1.20.4", "26.2"; "unknown" if it can't be told. */
	String joinedVersion();

	/** Whether ViaFabricPlus translates this connection to another version than the game's own. */
	boolean isTranslated();

	/**
	 * {@link #isStrict()} from anywhere, including before Myriad has started (then true: when in doubt, be careful).
	 */
	static boolean strict() {
		return inEffect().simulatesMovement();
	}

	/** {@link #profile()} from anywhere, including before Myriad has started (then {@link Profile#GRIM}). */
	static Profile inEffect() {
		return Myriad.isReady() ? Myriad.antiCheat().profile() : Profile.GRIM;
	}
}
