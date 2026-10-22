package dev.myriadclient.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outside a world (here: no game at all) every accessor throws, naming who asked. */
class ClientTest {
	@Test
	void outsideAWorldItSaysWhoAsked() {
		assertFalse(Client.inGame());
		IllegalStateException e = assertThrows(IllegalStateException.class, ClientTest::health);
		assertTrue(e.getMessage().startsWith("No player outside a world: ClientTest.health asked"), e.getMessage());
		assertThrows(IllegalStateException.class, Client::level);
		assertThrows(IllegalStateException.class, Client::connection);
		assertThrows(IllegalStateException.class, Client::interaction);
	}

	private static float health() {
		return Client.player().getHealth();
	}
}
