package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.util.ColorUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The picker keeps its hue on colours that have none, and reads a colour that changed elsewhere. */
class ColorPickerTest {
	@Test
	void aHueMovedOnWhiteOrBlackStays() {
		float[] white = {0.5f, 0, 1};
		assertArrayEquals(white, ColorPicker.resync(white, 255, 0xFFFFFFFF));
		float[] black = {0.7f, 0.4f, 0};
		assertArrayEquals(black, ColorPicker.resync(black, 255, 0xFF000000));
	}

	@Test
	void aColourSetElsewhereIsReadAgain() {
		float[] red = ColorPicker.resync(new float[]{0.5f, 0, 1}, 255, 0xFFFF0000);
		assertEquals(0xFFFF0000, ColorUtil.hsb(red[0], red[1], red[2], 255));
		assertArrayEquals(ColorUtil.toHsb(0xFF00FF00), ColorPicker.resync(null, 255, 0xFF00FF00));
	}
}
