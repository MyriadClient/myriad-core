package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Containers opened by code: the click, the view once its contents arrive, clicks in it, and closing. */
class ContainersHarnessTest {
	private static final BlockPos CHEST = new BlockPos(1, 1, 0);

	private static Harness besideAChest() {
		Harness h = Harness.start();
		h.level.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
		h.level.set(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		h.level.set(CHEST, Blocks.CHEST.defaultBlockState());
		h.standAt(0.5, 1, 0.5, 0, 0);
		return h;
	}

	@Test
	void openingByCodeClicksTheChestAndHandsBackItsViewOnceTheContentsArrive() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			@SuppressWarnings("unchecked") CompletableFuture<Containers.View>[] open = new CompletableFuture[1];
			h.onTick(() -> open[0] = Myriad.containers().open(this, CHEST, 20, Placement.Options.forServer())).ticks(3);
			List<ServerboundUseItemOnPacket> clicks = h.sent(ServerboundUseItemOnPacket.class);
			assertEquals(1, clicks.size(), "one click on the chest");
			assertEquals(CHEST, clicks.getFirst().getHitResult().getBlockPos());
			assertFalse(open[0].isDone());
			h.serverOpensChest(7, List.of(new ItemStack(Items.DIAMOND, 5), ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 3)));
			Containers.View view = open[0].getNow(null);
			assertSame(this, view.opener());
			assertFalse(view.byHand());
			assertEquals(8, view.count(s -> s.is(Items.DIAMOND)));
		}
	}

	@Test
	void standingStillItClicksAndClosesInTheSameTickInThatOrder() {
		try (Harness h = besideAChest()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			@SuppressWarnings("unchecked") CompletableFuture<Containers.View>[] open = new CompletableFuture[1];
			h.onTick(() -> open[0] = Myriad.containers().open(this, CHEST, 20, Placement.Options.forServer())).ticks(3);
			h.serverOpensChest(7, List.of(new ItemStack(Items.DIAMOND, 5)));
			Containers.View view = open[0].getNow(null);
			h.clearSent();
			h.onTick(() -> {
				view.quickMove(view.find(s -> s.is(Items.DIAMOND)));
				Myriad.containers().close();
			}).tick();
			assertEquals(List.of("ContainerClick", "ContainerClose"), h.kinds(h.currentTick()));
			assertEquals(7, h.sent(ServerboundContainerClickPacket.class).getFirst().containerId());
			assertFalse(view.isOpen());
		}
	}
}
