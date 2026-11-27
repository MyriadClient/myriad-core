package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spacer.fill(): what follows it sits at a panel's foot while the content is short, and just ends it once it scrolls. */
class SpacerFillTest {
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

	private static float footTop(float content, float view) {
		VBox column = new VBox(4, 6);
		column.add(Spacer.gap(content));
		column.add(Spacer.fill());
		Widget foot = column.add(Spacer.gap(10));
		ScrollView scroll = new ScrollView(column, Float.MAX_VALUE);
		scroll.setViewportHeight(view);
		scroll.layout(CANVAS, 0, 0, 200);
		scroll.layout(CANVAS, 0, 0, 200);
		return foot.y;
	}

	@Test
	void shortContentPutsTheFootAtTheBottom() {
		// 200 tall: the foot (10) ends 6 above the bottom, where the column's padding is.
		assertEquals(200 - 6 - 10, footTop(50, 200), 0.01);
	}

	@Test
	void longContentJustEndsWithIt() {
		// Content taller than the view: the fill takes nothing, the foot follows the content (6 + 300 + 4 + 0 + 4).
		assertEquals(6 + 300 + 4 + 4, footTop(300, 200), 0.01);
	}

	@Test
	void withoutAFixedHeightItTakesNothing() {
		VBox column = new VBox(0, 0);
		column.add(Spacer.gap(20));
		column.add(Spacer.fill());
		ScrollView scroll = new ScrollView(column, 500);
		scroll.layout(CANVAS, 0, 0, 200);
		assertEquals(20, scroll.layout(CANVAS, 0, 0, 200), 0.01);
	}
}
