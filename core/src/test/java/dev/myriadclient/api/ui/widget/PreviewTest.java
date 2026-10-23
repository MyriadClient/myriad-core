package dev.myriadclient.api.ui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A preview turns with a drag, moves with a right-drag, zooms with the wheel within limits, and a double-click puts it back. */
class PreviewTest {
	private static Preview laidOut() {
		Preview p = Preview.viewModel().view(-30, 10);
		p.x = 0;
		p.y = 0;
		p.width = 200;
		p.height = 120;
		return p;
	}

	@Test
	void aDragTurnsItAndTipsItNoFurtherThanStraightDown() {
		Preview p = laidOut();
		assertTrue(p.mouseClicked(100, 60, 0));
		p.mouseDragged(110, 60, 0, 10, 0);
		assertEquals(-22, p.yaw(), 1e-4);
		p.mouseDragged(110, 400, 0, 0, 500);
		assertEquals(90, p.pitch(), 1e-4);
		assertTrue(p.mouseReleased(110, 400, 0));
		assertFalse(p.mouseDragged(120, 400, 0, 10, 0), "dragged after the release");
	}

	@Test
	void aRightDragMovesIt() {
		Preview p = laidOut();
		p.mouseClicked(100, 60, 1);
		p.mouseDragged(105, 63, 1, 5, 3);
		assertEquals(5, p.panX(), 1e-4);
		assertEquals(3, p.panY(), 1e-4);
		assertEquals(-30, p.yaw(), 1e-4, "turned by a right-drag");
	}

	@Test
	void theWheelZoomsWithinLimitsOnlyOverIt() {
		Preview p = laidOut();
		assertFalse(p.mouseScrolled(500, 500, 1), "zoomed from outside");
		for (int i = 0; i < 50; i++) p.mouseScrolled(100, 60, 1);
		assertEquals(6, p.zoom(), 1e-4);
		for (int i = 0; i < 100; i++) p.mouseScrolled(100, 60, -1);
		assertEquals(0.25, p.zoom(), 1e-4);
	}

	@Test
	void aDoubleClickPutsItBack() {
		Preview p = laidOut();
		p.mouseClicked(100, 60, 0);
		p.mouseDragged(140, 80, 0, 40, 20);
		p.mouseReleased(140, 80, 0);
		p.mouseScrolled(100, 60, 3);
		p.mouseClicked(100, 60, 0);
		p.mouseReleased(100, 60, 0);
		assertEquals(-30, p.yaw(), 1e-4);
		assertEquals(10, p.pitch(), 1e-4);
		assertEquals(1, p.zoom(), 1e-4);
	}

	@Test
	void clicksOutsideArentTaken() {
		assertFalse(laidOut().mouseClicked(300, 60, 0));
	}
}
