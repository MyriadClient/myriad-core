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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/**
 * Shulker boxes and ender chests from the inventory ({@link Containers#openItem}): put down beside you, opened, and
 * later broken, picked up and put back in their slot. Both halves run as jobs made of the services' steps. Getting it
 * out is the owner's job; putting it back is Myriad's own, so a box is never left in the world because a module turned
 * off or its work failed.
 */
final class ItemBoxes {
	private static final int PLACE_TICKS = 60, GIVE_BACK_TICKS = 10, BREAK_TICKS = 20 * 10, PICKUP_TICKS = 60, RETURN_TICKS = 20;
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
		private int silk = -1;
		private @Nullable BlockPos spot;
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
			return spot;
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
				if (ender) {
					silk = Myriad.inventory().findInHotbar(s -> s.is(ItemTags.PICKAXES) && ItemInfo.enchantmentLevel(s, Enchantments.SILK_TOUCH) > 0);
					if (silk < 0) return Progress.failed(Why.of(Why.NO_ITEM, "a Silk Touch pickaxe in the hotbar, to pick the ender chest back up"));
				}
				spot = spot(options);
				if (spot == null) return Progress.failed(Why.of(Why.NO_ROOM, "nowhere beside you to put it down"));
				// Placing with your inventory open would click it, not the floor.
				if (Minecraft.getInstance().gui.screen() instanceof InventoryScreen) Client.player().closeContainer();
				return Progress.done();
			});
			Step<Void> place = Step.choose("Putting it down", () -> {
				Placement.Options sneaking = options.withSneak(true);
				// From the hotbar, or borrowed into it for the placement and given back after.
				Step<Void> down = index < 9 ? Steps.place(floorClick(spot), index, sneaking)
					: Steps.borrow(index).then(slot -> Steps.place(floorClick(spot), slot, sneaking));
				return down.timeout(PLACE_TICKS).then(Step.run("Placed", () -> placed = true));
			});
			// Whatever made room for it in the hotbar goes back first: core doesn't click in a container a module opened.
			Step<Void> givenBack = Step.until("Giving back", () -> Client.player().getInventory().getItem(index).isEmpty(), GIVE_BACK_TICKS)
				.recover(why -> Step.nothing());
			Step<Containers.View> opened = Step.choose("Opening", () -> Steps.openContainer(spot, ticks, options))
				.peek(v -> view = v);
			return check.then(place).then(givenBack).then(opened)
				.named(() -> ender ? "Opening an ender chest" : "Opening a shulker box")
				// Put down but not opened (a failure, the owner turning off): it mustn't be left behind.
				.finallyDo(() -> {
					if (placed && view == null) finish();
				});
		}

		// ---- putting it back --------------------------------------------------------------------------------------

		Step<Void> putBack() {
			ItemStack[] before = new ItemStack[36];
			int[] selected = {-1}, held = {0};
			Step<Void> close = Steps.closeContainer().recover(why -> Step.nothing());
			Step<Void> breakIt = Step.run("Before", () -> {
				LocalPlayer p = Client.player();
				for (int i = 0; i < 36; i++) before[i] = p.getInventory().getItem(i).copy();
				held[0] = count(box);
				// The Silk Touch pickaxe, not the fastest tool: an ender chest breaks to obsidian otherwise.
				if (ender) {
					selected[0] = p.getInventory().getSelectedSlot();
					Myriad.inventory().select(silk);
				}
			}).then(Steps.breakBlock(spot, 10, ender ? Breaking.Options.forServer().withAutoTool(false) : Breaking.Options.forServer()).timeout(BREAK_TICKS))
				.finallyDo(() -> {
					if (selected[0] >= 0 && Client.inGame()) Myriad.inventory().select(selected[0]);
				});
			Step<Void> pickUp = Step.of("Picking it up", () -> new PickUp(box, spot, held[0]));
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

	/** How many of the box's item (its colour, or ender chests) the inventory holds. */
	private static int count(ItemStack box) {
		int n = 0;
		var inv = Client.player().getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(box.getItem())) n += inv.getItem(i).getCount();
		return n;
	}

	/**
	 * Where a box goes: a free block beside your feet with a floor to stand on and room above for the lid, clear of you,
	 * in reach and placeable, nearest the way you face first.
	 */
	private static @Nullable BlockPos spot(Placement.Options options) {
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
		return best;
	}

	/** A click on the top of the block under {@code spot}, so the box faces up and its lid opens into the air above. */
	private static BlockHitResult floorClick(BlockPos spot) {
		BlockPos floor = spot.below();
		return new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
	}
}
