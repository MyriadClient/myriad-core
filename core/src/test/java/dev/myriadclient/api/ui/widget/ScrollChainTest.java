package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scroll view in a scroll view: the inner one scrolls first, and at its end the wheel goes on to the outer one. */
class ScrollChainTest {
	private static final Canvas CANVAS = (Canvas) Proxy.newProxyInstance(Canvas.class.getClassLoader(), new Class<?>[]{Canvas.class}, new InvocationHandler() {
		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) throws Throwable {
			return switch (m.getName()) {
				case "textWidth" -> 4f * ((String) a[a.length - 1]).length();
				case "textHeight", "defaultFontSize" -> 8f;
				case "defaultFont" -> FontFamily.SANS;
				default -> m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, a) : null;
			};
		}
	});

	@Test
	void innerFirstThenOuter() {
		ScrollView inner = new ScrollView(Spacer.gap(300), 100);
		VBox page = new VBox(0, 0);
		page.add(Spacer.gap(50));
		page.add(inner);
		page.add(Spacer.gap(400));
		ScrollView outer = new ScrollView(page, 200);
		// Twice: a view sizes to its child once the child has been laid out.
		outer.layout(CANVAS, 0, 0, 200);
		outer.layout(CANVAS, 0, 0, 200);
		float x = 20, y = 60;
		// Down: the inner one's 200 units of overflow go first (24 a notch), and the outer one stays put.
		for (int i = 0; i < 8; i++) assertTrue(outer.mouseScrolled(x, y, -1));
		assertEquals(192, inner.scroll());
		assertEquals(0, outer.scroll());
		assertTrue(outer.mouseScrolled(x, y, -1));
		assertEquals(200, inner.scroll());
		assertEquals(0, outer.scroll());
		// At its bottom, the next notch scrolls the page.
		assertTrue(outer.mouseScrolled(x, y, -1));
		assertEquals(200, inner.scroll());
		assertEquals(24, outer.scroll());
		// Back up: the inner one scrolls up first (the mouse is still over it), then the page.
		assertTrue(outer.mouseScrolled(x, y, 1));
		assertEquals(176, inner.scroll());
		assertEquals(24, outer.scroll());
		inner.setScroll(0);
		assertTrue(outer.mouseScrolled(x, y, 1));
		assertEquals(0, outer.scroll());
		// Both at the top: nothing takes it, so it goes on past the outer one too.
		assertFalse(outer.mouseScrolled(x, y, 1));
	}
}
