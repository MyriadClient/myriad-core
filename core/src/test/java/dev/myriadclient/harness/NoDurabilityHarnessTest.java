package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.service.AntiCheat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No Durability against the server's own count of the glide. */
class NoDurabilityHarnessTest {
	/** An owner that always wants the elytra kept. */
	private static final NoDurability.Owner KEEP = new NoDurability.Owner() {
		@Override
		public boolean noDurability() {
			return true;
		}

		@Override
		public void info(String message) {
		}

		@Override
		public void warn(String message) {
		}
	};

	/** Gliding high up, both sides agreeing, for {@code ticks}; No Durability running if {@code keep}. */
	private static ServerGlide glide(Harness h, boolean keep, int ticks) {
		h.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
		ServerGlide server = startGlide(h, keep, false);
		h.ticks(ticks);
		return server;
	}

	/** Starts gliding high up, both sides agreeing; No Durability running if {@code keep}; Grim's pings if {@code grim}. */
	private static ServerGlide startGlide(Harness h, boolean keep, boolean grim) {
		h.player.setPos(0.5, 300, 0.5);
		h.player.setXRot(10);
		h.player.setDeltaMovement(0, -0.1, 1.2);
		h.player.startFallFlying();
		ServerGlide server = h.serverGlides(true, grim);
		if (keep) {
			NoDurability engine = Myriad.elytra().noDurability(KEEP);
			engine.enable();
			// As a flight does: before the tick's actions, after its own steering; off with the test, as a module turning off.
			Myriad.events().listen(TickEvent.Pre.class, Priority.BEFORE_ACTIONS + 10, e -> engine.tick());
			h.onClose(engine::disable);
		}
		return server;
	}

	@Test
	void withoutItTheServerWearsTheElytra() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			ServerGlide server = glide(h, false, 300);
			assertTrue(server.wear() >= 10, "the model didn't wear it: " + server.wear());
		}
	}

	@Test
	void whereTheGlideIsntSimulatedTwoStartsBreakItBeforeTheServerWearsAnything() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.VANILLA);
			ServerGlide server = glide(h, true, 300);
			assertEquals(0, server.wear(), "the server wore the elytra; longest glide " + server.longestGlide() + " ticks");
			assertTrue(server.longestGlide() < 20, "glided " + server.longestGlide() + " ticks in one go");
			assertTrue(h.player.isFallFlying(), "the client stopped gliding");
		}
	}

	@Test
	void whereGrimSimulatesTheGlideAChestplateBreaksItAndGrimSeesTheStopBeforeEachStart() {
		try (Harness h = Harness.start()) {
			Myriad.antiCheat().choose(AntiCheat.Profile.GRIM);
			h.give(3, new ItemStack(Items.DIAMOND_CHESTPLATE));
			h.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
			ServerGlide server = startGlide(h, true, true);
			h.ticks(300);
			assertEquals(0, server.wear(), "the server wore the elytra: glides " + server.glides());
			// After the first (arming) glide, the server glides a tick at a time.
			List<Integer> glides = server.glides();
			assertTrue(server.starts() > 30, "too few restarts: " + server.starts());
			assertTrue(glides.subList(1, glides.size()).stream().allMatch(g -> g <= 1), "glided longer: " + glides);
			// Grim takes the stop once its ping is answered: every ping that came before a start is answered before it.
			Map<Integer, Integer> answered = new HashMap<>();
			for (Harness.Sent s : h.sent()) {
				if (s.packet() instanceof ServerboundPongPacket pong) answered.put(pong.getId(), s.tick());
				if (s.packet() instanceof ServerboundPlayerCommandPacket c && c.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
					for (var ping : h.pingsDelivered().entrySet()) {
						if (ping.getValue() < s.tick()) assertTrue(answered.containsKey(ping.getKey()), "a start at tick " + s.tick() + " before ping " + ping.getKey() + " was answered");
					}
				}
			}
			// No answer is held past Grim's patience (a second).
			for (var ping : h.pingsDelivered().entrySet()) {
				Integer at = answered.get(ping.getKey());
				if (at != null) assertTrue(at - ping.getValue() <= 20, "ping " + ping.getKey() + " held " + (at - ping.getValue()) + " ticks");
			}
			assertTrue(h.player.isFallFlying(), "the client stopped gliding");
		}
	}
}
