package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.CompletableFuture;

/**
 * Runs a {@link Containers.Planner} (an {@link Arranger}, say) on an open menu: its clicks go out at the start of each
 * tick, as many as the packet budget and the server's rules allow, until it has nothing more to do. Then the server is
 * asked what it really has ({@link MenuSync}), and if the planner isn't done, it goes again, three rounds at most.
 * Completes with whether the planner got there; fails if the menu closes, or something else clicks in it meanwhile
 * (a module, or you).
 */
final class PlannerJob implements MenuJob {
	private static final int ROUNDS = 3;
	/** Clicks a round may take: far past any real arrangement (a double chest sorted from scratch is about 120). */
	private static final int MAX_CLICKS = 600;

	private final Minecraft mc = Minecraft.getInstance();
	private final AbstractContainerMenu menu;
	private final Containers.Planner planner;
	final CompletableFuture<Boolean> result = new CompletableFuture<>();
	private CompletableFuture<Void> check;
	/** What the cursor held after the last click made here: anything else means a click came from elsewhere. */
	private ItemStack leftOnCursor;
	private int rounds, clicks;

	PlannerJob(AbstractContainerMenu menu, Containers.Planner planner) {
		this.menu = menu;
		this.planner = planner;
	}

	@Override
	public boolean tick(MenuSync sync) {
		if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != menu) return fail("The container closed");
		// Whatever is on the cursor when it starts is the planner's to place (it's read from the menu as it is).
		if (leftOnCursor == null) leftOnCursor = menu.getCarried().copy();
		if (check != null) {
			if (!check.isDone()) return false;
			check = null;
			if (planner.done() || ++rounds >= ROUNDS) {
				result.complete(planner.done());
				return true;
			}
			clicks = 0;
		}
		if (!ItemStack.matches(menu.getCarried(), leftOnCursor)) return fail("Something else clicked in the container");
		while (true) {
			Containers.Click c = clicks < MAX_CLICKS ? planner.next() : null;
			if (c == null) {
				// Done as far as it can tell, or stuck: what does the server say?
				if (!menu.getCarried().isEmpty()) return fail(planner.stuck() != null ? planner.stuck() : "Items left on the cursor");
				check = sync.refresh(menu);
				return false;
			}
			if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) || !Myriad.inventory().prepareClick()) return false;
			mc.gameMode.handleContainerInput(menu.containerId, c.slot(), c.button(), c.input(), mc.player);
			clicks++;
			leftOnCursor = menu.getCarried().copy();
		}
	}

	@Override
	public void abort(String why) {
		result.completeExceptionally(new IllegalStateException(why));
	}

	private boolean fail(String why) {
		abort(why);
		return true;
	}
}
