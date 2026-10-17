package com.example.myriadaddon.hooks;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A replacement for Myriad's placement service ({@code ctx.replace(Service.PLACEMENT)}), here only for this
 * addon's own modules ({@code Match.addon}): their placements go through it, everyone else's through core's. It only
 * counts what was placed and hands the rest to core's, but this is where your own placement logic would go: override
 * {@code place}, {@code check} or anything else and do it your way. Handing on to core's (as {@code super} does) keeps
 * it from fighting other addons over the one hand and the one rotation the server sees.
 * <p>
 * Extending {@link Placement.Forwarding} means you only write what you change, and methods Myriad adds later still
 * work (they go to core's). While it's the placement in use it gets events like an enabled module.
 */
public final class CountingPlacement extends Placement.Forwarding {
	private static final Logger LOG = LoggerFactory.getLogger("Example/Placement");
	private int placed;

	public CountingPlacement(Placement core) {
		super(core);
	}

	@Override
	public Attempt place(Object owner, BlockPos pos, int hotbarSlot, Options options) {
		Attempt attempt = super.place(owner, pos, hotbarSlot, options);
		attempt.result().thenAccept(ok -> {
			if (ok) placed++;
		});
		return attempt;
	}

	/** Runs only while this is the placement in use. */
	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		if (placed > 0) LOG.info("Placed {} blocks through Myriad's placement this session", placed);
		placed = 0;
	}

	@Override
	public void deactivated() {
		LOG.info("Back to Myriad's own placement");
	}
}
