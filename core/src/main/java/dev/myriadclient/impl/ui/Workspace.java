package dev.myriadclient.impl.ui;

import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;

import java.util.ArrayList;
import java.util.List;

/** One workspace: a tiling state plus floating windows in stacking order (last = top). */
final class Workspace {
	final int index;
	Layout layout;
	LayoutState<WindowImpl> tiling;
	final List<WindowImpl> floating = new ArrayList<>();
	WindowImpl focused;

	Workspace(int index, Layout layout) {
		this.index = index;
		setLayout(layout);
	}

	void setLayout(Layout layout) {
		List<WindowImpl> tiled = tiling == null ? List.of() : new ArrayList<>(tiling.windows());
		this.layout = layout;
		this.tiling = layout.createState();
		for (WindowImpl w : tiled) tiling.add(w, null);
	}

	/** All windows in draw order: tiled, then floating, fullscreen last. */
	List<WindowImpl> drawOrder() {
		List<WindowImpl> out = new ArrayList<>(tiling.windows());
		out.addAll(floating);
		WindowImpl fs = null;
		for (WindowImpl w : out) if (w.fullscreen) fs = w;
		if (fs != null) {
			out.remove(fs);
			out.add(fs);
		}
		return out;
	}

	boolean isEmpty() {
		return tiling.windows().isEmpty() && floating.isEmpty();
	}
}
