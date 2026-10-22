package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.Interactions;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.api.util.Slots;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

	private final Minecraft mc = Minecraft.getInstance();
	private final MenuSync sync;
	/** Takes and puts on the open container, in the order asked; the first is under way. */
	/** Moves and arrangements on the open container, run one after another. */
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
		MenuJob t = transfers.peek();
		if (t != null && t.tick(sync)) transfers.poll();
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (current != null && (mc.player == null || mc.player.containerMenu != current.handler)) closeTracked();
		if (pending != null && --pendingTicks <= 0) fail(new TimeoutException("Container at " + pendingPos + " didn't open"));
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
	public CompletableFuture<View> open(@Nullable Object owner, BlockPos pos, int timeoutTicks, @Nullable Placement.Options options) {
		if (pending != null) fail(new IllegalStateException("Replaced by another open()"));
		CompletableFuture<View> f = new CompletableFuture<>();
		double range = options == null ? Reach.blockRange() : options.range();
		BlockHitResult hit = Reach.hitFor(pos, false, range);
		if (mc.player == null || hit == null) {
			f.completeExceptionally(new IllegalStateException("Can't reach " + pos));
			return f;
		}
		if (current != null && pos.equals(current.pos) && current.loaded) {
			f.complete(current);
			return f;
		}
		// Another container is open: it closes first, as it would for a click on a block by hand.
		if (current != null) closeNow();
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
	public void close() {
		if (mc.player == null || current == null) return;
		if (Myriad.inventory().prepareClick()) closeNow();
		else closeWanted = true;
	}

	private void closeNow() {
		closeWanted = false;
		if (mc.player != null && current != null) mc.player.closeContainer();
	}

	private final class ViewImpl implements View {
		private final AbstractContainerMenu handler;
		private final BlockPos pos;
		private final @Nullable Object opener;
		private final boolean byHand;
		private boolean loaded;

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
