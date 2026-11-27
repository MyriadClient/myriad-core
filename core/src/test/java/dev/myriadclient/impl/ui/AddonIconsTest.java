package dev.myriadclient.impl.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

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
}
