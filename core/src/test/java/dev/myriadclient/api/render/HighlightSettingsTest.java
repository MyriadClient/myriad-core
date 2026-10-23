package dev.myriadclient.api.render;

import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.setting.Settings;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standard highlight options start where a module asks, and the constructors' defaults stay as they were. */
class HighlightSettingsTest {
	@Test
	void constructorsKeepTheirDefaults() {
		HighlightSettings h = new HighlightSettings(new Settings().group("Highlight"), () -> true, false);
		assertEquals(2, h.outlineWidth.get());
		assertEquals(0, h.glow.get());
		assertEquals(HighlightSettings.FillMode.NONE, h.fill.get());
		assertEquals(0.3, h.fillOpacity.get());
		assertEquals(2.5, h.dotSpacing.get());
		assertEquals(1, h.dotSize.get());
		assertFalse(h.gradient.get());
		assertNotNull(h.throughWalls);
		assertFalse(h.throughWalls.get());
		assertTrue(new HighlightSettings(new Settings().group("Highlight")).throughWalls.get());
	}

	@Test
	void builderStartsFromTheGivenLook() {
		HighlightStyle look = HighlightStyle.OUTLINE.withOutlineWidth(0).withGlow(5).withFill(HighlightStyle.Fill.SOLID).withFillOpacity(0.15f)
			.withGradient(0xFF00FF00).withThroughWalls(false);
		HighlightSettings h = HighlightSettings.builder(new Settings().group("Mining")).defaults(look).build();
		assertEquals(0, h.outlineWidth.get());
		assertEquals(5, h.glow.get());
		assertEquals(HighlightSettings.FillMode.SOLID, h.fill.get());
		// Rounded to the setting's decimals, not 0.15000000596.
		assertEquals(0.15, h.fillOpacity.get());
		assertTrue(h.gradient.get());
		assertEquals(0xFF00FF00, h.gradientColor.argb());
		assertFalse(h.throughWalls.get());
	}

	@Test
	void sharingABoxStylePutsBothInOneGroupWithEachSharedOptionShownForEither() {
		Settings settings = new Settings();
		boolean[] outline = {false};
		BoxStyle box = new BoxStyle(settings.group("Render"), () -> !outline[0]);
		HighlightSettings h = HighlightSettings.builder(settings.group("Render")).visible(() -> outline[0])
			.defaults(HighlightStyle.OUTLINE.withFill(HighlightStyle.Fill.SOLID)).sharing(box).build();
		assertSame(box.fillOpacity, h.fillOpacity);
		assertSame(box.throughWalls, h.throughWalls);
		assertEquals(1, settings.group("Render").settings().stream().filter(s -> s.name().equals("Fill Opacity")).count());
		assertTrue(h.fillOpacity.isVisible() && h.throughWalls.isVisible(), "box mode");
		outline[0] = true;
		assertTrue(h.fillOpacity.isVisible() && h.throughWalls.isVisible(), "outline mode with a fill");
		assertFalse(box.lineWidth.isVisible());
		h.fill.set(HighlightSettings.FillMode.NONE);
		assertFalse(h.fillOpacity.isVisible(), "outline mode with no fill");
	}

	@Test
	void throughWallsCanBeLeftOut() {
		Settings settings = new Settings();
		HighlightSettings h = HighlightSettings.builder(settings.group("Hand")).noThroughWalls().build();
		assertNull(h.throughWalls);
		assertTrue(settings.group("Hand").settings().stream().noneMatch(s -> s.name().equals("Through Walls")));
	}

	@Test
	void customFillsStartFromNone() {
		HighlightSettings h = HighlightSettings.builder(new Settings().group("Highlight"))
			.defaults(HighlightStyle.OUTLINE.withFill(HighlightStyle.Fill.DOTS)).build();
		assertEquals(HighlightSettings.FillMode.DOTS, h.fill.get());
	}

	@Test
	void firstHandHighlightWins() {
		HighlightEvent.Hand e = new HighlightEvent.Hand().reset(HighlightEvent.Hand.Part.ITEM, InteractionHand.OFF_HAND, HumanoidArm.LEFT, ItemStack.EMPTY, 0.5f);
		assertFalse(e.isHighlighted());
		e.highlight(HighlightStyle.OUTLINE, 0x00FFFFFF); // fully transparent: ignored
		assertFalse(e.isHighlighted());
		e.highlight(HighlightStyle.GLOW, 0xFFFF0000);
		e.highlight(HighlightStyle.OUTLINE, 0xFF00FF00);
		assertSame(HighlightStyle.GLOW, e.style());
		assertEquals(0xFFFF0000, e.color());
		assertEquals(InteractionHand.OFF_HAND, e.hand());
		assertEquals(HumanoidArm.LEFT, e.side());
		e.reset(HighlightEvent.Hand.Part.ARM, InteractionHand.MAIN_HAND, HumanoidArm.RIGHT, ItemStack.EMPTY, 0);
		assertFalse(e.isHighlighted());
		assertEquals(HighlightEvent.Hand.Part.ARM, e.part());
	}
}
