package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobContext;
import dev.myriadclient.api.job.Progress;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Steps;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.Client;
import dev.myriadclient.api.util.ItemInfo;
import dev.myriadclient.api.util.Reach;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Shulker boxes and ender chests from the inventory ({@link Containers#openItem}): put down above your head (else beside
 * you), opened in the same tick, and later broken, picked up and put back in their slot. Both halves run as jobs made
 * of the services' steps. Getting it out is the owner's job; putting it back is Myriad's own, so a box is never left in
 * the world because a module turned off or its work failed.
 */
final class ItemBoxes {
	private static final int PLACE_TICKS = 60, GIVE_BACK_TICKS = 10, BREAK_TICKS = 20 * 10, PICKUP_TICKS = 60, RETURN_TICKS = 20;
	/** Times the opening click is made again when it didn't go (no look onto the box in time), the box being there. */
	private static final int OPEN_TRIES = 3;
	/** How long it may wait to look at its spot before bringing a box from the inventory into the hotbar anyway. */
	private static final int LOOK_TICKS = 3;
	/** Ticks to wait for the box to be picked up where it fell before stepping towards it. */
	private static final int WALK_AFTER_TICKS = 12;
	/** Owns the put-back jobs: they finish whatever happens to whoever opened the box. */
	private static final Object PUT_BACK = new Object() {
		@Override
		public String toString() {
			return "Myriad";
		}
	};

	private ItemBoxes() {
	}

	static CompletableFuture<Containers.ItemSession> open(Object owner, int index, int ticks, Placement.Options options) {
		Session s = new Session(index, ticks, options);
		Job<Containers.View> job = Myriad.jobs().start(owner, s.getOut());
		// Asked for by a job: the box work ends with it (the put-back still runs, being Myriad's).
		if (owner instanceof Job<?> parent) parent.result().whenComplete((r, t) -> {
			if (job.state().isActive()) job.cancel();
		});
		return job.result().thenApply(view -> s);
	}

	private static final class Session implements Containers.ItemSession {
		private final int index, ticks;
		private final Placement.Options options;
		private ItemStack box = ItemStack.EMPTY;
		private boolean ender;
		/** The hotbar slot it's placed from: its own, or the one it was brought into from the inventory. */
		private int from = -1;
		private @Nullable Spot spot;
		private @Nullable Containers.View view;
		private boolean placed;
		private @Nullable CompletableFuture<Void> back;

		Session(int index, int ticks, Placement.Options options) {
			this.index = index;
			this.ticks = ticks;
			this.options = options;
		}

		@Override
		public Containers.View view() {
			return view;
		}

		@Override
		public BlockPos where() {
			return spot == null ? null : spot.pos();
		}

		@Override
		public synchronized CompletableFuture<Void> finish() {
			if (back == null) back = Myriad.jobs().start(PUT_BACK, putBack()).result();
			return back;
		}

		// ---- getting it out ---------------------------------------------------------------------------------------

		Step<Containers.View> getOut() {
			Step<Void> check = Step.of("Checking", () -> ctx -> {
				box = Client.player().getInventory().getItem(index).copy();
				ender = box.is(Items.ENDER_CHEST);
				if (!ender && !ItemInfo.isShulkerBox(box)) return Progress.failed(Why.of(Why.NO_ITEM, "no shulker box or ender chest in slot " + index));
				if (ender && Myriad.inventory().count(ItemBoxes::silkTouch) == 0)
					return Progress.failed(Why.of(Why.NO_ITEM, "a Silk Touch pickaxe, to pick the ender chest back up"));
				spot = spot(options, ender);
				if (spot == null) return Progress.failed(Why.of(Why.NO_ROOM, "nowhere above or beside you to put it down"));
				// Placing with your inventory open would click it, not the floor.
				if (Minecraft.getInstance().gui.screen() instanceof InventoryScreen) Client.player().closeContainer();
				return Progress.done();
			});
			// From the inventory, into the hotbar: placed from there in the same tick (the server has the click first).
			Step<Void> toHotbar = Step.of("Into the hotbar", () -> ctx -> {
				if (index < 9) {
					from = index;
					return Progress.done();
				}
				// Where looks are checked, the placement turns first and goes the tick after; a slot a click changed may
				// be placed from only in the tick of the click (or a round trip on). So it looks first, then pulls.
				if (options.rotate() && ctx.ticks() < LOOK_TICKS) {
					float[] look = Myriad.rotations().anglesTo(spot.click().getLocation());
					if (!Myriad.rotations().isFacing(look[0], look[1], 1)) {
						Myriad.rotations().request(ctx.job(), look[0], look[1], Rotations.PRIORITY_HIGH, Rotations.Options.MOVE_FIX, null);
						return Progress.running();
					}
				}
				int to = Myriad.inventory().pullToHotbar(index, s -> true);
				if (to >= 0) {
					from = to;
					return Progress.done();
				}
				return ctx.ticks() > PLACE_TICKS ? Progress.failed(Why.of(Why.TIMEOUT, "couldn't bring it into the hotbar")) : Progress.running();
			});
			Step<Containers.View> opened = Step.of("Putting it down", PutDown::new).peek(v -> view = v);
			// What made room for it in the hotbar goes back to the inventory, through the box's own screen (it shows your
			// inventory too) rather than before it opens.
			Step<Void> givenBack = Step.of("Giving back", () -> ctx -> {
				var inv = Client.player().getInventory();
				if (index < 9 || !view.isOpen() || inv.getItem(index).isEmpty() && inv.getItem(from).isEmpty()) return Progress.done();
				if (!Myriad.inventory().prepareClick()) return ctx.ticks() > GIVE_BACK_TICKS ? Progress.done() : Progress.running();
				view.swapWithHotbar(view.playerSlot(index), from);
				return Progress.done();
			});
			return check.then(toHotbar).then(opened).then(v -> givenBack.then(Step.supply("Open", () -> v)))
				.named(() -> ender ? "Opening an ender chest" : "Opening a shulker box")
				// Put down but not opened (a failure, the owner turning off): it mustn't be left behind.
				.finallyDo(() -> {
					if (placed && view == null) finish();
				});
		}

		/**
		 * Puts the box down and opens it in the same tick: the opening click follows the placement with the same look (one
		 * straight up for a box above your head), and the server takes them in that order, so it opens a round trip
		 * sooner than waiting for the placement's answer would.
		 */
		private final class PutDown implements Step.Run<Containers.View> {
			private @Nullable CompletableFuture<Boolean> placing;
			private @Nullable CompletableFuture<Containers.View> opening;
			private int tries;

			@Override
			public Progress<Containers.View> tick(JobContext ctx) {
				BlockPos pos = spot.pos();
				if (placing == null) {
					Placement.Attempt a = Myriad.placement().place(ctx.job(), spot.click(), from, options.withSneak(spot.sneak()));
					if (!a.sent()) {
						if (ctx.ticks() > PLACE_TICKS) return Progress.failed(Why.of(Why.TIMEOUT, "couldn't put it down", pos));
						Why why = Why.of(a.check(), pos);
						return why == null ? Progress.running() : why.is(Why.UNSUPPORTED) ? Progress.failed(why) : Progress.stuck(why);
					}
					placing = a.result();
					placed = true;
				}
				if (placing.isDone() && !placing.join() && !Client.level().getBlockState(pos).is(Block.byItem(box.getItem()))) {
					placed = false;
					return Progress.failed(Why.of(Why.SERVER_REFUSED, pos.toShortString(), pos));
				}
				if (opening == null) opening = Myriad.containers().open(ctx.job(), pos, ticks, options);
				if (!opening.isDone()) return Progress.running();
				try {
					return Progress.done(opening.join());
				} catch (RuntimeException e) {
					opening = null;
					Throwable cause = e.getCause() == null ? e : e.getCause();
					if (cause instanceof TimeoutException) return Progress.failed(Why.of(Why.TIMEOUT, "it didn't open", pos));
					// The click didn't go (no look onto the box in time): again, now that the box is there.
					return ++tries < OPEN_TRIES ? Progress.running() : Progress.failed(Why.of(Why.NO_CONTAINER, pos.toShortString(), pos));
				}
			}
		}

		// ---- putting it back --------------------------------------------------------------------------------------

		Step<Void> putBack() {
			ItemStack[] before = new ItemStack[36];
			int[] held = {0};
			Step<Void> close = Steps.closeContainer().recover(why -> Step.nothing());
			Step<Void> breakIt = Step.run("Before", () -> {
				LocalPlayer p = Client.player();
				for (int i = 0; i < 36; i++) before[i] = p.getInventory().getItem(i).copy();
				held[0] = count(box);
				// The Silk Touch pickaxe, not the fastest tool: an ender chest breaks to obsidian otherwise. Every other
				// tool for it is spared, so the break's own tool choice takes that one, from the inventory too.
				if (ender) Myriad.inventory().spare(PUT_BACK, s -> s.getDestroySpeed(Blocks.ENDER_CHEST.defaultBlockState()) > 1 && !silkTouch(s));
			}).then(Steps.breakBlock(spot.pos(), 10, Breaking.Options.forServer()).timeout(BREAK_TICKS))
				.finallyDo(() -> Myriad.inventory().spare(PUT_BACK, null));
			Step<Void> pickUp = Step.of("Picking it up", () -> new PickUp(box, spot.pos(), held[0]));
			Step<Void> home = Step.of("Back to its slot", () -> ctx -> {
				LocalPlayer p = Client.player();
				if (!p.getInventory().getItem(index).isEmpty() || p.containerMenu != p.inventoryMenu) return Progress.done();
				int landed = newest(before);
				if (landed < 0 || landed == index) return Progress.done();
				if (Myriad.inventory().prepareClick() && Myriad.inventory().move(landed, index)) return Progress.done();
				return ctx.ticks() > RETURN_TICKS ? Progress.done() : Progress.running();
			});
			return close.then(breakIt).then(pickUp).then(home).named(() -> ender ? "Putting the ender chest back" : "Putting the shulker box back");
		}

		/** The slot the box landed in: one holding its item now that didn't before the break. */
		private int newest(ItemStack[] before) {
			var inv = Client.player().getInventory();
			for (int i = 0; i < 36; i++) if (inv.getItem(i).is(box.getItem()) && (before[i] == null || !ItemStack.matches(inv.getItem(i), before[i]))) return i;
			return -1;
		}
	}

	/** Waits for the box to come back into the inventory, stepping towards it if it bounced out of reach. */
	private static final class PickUp implements Step.Run<Void> {
		private final ItemStack box;
		private final BlockPos spot;
		/** How many of its item the inventory held before the break, with the box itself still in the world. */
		private final int before;
		private @Nullable Vec3 walkTo;
		private boolean walking;

		PickUp(ItemStack box, BlockPos spot, int before) {
			this.box = box;
			this.spot = spot;
			this.before = before;
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (count(box) > before) return done();
			walkTo = null;
			if (ctx.ticks() > PICKUP_TICKS) {
				done();
				return Progress.failed(Why.of(Why.TIMEOUT, "couldn't pick it back up: it's on the ground at " + spot.toShortString(), spot));
			}
			if (ctx.ticks() > WALK_AFTER_TICKS) {
				Level level = Client.level();
				level.getEntitiesOfClass(ItemEntity.class, new AABB(spot).inflate(4), i -> i.getItem().is(box.getItem())).stream()
					.min(Comparator.comparingDouble(i -> i.distanceToSqr(Client.player()))).ifPresent(i -> walkTo = i.position());
				if (walkTo != null && !walking) {
					walking = true;
					Myriad.events().subscribe(this);
				}
			}
			return Progress.running();
		}

		@Override
		public void cancel(JobContext ctx) {
			done();
		}

		private Progress<Void> done() {
			if (walking) {
				walking = false;
				Myriad.events().unsubscribe(this);
			}
			return Progress.done();
		}

		/** Steps (sneaking, so no edge is walked off) towards the box where it fell. */
		@Subscribe(inGame = true)
		private void onInput(InputEvent e) {
			if (walkTo == null) return;
			LocalPlayer p = Client.player();
			double dx = walkTo.x - p.getX(), dz = walkTo.z - p.getZ();
			if (dx * dx + dz * dz < 0.04) return;
			float rel = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - p.getYRot());
			e.forward = Math.abs(rel) < 67.5f;
			e.backward = Math.abs(rel) > 112.5f;
			e.left = rel < -22.5f && rel > -157.5f;
			e.right = rel > 22.5f && rel < 157.5f;
			e.sneak = true;
			e.sprint = false;
			e.jump = false;
		}
	}

	private static boolean silkTouch(ItemStack s) {
		return s.is(ItemTags.PICKAXES) && ItemInfo.enchantmentLevel(s, Enchantments.SILK_TOUCH) > 0;
	}

	/** How many of the box's item (its colour, or ender chests) the inventory holds. */
	private static int count(ItemStack box) {
		int n = 0;
		var inv = Client.player().getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(box.getItem())) n += inv.getItem(i).getCount();
		return n;
	}

	/** Where a box goes and the click that puts it there, sneaking where the clicked block would open instead. */
	private record Spot(BlockPos pos, BlockHitResult click, boolean sneak) {
	}

	private static @Nullable Spot spot(Placement.Options options, boolean ender) {
		Spot above = above(options, ender);
		return above != null ? above : beside(options);
	}

	/**
	 * Above your head, so it falls back on you when it's broken, and placing and opening it share one look straight up:
	 * into the empty space itself where air placement is on, else against the underside of the block over it. A shulker
	 * box then faces down, so it goes a block higher where there's room: its lid opens into the space below it, and
	 * one opening into the space your head is in pushes you down. An ender chest stays shut under a solid block, so it
	 * only goes there by air placement.
	 */
	private static @Nullable Spot above(Placement.Options options, boolean ender) {
		BlockPos head = BlockPos.containing(Client.player().getX(), Math.ceil(Client.player().getBoundingBox().maxY), Client.player().getZ());
		Spot higher = above(options, ender, head.above());
		return higher != null ? higher : above(options, ender, head);
	}

	private static @Nullable Spot above(Placement.Options options, boolean ender, BlockPos pos) {
		LocalPlayer p = Client.player();
		Level level = Client.level();
		BlockPos under = pos.below(), over = pos.above();
		if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(under).getCollisionShape(level, under).isEmpty()) return null;
		if (p.getBoundingBox().intersects(new AABB(pos)) || !Myriad.placement().check(pos, options).ok()) return null;
		BlockState overState = level.getBlockState(over);
		if (ender && overState.isRedstoneConductor(level, over)) return null;
		// Straight above your eyes, so the look is straight up.
		Vec3 eyes = p.getEyePosition();
		double x = Math.clamp(eyes.x, pos.getX() + 0.05, pos.getX() + 0.95), z = Math.clamp(eyes.z, pos.getZ() + 0.05, pos.getZ() + 0.95);
		if (options.airPlace() && level.getBlockState(pos).isAir()) return new Spot(pos, new BlockHitResult(new Vec3(x, pos.getY(), z), Direction.DOWN, pos, false), false);
		if (ender || !overState.isFaceSturdy(level, over, Direction.DOWN) || BlockInfo.isClickable(overState)) return null;
		return new Spot(pos, new BlockHitResult(new Vec3(x, over.getY(), z), Direction.DOWN, over, false), false);
	}

	/**
	 * Beside your feet: a free block with a floor to stand on and room above for the lid, clear of you, in reach and
	 * placeable, nearest the way you face first. Placed on the floor's top, so it faces up.
	 */
	private static @Nullable Spot beside(Placement.Options options) {
		LocalPlayer p = Client.player();
		Level level = Client.level();
		BlockPos feet = p.blockPosition();
		Direction facing = p.getDirection();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos pos = feet.offset(dx, 0, dz);
				if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) continue;
				if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
				if (p.getBoundingBox().intersects(new AABB(pos)) || !Reach.canReach(pos)) continue;
				if (!Myriad.placement().check(pos, options).ok()) continue;
				double score = (dx * dx + dz * dz) - 0.5 * (dx * facing.getStepX() + dz * facing.getStepZ());
				if (score < bestScore) {
					bestScore = score;
					best = pos;
				}
			}
		}
		if (best == null) return null;
		BlockPos floor = best.below();
		// Sneaking only where the floor would open or be used instead (a chest under it).
		return new Spot(best, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false), BlockInfo.isClickable(level.getBlockState(floor)));
	}
}
