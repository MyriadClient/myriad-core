package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.Interactions;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.api.util.Slots;
import dev.myriadclient.api.util.Threads;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Follows the open container from the network handler's open/contents packets (see ClientPlayNetworkHandlerMixin),
 * and remembers the last block clicked so a container knows where it came from.
 */
public final class ContainerTracker implements Containers {
	/** A click more than this long before the screen opened isn't what opened it. */
	private static final long CLICK_WINDOW_MS = 2000;
	/** Ticks the held ender chest gets to open over itself. */
	private static final int UNLINK_TICKS = 40;
	/** Ticks that open is tried again on while its click can't go (a look still turning for something else, say). */
	private static final int UNLINK_TRIES = 40;
	/** Owns that open. */
	private static final Object HOLD = new Object() {
		@Override
		public String toString() {
			return "Myriad";
		}
	};

	private final Minecraft mc = Minecraft.getInstance();
	private final MenuSync sync;
	/** Moves and arrangements on the open container, run one after another; the first is under way. */
	private final ArrayDeque<MenuJob> transfers = new ArrayDeque<>();
	private ViewImpl current;
	private BlockPos lastClicked;
	private long lastClickedAt;
	private CompletableFuture<View> pending;
	private BlockPos pendingPos;
	private int pendingTicks;
	/** Who asked for the pending open (null with no owner given). */
	private @Nullable Object pendingOwner;
	/** A close asked for while the server wouldn't take one yet: it goes at the first moment it will. */
	private boolean closeWanted;
	/** The container held open on the server after its screen closes (Containers.hold), and that screen, kept to show again. */
	private @Nullable ViewImpl held;
	private @Nullable AbstractContainerScreen<?> heldScreen;
	/** The held ender chest is to be opened once more over itself (see hold), and where it's being opened so. */
	private boolean unlinkWanted;
	private @Nullable BlockPos unlinking;
	private int unlinkTries;
	/** Per owner, what its boxes do with no way back (noWayBack). */
	private final Map<Object, NoWayBack> noWayBack = Collections.synchronizedMap(new WeakHashMap<>());
	/** The next container opened in code here shows no screen if none is up as it opens (see unseen). */
	private @Nullable BlockPos unseen;
	/** The open container's screen that wasn't shown (unseen, or the held ender chest opened over itself with none up). */
	private @Nullable AbstractContainerScreen<?> hiddenScreen;
	/** Your inventory's screen is being shown over the held container (showInventory), not that one's instead. */
	private boolean showingInventory;

	public ContainerTracker(MenuSync sync) {
		this.sync = sync;
	}

	// ---- called from ClientPlayNetworkHandlerMixin (render thread, after vanilla handled the packet) ---------------

	public void onOpened(int syncId) {
		closeTracked();
		if (mc.player == null || mc.player.containerMenu.containerId != syncId || syncId == 0) return;
		BlockPos pos = lastClicked != null && Clock.millis() - lastClickedAt < CLICK_WINDOW_MS ? lastClicked : null;
		AbstractContainerMenu menu = mc.player.containerMenu;
		// Something else opened instead of what open() asked for (a server plugin's menu, say): it isn't that container.
		if (pending != null && pendingPos != null && mc.level != null && !ContainerKinds.opens(mc.level.getBlockState(pendingPos), menu)) {
			fail(new IllegalStateException("Something else opened instead of the container at " + pendingPos.toShortString()));
		}
		boolean byCode = pending != null;
		current = new ViewImpl(menu, pos, byCode ? pendingOwner : null, !byCode);
		if (unlinking != null && unlinking.equals(pos)) {
			// The held ender chest, opened over itself: held still, and now from anywhere (hold).
			current.free = true;
			held = current;
			heldScreen = screenOf(menu);
		}
		unseen = null;
		Myriad.events().post(new ContainerEvent.Opened(current));
	}

	public void onContents(int syncId) {
		sync.onContents(syncId);
		if (current == null || current.syncId() != syncId || current.loaded) return;
		current.loaded = true;
		Myriad.events().post(new ContainerEvent.Loaded(current));
		if (pending != null && (pendingPos == null || pendingPos.equals(current.pos))) {
			CompletableFuture<View> f = pending;
			pending = null;
			f.complete(current);
		}
	}

	public void onSlot(int syncId, int slot) {
		if (current == null || current.syncId() != syncId || slot < 0) return;
		Myriad.events().post(new ContainerEvent.SlotUpdated(current, slot));
	}

	// ---- tracking ---------------------------------------------------------------------------------------------------

	@Subscribe(packets = ServerboundUseItemOnPacket.class)
	private void onSend(PacketEvent.Send e) {
		if (e.packet() instanceof ServerboundUseItemOnPacket p) {
			lastClicked = p.getHitResult().getBlockPos().immutable();
			lastClickedAt = Clock.millis();
		}
	}

	/** At the start of the tick, before the movement packet, as clicks go. */
	@Subscribe
	private void runTransfers(TickEvent.Pre e) {
		if (closeWanted) {
			if (current == null || mc.player == null) closeWanted = false;
			else if (Myriad.inventory().prepareClick()) closeNow();
		}
		if (unlinkWanted && held != null && held == current && held.loaded && pending == null) {
			unlinkWanted = false;
			unlinking = held.pos;
			ViewImpl h = held;
			open(HOLD, held.pos, UNLINK_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> {
				unlinking = null;
				// Not opened (the click didn't go): again next tick, while it's still the one held.
				if (t != null && held == h && h == current && !h.free) {
					if (unlinkTries == 0 || unlinkTries == UNLINK_TRIES - 1) MyriadImpl.LOG.info("Opening the held ender chest over itself: {}", t.getMessage());
					if (++unlinkTries < UNLINK_TRIES) unlinkWanted = true;
				}
			});
		}
		MenuJob t = transfers.peek();
		if (t != null && t.tick(sync)) transfers.poll();
	}

	/** Arranges {@code menu} (your own inventory's, for {@code Inventory.arrange}) as a container's view does. */
	public CompletableFuture<Boolean> arrange(AbstractContainerMenu menu, Arrangement arrangement) {
		PlannerJob job = new PlannerJob(menu, new Arranger(menu, arrangement));
		transfers.add(job);
		return job.result;
	}

	/**
	 * What {@code menu}'s slot {@code slot} shows while a sort runs in it: the sorted result, or null for what it holds
	 * (see {@link PlannerJob#shown}). From AbstractContainerScreenMixin.
	 */
	public @Nullable ItemStack shown(AbstractContainerMenu menu, int slot) {
		return transfers.peek() instanceof PlannerJob job ? job.shown(menu, slot) : null;
	}

	/** Whether an arrangement (a sort) is running in {@code menu}, stopping included. */
	public boolean arranging(AbstractContainerMenu menu) {
		return transfers.peek() instanceof PlannerJob job && job.runsIn(menu);
	}

	/** Whether {@code menu} shows a sort's result while it runs: the cursor shows nothing then. */
	public boolean showsFinished(AbstractContainerMenu menu) {
		return transfers.peek() instanceof PlannerJob job && job.showsFinished(menu);
	}

	/**
	 * You clicked in {@code menu} while an arrangement (a sort) runs in it: it stops, putting down what it holds on the
	 * cursor first, and the click is dropped (true), for you to make again on what the menu really holds: it may have been
	 * aimed at a sort's result shown before it's real, and it would pick up or drop what the sort was carrying.
	 */
	public boolean yourClick(AbstractContainerMenu menu) {
		if (!(transfers.peek() instanceof PlannerJob job) || !job.runsIn(menu)) return false;
		job.stop("You clicked in the container");
		return true;
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (current != null && (mc.player == null || mc.player.containerMenu != current.handler)) closeTracked();
		if (pending != null && --pendingTicks <= 0) fail(new TimeoutException("Container at " + pendingPos + " didn't open"));
		// Your inventory's screen over the held one shows what's on the cursor in that (inventoryClick).
		AbstractContainerMenu h = heldMenu();
		if (h != null && mc.gui.screen() instanceof InventoryScreen && !ItemStack.matches(h.getCarried(), mc.player.inventoryMenu.getCarried())) {
			mc.player.inventoryMenu.setCarried(h.getCarried().copy());
		}
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		closeTracked();
		if (pending != null) fail(new IllegalStateException("Left the world"));
	}

	private void closeTracked() {
		if (current == null) return;
		ViewImpl closed = current;
		current = null;
		if (closed == held) {
			held = null;
			heldScreen = null;
		}
		if (hiddenScreen != null && hiddenScreen.getMenu() == closed.handler) hiddenScreen = null;
		closeWanted = false;
		for (MenuJob t : transfers) t.abort("The container closed");
		transfers.clear();
		Myriad.events().post(new ContainerEvent.Closed(closed));
	}

	private void fail(Throwable t) {
		CompletableFuture<View> f = pending;
		pending = null;
		if (f != null) f.completeExceptionally(t);
	}

	// ---- API --------------------------------------------------------------------------------------------------------

	@Override
	public Optional<View> current() {
		return Optional.ofNullable(current);
	}

	@Override
	public CompletableFuture<View> open(BlockPos pos, int timeoutTicks) {
		return open(null, pos, timeoutTicks);
	}

	@Override
	public CompletableFuture<View> open(@Nullable Object owner, BlockPos pos, int timeoutTicks) {
		return open(owner, pos, timeoutTicks, null);
	}

	@Override
	public CompletableFuture<ItemSession> openItem(Object owner, int inventoryIndex, int ticks, Placement.Options options) {
		return ItemBoxes.open(owner, inventoryIndex, ticks, options);
	}

	@Override
	public CompletableFuture<ItemSession> openItem(Object owner, View from, int screenSlot, int ticks, Placement.Options options) {
		return ItemBoxes.open(owner, from, screenSlot, ticks, options);
	}

	@Override
	public CompletableFuture<ItemSession> putDownItem(Object owner, int inventoryIndex, int ticks, Placement.Options options) {
		return ItemBoxes.putDown(owner, inventoryIndex, ticks, options);
	}

	@Override
	public CompletableFuture<ItemSession> putDownItem(Object owner, View from, int screenSlot, int ticks, Placement.Options options) {
		return ItemBoxes.putDown(owner, from, screenSlot, ticks, options);
	}

	@Override
	public CompletableFuture<View> open(@Nullable Object owner, BlockPos pos, int timeoutTicks, @Nullable Placement.Options options) {
		Threads.assertRender();
		CompletableFuture<View> f = new CompletableFuture<>();
		double range = options == null ? Reach.blockRange() : options.range();
		BlockHitResult hit = Reach.hitFor(pos, false, range);
		// One out of reach leaves an open under way alone: asked again each tick while it's stuck, it would end every other.
		if (mc.player == null || hit == null) {
			f.completeExceptionally(new IllegalStateException("Can't reach " + pos));
			return f;
		}
		if (pending != null) fail(new IllegalStateException("Replaced by another open()"));
		if (current != null && pos.equals(current.pos) && current.loaded && unlinking == null) {
			f.complete(current);
			return f;
		}
		// Another container open stays so, its screen up until this one's replaces it: the server closes it itself as it
		// opens this one (ServerPlayer.openMenu), so no close goes first, none while you move (Grim's MultiActionsD) and
		// no frame of the world shows between the two.
		pending = f;
		pendingOwner = owner;
		pendingPos = pos.immutable();
		if (options == null) {
			pendingTicks = Math.max(1, timeoutTicks);
			// Sneaking with an item in hand would place it instead of opening the container.
			boolean sneaking = mc.player.isShiftKeyDown();
			if (sneaking) mc.player.setShiftKeyDown(false);
			Interactions.interactBlock(hit, InteractionHand.MAIN_HAND, true);
			if (sneaking) mc.player.setShiftKeyDown(true);
			return f;
		}
		// The timeout starts once the click is made; until then, it's the click's to give up.
		pendingTicks = Integer.MAX_VALUE;
		Placement.Attempt a = Myriad.placement().use(owner, hit, -1, options.withSneak(false));
		if (!a.sent()) {
			fail(new IllegalStateException("Can't click " + pos.toShortString() + ": " + a.check()));
			return f;
		}
		a.result().whenComplete((answered, t) -> {
			if (pending != f) return;
			if (t != null || !answered) fail(new IllegalStateException("The click on " + pos.toShortString() + " didn't go"));
			// The server answers the click before it opens the screen; the wait starts now.
			else if (current == null || !pos.equals(current.pos)) pendingTicks = Math.max(1, timeoutTicks);
		});
		return f;
	}

	@Override
	public void noWayBack(Object owner, NoWayBack what) {
		noWayBack.put(owner, what);
	}

	/** What a box {@code owner} takes out does with no way back (noWayBack). */
	NoWayBack noWayBackOf(Object owner) {
		return noWayBack.getOrDefault(owner, NoWayBack.REFUSE);
	}

	/** Whether {@code view} is an ender chest's: its block, or the held one opened over itself (its block may be gone). */
	boolean isEnderChest(View view) {
		return view instanceof ViewImpl v && (v.free || v.pos != null && mc.level != null && mc.level.getBlockState(v.pos).is(Blocks.ENDER_CHEST));
	}

	@Override
	public void swapBar(Object owner, boolean on) {
		MyriadImpl.get().containerTabs().want(owner, on);
	}

	@Override
	public boolean hold(View view) {
		if (!(view instanceof ViewImpl v) || v != current || mc.player == null || mc.player.containerMenu != v.handler) return false;
		// An ender chest is opened once more over itself: the server's ender chest inventory forgets its block when its
		// menu is replaced, after the new one's block was set (PlayerEnderChestContainer.stopOpen, called from openMenu's
		// close), and without a block it never checks your distance (stillValid). So it stays open however far away, and
		// with its block broken: one put down for you (a box) goes back and stays held.
		boolean ender = v.pos != null && mc.level.getBlockState(v.pos).is(Blocks.ENDER_CHEST);
		// Any other box put down for you goes back when you close it; holding it would leave it standing.
		if (!ender && MyriadImpl.get().containerTabs().isBox(v.pos)) return false;
		held = v;
		AbstractContainerScreen<?> screen = screenOf(v.handler);
		if (screen != null) heldScreen = screen;
		unlinkWanted = ender && !v.free;
		unlinkTries = 0;
		return true;
	}

	@Override
	public Optional<View> held() {
		return Optional.ofNullable(held);
	}

	@Override
	public boolean reopen() {
		if (held == null || heldScreen == null || mc.player == null || mc.player.containerMenu != held.handler) return false;
		if (mc.gui.screen() != heldScreen) mc.gui.setScreen(heldScreen);
		return true;
	}

	@Override
	public void release() {
		ViewImpl h = held;
		held = null;
		heldScreen = null;
		if (h != null && mc.player != null && mc.player.containerMenu == h.handler) mc.player.closeContainer();
	}

	/**
	 * The held container's screen is closing (from AbstractContainerScreenMixin, its onClose): true to keep it open on the
	 * server, sending no close. Not with something on the cursor, which closing puts back where the server would.
	 */
	public boolean keepOpen(AbstractContainerMenu menu) {
		// Your inventory's screen over the held one (showInventory): closing it leaves that open too.
		if (mc.player != null && menu == mc.player.inventoryMenu && heldMenu() != null) {
			if (held.handler.getCarried().isEmpty()) return true;
			held = null;
			heldScreen = null;
			return false;
		}
		if (held == null || held.handler != menu || mc.player == null || mc.player.containerMenu != menu) return false;
		if (!menu.getCarried().isEmpty()) {
			held = null;
			heldScreen = null;
			return false;
		}
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu) heldScreen = screen;
		return true;
	}

	/**
	 * Shows your inventory's screen: over the held container, if one is, which stays open on the server and takes that
	 * screen's clicks (inventoryClick), rather than showing that one instead.
	 */
	public void showInventory() {
		if (mc.player == null) return;
		showingInventory = true;
		try {
			mc.gui.setScreen(new InventoryScreen(mc.player));
		} finally {
			showingInventory = false;
		}
	}

	/**
	 * Closes the open container (a held one let go) as LocalPlayer.closeContainer does, but leaves the screen to the
	 * caller: hiding it grabs the mouse, and showing the next one lets it go again at the window's centre.
	 */
	public void closeUnderScreen() {
		if (mc.player == null) return;
		held = null;
		heldScreen = null;
		if (mc.player.containerMenu == mc.player.inventoryMenu) return;
		mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
		mc.player.containerMenu = mc.player.inventoryMenu;
	}

	/**
	 * A click in your inventory's screen shown over the held container (showInventory, from the mixin on
	 * MultiPlayerGameMode.handleContainerInput): the server takes clicks only in the held one, so it goes to that one's
	 * own slot for the same item. One on what it doesn't show (armour, the off hand, the crafting grid), or a shift or
	 * double click, which would move things into it, lets it go first, the close going out before the click. What's on
	 * the cursor then the close puts back into your inventory (AbstractContainerMenu.removed), so it's picked up again
	 * from there for the click: an armour piece carried onto its slot goes on. True if it was made here.
	 */
	public boolean inventoryClick(int containerId, int slotId, int button, ContainerInput input) {
		AbstractContainerMenu h = heldMenu();
		if (h == null || containerId != mc.player.inventoryMenu.containerId || !(mc.gui.screen() instanceof InventoryScreen)) return false;
		int to = slotId;
		if (slotId >= 0) {
			to = -1;
			Slot from = slotId < mc.player.inventoryMenu.slots.size() ? mc.player.inventoryMenu.slots.get(slotId) : null;
			if (from != null && from.container == mc.player.getInventory()) {
				for (Slot s : h.slots) if (s.container == from.container && s.getContainerSlot() == from.getContainerSlot()) to = s.index;
			}
		}
		boolean same = input == ContainerInput.PICKUP || input == ContainerInput.SWAP || input == ContainerInput.THROW
			|| input == ContainerInput.CLONE || input == ContainerInput.QUICK_CRAFT;
		if (same && (slotId < 0 || to >= 0)) {
			mc.gameMode.handleContainerInput(h.containerId, to, button, input, mc.player);
			mc.player.inventoryMenu.setCarried(h.getCarried().copy());
			return true;
		}
		ItemStack carried = h.getCarried().copy();
		closeUnderScreen();
		if (!carried.isEmpty()) {
			Inventory inventory = mc.player.getInventory();
			int back = inventory.getSlotWithRemainingSpace(carried);
			if (back < 0) back = inventory.getFreeSlot();
			mc.player.inventoryMenu.setCarried(ItemStack.EMPTY);
			if (back >= 0) {
				// Where the server puts it, the same way.
				inventory.placeItemBackInInventory(carried);
				mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, Slots.playerScreen(back), 0, ContainerInput.PICKUP, mc.player);
			}
		}
		return false;
	}

	/** The held container's menu while it's the one open (your inventory's clicks go through it then), else null. */
	public @Nullable AbstractContainerMenu heldMenu() {
		return held != null && mc.player != null && mc.player.containerMenu == held.handler ? held.handler : null;
	}

	/** Opening your inventory while a container's held shows that instead: it shows your inventory too, and takes its clicks. */
	@Subscribe
	private void onScreenOpen(ScreenOpenEvent e) {
		if (e.screen() instanceof InventoryScreen && !showingInventory && heldScreen != null && heldMenu() != null && !mc.player.isCreative()) e.setScreen(heldScreen);
		// Opened with no screen up, for code that only needs it open on the server: it shows none. The menu is the
		// client's already (MenuScreens sets it before showing the screen), so its clicks and contents go on as shown.
		else if (e.screen() instanceof AbstractContainerScreen<?> s && mc.gui.screen() == null && pending != null && pendingPos != null
			&& (pendingPos.equals(unseen) || pendingPos.equals(unlinking)) && mc.player != null && s.getMenu() == mc.player.containerMenu) {
			hiddenScreen = s;
			e.cancel();
		}
	}

	/**
	 * The next open in code of the container at {@code pos} shows no screen if none is up as it opens: a box put back into
	 * the container it came from, after you closed it all, opens it only for the server.
	 */
	public void unseen(BlockPos pos) {
		unseen = pos.immutable();
	}

	/** {@code menu}'s screen, showing or opened unseen; null if it has none. */
	private @Nullable AbstractContainerScreen<?> screenOf(AbstractContainerMenu menu) {
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu) return screen;
		return hiddenScreen != null && hiddenScreen.getMenu() == menu ? hiddenScreen : null;
	}

	@Override
	public void close() {
		if (mc.player == null || current == null) return;
		if (Myriad.inventory().prepareClick()) closeNow();
		else closeWanted = true;
	}

	private void closeNow() {
		closeWanted = false;
		// A close asked for in code is a close, held or not.
		if (current != null && current == held) {
			held = null;
			heldScreen = null;
		}
		// Only while it's still the menu open: one closed this tick (it's forgotten next tick) would send your inventory's close.
		if (mc.player != null && current != null && mc.player.containerMenu == current.handler) mc.player.closeContainer();
	}

	private final class ViewImpl implements View {
		private final AbstractContainerMenu handler;
		private final BlockPos pos;
		private final @Nullable Object opener;
		private final boolean byHand;
		private boolean loaded;
		/** An ender chest opened over itself, which the server keeps open anywhere (hold). */
		private boolean free;

		ViewImpl(AbstractContainerMenu handler, BlockPos pos, @Nullable Object opener, boolean byHand) {
			this.handler = handler;
			this.pos = pos;
			this.opener = opener;
			this.byHand = byHand;
		}

		@Override
		public @Nullable Object opener() {
			return opener;
		}

		@Override
		public boolean byHand() {
			return byHand;
		}

		@Override
		public AbstractContainerMenu handler() {
			return handler;
		}

		@Override
		public int syncId() {
			return handler.containerId;
		}

		@Override
		public @Nullable BlockPos pos() {
			return pos;
		}

		@Override
		public boolean isLoaded() {
			return loaded;
		}

		@Override
		public boolean isOpen() {
			return current == this;
		}

		@Override
		public int size() {
			return Math.max(0, handler.slots.size() - 36);
		}

		@Override
		public ItemStack stack(int slot) {
			return slot >= 0 && slot < size() ? handler.getSlot(slot).getItem() : ItemStack.EMPTY;
		}

		@Override
		public List<ItemStack> stacks() {
			List<ItemStack> out = new ArrayList<>(size());
			for (int i = 0; i < size(); i++) out.add(stack(i));
			return out;
		}

		@Override
		public int find(Predicate<ItemStack> predicate) {
			for (int i = 0; i < size(); i++) if (!stack(i).isEmpty() && predicate.test(stack(i))) return i;
			return -1;
		}

		@Override
		public int emptySlot() {
			for (int i = 0; i < size(); i++) if (stack(i).isEmpty()) return i;
			return -1;
		}

		@Override
		public int count(Predicate<ItemStack> predicate) {
			int n = 0;
			for (int i = 0; i < size(); i++) if (!stack(i).isEmpty() && predicate.test(stack(i))) n += stack(i).getCount();
			return n;
		}

		@Override
		public int playerSlot(int inventoryIndex) {
			return Slots.containerScreen(size(), inventoryIndex);
		}

		@Override
		public void quickMove(int slot) {
			click(slot, 0, ContainerInput.QUICK_MOVE);
		}

		@Override
		public void quickMoveFromPlayer(int inventoryIndex) {
			click(playerSlot(inventoryIndex), 0, ContainerInput.QUICK_MOVE);
		}

		@Override
		public void swapWithHotbar(int slot, int hotbarSlot) {
			click(slot, hotbarSlot, ContainerInput.SWAP);
		}

		@Override
		public void drop(int slot, boolean wholeStack) {
			click(slot, wholeStack ? 1 : 0, ContainerInput.THROW);
		}

		@Override
		public void click(int screenSlot, int button, ContainerInput action) {
			if (!isOpen() || mc.player == null || mc.gameMode == null) return;
			// Grim cancels a click while you move, without telling the client: skipped, with your keys released for
			// the next tick so it can be made then.
			if (!Myriad.inventory().prepareClick()) return;
			mc.gameMode.handleContainerInput(handler.containerId, screenSlot, button, action, mc.player);
		}

		@Override
		public CompletableFuture<Integer> take(Predicate<ItemStack> what, Transfer how) {
			return transfer(what, how, true);
		}

		@Override
		public CompletableFuture<Integer> put(Predicate<ItemStack> what, Transfer how) {
			return transfer(what, how, false);
		}

		private CompletableFuture<Integer> transfer(Predicate<ItemStack> what, Transfer how, boolean take) {
			if (!isOpen()) return CompletableFuture.failedFuture(new IllegalStateException("The container closed"));
			ContainerTransfer t = new ContainerTransfer(handler, what, take, how);
			transfers.add(t);
			return t.result;
		}

		@Override
		public CompletableFuture<Boolean> arrange(Arrangement arrangement) {
			return run(new Arranger(handler, arrangement));
		}

		@Override
		public CompletableFuture<Boolean> run(Planner planner) {
			if (!isOpen()) return CompletableFuture.failedFuture(new IllegalStateException("The container closed"));
			PlannerJob job = new PlannerJob(handler, planner);
			transfers.add(job);
			return job.result;
		}

		@Override
		public CompletableFuture<View> refresh() {
			if (!isOpen()) return CompletableFuture.failedFuture(new IllegalStateException("The container closed"));
			return sync.refresh(handler).thenApply(v -> this);
		}

		@Override
		public void close() {
			if (isOpen()) ContainerTracker.this.close();
		}
	}
}
