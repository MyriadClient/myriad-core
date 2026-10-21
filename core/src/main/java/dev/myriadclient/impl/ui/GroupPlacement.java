package dev.myriadclient.impl.ui;

/**
 * Where a newly seen addon's group windows go, so a desktop with many addons stays readable. One addon at a time:
 * <ol>
 * <li>all together, on the first workspace with room for all of them;</li>
 * <li>and when there are more than one workspace holds, filling empty workspaces in order.</li>
 * </ol>
 * With no empty workspace left and none with room for them all, the least crowded workspace takes the rest.
 * A workspace has room while it holds fewer tiled windows than {@code capacity} (as many as fit side by side at a
 * readable width). Only windows the desktop hasn't seen are placed; anything already arranged stays where it is.
 */
final class GroupPlacement {
	private GroupPlacement() {
	}

	/**
	 * @param n        the addon's new windows
	 * @param counts   tiled windows per workspace; index 0 (the HUD) is never used. Updated as groups are placed.
	 * @param capacity windows a workspace takes
	 * @return the workspace for each window
	 */
	static int[] place(int n, int[] counts, int capacity) {
		int[] out = new int[n];
		if (n == 0) return out;
		int next = 0;
		boolean ownSpace = false;
		while (next < n) {
			int left = n - next;
			// Once the addon has started a workspace of its own, the rest follow onto the next empty one.
			int ws = ownSpace ? -1 : firstWithRoom(counts, capacity, left);
			int take;
			if (ws > 0) {
				take = left;
			} else if ((ws = firstEmpty(counts)) > 0) {
				take = Math.min(left, capacity);
				ownSpace = true;
			} else {
				// Every workspace is in use and none has room for them all: the least crowded one takes the rest, so the
				// addon's windows stay together.
				ws = leastCrowded(counts);
				take = left;
			}
			for (int i = 0; i < take; i++) out[next + i] = ws;
			counts[ws] += take;
			next += take;
		}
		return out;
	}

	private static int firstWithRoom(int[] counts, int capacity, int needed) {
		for (int ws = 1; ws < counts.length; ws++) if (counts[ws] + needed <= capacity) return ws;
		return -1;
	}

	private static int firstEmpty(int[] counts) {
		for (int ws = 1; ws < counts.length; ws++) if (counts[ws] == 0) return ws;
		return -1;
	}

	private static int leastCrowded(int[] counts) {
		int best = 1;
		for (int ws = 2; ws < counts.length; ws++) if (counts[ws] < counts[best]) best = ws;
		return best;
	}
}
