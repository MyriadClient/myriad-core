package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The swap bar's containers (drawn over container screens by ThemedScreens): your inventory, the container you last
 * opened yourself, the nearest ender chest in reach (or the one held), and the boxes standing that were handed over to
 * it ({@link Containers.ItemSession#finishWhenClosed}).
 * The server keeps one container open at a time, so a tab opens its container again (a click on its block, a round
 * trip; the held one, {@link Containers#hold}, shows at once). Boxes stay standing while you switch, and go back all
 * together (one opened from another goes back into it first) once you close a screen yourself; one left out of reach
 * with no screen open goes back on its own.
 */
public final class ContainerTabs {
	/** Ticks a tab's container gets to open. */
	private static final int OPEN_TICKS = 40;
	/** Owns the tabs' opens. */
	private static final Object TABS = new Object() {
		@Override
		public String toString() {
			return "Myriad";
		}
	};

	/** A tab: your inventory (no pos), or a container at {@code pos}, a box put down for you when {@code box} is set. */
	public record Tab(@Nullable BlockPos pos, Containers.@Nullable ItemSession box, ItemStack icon, String name) {
		public boolean you() {
			return pos == null;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	/** The container you last opened yourself (where its block is), and what shows for it, until you close a screen yourself. */
	private @Nullable BlockPos opened;
	private ItemStack openedIcon = ItemStack.EMPTY;
	/** The boxes standing, oldest first. */
	private final List<Containers.ItemSession> boxes = new ArrayList<>();
	/** Who wants the bar shown (Containers.swapBar). */
	private final Set<Object> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Putting them all back, once you closed a screen yourself. */
	private @Nullable CompletableFuture<Void> finishing;
	/** The nearest ender chest in reach that would open, looked for each tick a container screen is up: a tab for it. */
	private @Nullable BlockPos nearEnderChest;

	/** Containers.swapBar. */
	void want(Object owner, boolean on) {
		if (on) wanted.add(owner);
		else wanted.remove(owner);
	}

	/** {@code box} stands until you close a screen yourself (Containers.ItemSession.finishWhenClosed). */
	void add(Containers.ItemSession box) {
		if (!boxes.contains(box)) boxes.add(box);
	}

	/** Whether a box put down for you stands at {@code pos}: it isn't held (it goes back), nor the container you opened. */
	public boolean isBox(@Nullable BlockPos pos) {
		if (pos == null) return false;
		for (Containers.ItemSession b : boxes) if (pos.equals(b.where())) return true;
		return false;
	}

	/** The tabs to show: none unless there's somewhere to switch to (a box standing, or a container held). */
	public List<Tab> tabs() {
		List<Tab> tabs = new ArrayList<>();
		var held = Myriad.containers().held();
		if (mc.player == null || wanted.isEmpty() && boxes.isEmpty() && held.isEmpty()) return tabs;
		// The held one stays a tab however far away, as it shows at once.
		BlockPos heldAt = held.map(Containers.View::pos).orElse(null);
		Tab heldTab = heldAt == null ? null : tab(heldAt, null, heldAt.equals(opened) ? openedIcon : ItemStack.EMPTY, Items.ENDER_CHEST);
		tabs.add(new Tab(null, null, head(), "Your inventory"));
		if (heldTab != null) tabs.add(heldTab);
		if (opened != null && !opened.equals(heldAt) && !isBox(opened)) tabs.add(tab(opened, null, openedIcon, Items.CHEST));
		// Your ender chest is always a tab when one's in reach, held or not; held, that one is it already.
		if (heldTab == null && nearEnderChest != null && !nearEnderChest.equals(opened) && !isBox(nearEnderChest)) {
			tabs.add(tab(nearEnderChest, null, ItemStack.EMPTY, Items.ENDER_CHEST));
		}
		// An ender chest put down for you and held is the held one's tab.
		for (Containers.ItemSession b : boxes) if (b.where() != null && !b.where().equals(heldAt)) tabs.add(tab(b.where(), b, ItemStack.EMPTY, Items.SHULKER_BOX));
		return tabs;
	}

	/** A container's tab, its icon the block standing there unless given (or {@code fallback} if that's gone or unloaded). */
	private Tab tab(BlockPos pos, Containers.@Nullable ItemSession box, ItemStack icon, Item fallback) {
		if (icon.isEmpty()) icon = new ItemStack(mc.level.getBlockState(pos).getBlock().asItem());
		if (icon.isEmpty()) icon = new ItemStack(fallback);
		return new Tab(pos, box, icon, icon.getHoverName().getString());
	}

	/** Whether {@code tab} is what shows now. */
	public boolean isCurrent(Tab tab) {
		if (tab.you()) return mc.gui.screen() instanceof InventoryScreen;
		// Its screen showing: a held one stays the open container under your inventory's screen.
		return Myriad.containers().current().map(v -> tab.pos().equals(v.pos())
			&& mc.gui.screen() instanceof AbstractContainerScreen<?> s && s.getMenu() == v.handler()).orElse(false);
	}

	/** Whether {@code tab}'s container is in reach to be opened again (your inventory always is). */
	public boolean reachable(Tab tab) {
		if (tab.you() || Myriad.containers().held().map(v -> tab.pos().equals(v.pos())).orElse(false)) return true;
		return Reach.hitFor(tab.pos(), false, Reach.blockRange()) != null;
	}

	/** Whether {@code tab} is the held container's (a right click lets it go). */
	public boolean isHeld(Tab tab) {
		return !tab.you() && Myriad.containers().held().map(v -> tab.pos().equals(v.pos())).orElse(false);
	}

	/**
	 * Switches to {@code tab}: your inventory (the open container closed, at a moment the server takes it, but a held
	 * one left open), or its container opened again. False if it can't now (out of reach, or a click would have to wait).
	 */
	public boolean switchTo(Tab tab) {
		if (mc.player == null || isCurrent(tab) || !reachable(tab) || finishing != null) return false;
		var held = Myriad.containers().held();
		if (tab.you()) {
			ContainerTracker tracker = MyriadImpl.get().containerTracker();
			// A held one stays open, its screen hidden: your inventory's clicks go through it (ContainerTracker.inventoryClick).
			if (tracker.heldMenu() == null) {
				if (mc.player.containerMenu != mc.player.inventoryMenu && !Myriad.inventory().prepareClick()) return false;
				tracker.closeUnderScreen();
			}
			tracker.showInventory();
			return true;
		}
		if (held.isPresent() && tab.pos().equals(held.get().pos())) {
			// What your inventory's screen showed on the cursor was the held one's (ContainerTracker.inventoryClick).
			if (!Myriad.containers().reopen()) return false;
			mc.player.inventoryMenu.setCarried(ItemStack.EMPTY);
			return true;
		}
		Myriad.containers().open(TABS, tab.pos(), OPEN_TICKS, Placement.Options.forServer());
		return true;
	}

	/** Lets the held container go (a right click on its tab): closed on the server, your inventory shown if it was showing. */
	public void letGo(Tab tab) {
		if (mc.player == null || !isHeld(tab) || finishing != null) return;
		ContainerTracker tracker = MyriadImpl.get().containerTracker();
		if (tracker.heldMenu() == null) {
			Myriad.containers().release();
			return;
		}
		if (!Myriad.inventory().prepareClick()) return;
		boolean showing = !(mc.gui.screen() instanceof InventoryScreen);
		tracker.closeUnderScreen();
		if (showing) tracker.showInventory();
	}

	/**
	 * You closed a screen yourself (from AbstractContainerScreenMixin, its onClose): every box standing goes back, all
	 * together, and whatever putting them back opened again is closed after.
	 */
	public void userClosed(AbstractContainerMenu menu) {
		// Done with it: a box put down later isn't from there.
		opened = null;
		if (boxes.isEmpty() || finishing != null) return;
		List<Containers.ItemSession> order = new ArrayList<>(boxes);
		// All together: broken one after another, picked up, and into each container they came from at once (ItemBoxes.finishAll).
		finishing = ItemBoxes.finishAll(order).handle((v, t) -> {
			boxes.removeAll(order);
			finishing = null;
			// Putting a box back into a container opens that again; you'd closed it all. One you opened meanwhile stays.
			// A held one stays open, its screen gone as you left it.
			var held = Myriad.containers().held().orElse(null);
			if (Myriad.containers().current().filter(c -> !c.byHand() && c != held).isPresent()) Myriad.containers().close();
			else if (held != null && mc.gui.screen() instanceof AbstractContainerScreen<?> s && s.getMenu() == held.handler()) mc.gui.setScreen(null);
			return null;
		});
	}

	@Subscribe
	private void onOpened(ContainerEvent.Opened e) {
		Containers.View v = e.view();
		// A box opened from its tab: what it holds is known by this (one only put down hasn't shown it before).
		for (Containers.ItemSession b : boxes) if (v.pos() != null && v.pos().equals(b.where())) ItemBoxes.seen(b, v);
		if (!v.byHand() || v.pos() == null || isBox(v.pos()) || mc.level == null) return;
		opened = v.pos();
		openedIcon = new ItemStack(mc.level.getBlockState(v.pos()).getBlock().asItem());
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (mc.player == null) return;
		// A box gone (broken), or put back by its own session: off the bar.
		boxes.removeIf(b -> b.where() == null || mc.level.getBlockState(b.where()).isAir());
		nearEnderChest = mc.gui.screen() instanceof AbstractContainerScreen<?> && !wanted.isEmpty() ? nearestEnderChest() : null;
		if (finishing != null) return;
		if (opened != null && Reach.hitFor(opened, false, Reach.blockRange() + 4) == null) opened = null;
		// One left out of reach while no screen is open goes back now: later it couldn't be broken.
		if (mc.gui.screen() == null && Myriad.containers().current().isEmpty()) {
			for (Containers.ItemSession b : List.copyOf(boxes)) {
				if (Reach.hitFor(b.where(), false, Reach.blockRange()) != null) continue;
				boxes.remove(b);
				b.finish();
			}
		}
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		boxes.clear();
		opened = null;
		nearEnderChest = null;
		finishing = null;
	}

	/** The nearest ender chest a click would open: in reach, nothing solid on it (it wouldn't open then). */
	static @Nullable BlockPos nearestEnderChest() {
		Minecraft mc = Minecraft.getInstance();
		Vec3 eyes = mc.player.getEyePosition();
		double range = Reach.blockRange(), best = range * range;
		int r = Mth.ceil(range);
		BlockPos found = null;
		for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(eyes).offset(-r, -r, -r), BlockPos.containing(eyes).offset(r, r, r))) {
			if (!mc.level.getBlockState(p).is(Blocks.ENDER_CHEST)) continue;
			double d = p.distToCenterSqr(eyes);
			BlockPos above = p.above();
			if (d >= best || mc.level.getBlockState(above).isRedstoneConductor(mc.level, above) || Reach.hitFor(p, false, range) == null) continue;
			best = d;
			found = p.immutable();
		}
		return found;
	}

	/** Your head, for your inventory's tab. */
	private ItemStack head() {
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(mc.player.getGameProfile()));
		return head;
	}
}
