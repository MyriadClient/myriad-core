package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.elytra.ChestSwap;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.elytra.Flight;
import dev.myriadclient.api.elytra.GlideHold;
import dev.myriadclient.api.elytra.NoDurability;

import java.util.concurrent.CopyOnWriteArrayList;

/** {@link Elytra}: the pilots flying, and the shared engine; the mixins ask it about the local player's glide. */
public final class ElytraManager implements Elytra {
	private static final CopyOnWriteArrayList<Pilot> PILOTS = new CopyOnWriteArrayList<>();

	private final ChestSwap chestSwap = new ChestSwap() {
		@Override
		public boolean ready() {
			return ChestSwapper.ready();
		}

		@Override
		public boolean canGlide() {
			return ChestSwapper.canGlide();
		}

		@Override
		public boolean elytraWorn() {
			return ChestSwapper.elytraWorn();
		}

		@Override
		public boolean swap() {
			return ChestSwapper.swap();
		}

		@Override
		public boolean startGlide() {
			return ChestSwapper.startGlide();
		}

		@Override
		public boolean settled() {
			return ChestSwapper.settled();
		}

		@Override
		public void restoreElytra() {
			ChestSwapper.restoreElytra();
		}

		@Override
		public Need ensurePair(boolean mayStop) {
			return ChestSwapper.ensurePair(mayStop);
		}
	};

	private final GlideHold glideHold = new GlideHold() {
		@Override
		public boolean arm(Object owner) {
			return GlideHolder.arm(owner);
		}

		@Override
		public void disarm(Object owner) {
			GlideHolder.disarm(owner);
		}

		@Override
		public void release(Object owner) {
			GlideHolder.release(owner);
		}

		@Override
		public boolean clearHeld(Object owner) {
			return GlideHolder.clearHeld(owner);
		}

		@Override
		public boolean exposed(Object owner) {
			return GlideHolder.exposed(owner);
		}

		@Override
		public boolean cleared(Object owner) {
			return GlideHolder.cleared(owner);
		}

		@Override
		public boolean stoppedSinceStart(Object owner) {
			return GlideHolder.stoppedSinceStart(owner);
		}

		@Override
		public boolean glidingSince(long ms) {
			return GlideHolder.glidingSince(ms);
		}
	};

	@Override
	public void addPilot(Pilot pilot) {
		PILOTS.addIfAbsent(pilot);
	}

	@Override
	public void removePilot(Pilot pilot) {
		PILOTS.remove(pilot);
	}

	@Override
	public NoDurability noDurability(NoDurability.Owner owner) {
		return new NoDurabilityEngine(owner);
	}

	@Override
	public Flight altitude(NoDurability.Owner owner) {
		return new AltitudeFlight(owner);
	}

	@Override
	public Flight control(NoDurability.Owner owner) {
		return new ControlFlight(owner);
	}

	@Override
	public ChestSwap chestSwap() {
		return chestSwap;
	}

	@Override
	public GlideHold glideHold() {
		return glideHold;
	}

	// ---- for the mixins ---------------------------------------------------------------------------------------------

	/** Whether a pilot moves the local player by the rotation being sent to the server. */
	public static boolean steering() {
		for (Pilot p : PILOTS) if (p.steers()) return true;
		return false;
	}

	/** Whether any pilot is flying the local player. */
	static boolean piloted() {
		return !PILOTS.isEmpty();
	}

	/** Whether {@code pilot} is flying the local player. */
	static boolean flying(Pilot pilot) {
		return PILOTS.contains(pilot);
	}

	/** Whether a pilot holds the glide open. */
	static boolean pilotHoldsGlide() {
		for (Pilot p : PILOTS) if (p.holdsGlide()) return true;
		return false;
	}

	/** Whether the local player counts as gliding though the elytra closed: a pilot's hold, or No Durability's breaks. */
	public static boolean holdsGlide() {
		return NoDurabilityEngine.holdsGlide() || pilotHoldsGlide();
	}

	/** Whether No Durability holds the local player still this tick. */
	public static boolean holdsStill() {
		return NoDurabilityEngine.holdsStill();
	}

	/** A ping is being answered: true to hold the answer back (see {@link GlideHolder}). */
	public static boolean holdAnswer(int id) {
		return GlideHolder.holdAnswer(id);
	}

	/** The server's glide clear is being applied: true to keep gliding (see {@link GlideHolder}). */
	public static boolean keepGliding() {
		return GlideHolder.keepGliding();
	}
}
