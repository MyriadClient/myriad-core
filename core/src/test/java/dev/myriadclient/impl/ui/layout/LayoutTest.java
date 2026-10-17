package dev.myriadclient.impl.ui.layout;

import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.layout.LayoutState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
	private static final Rect AREA = new Rect(0, 0, 1000, 500);

	private static Map<String, Rect> arrange(LayoutState<String> s, float gap) {
		Map<String, Rect> out = new LinkedHashMap<>();
		s.arrange(AREA, gap, out::put);
		return out;
	}

	@Test
	void dwindleSplitsFocusedWindowAlongLongerSide() {
		LayoutState<String> s = new DwindleLayout().createState();
		s.add("a", null);
		assertEquals(AREA, arrange(s, 0).get("a"));
		s.add("b", "a");
		Map<String, Rect> r = arrange(s, 0);
		assertEquals(new Rect(0, 0, 500, 500), r.get("a"));
		assertEquals(new Rect(500, 0, 500, 500), r.get("b"));
		s.add("c", "b"); // b is 500x500 -> splits side by side
		r = arrange(s, 0);
		assertEquals(new Rect(500, 0, 250, 500), r.get("b"));
		assertEquals(new Rect(750, 0, 250, 500), r.get("c"));
	}

	@Test
	void dwindleGapsSeparateTilesAndKeepOuterEdges() {
		LayoutState<String> s = new DwindleLayout().createState();
		s.add("a", null);
		s.add("b", "a");
		Map<String, Rect> r = arrange(s, 10);
		assertEquals(0, r.get("a").x(), 1e-4);
		assertEquals(1000, r.get("b").right(), 1e-4);
		assertEquals(10, r.get("b").x() - r.get("a").right(), 1e-4);
	}

	@Test
	void dwindleRemoveSwapToggleAndSplitter() {
		LayoutState<String> s = new DwindleLayout().createState();
		s.add("a", null);
		s.add("b", "a");
		s.add("c", "b");
		s.remove("b");
		assertEquals(List.of("a", "c"), s.windows());
		s.swap("a", "c");
		assertEquals(List.of("c", "a"), s.windows());
		s.toggleSplit("a");
		Map<String, Rect> r = arrange(s, 0);
		assertEquals(new Rect(0, 0, 1000, 250), r.get("c"));
		LayoutState.Splitter sp = s.splitters().getFirst();
		sp.drag(0, 400);
		assertEquals(400, arrange(s, 0).get("c").h(), 1e-3);
	}

	@Test
	void dwindleToggleSplitOnlyFlipsTheFocusedSplit() {
		// A screen-shaped area: halves are taller than wide, so splitting a half stacks.
		Rect screen = new Rect(0, 0, 1000, 800);
		LayoutState<String> s = new DwindleLayout().createState();
		Map<String, Rect> r = new LinkedHashMap<>();
		s.add("a", null);
		s.arrange(screen, 0, r::put);
		s.add("b", "a");
		s.arrange(screen, 0, r::put);
		s.add("c", "a");
		s.arrange(screen, 0, r::put);
		s.add("d", "b");
		s.arrange(screen, 0, r::put);
		// A 2x2 grid: a over c on the left, b over d on the right.
		assertEquals(new Rect(0, 0, 500, 400), r.get("a"));
		assertEquals(new Rect(0, 400, 500, 400), r.get("c"));
		assertEquals(new Rect(500, 0, 500, 400), r.get("b"));
		assertEquals(new Rect(500, 400, 500, 400), r.get("d"));

		// Flipping a/c puts them side by side; b and d stay stacked.
		s.toggleSplit("c");
		s.arrange(screen, 0, r::put);
		assertEquals(new Rect(0, 0, 250, 800), r.get("a"));
		assertEquals(new Rect(250, 0, 250, 800), r.get("c"));
		assertEquals(new Rect(500, 0, 500, 400), r.get("b"));
		assertEquals(new Rect(500, 400, 500, 400), r.get("d"));
	}

	@Test
	void dwindleFlippingAParentKeepsItsChildrenSplits() {
		Rect screen = new Rect(0, 0, 1000, 800);
		LayoutState<String> s = new DwindleLayout().createState();
		Map<String, Rect> r = new LinkedHashMap<>();
		s.add("a", null);
		s.arrange(screen, 0, r::put);
		s.add("b", "a");
		s.arrange(screen, 0, r::put);
		s.add("c", "b"); // b over c on the right
		s.arrange(screen, 0, r::put);
		// Stack a above the b/c split: b/c used to turn side by side here because its box became wide.
		s.toggleSplit("a");
		s.arrange(screen, 0, r::put);
		assertEquals(new Rect(0, 0, 1000, 400), r.get("a"));
		assertEquals(new Rect(0, 400, 1000, 200), r.get("b"));
		assertEquals(new Rect(0, 600, 1000, 200), r.get("c"));
	}

	@Test
	void dwindleSavesAndLoadsDroppingMissingWindows() {
		LayoutState<String> s = new DwindleLayout().createState();
		s.add("a", null);
		s.add("b", "a");
		s.add("c", "b");
		var json = s.save(w -> w);
		LayoutState<String> loaded = new DwindleLayout().createState();
		loaded.load(json, k -> k.equals("b") ? null : k);
		assertEquals(List.of("a", "c"), loaded.windows());
		assertEquals(2, arrange(loaded, 0).size());
	}

	@Test
	void columnsFitWhileTheyCanThenScrollSideways() {
		LayoutState<String> s = new ColumnsLayout().createState();
		for (String w : List.of("a", "b", "c", "d")) s.add(w, null);
		assertEquals(250, arrange(s, 0).get("a").w(), 1e-3);
		assertFalse(s.scroll(-1), "everything fits: nothing to scroll");

		// Ten columns would be 100 wide: they stay readable at the minimum and the row overflows.
		for (String w : List.of("e", "f", "g", "h", "i", "j")) s.add(w, null);
		Map<String, Rect> r = arrange(s, 0);
		assertEquals(ColumnsLayout.MIN_WIDTH, r.get("a").w(), 1e-3);
		assertEquals(0, r.get("a").x(), 1e-3);
		assertTrue(s.scroll(-1));
		assertEquals(-60, arrange(s, 0).get("a").x(), 1e-3);
		s.scroll(100);
		assertEquals(0, arrange(s, 0).get("a").x(), 1e-3, "can't scroll past the start");

		// Focusing the last column brings it fully into view.
		s.reveal("j");
		r = arrange(s, 0);
		assertEquals(1000, r.get("j").x() + r.get("j").w(), 1e-3);
	}
}
