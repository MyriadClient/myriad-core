package dev.myriadclient.api.event.events;

import dev.myriadclient.api.service.Containers;
import org.jetbrains.annotations.ApiStatus;

/**
 * A container (chest, shulker, furnace, villager trade, …) opening, receiving its contents, and closing. Use
 * {@link #view()} to read and click slots. See {@link Containers} to open one yourself. Posted on the render thread,
 * for containers opened by you or by code alike (not for your own inventory). Not cancellable.
 */
public abstract class ContainerEvent {
	private final Containers.View view;

	/** Core's; addons don't make these. */
	protected ContainerEvent(Containers.View view) {
		this.view = view;
	}

	/** The container: its slots, what's in them, and clicks on them. */
	public Containers.View view() {
		return view;
	}

	/** The server opened a container screen. Its slots may still be empty until {@link Loaded}. */
	public static final class Opened extends ContainerEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Opened(Containers.View view) {
			super(view);
		}
	}

	/** The container's contents arrived; slots are now accurate. */
	public static final class Loaded extends ContainerEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Loaded(Containers.View view) {
			super(view);
		}
	}

	/** One slot changed while the container is open (an item moved, a furnace finished smelting). */
	public static final class SlotUpdated extends ContainerEvent {
		private final int slot;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public SlotUpdated(Containers.View view, int slot) {
			super(view);
			this.slot = slot;
		}

		/** The screen slot id; below {@code view().size()} it's one of the container's own slots. */
		public int slot() {
			return slot;
		}
	}

	/** The container closed (by you, the server, or a new screen replacing it). */
	public static final class Closed extends ContainerEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Closed(Containers.View view) {
			super(view);
		}
	}
}
