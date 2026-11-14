package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * You are about to attack an entity: posted just before the attack packet is sent, whoever sends it (vanilla
 * clicks, a module, {@code Interactions.attack}). Cancel to stop the attack. Packets you send from a handler go out
 * before the attack, which is how criticals work. Posted on the thread that sends the packet (the render thread for
 * every attack in practice), and only for an entity the client knows.
 */
public final class AttackEvent extends Cancellable {
	private final Entity target;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public AttackEvent(Entity target) {
		this.target = target;
	}

	/** The entity being attacked. */
	public Entity target() {
		return target;
	}
}
