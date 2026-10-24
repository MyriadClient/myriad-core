package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.AntiCheat.Profile;
import dev.myriadclient.api.service.AntiCheat.ServerSeen;
import dev.myriadclient.api.util.MyriadId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AntiCheatTrackerTest {
	private static ServerSeen at(String host, int pings) {
		return new ServerSeen(host, false, pings, 765);
	}

	@Test
	void knownHostsAndThePingsDecide() {
		AntiCheatTracker t = new AntiCheatTracker();
		assertSame(Profile.GRIM_2B2T, t.guess(at("2b2t.org", 0)));
		assertSame(Profile.GRIM_2B2T, t.guess(at("connect.2b2t.org", 0)));
		assertSame(Profile.NCP, t.guess(at("6b6t.org", 0)));
		assertSame(Profile.NCP, t.guess(at("play.6b6t.org", 0)));
		// If 6b6t ever runs Grim again, its pings say so.
		assertSame(Profile.GRIM, t.guess(at("6b6t.org", 5)));
		assertSame(Profile.VANILLA, t.guess(at("not6b6t.org", 4)));
		assertSame(Profile.GRIM, t.guess(at("example.net", 5)));
		assertSame(Profile.VANILLA, t.guess(new ServerSeen(null, true, 0, 775)));
		t.setKnownServers(List.of("Example.net:25565"));
		assertSame(Profile.GRIM, t.guess(at("example.net", 0)));
		assertSame(Profile.VANILLA, t.guess(at("2b2t.org", 0)));
	}

	@Test
	void aChoiceWaitsForTheAddonThatRegistersItAndGoesWithIt() {
		AntiCheatTracker t = new AntiCheatTracker();
		Profile mine = Profile.GRIM.named("my-addon:mine", "Mine").withSimulatesGlide(false);
		assertThrows(IllegalArgumentException.class, () -> t.choose(mine));

		t.setChoiceId(MyriadId.parse("my-addon:mine"));
		assertNull(t.choice());
		assertSame(Profile.VANILLA, t.profile());

		t.profiles().register(mine, "my-addon");
		assertSame(mine, t.choice());
		assertSame(mine, t.profile());

		t.profiles().unregisterAll("my-addon");
		assertNull(t.choice());
		assertEquals(MyriadId.parse("my-addon:mine"), t.choiceId());

		t.choose(Profile.NCP);
		assertSame(Profile.NCP, t.profile());
		t.choose(null);
		assertNull(t.choiceId());
	}
}
