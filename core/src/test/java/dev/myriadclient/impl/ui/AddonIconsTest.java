package dev.myriadclient.impl.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonIconsTest {
	private static byte[] header(int w, int h) {
		ByteBuffer b = ByteBuffer.allocate(33);
		b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
		b.putInt(w).putInt(h);
		return b.array();
	}

	@Test
	void onlySensiblySizedPngsAreDecoded() throws Exception {
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", png);
		assertTrue(AddonIcons.saneSize(png.toByteArray()));
		assertFalse(AddonIcons.saneSize(header(30000, 30000)), "a small file claiming a huge image");
		assertFalse(AddonIcons.saneSize(header(0, 16)));
		assertFalse(AddonIcons.saneSize(header(-1, 16)), "sizes past 2^31 read as huge, not negative");
		assertFalse(AddonIcons.saneSize("<svg onload=alert(1)>".getBytes()));
		assertFalse(AddonIcons.saneSize(new byte[3]));
	}

	@Test
	void shrinkingAveragesEveryPixelItCovers() {
		// A 1-pixel checkerboard of black and white halves to grey, where every-other-pixel sampling would give one colour.
		int[] src = new int[16 * 16];
		for (int i = 0; i < src.length; i++) src[i] = ((i % 16 + i / 16) % 2 == 0) ? 0xFF000000 : 0xFFFFFFFF;
		for (int p : AddonIcons.resize(src, 16, 16, 4, 4)) assertEquals(0xFF808080, p, Integer.toHexString(p));
		// Thirds of a pixel: 3 to 2 still weighs each source pixel by how much of it each new pixel covers.
		int[] row = AddonIcons.resize(new int[] {0xFFFF0000, 0xFF00FF00, 0xFF0000FF}, 3, 1, 2, 1);
		assertEquals(0xFFAA5500, row[0], Integer.toHexString(row[0]));
		assertEquals(0xFF0055AA, row[1], Integer.toHexString(row[1]));
	}

	@Test
	void transparentPixelsDontDarkenEdges() {
		// Red next to fully transparent black: half as opaque, still pure red.
		int[] out = AddonIcons.resize(new int[] {0xFFFF0000, 0x00000000}, 2, 1, 1, 1);
		assertEquals(0x80FF0000, out[0], Integer.toHexString(out[0]));
		assertArrayEquals(new int[] {0}, AddonIcons.resize(new int[] {0, 0, 0, 0}, 2, 2, 1, 1));
	}
}
