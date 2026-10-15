package dev.myriad.impl.service;

import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClickRulesTest {
	private static final Input STILL = Input.EMPTY;
	private static final Input WALKING = new Input(true, false, false, false, false, false, false);
	private static final Input JUMPING = new Input(false, false, false, false, true, false, false);
	private static final Input SNEAKING = new Input(false, false, false, false, false, true, false);

	@Test
	void versionsFallIntoTheirBands() {
		assertEquals(ClickRules.ALL, ClickRules.forProtocol(340), "1.12.2: sneaking counts before 1.15");
		assertEquals(ClickRules.SPRINT, ClickRules.forProtocol(573), "1.15");
		assertEquals(ClickRules.SPRINT, ClickRules.forProtocol(765), "1.20.4");
		assertEquals(ClickRules.SPRINT, ClickRules.forProtocol(767), "1.21.1");
		assertEquals(ClickRules.INPUT, ClickRules.forProtocol(768), "1.21.2");
		assertEquals(ClickRules.INPUT, ClickRules.forProtocol(769), "1.21.4");
		assertEquals(ClickRules.INPUT, ClickRules.forProtocol(772), "1.21.8");
		assertEquals(ClickRules.ALL, ClickRules.forProtocol(773), "1.21.9");
		assertEquals(ClickRules.ALL, ClickRules.forProtocol(776), "26.2");
	}

	@Test
	void anUnknownVersionGetsTheStrictestRules() {
		assertEquals(ClickRules.ALL, ClickRules.forProtocol(-1));
	}

	@Test
	void sprintingAlwaysRefuses() {
		for (ClickRules rules : ClickRules.values()) {
			assertTrue(rules.refuses(STILL, true), rules.name());
			assertFalse(rules.refuses(STILL, false), rules.name());
		}
	}

	@Test
	void olderVersionsClickWhileWalkingJumpingAndSneaking() {
		assertFalse(ClickRules.SPRINT.refuses(WALKING, false));
		assertFalse(ClickRules.SPRINT.refuses(JUMPING, false));
		assertFalse(ClickRules.SPRINT.refuses(SNEAKING, false));
	}

	@Test
	void fromOneTwentyOneTwoMovementKeysAndJumpCount() {
		assertTrue(ClickRules.INPUT.refuses(WALKING, false));
		assertTrue(ClickRules.INPUT.refuses(JUMPING, false));
		assertFalse(ClickRules.INPUT.refuses(SNEAKING, false));
	}

	@Test
	void fromOneTwentyOneNineSneakingCountsToo() {
		assertTrue(ClickRules.ALL.refuses(WALKING, false));
		assertTrue(ClickRules.ALL.refuses(SNEAKING, false));
	}

	@Test
	void twoBeeTwoTeeDoesNotJudgeTheKeys() {
		assertEquals(ClickRules.SNEAK, ClickRules.forServer(776, "2b2t.org"));
		assertEquals(ClickRules.SNEAK, ClickRules.forServer(776, "connect.2B2T.org:25565"));
		assertEquals(ClickRules.SPRINT, ClickRules.forServer(769, "2b2t.org"), "1.21.4 there: sprint alone");
		assertEquals(ClickRules.SPRINT, ClickRules.forServer(765, "2b2t.org"));
		assertEquals(ClickRules.ALL, ClickRules.forServer(776, "not2b2t.org"));
		assertEquals(ClickRules.ALL, ClickRules.forServer(776, null));
		assertFalse(ClickRules.SNEAK.refuses(WALKING, false));
		assertFalse(ClickRules.SNEAK.refuses(JUMPING, false));
		assertTrue(ClickRules.SNEAK.refuses(SNEAKING, false));
	}

	@Test
	void theKeysAloneLeaveSprintOut() {
		assertFalse(ClickRules.ALL.refusesKeys(STILL));
		assertTrue(ClickRules.ALL.refusesKeys(WALKING));
		assertFalse(ClickRules.SPRINT.refusesKeys(WALKING));
	}
}
