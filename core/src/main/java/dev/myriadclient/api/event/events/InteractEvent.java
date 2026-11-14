package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

/**
 * The local player is about to right-click: a block, an item in the air, or an entity. Posted for clicks from the
 * mouse and from features alike. Cancel to stop the interaction: nothing is sent and vanilla treats it as failed.
 * Posted on the render thread, only while something listens.
 */
public abstract class InteractEvent extends Cancellable {
	private final InteractionHand hand;

	/** Core's; addons don't make these. */
	protected InteractEvent(InteractionHand hand) {
		this.hand = hand;
	}

	/** The hand used: main or off (vanilla tries the main hand first, then the off hand). */
	public InteractionHand hand() {
		return hand;
	}

	/** Right-clicking a block face (placing, opening, using). */
	public static final class Block extends InteractEvent {
		private final BlockHitResult hit;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Block(InteractionHand hand, BlockHitResult hit) {
			super(hand);
			this.hit = hit;
		}

		/** The block, face and point clicked. */
		public BlockHitResult hit() {
			return hit;
		}
	}

	/** Using the held item without a block (eating, throwing, shooting). */
	public static final class Item extends InteractEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Item(InteractionHand hand) {
			super(hand);
		}
	}

	/** Right-clicking an entity (trading, mounting, naming). */
	public static final class EntityTarget extends InteractEvent {
		private final Entity entity;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public EntityTarget(InteractionHand hand, Entity entity) {
			super(hand);
			this.entity = entity;
		}

		/** The entity clicked. */
		public Entity entity() {
			return entity;
		}
	}
}
