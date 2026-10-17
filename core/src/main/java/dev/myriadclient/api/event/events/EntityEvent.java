package dev.myriadclient.api.event.events;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/** Entities entering and leaving the client world, popping totems and dying. Posted on the render thread. */
public abstract class EntityEvent {
	private final Entity entity;

	protected EntityEvent(Entity entity) {
		this.entity = entity;
	}

	public Entity entity() {
		return entity;
	}

	/** An entity was added to the world (spawned, or came into view). */
	public static final class Added extends EntityEvent {
		@ApiStatus.Internal
		public Added(Entity entity) {
			super(entity);
		}
	}

	/**
	 * A living entity used a totem of undying (the server shows the pop to everyone near). {@link #pops()} counts its pops
	 * since it last died, this one included, as {@code Myriad.server().totemPops} does.
	 */
	public static final class TotemPopped extends EntityEvent {
		private final int pops;

		@ApiStatus.Internal
		public TotemPopped(Entity entity, int pops) {
			super(entity);
			this.pops = pops;
		}

		public int pops() {
			return pops;
		}
	}

	/** A living entity died (the server shows the death to everyone near), with the totems it had used before. */
	public static final class Died extends EntityEvent {
		private final int pops;

		@ApiStatus.Internal
		public Died(Entity entity, int pops) {
			super(entity);
			this.pops = pops;
		}

		/** Totems it used since it last died. */
		public int pops() {
			return pops;
		}
	}

	/** An entity is about to be removed; it's still in the world while handlers run. */
	public static final class Removed extends EntityEvent {
		private final Entity.RemovalReason reason;

		@ApiStatus.Internal
		public Removed(Entity entity, Entity.RemovalReason reason) {
			super(entity);
			this.reason = reason;
		}

		public Entity.RemovalReason reason() {
			return reason;
		}
	}
}
