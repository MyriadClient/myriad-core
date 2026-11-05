package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rows and text in a narrow window: they give way, and nothing ends up past the width. */
class NarrowLayoutTest {
	/** Every character 4 wide and lines 8 high, whatever the font. */
	private static final Canvas CANVAS = (Canvas) Proxy.newProxyInstance(Canvas.class.getClassLoader(), new Class<?>[]{Canvas.class}, new InvocationHandler() {
		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) throws Throwable {
			return switch (m.getName()) {
				case "textWidth" -> 4f * ((String) a[a.length - 1]).length();
				case "textHeight" -> 8f;
				case "defaultFont" -> FontFamily.SANS;
				case "defaultFontSize" -> 8f;
				default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, a) : null;
			};
		}
	});

	private static float layout(Widget w, float width) {
		return w.layout(CANVAS, 0, 0, width);
	}

	private static void assertInside(HBox row, float width) {
		for (Widget c : row.children()) {
			if (!c.isVisible()) continue;
			assertTrue(c.x() >= 0 && c.x() + c.width() <= width + 0.01f, c.getClass().getSimpleName() + " at " + c.x() + " wide " + c.width() + " in " + width);
		}
	}

	@Test
	void aRoomyRowKeepsItsWidths() {
		HBox row = new HBox(4);
		Label name = row.addWeighted(new Label("Name"), 1);
		Button button = row.addFixed(new Button("Load", () -> {
		}), 40);
		assertEquals(14, layout(row, 200));
		assertEquals(40, button.width());
		assertEquals(156, name.width());
		assertEquals(160, button.x());
	}

	@Test
	void aTightRowShrinksFixedChildrenBeforeWrapping() {
		HBox row = new HBox(4);
		Label name = row.addWeighted(new Label("Name"), 1);
		Button button = row.addFixed(new Button("Load", () -> {
		}), 90);
		assertEquals(14, layout(row, 80), "still one line");
		assertTrue(button.width() < 90 && button.width() >= 24, "the button gave way: " + button.width());
		assertEquals(button.y(), 0);
		assertTrue(name.width() >= 16, "the name fits: " + name.width());
		assertInside(row, 80);
	}

	@Test
	void aRowThatCantFitWrapsAndFillsEachLine() {
		HBox row = new HBox(4);
		Label name = row.addWeighted(new Label("A rather long name here"), 1);
		Button load = row.addFixed(new Button("Load", () -> {
		}), 40);
		Button delete = row.addFixed(new Button("Delete", () -> {
		}), 44);
		float h = layout(row, 70);
		assertTrue(h > 14, "more than one line: " + h);
		assertEquals(70, name.width(), "the name has its line");
		assertTrue(load.y() > name.y(), "the buttons go under it");
		assertInside(row, 70);
		// A line of only buttons fills the width.
		if (load.y() == delete.y()) assertEquals(70, load.width() + 4 + delete.width(), 0.01f);
	}

	@Test
	void aSpacerNeverTakesALine() {
		HBox row = new HBox(4);
		row.addWeighted(Spacer.gap(0), 1);
		Button delete = row.addFixed(new Button("Delete", () -> {
		}), 44);
		assertEquals(14, layout(row, 44));
		assertEquals(0, delete.y());
		assertEquals(40, delete.width(), "all there is, beside the gap");
		assertInside(row, 44);
	}

	@Test
	void hiddenChildrenTakeNoRoom() {
		HBox row = new HBox(4);
		Button hidden = row.addFixed(new Button("Hidden", () -> {
		}), 100);
		hidden.visible(() -> false);
		Label name = row.addWeighted(new Label("Name"), 1);
		layout(row, 60);
		assertEquals(60, name.width());
		assertEquals(0, name.x());
	}

	@Test
	void aWordWiderThanTheLineBreaksInsideIt() {
		List<String> lines = Label.wrap(CANVAS, FontFamily.SANS, 8, "short AVeryLongWordThatDoesNotFit end", 40);
		assertEquals("shortAVeryLongWordThatDoesNotFitend", String.join("", lines).replace(" ", ""), "nothing lost");
		for (String line : lines) assertTrue(line.length() * 4 <= 40, "\"" + line + "\" is too wide");
	}

	@Test
	void wrappingStopsEvenWithNoRoomAtAll() {
		List<String> lines = Label.wrap(CANVAS, FontFamily.SANS, 8, "abc", 1);
		assertEquals(List.of("a", "b", "c"), lines);
	}

	/** A window that rebuilds (the Profiler, every second) makes a new view: its first frame mustn't lay out wider. */
	@Test
	void aNewScrollViewHasItsWidthFromTheFirstFrame() {
		VBox tall = new VBox(0, 0);
		for (int i = 0; i < 20; i++) tall.add(new Label("A row"));
		ScrollView view = new ScrollView(tall, Float.MAX_VALUE);
		view.setViewportHeight(50);
		layout(view, 100);
		assertEquals(96, tall.width(), "first frame");
		layout(view, 100);
		assertEquals(96, tall.width(), "second frame");
	}

	/** Unfolding enough settings to need the bar mustn't move what's already there. */
	@Test
	void contentOutgrowingItsScrollViewKeepsItsWidth() {
		VBox content = new VBox(0, 0);
		content.add(new Label("A row"));
		ScrollView view = new ScrollView(content, Float.MAX_VALUE);
		view.setViewportHeight(50);
		layout(view, 100);
		assertEquals(96, content.width(), "fits");
		for (int i = 0; i < 20; i++) content.add(new Label("A row"));
		layout(view, 100);
		assertEquals(96, content.width(), "just outgrew it");
		layout(view, 100);
		assertEquals(96, content.width(), "scrolling");
	}

	@Test
	void aScrollViewCanLeaveTheBarToItsChildsPadding() {
		VBox content = new VBox(0, 6);
		for (int i = 0; i < 20; i++) content.add(new Label("A row"));
		ScrollView view = new ScrollView(content, Float.MAX_VALUE).gutter(0);
		view.setViewportHeight(50);
		layout(view, 100);
		assertEquals(100, content.width());
	}

	@Test
	void aOneLineLabelIsCutShortAndNamesItselfInItsTooltip() {
		Label label = new Label("Something too long for this").oneLine();
		assertEquals(9, layout(label, 40), "one line");
		assertEquals("Something too long for this", label.tooltip);
		layout(label, 400);
		assertEquals(null, label.tooltip, "fits again: no tooltip");
	}
}
