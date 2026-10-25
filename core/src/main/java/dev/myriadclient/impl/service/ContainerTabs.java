package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The swap bar's containers (drawn over container screens by ThemedScreens): your inventory, the container you last
 * opened yourself, and the boxes standing that were handed over to it ({@link Containers.ItemSession#finishWhenClosed}).
 * The server keeps one container open at a time, so a tab opens its container again (a click on its block, a round
 * trip; the held one, {@link Containers#hold}, shows at once). Boxes stay standing while you switch, and go back, newest
 * first (one opened from another goes back into it first), once you close a screen yourself; one left out of reach
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
	public record Tab(@Nullable BlockPos pos, Containers.@Nullable ItemSession box, ItemStack icon) {
		public boolean you() {
			return pos == null;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	/** The container you last opened yourself (where its block is), and what shows for it. */
	private @Nullable BlockPos opened;
	private ItemStack openedIcon = ItemStack.EMPTY;
	/** The boxes standing, oldest first. */
	private final List<Containers.ItemSession> boxes = new ArrayList<>();
	/** Putting them all back, once you closed a screen yourself. */
	private @Nullable CompletableFuture<Void> finishing;

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
		if (mc.player == null || boxes.isEmpty() && Myriad.containers().held().isEmpty()) return tabs;
		// Your inventory's tab is the held container itself while one is (its screen shows your inventory).
		if (Myriad.containers().held().isEmpty()) tabs.add(new Tab(null, null, head()));
		if (opened != null && !isBox(opened)) tabs.add(new Tab(opened, null, openedIcon));
		for (Containers.ItemSession b : boxes) {
			if (b.where() == null) continue;
			ItemStack icon = new ItemStack(mc.level.getBlockState(b.where()).getBlock().asItem());
			tabs.add(new Tab(b.where(), b, icon.isEmpty() ? new ItemStack(Items.SHULKER_BOX) : icon));
		}
		return tabs;
	}

	/** Whether {@code tab} is what shows now. */
	public boolean isCurrent(Tab tab) {
		if (tab.you()) return mc.gui.screen() instanceof InventoryScreen;
		return Myriad.containers().current().map(v -> tab.pos().equals(v.pos())).orElse(false);
	}

	/** Whether {@code tab}'s container is in reach to be opened again (your inventory always is). */
	public boolean reachable(Tab tab) {
		return tab.you() || Reach.hitFor(tab.pos(), false, Reach.blockRange()) != null;
	}

	/**
	 * Switches to {@code tab}: your inventory (the open container closed, at a moment the server takes it), or its
	 * container opened again. False if it can't now (out of reach, or a click would have to wait).
	 */
	public boolean switchTo(Tab tab) {
		if (mc.player == null || isCurrent(tab) || !reachable(tab) || finishing != null) return false;
		var held = Myriad.containers().held();
		if (tab.you()) {
			if (!Myriad.inventory().prepareClick()) return false;
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			mc.gui.setScreen(new InventoryScreen(mc.player));
			return true;
		}
		if (held.isPresent() && tab.pos().equals(held.get().pos())) return Myriad.containers().reopen();
		Myriad.containers().open(TABS, tab.pos(), OPEN_TICKS, Placement.Options.forServer());
		return true;
	}

	/**
	 * You closed a screen yourself (from AbstractContainerScreenMixin, its onClose): every box standing goes back, newest
	 * first, and whatever putting them back opened again is closed after.
	 */
	public void userClosed(AbstractContainerMenu menu) {
		if (boxes.isEmpty() || finishing != null) return;
		List<Containers.ItemSession> order = new ArrayList<>(boxes);
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (int i = order.size() - 1; i >= 0; i--) {
			Containers.ItemSession b = order.get(i);
			chain = chain.handle((v, t) -> null).thenCompose(v -> b.finish());
		}
		finishing = chain.handle((v, t) -> {
			boxes.removeAll(order);
			finishing = null;
			// Putting a box back into a container opens that again; you'd closed it all. One you opened meanwhile stays.
			if (Myriad.containers().current().filter(c -> !c.byHand()).isPresent()) Myriad.containers().close();
			return null;
		});
	}

	@Subscribe
	private void onOpened(ContainerEvent.Opened e) {
		Containers.View v = e.view();
		if (!v.byHand() || v.pos() == null || isBox(v.pos()) || mc.level == null) return;
		opened = v.pos();
		openedIcon = new ItemStack(mc.level.getBlockState(v.pos()).getBlock().asItem());
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (mc.player == null || finishing != null) return;
		// A box gone (broken), or put back by its own session: off the bar.
		boxes.removeIf(b -> b.where() == null || mc.level.getBlockState(b.where()).isAir());
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
		finishing = null;
	}

	/** Your head, for your inventory's tab. */
	private ItemStack head() {
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(mc.player.getGameProfile()));
		return head;
	}
}
