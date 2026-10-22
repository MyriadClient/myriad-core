package dev.myriadclient.harness;

import dev.myriadclient.api.util.Interactions;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hits as the server sees them. */
class CombatHarnessTest {
	@Test
	void aHitGoesBeforeTheMovementPacketAndItsSwingAfterIt() {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 1, 0.5, 0, 0);
			Zombie zombie = new Zombie(h.level);
			zombie.setPos(0.5, 1, 2.5);
			h.level.add(zombie);
			// Turned this tick too, so there is a movement packet to come after the hit.
			h.onTick(() -> {
				h.player.setYRot(10);
				Interactions.attack(zombie, true);
			}).tick();
			assertEquals(List.of("Attack", "Swing", "MovePlayer"), h.kinds(1));
		}
	}
}
