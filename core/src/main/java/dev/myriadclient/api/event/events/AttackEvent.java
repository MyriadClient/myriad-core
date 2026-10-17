package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * You are about to attack an entity: posted just before the attack packet is sent, whoever sends it (vanilla
 * clicks, a module, {@code Interactions.attack}). Cancel to stop the attack. Packets you send from a handler go out
 * before the attack, which is how criticals work.
 */
public final class AttackEvent extends Cancellable {
	private final Entity target;

	@ApiStatus.Internal
	public AttackEvent(Entity target) {
		this.target = target;
	}

	public Entity target() {
		return target;
	}
}
