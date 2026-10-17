package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

/**
 * The local player is about to right-click: a block, an item in the air, or an entity. Posted for clicks from the
 * mouse and from features alike. Cancel to stop the interaction.
 */
public abstract class InteractEvent extends Cancellable {
	private final InteractionHand hand;

	protected InteractEvent(InteractionHand hand) {
		this.hand = hand;
	}

	public InteractionHand hand() {
		return hand;
	}

	/** Right-clicking a block face (placing, opening, using). */
	public static final class Block extends InteractEvent {
		private final BlockHitResult hit;

		@ApiStatus.Internal
		public Block(InteractionHand hand, BlockHitResult hit) {
			super(hand);
			this.hit = hit;
		}

		public BlockHitResult hit() {
			return hit;
		}
	}

	/** Using the held item without a block (eating, throwing, shooting). */
	public static final class Item extends InteractEvent {
		@ApiStatus.Internal
		public Item(InteractionHand hand) {
			super(hand);
		}
	}

	/** Right-clicking an entity (trading, mounting, naming). */
	public static final class EntityTarget extends InteractEvent {
		private final Entity entity;

		@ApiStatus.Internal
		public EntityTarget(InteractionHand hand, Entity entity) {
			super(hand);
			this.entity = entity;
		}

		public Entity entity() {
			return entity;
		}
	}
}
