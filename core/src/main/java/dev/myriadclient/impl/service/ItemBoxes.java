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
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Shulker boxes and ender chests from the inventory or an open container ({@link Containers#openItem}): put down above
 * your head (else beside you), opened in the same tick, and later broken, picked up and put back where they came from
 * (the container opened again for one from it, into any free slot there; one from your inventory stays where it lands). Both halves run as jobs made
 * of the services' steps. Getting it out is the owner's job; putting it back is Myriad's own, so a box is never left in
 * the world because a module turned off or its work failed.
 */
final class ItemBoxes {
	private static final int PLACE_TICKS = 60, GIVE_BACK_TICKS = 10, BREAK_TICKS = 20 * 10, PICKUP_TICKS = 60, RETURN_TICKS = 20;
	/** Times the opening click is made again when it didn't go (no look onto the box in time), the box being there. */
	private static final int OPEN_TRIES = 3;
	/** Ticks putting it back waits for the container it came from to be in reach again. */
	private static final int REOPEN_TICKS = 40;
	/** How long it may wait to look at its spot before bringing a box from the inventory into the hotbar anyway. */
	private static final int LOOK_TICKS = 3;
	/** Ticks to wait for the box to be picked up where it fell before stepping towards it, wherever it is. */
	private static final int WALK_AFTER_TICKS = 12;
	/** How far down it steps off an edge to a box that fell lower: a fall that doesn't hurt. */
	private static final double SAFE_DROP = 3;
		/** Ticks putting boxes back walks for to get the container they came from in reach again. */
	private static final int BACK_TICKS = 100;
	/** How far away (blocks) the container may be to be walked back to. */
	private static final double WALK_BACK = 10;
	/** Ticks a dropped block can't be picked up for (vanilla's default pick-up delay). */
	private static final int PICKUP_DELAY = 10;
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
		return start(owner, new Session(index, ticks, options, null, -1, true));
	}

	static CompletableFuture<Containers.ItemSession> open(Object owner, Containers.View from, int screenSlot, int ticks, Placement.Options options) {
		return start(owner, session(from, screenSlot, ticks, options, true));
	}

	static CompletableFuture<Containers.ItemSession> putDown(Object owner, int index, int ticks, Placement.Options options) {
		return start(owner, new Session(index, ticks, options, null, -1, false));
	}

	static CompletableFuture<Containers.ItemSession> putDown(Object owner, Containers.View from, int screenSlot, int ticks, Placement.Options options) {
		return start(owner, session(from, screenSlot, ticks, options, false));
	}

	private static Session session(Containers.View from, int screenSlot, int ticks, Placement.Options options, boolean opens) {
		var slots = from.handler().slots;
		Slot slot = screenSlot >= 0 && screenSlot < slots.size() ? slots.get(screenSlot) : null;
		boolean yours = slot != null && slot.container == Client.player().getInventory();
		int own = slot != null && !yours && screenSlot < from.size() ? screenSlot : -1;
		return new Session(yours ? slot.getContainerSlot() : -1, ticks, options, from, own, opens);
	}

	/** {@code box} was opened ({@code view}), after it was put down: what it holds is known by that (finishAll). */
	static void seen(Containers.ItemSession box, Containers.View view) {
		if (box instanceof Session b) b.view = view;
	}

	private static CompletableFuture<Containers.ItemSession> start(Object owner, Session s) {
		s.noWayBack = MyriadImpl.get().containerTracker().noWayBackOf(owner);
		Job<Containers.View> job = Myriad.jobs().start(owner, s.getOut());
		// Asked for by a job: the box work ends with it (the put-back still runs, being Myriad's).
		if (owner instanceof Job<?> parent) parent.result().whenComplete((r, t) -> {
			if (job.state().isActive()) job.cancel();
		});
		return job.result().thenApply(view -> s);
	}

	private static final class Session implements Containers.ItemSession {
		/** Its inventory index, or -1 when it's in {@link #origin}'s own slot {@link #own}. */
		private final int index, ticks;
		private final Placement.Options options;
		/** The container open when it was asked for from one, opened again once it's back; and the slot there it came from, or -1. */
		private final @Nullable Containers.View origin;
		private final int own;
		private ItemStack box = ItemStack.EMPTY;
		private boolean ender;
		/** The hotbar slot it's placed from: its own, or the one it was brought into from the inventory. */
		private int from = -1;
		/**
		 * With the hotbar full, the empty inventory slot what made room for a box from a container waits in, or -1; with
		 * the inventory full too, it waits in the box's own slot in the container ({@code inPlace}).
		 */
		private int parked = -1;
		private boolean inPlace;
		private @Nullable Spot spot;
		private @Nullable Containers.View view;
		private boolean placed;
		/** Handed to the swap bar (finishWhenClosed), which closes everything once it's back. */
		private boolean handedOver;
		private @Nullable CompletableFuture<Void> back;
		/** The inventory before the break, and how many of its item it holds once it's back; where it landed; whether it was broken. */
		private ItemStack[] before = new ItemStack[36];
		private int countBack;
		private int landed = -1;
		private boolean broken;
		/** Put back with others (finishAll), which set what the inventory held before them all. */
		private @Nullable Together together;
		/** Opened once it's down (openItem), or only put down (putDownItem), the screen up staying up; and whether that's done. */
		private final boolean opens;
		private boolean out;
		/** What it does if the container it came from can't be opened again (Containers.noWayBack); and whether that's an ender chest. */
		private Containers.NoWayBack noWayBack = Containers.NoWayBack.REFUSE;
		private boolean enderOrigin;

		Session(int index, int ticks, Placement.Options options, @Nullable Containers.View origin, int own, boolean opens) {
			this.index = index;
			this.ticks = ticks;
			this.options = options;
			this.origin = origin;
			this.own = own;
			this.opens = opens;
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
		public void finishWhenClosed() {
			handedOver = true;
			MyriadImpl.get().containerTabs().add(this);
		}

		@Override
		public synchronized CompletableFuture<Void> finish() {
			if (back == null) back = Myriad.jobs().start(PUT_BACK, putBack()).result();
			return back;
		}

		// ---- getting it out ---------------------------------------------------------------------------------------

		Step<Containers.View> getOut() {
			Step<Void> check = Step.of("Checking", () -> ctx -> {
				if (origin != null && (!origin.isOpen() || origin.pos() == null))
					return Progress.failed(Why.of(Why.UNSUPPORTED, "the container isn't open, or wasn't opened from a block to open again"));
				// One in a container held far away goes back through another ender chest, or as noWayBack says.
				enderOrigin = origin != null && MyriadImpl.get().containerTracker().isEnderChest(origin);
				if (own >= 0 && Reach.hitFor(origin.pos(), false, options.range()) == null && !wayBackLater())
					return Progress.failed(Why.of(Why.OUT_OF_REACH, "the container it's in is out of reach, to put it back", origin.pos()));
				box = (own >= 0 ? origin.stack(own) : index >= 0 ? Client.player().getInventory().getItem(index) : ItemStack.EMPTY).copy();
				ender = box.is(Items.ENDER_CHEST);
				if (!ender && !ItemInfo.isShulkerBox(box)) return Progress.failed(Why.of(Why.NO_ITEM, "no shulker box or ender chest in slot " + index));
				if (ender && Myriad.inventory().count(ItemBoxes::silkTouch) == 0)
					return Progress.failed(Why.of(Why.NO_ITEM, "a Silk Touch pickaxe, to pick the ender chest back up"));
				spot = spot(options, ender);
				if (spot == null) return Progress.failed(Why.of(Why.NO_ROOM, "nowhere above or beside you to put it down"));
				// Placing with your inventory open would click it, not the floor. A container's screen stays up while a box
				// from its slots is put down and opened over it (the server closes the container as it opens the box), so no
				// frame of the world shows between the two; for one from your inventory below them, the container's closed
				// first (your inventory's clicks bring it to the hotbar), at a moment the server takes it (MultiActionsD).
				// Only put down, the screen up stays up.
				if (!opens) return Progress.done();
				if (origin != null && own < 0) {
					if (!Myriad.inventory().prepareClick()) return ctx.ticks() > PLACE_TICKS ? Progress.failed(Why.of(Why.TIMEOUT, "couldn't close the container")) : Progress.running();
					Client.player().closeContainer();
				} else if (origin == null && Minecraft.getInstance().gui.screen() instanceof InventoryScreen) Client.player().closeContainer();
				return Progress.done();
			});
			// From the inventory or the container, into the hotbar: placed from there in the same tick (the server has the
			// click first).
			Step<Void> toHotbar = Step.of("Into the hotbar", () -> ctx -> {
				if (index >= 0 && index < 9) {
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
				if (own >= 0) return swapOut(ctx);
				// The container stays open (only put down): through its screen, as your inventory's own clicks don't go then.
				if (origin != null && !opens) {
					int to = hotbarRoom();
					if (to >= 0 && Myriad.inventory().prepareClick()) {
						origin.click(origin.playerSlot(index), to, ContainerInput.SWAP);
						from = to;
						return Progress.done();
					}
					return ctx.ticks() > PLACE_TICKS ? Progress.failed(Why.of(Why.TIMEOUT, "couldn't bring it into the hotbar")) : Progress.running();
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
			// Only put down, through the container's screen still up, or your inventory's clicks.
			Step<Void> givenBack = Step.of("Giving back", () -> ctx -> {
				var inv = Client.player().getInventory();
				Containers.View through = view != null ? view : origin;
				if (index < 9 || through != null && !through.isOpen() || inv.getItem(index).isEmpty() && inv.getItem(from).isEmpty()) return Progress.done();
				if (!Myriad.inventory().prepareClick()) return ctx.ticks() > GIVE_BACK_TICKS ? Progress.done() : Progress.running();
				if (through != null) through.swapWithHotbar(through.playerSlot(index), from);
				else Myriad.inventory().move(index, from);
				return Progress.done();
			});
			return check.then(toHotbar).then(opened).then(v -> givenBack.then(Step.supply("Out", () -> {
					out = true;
					return v;
				})))
				.named(() -> (opens ? "Opening " : "Putting down ") + (ender ? "an ender chest" : "a shulker box"))
				// Put down but not out (a failure, the owner turning off): it mustn't be left behind.
				.finallyDo(() -> {
					if (placed && !out) finish();
				});
		}

		/** Whether it can go back somehow, its container out of reach: as noWayBack allows, or through another ender chest. */
		private boolean wayBackLater() {
			return switch (noWayBack) {
				case INVENTORY -> true;
				case ENDER_CHEST -> enderOrigin && (ContainerTabs.nearestEnderChest() != null || enderChestItem() >= 0 && Myriad.inventory().count(ItemBoxes::silkTouch) > 0);
				case REFUSE -> enderOrigin && ContainerTabs.nearestEnderChest() != null;
			};
		}

		/**
		 * Where it goes back into: the container it came from, in reach; for one from an ender chest, out of reach or gone
		 * (held, and broken), the nearest other in reach, as each holds the same things. Null if none.
		 */
		private @Nullable BlockPos wayBack() {
			BlockPos pos = origin.pos();
			boolean there = Reach.hitFor(pos, false, options.range()) != null && (!enderOrigin || Client.level().getBlockState(pos).is(Blocks.ENDER_CHEST));
			return there ? pos : enderOrigin ? ContainerTabs.nearestEnderChest() : null;
		}

		/**
		 * The container it came from to walk back to, out of reach, if it still stands nearby (for one from an ender
		 * chest, an ender chest still there); null if not.
		 */
		private @Nullable BlockPos walkBack() {
			BlockPos pos = origin.pos();
			var state = Client.level().getBlockState(pos);
			boolean stands = enderOrigin ? state.is(Blocks.ENDER_CHEST) : !state.isAir();
			return stands && pos.distToCenterSqr(Client.player().position()) < WALK_BACK * WALK_BACK ? pos : null;
		}

		/** An ender chest in the inventory (not a box landed there to put back), or -1. */
		private int enderChestItem() {
			var inv = Client.player().getInventory();
			for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.ENDER_CHEST) && !(ender && i == landed)) return i;
			return -1;
		}

		/** A hotbar slot to bring it into: an empty one, or one whose item can wait in the inventory. */
		private int hotbarRoom() {
			// Never the Silk Touch pickaxe an ender chest is picked back up with.
			return MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory ? inventory.hotbarRoom(s -> !ender || !silkTouch(s)) : -1;
		}

		/**
		 * Swaps the box out of the container's slot into a hotbar slot, through the container's screen, which stays up. Into
		 * an empty hotbar slot if there is one; else what was in the hotbar slot waits in an empty slot of your inventory
		 * (two swaps in the tick), or, with that full too, in the box's place in the container, until it's back.
		 */
		private Progress<Void> swapOut(JobContext ctx) {
			if (!origin.isOpen()) return Progress.failed(Why.of(Why.CANCELLED, "the container closed"));
			var inv = Client.player().getInventory();
			int to = hotbarRoom();
			if (to >= 0 && Myriad.inventory().prepareClick()) {
				int spare = inv.getItem(to).isEmpty() ? -1 : Myriad.inventory().findInInventory(ItemStack::isEmpty);
				inPlace = spare < 0 && !inv.getItem(to).isEmpty();
				if (spare >= 0) origin.click(origin.playerSlot(spare), to, ContainerInput.SWAP);
				origin.swapWithHotbar(own, to);
				if (inv.getItem(to).is(box.getItem())) {
					from = to;
					parked = spare;
					return Progress.done();
				}
			}
			return ctx.ticks() > PLACE_TICKS ? Progress.failed(Why.of(Why.TIMEOUT, "couldn't bring it into the hotbar")) : Progress.running();
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
				// Only put down: done once the server has it.
				if (!opens) return placing.isDone() ? Progress.done(null) : Progress.running();
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
			// A held ender chest stays open as its block is broken (Containers.hold frees it from its block).
			boolean kept = Myriad.containers().held().map(h -> spot.pos().equals(h.pos())).orElse(false);
			Step<Void> close = kept ? Step.nothing() : Steps.closeContainer().recover(why -> Step.nothing());
			// The container it came from, again: to go back into it, or for one from your inventory to be where you were
			// (unless the swap bar closes it all anyway).
			Step<Void> reopen = origin == null || own < 0 && handedOver ? Step.nothing() : intoOrigin(List.of(this));
			return close.then(breakIt()).then(pickUp()).then(landing()).then(reopen).named(() -> ender ? "Putting the ender chest back" : "Putting the shulker box back");
		}

		/** Broken (with Silk Touch for an ender chest), what the inventory held before noted to know it by when it lands. */
		Step<Void> breakIt() {
			// An ender chest broken without Silk Touch is obsidian: rather left standing, saying where.
			Step<Void> silk = Step.of("Silk Touch", () -> ctx -> !ender || Myriad.inventory().count(ItemBoxes::silkTouch) > 0 ? Progress.done()
				: Progress.failed(Why.of(Why.NO_ITEM, "no Silk Touch pickaxe to pick the ender chest back up: it's left at " + spot.pos().toShortString(), spot.pos())));
			return silk.then(Step.run("Before", () -> {
				LocalPlayer p = Client.player();
				if (together == null) {
					for (int i = 0; i < 36; i++) before[i] = p.getInventory().getItem(i).copy();
					countBack = count(box) + 1;
				}
				// The Silk Touch pickaxe, not the fastest tool: an ender chest breaks to obsidian otherwise. Every other
				// tool for it is spared, so the break's own tool choice takes that one, from the inventory too.
				if (ender) Myriad.inventory().spare(PUT_BACK, s -> s.getDestroySpeed(Blocks.ENDER_CHEST.defaultBlockState()) > 1 && !silkTouch(s));
			})).then(Steps.breakBlock(spot.pos(), 10, Breaking.Options.forServer()).timeout(BREAK_TICKS))
				.finallyDo(() -> Myriad.inventory().spare(PUT_BACK, null))
				.then(Step.run("Broken", () -> broken = true));
		}

		/** Back in the inventory, once broken. */
		Step<Void> pickUp() {
			// Put back with others of its item, it waits for as many as were broken up to it.
			// Put back with others, any of theirs lying out of reach is stepped to as well while it waits.
			return Step.of("Picking it up", () -> !broken ? ctx -> Progress.done() : together == null ? new PickUp(box, spot.pos(), countBack, s -> s.is(box.getItem()))
				: new PickUp(box, spot.pos(), together.back(this), s -> together.boxes.stream().anyMatch(b -> s.is(b.box.getItem()))));
		}

		/**
		 * Where it landed, noted once it's back: one from your inventory stays there (any slot will do), one from a
		 * container goes back in from there.
		 */
		Step<Void> landing() {
			return Step.run("Landed", () -> {
				if (broken && landed < 0) landed = landed();
			});
		}

		/**
		 * Back into the container it came from, from where it landed: a shift-click, into the first free slot there (any
		 * will do), and with the container full it stays in your inventory. What made room for it in the hotbar and waited
		 * in the box's own slot (the inventory full too) is swapped back as the box goes in; one that waited in the
		 * inventory goes back after (unpark).
		 */
		private Step<Void> intoContainer(Containers.View view) {
			return Step.of("Into the container", () -> ctx -> {
				var inv = Client.player().getInventory();
				int at = landed >= 0 && inv.getItem(landed).is(box.getItem()) ? landed : -1;
				if (own < 0 || !view.isOpen() || at < 0) return Progress.done();
				if (!Myriad.inventory().prepareClick()) return ctx.ticks() > RETURN_TICKS ? Progress.done() : Progress.running();
				if (!inPlace) view.click(view.playerSlot(at), 0, ContainerInput.QUICK_MOVE);
				else if (at == from) view.swapWithHotbar(own, from);
				else {
					view.click(view.playerSlot(at), 0, ContainerInput.PICKUP);
					view.click(own, 0, ContainerInput.PICKUP);
					if (!view.handler().getCarried().isEmpty()) view.click(view.playerSlot(from), 0, ContainerInput.PICKUP);
				}
				return Progress.done();
			});
		}

		/** What made room for it in the hotbar, back there from the inventory slot it waited in (once it's free). */
		private Step<Void> unpark(Containers.View view) {
			return Step.of("Back to the hotbar", () -> ctx -> {
				var inv = Client.player().getInventory();
				if (parked < 0 || !view.isOpen() || !inv.getItem(from).isEmpty() || inv.getItem(parked).isEmpty()) return Progress.done();
				if (!Myriad.inventory().prepareClick()) return ctx.ticks() > RETURN_TICKS ? Progress.done() : Progress.running();
				view.click(view.playerSlot(parked), from, ContainerInput.SWAP);
				return Progress.done();
			});
		}

		/**
		 * The slot it landed in: one holding its item now that didn't before the break, not one another box put back with it
		 * took; of those, one holding what it showed (a box drops with its contents), so two of one colour are told apart.
		 */
		private int landed() {
			var inv = Client.player().getInventory();
			// As it showed when last open, or for one never opened, as it went down.
			ItemContainerContents shown = ender ? null : view == null ? box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
				: ItemContainerContents.fromItems(view.handler().slots.subList(0, view.size()).stream().map(Slot::getItem).toList());
			int changed = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack s = inv.getItem(i);
				if (!s.is(box.getItem()) || before[i] != null && ItemStack.matches(s, before[i]) || together != null && together.taken.contains(i)) continue;
				if (shown != null && shown.equals(s.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY))) return take(i);
				if (changed < 0) changed = i;
			}
			return changed < 0 ? -1 : take(changed);
		}

		private int take(int slot) {
			if (together != null) together.taken.add(slot);
			return slot;
		}
	}

	/**
	 * Puts {@code sessions} back together (the swap bar's, once you close a screen yourself): rather than each broken,
	 * picked up and put back before the next, they're broken one after another and picked up as they fall, then each
	 * container they came from is opened once for all of its own, as many at a time as the inventory has room for. One
	 * opened from another goes back into it first, before that one's broken; two of one colour are told apart by what's
	 * in them. One left standing after (no Silk Touch for
	 * an ender chest, say) is put back on its own, which says why.
	 */
	static CompletableFuture<Void> finishAll(List<Containers.ItemSession> sessions) {
		List<Session> left = new ArrayList<>();
		CompletableFuture<Void> others = CompletableFuture.completedFuture(null);
		for (Containers.ItemSession s : sessions) {
			if (s instanceof Session b && b.back == null && b.spot != null) left.add(b);
			else others = others.thenCompose(v -> s.finish().handle((r, t) -> null));
		}
		if (left.size() < 2) {
			for (Session b : left) others = others.thenCompose(v -> b.finish().handle((r, t) -> null));
			return others;
		}
		// A held ender chest stays open as its block is broken (Containers.hold frees it from its block).
		var held = Myriad.containers().held().orElse(null);
		Step<Void> all = Myriad.containers().current().filter(c -> c != held).isPresent() ? Steps.closeContainer().recover(why -> Step.nothing()) : Step.nothing();
		List<Session> rest = new ArrayList<>(left);
		while (!rest.isEmpty()) {
			// Those no other box standing came from.
			List<Session> round = rest.stream().filter(b -> rest.stream().noneMatch(r -> r.origin != null && b.spot.pos().equals(r.origin.pos()))).toList();
			if (round.isEmpty()) round = List.copyOf(rest);
			rest.removeAll(round);
			for (List<Session> batch : batches(round)) all = all.then(round(batch));
		}
		Step<Void> together = all.named(() -> "Putting the boxes back");
		CompletableFuture<Void> f = others.thenCompose(v -> Myriad.jobs().start(PUT_BACK, together).result().handle((r, t) -> null))
			.thenCompose(v -> {
				CompletableFuture<Void> again = CompletableFuture.completedFuture(null);
				for (Session b : left) {
					if (!Client.level().getBlockState(b.spot.pos()).is(Block.byItem(b.box.getItem()))) continue;
					b.back = null;
					again = again.thenCompose(x -> b.finish().handle((r, t) -> null));
				}
				return again;
			});
		for (Session b : left) b.back = f;
		return f;
	}

	/**
	 * A round of finishAll split so each part fits the inventory, as a part's boxes are all picked up before any goes
	 * back into its container: as many as there are empty slots (at least one: the slot a box left by is empty again once
	 * it's down). One from your inventory stays there, taking a slot for good, so those go first.
	 */
	private static List<List<Session>> batches(List<Session> round) {
		var inv = Client.player().getInventory();
		int free = 0;
		for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) free++;
		List<Session> stay = round.stream().filter(b -> b.own < 0).toList(), back = round.stream().filter(b -> b.own >= 0).toList();
		int size = Math.max(1, free - stay.size());
		List<List<Session>> batches = new ArrayList<>();
		List<Session> batch = new ArrayList<>(stay);
		for (Session b : back) {
			if (batch.size() - (batches.isEmpty() ? stay.size() : 0) >= size) {
				batches.add(batch);
				batch = new ArrayList<>();
			}
			batch.add(b);
		}
		if (!batch.isEmpty()) batches.add(batch);
		return batches;
	}

	/** One round of finishAll: all broken, then picked up as they fall, then into their containers. */
	private static Step<Void> round(List<Session> round) {
		Together together = new Together(round);
		Step<Void> s = Step.run("Before", together::start).then(Step.of("Breaking them", () -> new BreakAll(round)));
		for (Session b : round) s = s.then(b.pickUp().then(b.landing()).recover(why -> Step.nothing()));
		Map<BlockPos, List<Session>> byOrigin = new LinkedHashMap<>();
		for (Session b : round) if (b.origin != null && b.own >= 0) byOrigin.computeIfAbsent(b.origin.pos(), k -> new ArrayList<>()).add(b);
		for (List<Session> group : byOrigin.values()) s = s.then(intoOrigin(group));
		return s;
	}

	/**
	 * The container {@code boxes} came from opened again (or, for an ender chest, another in reach: see wayBack), and
	 * each put back into it. With no screen up (you closed it all), it opens only for the server, its screen not shown.
	 * With none to open they stay in your inventory, unless noWayBack says to put down an ender chest from it for them.
	 */
	private static Step<Void> intoOrigin(List<Session> boxes) {
		Session first = boxes.getFirst();
		return Step.choose("Back to the container", () -> {
			BlockPos pos = first.wayBack();
			if (pos != null) return openAndInto(pos, boxes);
			// Out of reach after picking them up (stepping to where they fell): back towards it first.
			BlockPos back = first.walkBack();
			if (back != null) {
				return Step.of("Back in reach", () -> new BackInReach(back, first.options.range())).then(openAndInto(back, boxes)).recover(why -> Step.nothing());
			}
			int index = first.enderChestItem();
			if (first.noWayBack == Containers.NoWayBack.ENDER_CHEST && first.enderOrigin && index >= 0) {
				return Steps.inItem(index, view -> into(view, boxes)).recover(why -> Step.nothing());
			}
			return Step.nothing();
		});
	}

	private static Step<Void> openAndInto(BlockPos pos, List<Session> boxes) {
		Session first = boxes.getFirst();
		return Step.run("Unseen", () -> MyriadImpl.get().containerTracker().unseen(pos))
			.then(Steps.openContainer(pos, first.ticks, first.options).timeout(first.ticks + REOPEN_TICKS))
			.then(view -> into(view, boxes))
			.recover(why -> Step.nothing());
	}

	private static Step<Void> into(Containers.View view, List<Session> boxes) {
		Step<Void> into = Step.nothing();
		for (Session b : boxes) into = into.then(b.intoContainer(view));
		// What made room in the hotbar goes back once they're all in: another box may have landed in its slot meanwhile.
		for (Session b : boxes) into = into.then(b.unpark(view));
		return into;
	}

	/**
	 * Breaks boxes put back together, queued with the breaking service at once: each starts as the one before it
	 * finishes, not a round trip later once the server says it's gone. An ender chest goes only with a Silk Touch
	 * pickaxe (obsidian otherwise); one not broken is left standing, for finishAll to put back on its own.
	 */
	private static final class BreakAll implements Step.Run<Void> {
		private final List<Session> boxes;
		private final Map<Session, CompletableFuture<Boolean>> breaking = new HashMap<>();
		private boolean spared;

		BreakAll(List<Session> boxes) {
			this.boxes = boxes;
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			// The Silk Touch pickaxe for an ender chest, not the fastest tool: every other tool for it is spared, so the
			// break's own tool choice takes that one.
			if (!spared && boxes.stream().anyMatch(b -> b.ender)) {
				spared = true;
				Myriad.inventory().spare(PUT_BACK, st -> st.getDestroySpeed(Blocks.ENDER_CHEST.defaultBlockState()) > 1 && !silkTouch(st));
			}
			boolean waiting = false;
			for (Session b : boxes) {
				CompletableFuture<Boolean> f = breaking.get(b);
				if (f == null) {
					if (b.ender && Myriad.inventory().count(ItemBoxes::silkTouch) == 0) {
						breaking.put(b, CompletableFuture.completedFuture(false));
						continue;
					}
					Breaking.Attempt a = Myriad.breaking().breakBlock(ctx.job(), b.spot.pos(), 10, Breaking.Options.forServer());
					switch (a.check()) {
						case OK -> breaking.put(b, a.result());
						case NOTHING_THERE -> breaking.put(b, CompletableFuture.completedFuture(true));
						case PENDING -> {
						}
						default -> breaking.put(b, CompletableFuture.completedFuture(false));
					}
					waiting = true;
				} else if (!f.isDone()) waiting = true;
				else b.broken = f.join();
			}
			if (waiting && ctx.ticks() <= BREAK_TICKS) return Progress.running();
			for (Session b : boxes) Myriad.breaking().cancel(b.spot.pos());
			return done();
		}

		@Override
		public void cancel(JobContext ctx) {
			for (Session b : boxes) Myriad.breaking().cancel(b.spot.pos());
			done();
		}

		private Progress<Void> done() {
			if (spared) Myriad.inventory().spare(PUT_BACK, null);
			spared = false;
			return Progress.done();
		}
	}

	/** Boxes put back together in one round of finishAll. */
	private static final class Together {
		private final List<Session> boxes;
		/** How many of each item the inventory held before them all. */
		private final Map<Item, Integer> base = new HashMap<>();
		/** The slots boxes landed in, so another of the same item doesn't take one. */
		private final Set<Integer> taken = new HashSet<>();

		Together(List<Session> boxes) {
			this.boxes = boxes;
		}

		void start() {
			ItemStack[] before = new ItemStack[36];
			for (int i = 0; i < 36; i++) before[i] = Client.player().getInventory().getItem(i).copy();
			for (Session b : boxes) {
				b.together = this;
				b.before = before;
				base.putIfAbsent(b.box.getItem(), count(b.box));
			}
		}

		/** How many of {@code box}'s item the inventory holds once it's back: those before, and each of them broken up to it. */
		int back(Session box) {
			int n = base.get(box.box.getItem());
			for (Session b : boxes) {
				if (b.broken && b.box.is(box.box.getItem())) n++;
				if (b == box) break;
			}
			return n;
		}
	}

	/** Waits for the box to come back into the inventory, stepping towards it (or {@code drops}) if it bounced out of reach. */
	private static final class PickUp implements Step.Run<Void> {
		private final ItemStack box;
		private final BlockPos spot;
		/** How many of its item the inventory holds once it's back. */
		private final int back;
		/** What's stepped to: its own drop, or any of the boxes' put back with it. */
		private final Predicate<ItemStack> drops;
		private final Steering steering = new Steering();

		PickUp(ItemStack box, BlockPos spot, int back, Predicate<ItemStack> drops) {
			this.box = box;
			this.spot = spot;
			this.back = back;
			this.drops = drops;
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (count(box) >= back) return done();
			steering.to(null);
			if (ctx.ticks() > PICKUP_TICKS) {
				done();
				return Progress.failed(Why.of(Why.TIMEOUT, "couldn't pick it back up: it's on the ground at " + spot.toShortString(), spot));
			}
			LocalPlayer p = Client.player();
			List<ItemEntity> lying = Client.level().getEntitiesOfClass(ItemEntity.class, new AABB(spot).inflate(4), i -> drops.test(i.getItem()));
			AABB pickUp = p.getBoundingBox().inflate(1, 0.5, 1);
			Comparator<ItemEntity> nearest = Comparator.comparingDouble(i -> i.distanceToSqr(p));
			// Stepped to as soon as one can be picked up and you're not near enough to (a dropped block waits PICKUP_DELAY
			// ticks, falling meanwhile), or the nearest once it's taken this long anyway.
			ItemEntity to = lying.stream().filter(i -> i.getAge() >= PICKUP_DELAY && !pickUp.intersects(i.getBoundingBox())).min(nearest).orElse(null);
			if (to == null && ctx.ticks() > WALK_AFTER_TICKS) to = lying.stream().min(nearest).orElse(null);
			if (to != null) steering.to(to.position());
			return Progress.running();
		}

		@Override
		public void cancel(JobContext ctx) {
			done();
		}

		private Progress<Void> done() {
			steering.stop();
			return Progress.done();
		}
	}

	/**
	 * Walks back towards the container {@code pos} until it's in reach to be opened again, after picking the boxes up
	 * took you away from it. Gives up after BACK_TICKS.
	 */
	private static final class BackInReach implements Step.Run<Void> {
		private final BlockPos pos;
		private final double range;
		private final Steering steering = new Steering();

		BackInReach(BlockPos pos, double range) {
			this.pos = pos;
			this.range = range;
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (Reach.hitFor(pos, false, range) != null) return done();
			if (ctx.ticks() > BACK_TICKS) {
				steering.stop();
				return Progress.failed(Why.of(Why.OUT_OF_REACH, "couldn't get back in reach of " + pos.toShortString(), pos));
			}
			steering.to(Vec3.atCenterOf(pos));
			return Progress.running();
		}

		@Override
		public void cancel(JobContext ctx) {
			done();
		}

		private Progress<Void> done() {
			steering.stop();
			return Progress.done();
		}
	}

	/** Steers you towards a point while it has one, as your movement keys would. */
	private static final class Steering {
		private @Nullable Vec3 walkTo;
		private boolean subscribed;

		void to(@Nullable Vec3 target) {
			walkTo = target;
			if (target != null && !subscribed) {
				subscribed = true;
				Myriad.events().subscribe(this);
			}
		}

		void stop() {
			walkTo = null;
			if (subscribed) {
				subscribed = false;
				Myriad.events().unsubscribe(this);
			}
		}

		/**
		 * Steps towards the point: sneaking, so no edge is walked off, unless it's lower, no further down than a fall
		 * that doesn't hurt; jumping where it's up a block and you're against it.
		 */
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
			double dy = walkTo.y - p.getY();
			e.sneak = dy > -0.5 || dy < -SAFE_DROP;
			e.sprint = false;
			e.jump = dy > p.maxUpStep() && p.horizontalCollision && p.onGround();
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
		return above != null ? above : beside(options, ender);
	}

	/**
	 * Above your head, so it falls back on you when it's broken, and placing and opening it share one look straight up:
	 * into the empty space itself where air placement is on, else against the underside of the block over it. A shulker
	 * box then faces down, so it goes a block higher, its lid opening into the space below it (one opening into the space
	 * your head is in pushes you down), and with no room there it goes beside you. An ender chest has no lid to push, so
	 * right over your head does too, but it stays shut under a solid block, so it only goes there by air placement.
	 */
	private static @Nullable Spot above(Placement.Options options, boolean ender) {
		BlockPos head = BlockPos.containing(Client.player().getX(), Math.ceil(Client.player().getBoundingBox().maxY), Client.player().getZ());
		Spot higher = above(options, ender, head.above());
		// Right over your head only an ender chest: a shulker box's lid there pushes you down as it opens.
		return higher != null || !ender ? higher : above(options, true, head);
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
	 * Around you, a free block clear of you, in reach and placeable, nearest the way you face first: beside your feet on
	 * a floor (placed on its top, so a shulker box faces up and its lid opens into the block above), or with air
	 * placement into the empty space itself, clicked on the face you see: beside your feet its top (facing up, as on a
	 * floor), a block over your head its underside (facing down, its lid opening into the block below). Each ring is the eight blocks around you, so a box broken there falls within a block of you. An
	 * ender chest opens under nothing solid.
	 */
	private static @Nullable Spot beside(Placement.Options options, boolean ender) {
		LocalPlayer p = Client.player();
		Level level = Client.level();
		BlockPos feet = p.blockPosition();
		Direction facing = p.getDirection();
		Spot best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dy : options.airPlace() ? new int[]{0, 2} : new int[]{0}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					BlockPos pos = feet.offset(dx, dy, dz), floor = pos.below(), above = pos.above();
					if (!level.getBlockState(pos).canBeReplaced() || p.getBoundingBox().intersects(new AABB(pos)) || !Reach.canReach(pos)) continue;
					if (ender && level.getBlockState(above).isRedstoneConductor(level, above)) continue;
					if (!Myriad.placement().check(pos, options).ok()) continue;
					Spot spot = null;
					boolean onFloor = dy == 0 && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
					if (onFloor && (ender || level.getBlockState(above).getCollisionShape(level, above).isEmpty())) {
						// Sneaking only where the floor would open or be used instead (a chest under it).
						spot = new Spot(pos, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false), BlockInfo.isClickable(level.getBlockState(floor)));
					} else if (options.airPlace() && level.getBlockState(pos).isAir()) {
						// The face you see: its top beside your feet, its underside over your head; the lid opens away from it.
						Direction face = dy == 0 ? Direction.UP : Direction.DOWN;
						BlockPos lid = pos.relative(face);
						if (ender || level.getBlockState(lid).getCollisionShape(level, lid).isEmpty())
							spot = new Spot(pos, new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false), false);
					}
					if (spot == null) continue;
					// The ring at your feet first, a floor before air placement.
					double score = (dx * dx + dz * dz) - 0.5 * (dx * facing.getStepX() + dz * facing.getStepZ()) + (dy > 0 ? 2 : 0) + (onFloor ? 0 : 0.5);
					if (score < bestScore) {
						bestScore = score;
						best = spot;
					}
				}
			}
		}
		return best;
	}
}
