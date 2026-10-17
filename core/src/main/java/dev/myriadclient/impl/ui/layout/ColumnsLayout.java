package dev.myriadclient.impl.ui.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Side-by-side columns with adjustable widths; the default for category windows (a tiled click-GUI). Columns never get
 * narrower than {@link #MIN_WIDTH}: when they don't all fit, the row scrolls sideways (Shift + mouse wheel, or the wheel
 * over a gap), and focusing a column scrolls it into view.
 */
public final class ColumnsLayout implements Layout {
	public static final MyriadId ID = MyriadId.of("myriad", "columns");
	/** The narrowest a column gets, in UI units: a category window's module names still fit. */
	public static final float MIN_WIDTH = 130;
	/** How far one wheel notch scrolls. */
	private static final float SCROLL_STEP = 60;

	@Override
	public MyriadId id() {
		return ID;
	}

	@Override
	public String name() {
		return "Columns";
	}

	@Override
	public <W> LayoutState<W> createState() {
		return new State<>();
	}

	static final class State<W> implements LayoutState<W> {
		private final List<W> windows = new ArrayList<>();
		/** Relative widths, parallel to {@link #windows}. */
		private final List<Float> weights = new ArrayList<>();
		private final List<Splitter> splitters = new ArrayList<>();

		@Override
		public void add(W window, W focused) {
			int at = focused == null ? windows.size() : windows.indexOf(focused) + 1;
			if (at <= 0) at = windows.size();
			windows.add(at, window);
			weights.add(at, 1f);
		}

		@Override
		public void remove(W window) {
			int i = windows.indexOf(window);
			if (i < 0) return;
			windows.remove(i);
			weights.remove(i);
		}

		@Override
		public List<W> windows() {
			return Collections.unmodifiableList(windows);
		}

		private float total() {
			float t = 0;
			for (float w : weights) t += w;
			return t;
		}

		/** Sideways scroll (0 = first column at the left edge); clamped on each arrange. */
		private float scroll;
		/** Set by {@link #reveal}: the window to bring into view on the next arrange. */
		private W revealing;
		/** The last arrange's geometry: area width and each column's offset from the row's start and width. */
		private float areaWidth, contentWidth;
		private float[] offsets = new float[0], widths = new float[0];

		@Override
		public void arrange(Rect area, float gap, BiConsumer<W, Rect> out) {
			splitters.clear();
			int n = windows.size();
			if (n == 0) return;
			float usable = area.w() - gap * (n - 1), total = total();
			if (offsets.length != n) {
				offsets = new float[n];
				widths = new float[n];
			}
			float x = 0;
			for (int i = 0; i < n; i++) {
				widths[i] = Math.max(MIN_WIDTH, usable * weights.get(i) / total);
				offsets[i] = x;
				x += widths[i] + gap;
			}
			areaWidth = area.w();
			contentWidth = x - gap;
			if (revealing != null) {
				int i = windows.indexOf(revealing);
				revealing = null;
				if (i >= 0) {
					if (offsets[i] < scroll) scroll = offsets[i];
					else if (offsets[i] + widths[i] > scroll + areaWidth) scroll = offsets[i] + widths[i] - areaWidth;
				}
			}
			scroll = Math.clamp(scroll, 0, Math.max(0, contentWidth - areaWidth));
			for (int i = 0; i < n; i++) {
				float left = area.x() + offsets[i] - scroll;
				out.accept(windows.get(i), new Rect(left, area.y(), widths[i], area.h()));
				if (i < n - 1) {
					int index = i;
					float boundary = left + widths[i];
					splitters.add(new Splitter() {
						@Override
						public Rect bounds() {
							return new Rect(boundary - 2, area.y(), gap + 4, area.h());
						}

						@Override
						public boolean vertical() {
							return true;
						}

						@Override
						public void drag(float mouseX, float mouseY) {
							// Move the boundary between columns index and index+1, keeping their combined width.
							float pairStart = area.x() + offsets[index] - scroll, pairWidth = widths[index] + widths[index + 1];
							float pair = weights.get(index) + weights.get(index + 1);
							float f = Math.clamp((mouseX - pairStart) / pairWidth, 0.1f, 0.9f);
							weights.set(index, pair * f);
							weights.set(index + 1, pair * (1 - f));
						}
					});
				}
			}
		}

		@Override
		public boolean scroll(float amount) {
			if (contentWidth <= areaWidth) return false;
			scroll -= amount * SCROLL_STEP;
			return true;
		}

		@Override
		public void reveal(W window) {
			revealing = window;
		}

		@Override
		public void swap(W a, W b) {
			int i = windows.indexOf(a), j = windows.indexOf(b);
			if (i < 0 || j < 0) return;
			Collections.swap(windows, i, j);
		}

		@Override
		public void resize(W window, float amount) {
			int i = windows.indexOf(window);
			if (i < 0 || windows.size() < 2) return;
			int other = i + 1 < windows.size() ? i + 1 : i - 1;
			float delta = total() * amount;
			if (weights.get(i) + delta < 0.2f || weights.get(other) - delta < 0.2f) return;
			weights.set(i, weights.get(i) + delta);
			weights.set(other, weights.get(other) - delta);
		}

		@Override
		public List<Splitter> splitters() {
			return splitters;
		}

		@Override
		public JsonObject save(Function<W, String> keys) {
			JsonObject o = new JsonObject();
			JsonArray a = new JsonArray();
			for (int i = 0; i < windows.size(); i++) {
				JsonObject e = new JsonObject();
				e.addProperty("window", keys.apply(windows.get(i)));
				e.addProperty("weight", weights.get(i));
				a.add(e);
			}
			o.add("columns", a);
			return o;
		}

		@Override
		public void load(JsonObject json, Function<String, W> lookup) {
			windows.clear();
			weights.clear();
			if (!json.has("columns")) return;
			for (JsonElement el : json.getAsJsonArray("columns")) {
				JsonObject e = el.getAsJsonObject();
				W w = lookup.apply(e.get("window").getAsString());
				if (w == null) continue;
				windows.add(w);
				weights.add(e.has("weight") ? e.get("weight").getAsFloat() : 1f);
			}
		}
	}
}
