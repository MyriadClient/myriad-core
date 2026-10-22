package dev.myriadclient.api.service;

import dev.myriadclient.api.setting.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standard settings groups add the settings they promise and build on the server's presets. */
class SettingsGroupsTest {
	private static final AntiCheat.Profile GRIM = AntiCheat.Profile.GRIM, VANILLA = AntiCheat.Profile.VANILLA;

	@Test
	void turnSpeedChangesOnlyTheSpeed() {
		Settings settings = new Settings();
		RotationSettings r = new RotationSettings(settings.group("General"));
		assertEquals("turn_speed", r.turnSpeed.id());
		r.turnSpeed.set(30);
		assertEquals(Rotations.Options.forServer().withTurnSpeed(30), r.options());
	}

	@Test
	void placementKeepsTheServersPresetBesideRangeAndAirPlace() {
		Settings settings = new Settings();
		PlacementSettings p = new PlacementSettings(settings.group("General"));
		p.range.set(5.0);
		assertEquals(Placement.Options.STRICT.withRange(4.5), p.options(GRIM, 4.5));
		p.airPlace.set(true);
		assertEquals(Placement.Options.DEFAULT.withRange(3).withAirPlace(true), p.options(VANILLA, 3));
	}

	@Test
	void fastBreaksPastGrimWithDecoysAndElsewhereAtOnce() {
		Settings settings = new Settings();
		BreakingSettings b = new BreakingSettings(settings.group("General"));
		assertEquals(Breaking.Options.PACKET, b.options(GRIM));
		// Where breaks aren't timed the server's preset is already fast; double break only comes with Fast.
		assertEquals(Breaking.Options.FAST.withDoubleBreak(false), b.options(VANILLA));
		assertFalse(b.doubleBreak.isVisible());
		b.mode.set(BreakingSettings.Mode.FAST);
		assertTrue(b.doubleBreak.isVisible());
		assertEquals(Breaking.Mode.FAST_GRIM, b.options(GRIM).mode());
		assertTrue(b.options(GRIM).doubleBreak());
		assertEquals(Breaking.Options.FAST, b.options(VANILLA));
		b.doubleBreak.set(false);
		assertFalse(b.options(GRIM).doubleBreak());
	}

	@Test
	void buildingCombinesTheGroupsAndBreaksAsFarAsItPlaces() {
		Settings settings = new Settings();
		BuildingSettings b = new BuildingSettings(settings.group("General"));
		assertFalse(b.breaking.mode.isVisible());
		b.breakWrong.set(true);
		assertTrue(b.breaking.mode.isVisible());
		b.actionsPerTick.set(4);
		Building.Options o = b.options(GRIM, 4);
		assertEquals(Placement.Options.STRICT.withRange(4), o.placing());
		assertEquals(Breaking.Options.PACKET.withRange(4), o.breaking());
		assertTrue(o.breakWrong());
		assertEquals(4, o.placesPerTick());
		assertTrue(new BuildingSettings(new Settings().group("General"), true).breakWrong.get());
	}
}
