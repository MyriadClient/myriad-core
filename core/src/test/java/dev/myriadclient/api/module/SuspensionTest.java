package dev.myriadclient.api.module;

import dev.myriadclient.FakeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A module held off comes back on with the last suspender, and saves as the player has it. */
class SuspensionTest {
	static final class Flight extends Module {
		int enables, disables;

		Flight() {
			super(Tags.MOVEMENT, "Flight", "");
		}

		@Override
		protected void onEnable() {
			enables++;
		}

		@Override
		protected void onDisable() {
			disables++;
		}
	}

	static final class Searcher extends Module {
		Searcher() {
			super(Tags.WORLD, "Searcher", "");
		}
	}

	private Flight flight;

	@BeforeEach
	void setUp() {
		FakeApi api = FakeApi.install();
		flight = new Flight();
		api.modules().register(flight, "test");
	}

	@AfterEach
	void tearDown() {
		FakeApi.uninstall();
	}

	@Test
	void itComesBackWithTheLastSuspender() {
		flight.setEnabled(true);
		Object a = new Object(), b = new Object();
		Modules.suspend(a, flight);
		Modules.suspend(b, Flight.class);
		Modules.suspend(b, flight);
		assertFalse(flight.isEnabled());
		assertTrue(flight.isSuspended());
		assertTrue(flight.savesEnabled());
		assertNull(Modules.active(Flight.class));
		Modules.resume(a);
		assertFalse(flight.isEnabled());
		Modules.resume(b);
		assertTrue(flight.isEnabled());
		assertFalse(flight.isSuspended());
		assertEquals(2, flight.enables);
		assertEquals(1, flight.disables);
	}

	@Test
	void thePlayersChoiceWhileHeldIsKept() {
		Object job = new Object() {
			@Override
			public String toString() {
				return "Search › Flying the route";
			}
		};
		Modules.suspend(job, flight);
		assertFalse(flight.isSuspended());
		flight.setEnabled(true);
		assertFalse(flight.isEnabled());
		assertTrue(flight.savesEnabled());
		assertEquals("Search › Flying the route", flight.suspendedBy());
		flight.setEnabled(false);
		assertFalse(flight.savesEnabled());
		Modules.resume(job);
		assertFalse(flight.isEnabled());
		assertEquals(0, flight.enables);
	}

	@Test
	void aModuleThatTurnsOffLetsGo() {
		Searcher searcher = new Searcher();
		searcher.assignNamespace("test");
		flight.setEnabled(true);
		searcher.setEnabled(true);
		Modules.suspend(searcher, flight);
		assertEquals("Searcher", flight.suspendedBy());
		searcher.setEnabled(false);
		assertTrue(flight.isEnabled());
	}
}
