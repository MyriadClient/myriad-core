package dev.myriadclient.api.service;

import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.util.RenderThread;
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
 *   <li>Placements go bottom up and nearest first (or nearest first alone: {@link Options#withOrder}), so each one has
 *   something to be placed against; a position with nothing to click waits until a neighbour is placed.</li>
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
@RenderThread
public interface Building {
	/**
	 * How a blueprint is built. Start from a preset ({@link #forServer()}, {@link #DEFAULT}, {@link #STRICT}) and change
	 * what you need with the {@code with} methods, so options added in later versions keep their defaults in your code.
	 */
	final class Options {
		public static final Options DEFAULT = new Options(Placement.Options.DEFAULT, Breaking.Options.DEFAULT, true, true, 4, 0, false, true, Order.BOTTOM_UP);
		public static final Options STRICT = new Options(Placement.Options.STRICT, Breaking.Options.STRICT, true, true, 1, 0, false, true, Order.BOTTOM_UP);

		private final Placement.Options placing;
		private final Breaking.Options breaking;
		private final boolean breakWrong, avoidFluids, keepUp, clickSettings;
		private final int placesPerTick, priority;
		private final Order order;

		private Options(Placement.Options placing, Breaking.Options breaking, boolean breakWrong, boolean avoidFluids, int placesPerTick,
		                int priority, boolean keepUp, boolean clickSettings, Order order) {
			this.placing = Objects.requireNonNull(placing);
			this.breaking = Objects.requireNonNull(breaking);
			this.breakWrong = breakWrong;
			this.avoidFluids = avoidFluids;
			this.placesPerTick = Math.max(1, placesPerTick);
			this.priority = priority;
			this.keepUp = keepUp;
			this.clickSettings = clickSettings;
			this.order = Objects.requireNonNull(order);
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

		/** Most placements and hand clicks (settings) started per tick; breaks go at the breaking service's own pace. */
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
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		public Options withBreaking(Breaking.Options breaking) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		public Options withBreakWrong(boolean breakWrong) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		public Options withAvoidFluids(boolean avoidFluids) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		public Options withPlacesPerTick(int placesPerTick) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		public Options withPriority(int priority) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		public Options withKeepUp(boolean keepUp) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		/**
		 * Click blocks into their setting by hand after placing them, where the target says (a repeater's delay, a
		 * comparator's mode, a note, open or closed, a lever: {@link dev.myriadclient.api.build.Target#exact}). On by
		 * default; off, a block placed the right way counts as done whatever its setting.
		 */
		public Options withClickSettings(boolean clickSettings) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		/** Whether settings are clicked; see {@link #withClickSettings}. */
		public boolean clickSettings() {
			return clickSettings;
		}

		/** Which placements in reach go first ({@link Order#BOTTOM_UP} by default). */
		public Options withOrder(Order order) {
			return new Options(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		/** Which placements go first; see {@link #withOrder}. */
		public Order order() {
			return order;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Options x && x.placing.equals(placing) && x.breaking.equals(breaking) && x.breakWrong == breakWrong
				&& x.avoidFluids == avoidFluids && x.placesPerTick == placesPerTick && x.priority == priority && x.keepUp == keepUp
				&& x.clickSettings == clickSettings && x.order == order;
		}

		@Override
		public int hashCode() {
			return Objects.hash(placing, breaking, breakWrong, avoidFluids, placesPerTick, priority, keepUp, clickSettings, order);
		}

		@Override
		public String toString() {
			return "Building.Options[placing=" + placing + ", breaking=" + breaking + ", breakWrong=" + breakWrong + ", avoidFluids="
				+ avoidFluids + ", placesPerTick=" + placesPerTick + ", priority=" + priority + ", keepUp=" + keepUp + ", clickSettings=" + clickSettings + ", order=" + order + "]";
		}
	}

	/**
	 * Which of the placements in reach go first. Either way, blocks the click doesn't shape go before the ones it does
	 * (a stair's facing, a log's axis), so those have neighbours to be clicked against.
	 */
	enum Order {
		/** Lowest first, then nearest: each block has the one under it to go against. Fills a layer before the next. */
		BOTTOM_UP,
		/** Nearest first, whatever the height: what's beside you as you walk past, before it's out of reach. */
		NEAREST
	}

	/** Starts building {@code blueprint} for {@code owner}. */
	Build start(Object owner, Blueprint blueprint, Options options);

	/** Stops every build {@code owner} started. */
	void stop(Object owner);

	/** The builds running now. */
	List<Build> running();
}
