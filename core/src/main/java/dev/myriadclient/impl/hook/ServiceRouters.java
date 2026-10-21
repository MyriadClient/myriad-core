package dev.myriadclient.impl.hook;

import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * What {@code Myriad.rotations()}, {@code inventory()}, {@code placement()} and {@code breaking()} return: each call
 * that names an owner goes to the implementation for that owner ({@link HookManager#current(Service, Object)}: a
 * replacement scoped to its addon, the one for everyone, or core's); the rest go to the one for everyone. Generated
 * from the services' methods; keep in step with them.
 */
final class ServiceRouters {
	private ServiceRouters() {
	}

	static final class RotationsRouter implements Rotations {
		private final HookManager hooks;

		RotationsRouter(HookManager hooks) {
			this.hooks = hooks;
		}

		private Rotations all() {
			return hooks.current(Service.ROTATIONS);
		}

		private Rotations of(Object owner) {
			return hooks.current(Service.ROTATIONS, owner);
		}

		@Override
		public void request(Object owner, float yaw, float pitch, int priority, Rotations.Options options, Runnable afterSent) {
			of(owner).request(owner, yaw, pitch, priority, options, afterSent);
		}

		@Override
		public void sendRotationThisTick() {
			all().sendRotationThisTick();
		}

		@Override
		public float[] rotationForAction() {
			return all().rotationForAction();
		}

		@Override
		public boolean isRotating() {
			return all().isRotating();
		}

		@Override
		public float serverYaw() {
			return all().serverYaw();
		}

		@Override
		public float serverPitch() {
			return all().serverPitch();
		}

		@Override
		public float[] anglesTo(Vec3 target) {
			return all().anglesTo(target);
		}
	}

	static final class InventoryRouter implements Inventory {
		private final HookManager hooks;

		InventoryRouter(HookManager hooks) {
			this.hooks = hooks;
		}

		private Inventory all() {
			return hooks.current(Service.INVENTORY);
		}

		private Inventory of(Object owner) {
			return hooks.current(Service.INVENTORY, owner);
		}

		@Override
		public int serverSlot() {
			return all().serverSlot();
		}

		@Override
		public void select(int hotbarSlot) {
			all().select(hotbarSlot);
		}

		@Override
		public void silentSwap(int hotbarSlot, Runnable action) {
			all().silentSwap(hotbarSlot, action);
		}

		@Override
		public boolean hold(Object owner, int hotbarSlot, int maxTicks) {
			return of(owner).hold(owner, hotbarSlot, maxTicks);
		}

		@Override
		public boolean holdWeakly(Object owner, int hotbarSlot, int maxTicks) {
			return of(owner).holdWeakly(owner, hotbarSlot, maxTicks);
		}

		@Override
		public void release(Object owner) {
			of(owner).release(owner);
		}

		@Override
		public boolean isHolding() {
			return all().isHolding();
		}

		@Override
		public ItemStack serverItem() {
			return all().serverItem();
		}

		@Override
		public float attackCharge() {
			return all().attackCharge();
		}

		@Override
		public int findInHotbar(Predicate<ItemStack> predicate) {
			return all().findInHotbar(predicate);
		}

		@Override
		public int findInInventory(Predicate<ItemStack> predicate) {
			return all().findInInventory(predicate);
		}

		@Override
		public int bestInHotbar(ToDoubleFunction<ItemStack> score) {
			return all().bestInHotbar(score);
		}

		@Override
		public int bestInInventory(ToDoubleFunction<ItemStack> score) {
			return all().bestInInventory(score);
		}

		@Override
		public void spare(Object owner, @Nullable Predicate<ItemStack> spared) {
			of(owner).spare(owner, spared);
		}

		@Override
		public boolean isSpared(ItemStack stack) {
			return all().isSpared(stack);
		}

		@Override
		public int count(Predicate<ItemStack> predicate) {
			return all().count(predicate);
		}

		@Override
		public boolean moveToHotbar(int inventoryIndex, int hotbarSlot) {
			return all().moveToHotbar(inventoryIndex, hotbarSlot);
		}

		@Override
		public int ensureInHotbar(Predicate<ItemStack> predicate, int preferredSlot) {
			return all().ensureInHotbar(predicate, preferredSlot);
		}

		@Override
		public boolean safeToClick() {
			return all().safeToClick();
		}

		@Override
		public boolean prepareClick() {
			return all().prepareClick();
		}

		@Override
		public boolean clickWaiting() {
			return all().clickWaiting();
		}

		@Override
		public void keepKeys() {
			all().keepKeys();
		}

		@Override
		public int pullToHotbar(int inventoryIndex, Predicate<ItemStack> replaceable) {
			return all().pullToHotbar(inventoryIndex, replaceable);
		}

		@Override
		public int borrow(Object owner, int inventoryIndex, Predicate<ItemStack> replaceable) {
			return of(owner).borrow(owner, inventoryIndex, replaceable);
		}

		@Override
		public void giveBack(Object owner) {
			of(owner).giveBack(owner);
		}

		@Override
		public ItemStack shownInHotbar(int hotbarSlot) {
			return all().shownInHotbar(hotbarSlot);
		}

		@Override
		public void mask(Object owner, long holdMs, int... inventoryIndexes) {
			of(owner).mask(owner, holdMs, inventoryIndexes);
		}

		@Override
		public void unmask(Object owner) {
			of(owner).unmask(owner);
		}

		@Override
		public ItemStack shown(int inventoryIndex) {
			return all().shown(inventoryIndex);
		}

		@Override
		public int shownArmor() {
			return all().shownArmor();
		}

		@Override
		public boolean move(int from, int to) {
			return all().move(from, to);
		}

		@Override
		public boolean merge(int from, int to) {
			return all().merge(from, to);
		}

		@Override
		public boolean swapWithOffhand(int inventoryIndex) {
			return all().swapWithOffhand(inventoryIndex);
		}

		@Override
		public boolean quickMove(int inventoryIndex) {
			return all().quickMove(inventoryIndex);
		}

		@Override
		public boolean drop(int inventoryIndex, boolean wholeStack) {
			return all().drop(inventoryIndex, wholeStack);
		}

		@Override
		public CompletableFuture<Void> refresh() {
			return all().refresh();
		}
	}

	static final class PlacementRouter implements Placement {
		private final HookManager hooks;

		PlacementRouter(HookManager hooks) {
			this.hooks = hooks;
		}

		private Placement all() {
			return hooks.current(Service.PLACEMENT);
		}

		private Placement of(Object owner) {
			return hooks.current(Service.PLACEMENT, owner);
		}

		@Override
		public Placement.Check check(BlockPos pos, Placement.Options options) {
			return all().check(pos, options);
		}

		@Override
		public Placement.Attempt place(Object owner, BlockPos pos, int hotbarSlot, Placement.Options options) {
			return of(owner).place(owner, pos, hotbarSlot, options);
		}

		@Override
		public Placement.Attempt place(Object owner, BlockHitResult hit, int hotbarSlot, Placement.Options options) {
			return of(owner).place(owner, hit, hotbarSlot, options);
		}

		@Override
		public Placement.Attempt use(Object owner, BlockHitResult hit, int hotbarSlot, Placement.Options options) {
			return of(owner).use(owner, hit, hotbarSlot, options);
		}

		@Override
		public void cancel(Object owner) {
			of(owner).cancel(owner);
		}

		@Override
		public List<BlockHitResult> clickTargets(BlockPos pos) {
			return all().clickTargets(pos);
		}

		@Override
		public boolean isPending(BlockPos pos) {
			return all().isPending(pos);
		}

		@Override
		public int offHandSlot() {
			return all().offHandSlot();
		}

		@Override
		public boolean canPlaceWith(int hotbarSlot) {
			return all().canPlaceWith(hotbarSlot);
		}
	}

	static final class BreakingRouter implements Breaking {
		private final HookManager hooks;

		BreakingRouter(HookManager hooks) {
			this.hooks = hooks;
		}

		private Breaking all() {
			return hooks.current(Service.BREAKING);
		}

		private Breaking of(Object owner) {
			return hooks.current(Service.BREAKING, owner);
		}

		@Override
		public Breaking.Check check(BlockPos pos, Breaking.Options options) {
			return all().check(pos, options);
		}

		@Override
		public Breaking.Attempt breakBlock(Object owner, BlockPos pos, int priority, Breaking.Options options) {
			return of(owner).breakBlock(owner, pos, priority, options);
		}

		@Override
		public void cancel(Object owner) {
			of(owner).cancel(owner);
		}

		@Override
		public void cancel(BlockPos pos) {
			all().cancel(pos);
		}

		@Override
		public boolean isPending(BlockPos pos) {
			return all().isPending(pos);
		}

		@Override
		public float progress(BlockPos pos) {
			return all().progress(pos);
		}

		@Override
		public boolean canRebreak(BlockPos pos) {
			return all().canRebreak(pos);
		}
	}
}
