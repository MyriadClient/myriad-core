package dev.myriadclient.api.job;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.BlockRules;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.Client;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The services as steps. Each is a thin adapter: it asks the service with {@code ctx.job()} as the owner, follows the
 * attempt or future, and turns the service's answer into {@link Progress} with a {@link Why} from the service's own
 * vocabulary ({@code Placement.Check}, {@code Build.Status}). What each can be stuck or fail on is in its javadoc, so a
 * caller knows what to {@code whenStuck} and {@code recover}. They're here so every addon's "open the chest" is the
 * same one; an addon's own steps are written the same way, with {@link Step#of} and a {@link Step.Run}.
 */
public final class Steps {
	/** How long a transfer that moved nothing waits before trying again (the player may make room). */
	private static final int ROOM_RETRY_TICKS = 20;
	private static final int CLOSE_TICKS = 100;
	private static final int OPEN_BOX_TICKS = 40, PUT_BACK_TICKS = 20 * 30;

	private Steps() {
	}

	// ---- placing and breaking -----------------------------------------------------------------------------------

	/**
	 * Places the block in hotbar {@code slot} at {@code pos}; done at once if that block is already there. Stuck:
	 * {@code OUT_OF_REACH}, {@code NOT_VISIBLE}, {@code NO_SUPPORT}, {@code ENTITY_IN_WAY}, {@code BLOCKED} (something
	 * else is there), {@code RATE_LIMITED} (for a tick). Failed: {@code SERVER_REFUSED}, {@code UNSUPPORTED} (not a hotbar
	 * slot, or not in a world).
	 */
	public static Step<Void> place(BlockPos pos, int slot, Placement.Options options) {
		return Step.of("Place at " + pos.toShortString(), () -> new Attempting(pos) {
			@Override
			boolean already() {
				return holds(pos, slot);
			}

			@Override
			Placement.Attempt attempt(JobContext ctx) {
				return Myriad.placement().place(ctx.job(), pos, slot, options);
			}
		});
	}

	/** Places with this exact click (the face matters: stairs, logs, slabs); as {@link #place(BlockPos, int, Placement.Options)}. */
	public static Step<Void> place(BlockHitResult hit, int slot, Placement.Options options) {
		BlockPos at = hit.getBlockPos().relative(hit.getDirection());
		return Step.of("Place at " + at.toShortString(), () -> new Attempting(at) {
			@Override
			boolean already() {
				return holds(at, slot) || holds(hit.getBlockPos(), slot) && !Client.level().getBlockState(hit.getBlockPos()).canBeReplaced();
			}

			@Override
			Placement.Attempt attempt(JobContext ctx) {
				return Myriad.placement().place(ctx.job(), hit, slot, options);
			}
		});
	}

	/**
	 * Makes the block at {@code pos} {@code wanted}, with the block in hotbar {@code slot}: placed with a
	 * {@link Placement#solve solved} click so it faces the right way, one more added where the block takes several
	 * (candles, layers, a slab's other half), then clicked by hand into its setting (a repeater's delay, a lever, a door
	 * open or shut). Done once it's there; what the game sets itself (connections, power) isn't waited for. Stuck:
	 * {@code WRONG_ANGLE} (no click from here: move), {@code OUT_OF_REACH}, {@code NOT_VISIBLE}, {@code NO_SUPPORT},
	 * {@code ENTITY_IN_WAY}, {@code BLOCKED} (another block, or this one facing another way), {@code RATE_LIMITED};
	 * {@code NO_SUPPORT} for a door's top half or a bed's head until the other half is placed, which brings it.
	 * Failed: {@code SERVER_REFUSED}, {@code UNSUPPORTED} (a block no click makes, or no block in the slot).
	 */
	public static Step<Void> placeState(BlockPos pos, BlockState wanted, int slot, Placement.Options options) {
		return Step.of("Place " + wanted.getBlock().getName().getString() + " at " + pos.toShortString(), () -> new Step.Run<>() {
			private @Nullable CompletableFuture<Boolean> sent;

			@Override
			public Progress<Void> tick(JobContext ctx) {
				BlockState current = Client.level().getBlockState(pos);
				if (sent != null) {
					if (!sent.isDone()) return Progress.running();
					boolean ok = sent.join();
					sent = null;
					if (!ok && !BlockRules.matches(current, wanted, true)) return Progress.failed(Why.of(Why.SERVER_REFUSED, pos.toShortString(), pos));
				}
				if (BlockRules.matches(current, wanted, true)) return Progress.done();
				// The top half of a door, the head of a bed: placed with the other half.
				if (BlockRules.placedWithNeighbour(wanted))
					return current.is(wanted.getBlock()) ? Progress.done() : Progress.stuck(Why.of(Why.NO_SUPPORT, pos.toShortString(), pos));
				Placement.Attempt a;
				if (current.is(wanted.getBlock())) {
					if (!BlockRules.placedMatches(current, wanted) || BlockRules.count(current) > BlockRules.count(wanted))
						return Progress.stuck(Why.of(Why.BLOCKED, pos.toShortString(), pos));
					if (BlockRules.count(current) < BlockRules.count(wanted)) a = solved(ctx);
					else {
						if (BlockRules.clicksToMatch(current, wanted) < 0) return Progress.failed(Why.of(Why.UNSUPPORTED, pos.toShortString(), pos));
						BlockHitResult hit = handHit(current);
						if (hit == null) {
							if (Reach.hitFor(pos, options.visibleFaces(), options.range()) != null) return Progress.stuck(Why.of(Why.WRONG_ANGLE, pos.toShortString(), pos));
							return Progress.stuck(Why.of(Reach.canReach(pos, options.range()) ? Why.NOT_VISIBLE : Why.OUT_OF_REACH, pos.toShortString(), pos));
						}
						a = Myriad.placement().use(ctx.job(), hit, -1, options.withSneak(false));
					}
				} else if (!current.canBeReplaced()) return Progress.stuck(Why.of(Why.BLOCKED, pos.toShortString(), pos));
				else a = solved(ctx);
				if (a == null) return Progress.failed(Why.of(Why.UNSUPPORTED, pos.toShortString(), pos));
				if (a.sent()) {
					sent = a.result();
					return Progress.running();
				}
				Why why = Why.of(a.check(), pos);
				if (why == null) return Progress.running();
				return why.is(Why.UNSUPPORTED) ? Progress.failed(why) : Progress.stuck(why);
			}

			/**
			 * The nearest point of the block you can click by hand (seen, if the options want it) from where clicking keeps
			 * it facing the way it does: a fence gate or door swings away from where you stand.
			 */
			private @Nullable BlockHitResult handHit(BlockState current) {
				Vec3 eyes = Client.player().getEyePosition();
				double r2 = options.range() * options.range();
				BlockHitResult best = null;
				double bestD = Double.MAX_VALUE;
				for (Direction face : Direction.values()) {
					for (Vec3 p : Reach.clickPoints(pos, face, false)) {
						double d = eyes.distanceToSqr(p);
						if (d > r2 || d >= bestD) continue;
						// With a few degrees to spare: the look that gets there may come out a little off.
						float yaw = MathUtil.anglesTo(eyes, p)[0];
						if (!BlockRules.clickKeepsFacing(current, yaw - 5) || !BlockRules.clickKeepsFacing(current, yaw + 5)) continue;
						BlockHitResult hit = new BlockHitResult(p, face, pos, false);
						if (options.visibleFaces() && !Reach.canSee(hit)) continue;
						best = hit;
						bestD = d;
					}
				}
				return best;
			}

			/** The solved click's attempt, or a refused one saying why there's no click; null without a block item. */
			private Placement.@Nullable Attempt solved(JobContext ctx) {
				if (slot < 0 || slot > 8 || !(Client.player().getInventory().getItem(slot).getItem() instanceof BlockItem)) return null;
				Placement.Solution s = Myriad.placement().solve(pos, wanted, slot, options);
				if (!s.found()) return Placement.Attempt.refused(pos, s.check());
				return Myriad.placement().place(ctx.job(), s.click(), slot, options);
			}
		});
	}

	/**
	 * Right-clicks a block to use it (a lever, a repeater's delay, a composter), turned onto it first where the server
	 * checks; done once the server answered. As {@link #place(BlockPos, int, Placement.Options)} for what it's stuck on.
	 */
	public static Step<Void> use(BlockHitResult hit, int slot, Placement.Options options) {
		return Step.of("Use " + hit.getBlockPos().toShortString(), () -> new Attempting(hit.getBlockPos()) {
			@Override
			boolean already() {
				return false;
			}

			@Override
			Placement.Attempt attempt(JobContext ctx) {
				return Myriad.placement().use(ctx.job(), hit, slot, options);
			}
		});
	}

	/**
	 * Breaks the block at {@code pos} through the breaking service (tool, rotation, timing); done at once if there's
	 * nothing there. Stuck: {@code OUT_OF_REACH}, {@code UNBREAKABLE}. Failed: {@code SERVER_REFUSED},
	 * {@code UNSUPPORTED} (not in a world). To break with a chosen tool, select it first and pass
	 * {@code options.withAutoTool(false)}.
	 */
	public static Step<Void> breakBlock(BlockPos pos, int priority, Breaking.Options options) {
		return Step.of("Break " + pos.toShortString(), () -> new Step.Run<>() {
			private @Nullable CompletableFuture<Boolean> breaking;

			@Override
			public Progress<Void> tick(JobContext ctx) {
				if (breaking != null) {
					if (!breaking.isDone()) return Progress.running();
					if (breaking.join()) return Progress.done();
					return Progress.failed(Why.of(Why.SERVER_REFUSED, pos.toShortString(), pos));
				}
				Breaking.Attempt a = Myriad.breaking().breakBlock(ctx.job(), pos, priority, options);
				return switch (a.check()) {
					case OK -> {
						breaking = a.result();
						yield Progress.running();
					}
					case NOTHING_THERE -> Progress.done();
					case PENDING -> Progress.running();
					case UNBREAKABLE -> Progress.stuck(Why.of(Why.UNBREAKABLE, pos.toShortString(), pos));
					case OUT_OF_RANGE -> Progress.stuck(Why.of(Why.OUT_OF_REACH, pos.toShortString(), pos));
					case UNAVAILABLE -> Progress.failed(Why.of(Why.UNSUPPORTED, "not in a world"));
				};
			}
		});
	}

	// ---- looking and holding ------------------------------------------------------------------------------------

	/** Turns the server-side look onto {@code target} and is done once the rotation has gone out (asked for each tick until then). */
	public static Step<Void> lookAt(Vec3 target, Rotations.Options options) {
		return Step.of("Look", () -> new Turning() {
			@Override
			float[] angles() {
				return Myriad.rotations().anglesTo(target);
			}
		}.with(options));
	}

	public static Step<Void> face(float yaw, float pitch, Rotations.Options options) {
		return Step.of("Face", () -> new Turning() {
			@Override
			float[] angles() {
				return new float[]{yaw, pitch};
			}
		}.with(options));
	}

	/** Selects a hotbar slot (client and server) and is done. */
	public static Step<Void> selectSlot(int slot) {
		return Step.run("Select slot " + (slot + 1), () -> Myriad.inventory().select(slot));
	}

	/**
	 * Brings an item matching {@code what} into the hotbar (there already, or moved from the inventory when a click is
	 * safe) and produces its slot. Stuck: {@code NO_ITEM}, {@code NO_SLOT}; running while it waits for a still moment.
	 */
	public static Step<Integer> ensureInHotbar(Predicate<ItemStack> what, int preferredSlot) {
		return Step.of("Get it into the hotbar", () -> ctx -> {
			Inventory inv = Myriad.inventory();
			int slot = inv.findInHotbar(what);
			if (slot >= 0) return Progress.done(slot);
			if (inv.findInInventory(what) < 0) return Progress.stuck(Why.of(Why.NO_ITEM, ""));
			if (!inv.safeToClick()) {
				ctx.status("waiting for a moment to click");
				return Progress.running();
			}
			slot = inv.ensureInHotbar(what, preferredSlot);
			return slot >= 0 ? Progress.done(slot) : Progress.stuck(Why.of(Why.NO_SLOT, ""));
		});
	}

	/**
	 * Borrows the stack at inventory {@code index} (9-35) into the hotbar for the steps that follow and produces the
	 * hotbar slot; it goes back to that inventory slot (whatever is in the hotbar slot by then) when the enclosing step
	 * ends. Failed: {@code NO_ITEM} (that slot is empty).
	 */
	public static Step<Integer> borrow(int index) {
		return Step.of("Borrow slot " + index, () -> ctx -> {
			JobNode<?> holder = enclosing(ctx);
			if (Client.player().getInventory().getItem(index).isEmpty()) return Progress.failed(Why.of(Why.NO_ITEM, "slot " + index + " is empty"));
			Inventory inv = Myriad.inventory();
			int slot = inv.borrow(holder, index, s -> false);
			if (slot < 0) return Progress.running();
			// Asked for each tick the enclosing step runs, as the inventory wants; given back when it ends.
			holder.keep(() -> inv.borrow(holder, slot, s -> false));
			return Progress.done(slot);
		});
	}

	// ---- containers ---------------------------------------------------------------------------------------------

	/**
	 * Opens the container at {@code pos} (turned onto it first where the server checks) and produces its view once the
	 * contents arrived. Stuck: {@code OUT_OF_REACH} (tried again each tick). Failed: {@code TIMEOUT},
	 * {@code NO_CONTAINER}.
	 */
	public static Step<Containers.View> openContainer(BlockPos pos, int ticks, Placement.Options options) {
		return Step.of("Open " + pos.toShortString(), () -> new Step.Run<>() {
			private @Nullable CompletableFuture<Containers.View> opening;

			@Override
			public Progress<Containers.View> tick(JobContext ctx) {
				if (opening == null) opening = Myriad.containers().open(ctx.job(), pos, ticks, options);
				if (!opening.isDone()) return Progress.running();
				try {
					return Progress.done(opening.join());
				} catch (RuntimeException e) {
					opening = null;
					Throwable cause = e.getCause() == null ? e : e.getCause();
					if (cause instanceof IllegalStateException) return Progress.stuck(Why.of(Why.OUT_OF_REACH, pos.toShortString(), pos));
					if (cause instanceof TimeoutException) return Progress.failed(Why.of(Why.TIMEOUT, "the container at " + pos.toShortString() + " didn't open", pos));
					return Progress.failed(Why.of(Why.NO_CONTAINER, pos.toShortString(), pos));
				}
			}
		});
	}

	/** Closes the open container when the server takes a close (standing still, where Grim checks), and is done once it's closed. Failed: {@code TIMEOUT}. */
	public static Step<Void> closeContainer() {
		return Step.of("Close", () -> ctx -> {
			if (Myriad.containers().current().isEmpty()) return Progress.done();
			if (ctx.ticks() == 1) Myriad.containers().close();
			return ctx.ticks() > CLOSE_TICKS ? Progress.failed(Why.of(Why.TIMEOUT, "the container didn't close")) : Progress.running();
		});
	}

	/** Waits for the player to close {@code view} (they're browsing). Failed: {@code TIMEOUT}. */
	public static Step<Void> untilClosed(Containers.View view, int ticks) {
		return Step.until("Until closed", () -> !view.isOpen(), ticks);
	}

	/**
	 * Takes {@code what} from the container into the inventory; produces how many moved. Stuck: {@code NO_ROOM} (none
	 * moved though the container has some: your inventory is full; tried again every second). Failed:
	 * {@code NO_CONTAINER} (it closed).
	 */
	public static Step<Integer> take(Containers.View view, Predicate<ItemStack> what, Containers.Transfer how) {
		return Step.of("Take", () -> new Moving(view, how) {
			@Override
			CompletableFuture<Integer> move() {
				return view.take(what, how);
			}

			@Override
			boolean couldMoveMore() {
				return view.count(what) > 0;
			}
		});
	}

	/**
	 * Puts {@code what} from the inventory into the container; produces how many moved (0 when you have none). Stuck:
	 * {@code NO_ROOM} (the container is full). Failed: {@code NO_CONTAINER} (it closed).
	 */
	public static Step<Integer> put(Containers.View view, Predicate<ItemStack> what, Containers.Transfer how) {
		return Step.of("Put", () -> new Moving(view, how) {
			@Override
			CompletableFuture<Integer> move() {
				return view.put(what, how);
			}

			@Override
			boolean couldMoveMore() {
				return Myriad.inventory().count(what) > 0;
			}
		});
	}

	/** Makes the container hold an exact layout; produces whether it all went in. Failed: {@code NO_CONTAINER} (it closed). */
	public static Step<Boolean> arrange(Containers.View view, Containers.Arrangement arrangement) {
		return Step.of("Arrange", () -> new Step.Run<>() {
			private @Nullable CompletableFuture<Boolean> arranging;

			@Override
			public Progress<Boolean> tick(JobContext ctx) {
				if (arranging == null) arranging = view.arrange(arrangement);
				if (!arranging.isDone()) return Progress.running();
				try {
					return Progress.done(arranging.join());
				} catch (RuntimeException e) {
					return Progress.failed(view.isOpen() ? Why.of(e) : Why.of(Why.NO_CONTAINER, "it closed"));
				}
			}
		});
	}

	/**
	 * Works in the shulker box (or ender chest) at inventory index {@code index}: put down beside you, opened, handed to
	 * {@code work}, then closed, broken, picked up and put back in its slot, however {@code work} ends (cancelled
	 * too: putting it back is Myriad's own job, so it finishes anyway). Produces the work's result once the box is back.
	 * Failed: {@code NO_ITEM} (no box there, or no Silk Touch pickaxe for an ender chest), {@code NO_ROOM} (nowhere to
	 * put it down), {@code SERVER_REFUSED}, {@code TIMEOUT}; and whatever {@code work} fails with.
	 *
	 * <pre>{@code
	 * Steps.inItem(index, view -> Steps.untilClosed(view, 20 * 60 * 5))   // let the player browse it
	 * }</pre>
	 */
	public static <T> Step<T> inItem(int index, Function<Containers.View, ? extends Step<T>> work) {
		Step<Containers.ItemSession> out = Step.of("Getting it out", () -> new Step.Run<>() {
			private @Nullable CompletableFuture<Containers.ItemSession> opening;

			@Override
			public void start(JobContext ctx) {
				opening = Myriad.containers().openItem(ctx.job(), index, OPEN_BOX_TICKS, Placement.Options.forServer());
			}

			@Override
			public Progress<Containers.ItemSession> tick(JobContext ctx) {
				if (!opening.isDone()) return Progress.running();
				try {
					return Progress.done(opening.join());
				} catch (RuntimeException e) {
					return Progress.failed(Why.of(e));
				}
			}
		});
		return out.then(session -> {
			Step<T> inside = work.apply(session.view());
			return inside.finallyDo(session::finish)
				.then(result -> Step.await("Putting it back", session::finish, PUT_BACK_TICKS).then(Step.supply("Back", () -> result)))
				// The work failed: the box still goes back first, then the failure stands.
				.recover(why -> Step.await("Putting it back", session::finish, PUT_BACK_TICKS).then(Step.<T>fail(why)));
		}).named("Open box");
	}

	// ---- building -----------------------------------------------------------------------------------------------

	/**
	 * Builds {@code blueprint} through the building service and is done when it's complete. Stuck when nothing in reach
	 * can go on, with the commonest reason and how many positions share it ("no item: ×12 cobblestone"); the reason's
	 * subject is the first such position. Never fails on its own (add {@code stuckFor} or {@code timeout}), except
	 * {@code CANCELLED} when the build is stopped from elsewhere.
	 */
	public static Step<Void> build(Blueprint blueprint, Building.Options options) {
		return Step.of("Build", () -> new Builds(blueprint, options));
	}

	/** Keeps {@code blueprint} as it is (a surround): never done; cancel it. Stuck as {@link #build}. */
	public static Step<Void> keepUp(Blueprint blueprint, Building.Options options) {
		return Step.of("Keep up", () -> new Builds(blueprint, options.withKeepUp(true)));
	}

	// ---- modules ------------------------------------------------------------------------------------------------

	/** Holds these modules off until the enclosing step ends (the same as {@code Step.suspending}, as a step). */
	public static Step<Void> suspend(MyriadId... modules) {
		List<MyriadId> ids = List.of(modules);
		return Step.of("Hold off", () -> ctx -> {
			JobNode<?> holder = enclosing(ctx);
			holder.suspends = true;
			for (MyriadId id : ids) Modules.suspend(holder, id);
			return Progress.done();
		});
	}

	@SafeVarargs
	public static Step<Void> suspend(Class<? extends Module>... modules) {
		List<Class<? extends Module>> types = List.of(modules);
		return Step.of("Hold off", () -> ctx -> {
			JobNode<?> holder = enclosing(ctx);
			holder.suspends = true;
			for (Class<? extends Module> t : types) Modules.suspend(holder, t);
			return Progress.done();
		});
	}

	// ---- shared -------------------------------------------------------------------------------------------------

	/** The job of the step this one is in (its parent), whose end gives back what this one took. */
	private static JobNode<?> enclosing(JobContext ctx) {
		Job<?> j = ctx.job();
		return (JobNode<?>) (j.parent() != null ? j.parent() : j);
	}

	/** Whether {@code pos} already holds the block the item in hotbar {@code slot} places. */
	private static boolean holds(BlockPos pos, int slot) {
		if (slot < 0 || slot > 8) return false;
		ItemStack stack = Client.player().getInventory().getItem(slot);
		return stack.getItem() instanceof BlockItem b && Client.level().getBlockState(pos).is(b.getBlock());
	}

	/** A placement or click: asks until it's sent, then waits for the server's answer. */
	private abstract static class Attempting implements Step.Run<Void> {
		private final BlockPos pos;
		private @Nullable CompletableFuture<Boolean> sent;

		Attempting(BlockPos pos) {
			this.pos = pos;
		}

		abstract boolean already();

		abstract Placement.Attempt attempt(JobContext ctx);

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (sent != null) {
				if (!sent.isDone()) return Progress.running();
				if (sent.join() || already()) return Progress.done();
				return Progress.failed(Why.of(Why.SERVER_REFUSED, pos.toShortString(), pos));
			}
			if (already()) return Progress.done();
			Placement.Attempt a = attempt(ctx);
			if (a.sent()) {
				sent = a.result();
				return Progress.running();
			}
			Why why = Why.of(a.check(), pos);
			if (why == null) return Progress.running();
			return why.is(Why.UNSUPPORTED) ? Progress.failed(why) : Progress.stuck(why);
		}
	}

	/** A turn asked for each tick until the rotation went out. */
	private abstract static class Turning implements Step.Run<Void> {
		private Rotations.Options options = Rotations.Options.INSTANT;
		private boolean sent;

		abstract float[] angles();

		Turning with(Rotations.Options options) {
			this.options = options;
			return this;
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (sent) return Progress.done();
			float[] r = angles();
			Myriad.rotations().request(ctx.job(), r[0], r[1], Rotations.PRIORITY_NORMAL, options, () -> sent = true);
			return Progress.running();
		}
	}

	/** A take or put: once, then again every second while it moves nothing though it could. */
	private abstract static class Moving implements Step.Run<Integer> {
		private final Containers.View view;
		private final Containers.Transfer how;
		private @Nullable CompletableFuture<Integer> moving;
		private int waitUntil;

		Moving(Containers.View view, Containers.Transfer how) {
			this.view = view;
			this.how = how;
		}

		abstract CompletableFuture<Integer> move();

		abstract boolean couldMoveMore();

		@Override
		public Progress<Integer> tick(JobContext ctx) {
			if (how.count() == 0) return Progress.done(0);
			if (!view.isOpen()) return Progress.failed(Why.of(Why.NO_CONTAINER, "it closed"));
			if (moving == null) {
				if (ctx.ticks() < waitUntil) return Progress.stuck(Why.of(Why.NO_ROOM, ""));
				moving = move();
			}
			if (!moving.isDone()) return Progress.running();
			int moved;
			try {
				moved = moving.join();
			} catch (RuntimeException e) {
				return Progress.failed(view.isOpen() ? Why.of(e) : Why.of(Why.NO_CONTAINER, "it closed"));
			}
			moving = null;
			if (moved > 0 || !couldMoveMore()) return Progress.done(moved);
			waitUntil = ctx.ticks() + ROOM_RETRY_TICKS;
			return Progress.stuck(Why.of(Why.NO_ROOM, ""));
		}
	}

	/** A build through the service: done when the blueprint is, stuck on the commonest reason in reach. */
	static final class Builds implements Step.Run<Void> {
		private final Blueprint blueprint;
		private final Building.Options options;
		private @Nullable Build build;
		private int total;

		Builds(Blueprint blueprint, Building.Options options) {
			this.blueprint = blueprint;
			this.options = options;
		}

		@Override
		public void start(JobContext ctx) {
			build = Myriad.building().start(ctx.job(), blueprint, options);
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (build.finished().isDone()) {
				return build.finished().join() ? Progress.done() : Progress.failed(Why.of(Why.CANCELLED, "the build was stopped"));
			}
			if (!options.keepUp() && build.isDone()) return Progress.done();
			int left = build.remaining();
			if (left >= 0) {
				total = Math.max(total, left);
				ctx.progress(total - left, total);
			}
			return stuckOrRunning(build.steps(), left);
		}

		@Override
		public void cancel(JobContext ctx) {
			if (build != null) build.stop();
		}

		@Override
		public @Nullable String status() {
			Build b = build;
			return b == null || b.remaining() < 0 ? null : b.remaining() + " left";
		}

		static Progress<Void> stuckOrRunning(List<Build.Step> steps, int left) {
			if (steps.isEmpty()) {
				return left > 0 ? Progress.stuck(Why.of(Why.OUT_OF_REACH, left + " left, none in reach")) : Progress.running();
			}
			Map<Why.Code, Integer> counts = new HashMap<>();
			Map<Why.Code, Build.Step> first = new HashMap<>();
			for (Build.Step s : steps) {
				Why w = Why.of(s.status(), s.pos());
				if (w == null) return Progress.running();
				counts.merge(w.code(), 1, Integer::sum);
				first.putIfAbsent(w.code(), s);
			}
			Why.Code top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
			Build.Step s = first.get(top);
			String what = top.equals(Why.NO_ITEM) ? " " + s.target().describe() : "";
			return Progress.stuck(Why.of(top, "×" + counts.get(top) + what, s.pos()));
		}
	}
}
