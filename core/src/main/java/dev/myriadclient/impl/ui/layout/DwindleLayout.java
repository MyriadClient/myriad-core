package dev.myriadclient.impl.ui.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Hyprland's dwindle layout: a binary tree where each new window splits the focused one. A split's direction is
 * chosen from the shape of the box it splits (wide boxes split side by side) when it is created, and then kept, like
 * Hyprland with {@code preserve_split = true} (Omarchy's setting). toggleSplit flips only the focused window's own
 * split, so the windows inside other splits keep their arrangement, which is what makes grids possible.
 */
public final class DwindleLayout implements Layout {
	public static final MyriadId ID = MyriadId.of("myriad", "dwindle");

	@Override
	public MyriadId id() {
		return ID;
	}

	@Override
	public String name() {
		return "Dwindle";
	}

	@Override
	public <W> LayoutState<W> createState() {
		return new State<>();
	}

	static final class Node<W> {
		Node<W> parent, a, b;
		W window;
		float ratio = 0.5f;
		/** true = side by side, false = stacked; null only until the split is first laid out. */
		Boolean horizontal;
		Rect box = Rect.ZERO;

		boolean leaf() {
			return window != null;
		}
	}

	static final class State<W> implements LayoutState<W> {
		private Node<W> root;
		private final List<Splitter> splitters = new ArrayList<>();
		private float gap;

		private Node<W> find(Node<W> n, W w) {
			if (n == null) return null;
			if (n.leaf()) return n.window == w ? n : null;
			Node<W> r = find(n.a, w);
			return r != null ? r : find(n.b, w);
		}

		@Override
		public void add(W window, W focused) {
			Node<W> leaf = new Node<>();
			leaf.window = window;
			if (root == null) {
				root = leaf;
				return;
			}
			Node<W> target = focused != null ? find(root, focused) : null;
			if (target == null) target = lastLeaf(root);
			Node<W> split = new Node<>();
			split.parent = target.parent;
			if (target.parent == null) root = split;
			else if (target.parent.a == target) target.parent.a = split;
			else target.parent.b = split;
			split.a = target;
			split.b = leaf;
			target.parent = split;
			leaf.parent = split;
			split.box = target.box;
			// Decide the direction now from the box being split; before the first layout it's decided there instead.
			if (target.box.w() > 0 && target.box.h() > 0) split.horizontal = target.box.w() >= target.box.h();
		}

		private Node<W> lastLeaf(Node<W> n) {
			while (!n.leaf()) n = n.b;
			return n;
		}

		@Override
		public void remove(W window) {
			Node<W> n = find(root, window);
			if (n == null) return;
			Node<W> parent = n.parent;
			if (parent == null) {
				root = null;
				return;
			}
			Node<W> sibling = parent.a == n ? parent.b : parent.a;
			sibling.parent = parent.parent;
			if (parent.parent == null) root = sibling;
			else if (parent.parent.a == parent) parent.parent.a = sibling;
			else parent.parent.b = sibling;
		}

		@Override
		public List<W> windows() {
			List<W> out = new ArrayList<>();
			collect(root, out);
			return out;
		}

		private void collect(Node<W> n, List<W> out) {
			if (n == null) return;
			if (n.leaf()) out.add(n.window);
			else {
				collect(n.a, out);
				collect(n.b, out);
			}
		}

		@Override
		public void arrange(Rect area, float gap, BiConsumer<W, Rect> out) {
			this.gap = gap;
			splitters.clear();
			if (root == null) return;
			// Expand by half a gap so every tile can shrink by half a gap on each side.
			layout(root, area.inset(-gap / 2), out);
		}

		/** The split's direction, fixing it from {@code box}'s shape the first time it's needed. */
		private boolean isHorizontal(Node<W> n, Rect box) {
			if (n.horizontal == null) n.horizontal = box.w() >= box.h();
			return n.horizontal;
		}

		private void layout(Node<W> n, Rect box, BiConsumer<W, Rect> out) {
			n.box = box;
			if (n.leaf()) {
				out.accept(n.window, box.inset(gap / 2));
				return;
			}
			boolean h = isHorizontal(n, box);
			if (h) {
				float wa = box.w() * n.ratio;
				layout(n.a, new Rect(box.x(), box.y(), wa, box.h()), out);
				layout(n.b, new Rect(box.x() + wa, box.y(), box.w() - wa, box.h()), out);
				splitters.add(new SplitterImpl<>(n, new Rect(box.x() + wa - gap / 2 - 2, box.y(), gap + 4, box.h()), true));
			} else {
				float ha = box.h() * n.ratio;
				layout(n.a, new Rect(box.x(), box.y(), box.w(), ha), out);
				layout(n.b, new Rect(box.x(), box.y() + ha, box.w(), box.h() - ha), out);
				splitters.add(new SplitterImpl<>(n, new Rect(box.x(), box.y() + ha - gap / 2 - 2, box.w(), gap + 4), false));
			}
		}

		@Override
		public void swap(W a, W b) {
			Node<W> na = find(root, a), nb = find(root, b);
			if (na == null || nb == null) return;
			na.window = b;
			nb.window = a;
		}

		@Override
		public void toggleSplit(W window) {
			Node<W> n = find(root, window);
			if (n == null || n.parent == null) return;
			Node<W> p = n.parent;
			p.horizontal = !isHorizontal(p, p.box);
		}

		@Override
		public void resize(W window, float amount) {
			Node<W> n = find(root, window);
			if (n == null || n.parent == null) return;
			Node<W> p = n.parent;
			p.ratio = Math.clamp(p.ratio + (p.a == n ? amount : -amount), 0.1f, 0.9f);
		}

		@Override
		public List<Splitter> splitters() {
			return splitters;
		}

		@Override
		public JsonObject save(Function<W, String> keys) {
			JsonObject o = new JsonObject();
			if (root != null) o.add("root", write(root, keys));
			return o;
		}

		private JsonElement write(Node<W> n, Function<W, String> keys) {
			JsonObject o = new JsonObject();
			if (n.leaf()) {
				o.addProperty("window", keys.apply(n.window));
				return o;
			}
			o.addProperty("ratio", n.ratio);
			if (n.horizontal != null) o.addProperty("horizontal", n.horizontal);
			JsonArray children = new JsonArray();
			children.add(write(n.a, keys));
			children.add(write(n.b, keys));
			o.add("children", children);
			return o;
		}

		@Override
		public void load(JsonObject json, Function<String, W> windows) {
			root = json.has("root") ? read(json.getAsJsonObject("root"), windows, null) : null;
		}

		private Node<W> read(JsonObject o, Function<String, W> windows, Node<W> parent) {
			if (o.has("window")) {
				W w = windows.apply(o.get("window").getAsString());
				if (w == null) return null;
				Node<W> n = new Node<>();
				n.window = w;
				n.parent = parent;
				return n;
			}
			Node<W> n = new Node<>();
			n.parent = parent;
			n.ratio = o.has("ratio") ? o.get("ratio").getAsFloat() : 0.5f;
			if (o.has("horizontal")) n.horizontal = o.get("horizontal").getAsBoolean();
			JsonArray c = o.getAsJsonArray("children");
			Node<W> a = read(c.get(0).getAsJsonObject(), windows, n);
			Node<W> b = read(c.get(1).getAsJsonObject(), windows, n);
			// Collapse branches whose windows no longer exist.
			if (a == null && b == null) return null;
			if (a == null) {
				b.parent = parent;
				return b;
			}
			if (b == null) {
				a.parent = parent;
				return a;
			}
			n.a = a;
			n.b = b;
			return n;
		}
	}

	private record SplitterImpl<W>(Node<W> node, Rect bounds, boolean vertical) implements LayoutState.Splitter {
		@Override
		public void drag(float mouseX, float mouseY) {
			Rect box = node.box;
			float r = vertical ? (mouseX - box.x()) / box.w() : (mouseY - box.y()) / box.h();
			node.ratio = Math.clamp(r, 0.1f, 0.9f);
		}
	}
}
