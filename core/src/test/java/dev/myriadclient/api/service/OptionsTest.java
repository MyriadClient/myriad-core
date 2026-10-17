package dev.myriadclient.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Options change one thing at a time and leave the rest as the preset had it. */
class OptionsTest {
	@Test
	void placementWithersChangeOnlyTheirOption() {
		Placement.Options o = Placement.Options.STRICT.withRange(6).withRotate(false);
		assertEquals(6, o.range());
		assertFalse(o.rotate());
		assertTrue(o.visibleFaces());
		assertTrue(o.swing());
		assertEquals(Placement.Options.STRICT, Placement.Options.STRICT.withRange(4.5));
		assertNotEquals(Placement.Options.STRICT, o);
	}

	@Test
	void breakingWithersChangeOnlyTheirOption() {
		Breaking.Options o = Breaking.Options.PACKET.withDoubleBreak(true).withAutoTool(false);
		assertEquals(Breaking.Mode.PACKET, o.mode());
		assertTrue(o.doubleBreak());
		assertFalse(o.autoTool());
		assertEquals(Breaking.Options.FAST, Breaking.Options.PACKET.withMode(Breaking.Mode.FAST).withDoubleBreak(true));
	}

	@Test
	void rotationAndBuildingOptions() {
		assertEquals(Rotations.Options.MOVE_FIX, Rotations.Options.INSTANT.withMoveFix(true));
		assertEquals(0, Rotations.Options.INSTANT.withTurnSpeed(-5).turnSpeed());
		Building.Options b = Building.Options.STRICT.withKeepUp(true).withPlacesPerTick(0);
		assertTrue(b.keepUp());
		assertEquals(1, b.placesPerTick());
		assertEquals(Placement.Options.STRICT, b.placing());
	}

	@Test
	void presetsForTheServerAreStrictBeforeMyriadStarts() {
		assertTrue(AntiCheat.strict());
		assertEquals(Placement.Options.STRICT, Placement.Options.forServer());
		assertEquals(Breaking.Options.PACKET, Breaking.Options.forServer());
	}
}
