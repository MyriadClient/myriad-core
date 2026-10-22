package dev.myriadclient.api.service;

import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link Blueprint}: describe the shape you want, and the planner works out each tick, for what's in reach,
 * what to break and what to place, in an order that works, and does it through {@link Breaking} and
 * {@link Placement}. Nuker, tunnelers, highway builders, surround, scaffold and printers become mostly a blueprint.
 *
 * <ul>
 *   <li>Wrong blocks are broken first, top down so falling blocks drop into space that's already been cleared,
 *   never the one you stand on, and (by default) not if it lets a fluid in.</li>
 *   <li>Placements go bottom up and nearest first, so each one has something to be placed against; a position
 *   with nothing to click waits until a neighbour is placed.</li>
 *   <li>Materials come from the hotbar, moved there from the inventory when needed.</li>
 *   <li>Oriented targets ({@code Target.state}) are placed facing the right way: the planner tries the clicks it
 *   could make and the rotation each needs, as the game would place them, and uses one that comes out right.</li>
 * </ul>
 *
 * <pre>{@code
 * Build build = Myriad.building().start(this, Blueprint.box(a, b, Target.air()), Building.Options.DEFAULT);
 * build.finished().thenAccept(done -> info(done ? "Tunnel finished" : "Stopped"));
 * }</pre>
 *
 * A module's builds stop when it's disabled.
 */
@ApiStatus.NonExtendable
public interface Building {
	/**
	 * How a blueprint is built. Start from a preset ({@link #forServer()}, {@link #DEFAULT}, {@link #STRICT}) and change
	 * what you need with the {@code with} methods, so options added in later versions keep their defaults in your code.
	 */
	final class Options {
		public static final Options DEFAULT = new Options(Placement.Options.DEFAULT, Breaking.Options.DEFAULT, true, true, 4, 0, false);
		public static final Options STRICT = new Options(Placement.Options.STRICT, Breaking.Options.STRICT, true, true, 1, 0, false);

		private final Placement.Options placing;
		private final Breaking.Options breaking;
		private final boolean breakWrong, avoidFluids, keepUp;
		private final int placesPerTick, priority;

		private Options(Placement.Options placing, Breaking.Options breaking, boolean breakWrong, boolean avoidFluids, int placesPerTick,
		                int priority, boolean keepUp) {
			this.placing = Objects.requireNonNull(placing);
			this.breaking = Objects.requireNonNull(breaking);
			this.breakWrong = breakWrong;
			this.avoidFluids = avoidFluids;
			this.placesPerTick = Math.max(1, placesPerTick);
			this.priority = priority;
			this.keepUp = keepUp;
		}

		/**
		 * What the server in effect takes ({@link AntiCheat#inEffect()}): where it checks the look of each click, one
		 * placement a tick with a turn for each, and breaks turned too; placements as {@link Placement.Options#forServer()}.
		 * {@link #STRICT} on Grim, {@link #DEFAULT} on a vanilla server.
		 */
		public static Options forServer() {
			return forProfile(AntiCheat.inEffect());
		}

		static Options forProfile(AntiCheat.Profile p) {
			return (p.checksLookOnActions() ? STRICT : DEFAULT).withPlacing(Placement.Options.forProfile(p));
		}

		/** How blocks are placed. */
		public Placement.Options placing() {
			return placing;
		}

		/** How blocks are broken. */
		public Breaking.Options breaking() {
			return breaking;
		}

		/**
		 * Break blocks that are in the way of a placement; off for builds that should only fill empty space (surround
		 * shouldn't dig up your base).
		 */
		public boolean breakWrong() {
			return breakWrong;
		}

		/** Don't break blocks that hold back water or lava. */
		public boolean avoidFluids() {
			return avoidFluids;
		}

		/** Most placements started per tick. */
		public int placesPerTick() {
			return placesPerTick;
		}

		/** The priority of this build's breaks against other modules' breaks. */
		public int priority() {
			return priority;
		}

		/**
		 * Keep running when everything is done, putting back what changes (surround); otherwise the build finishes once
		 * the blueprint is done.
		 */
		public boolean keepUp() {
			return keepUp;
		}

		public Options withPlacing(Placement.Options placing) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		public Options withBreaking(Breaking.Options breaking) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		public Options withBreakWrong(boolean breakWrong) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		public Options withAvoidFluids(boolean avoidFluids) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		public Options withPlacesPerTick(int placesPerTick) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		public Options withPriority(int priority) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		public Options withKeepUp(boolean keepUp) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Options x && x.placing.equals(placing) && x.breaking.equals(breaking) && x.breakWrong == breakWrong
				&& x.avoidFluids == avoidFluids && x.placesPerTick == placesPerTick && x.priority == priority && x.keepUp == keepUp;
		}

		@Override
		public int hashCode() {
			return Objects.hash(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp);
		}

		@Override
		public String toString() {
			return "Building.Options[placing=" + placing + ", breaking=" + breaking + ", breakWrong=" + breakWrong + ", avoidFluids="
				+ avoidFluids + ", placesPerTick=" + placesPerTick + ", priority=" + priority + ", keepUp=" + keepUp + "]";
		}
	}

	/** Starts building {@code blueprint} for {@code owner}. */
	Build start(Object owner, Blueprint blueprint, Options options);

	/** Stops every build {@code owner} started. */
	void stop(Object owner);

	/** The builds running now. */
	List<Build> running();
}
