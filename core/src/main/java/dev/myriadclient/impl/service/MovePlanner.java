package dev.myriadclient.impl.service;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Arrays;

/**
 * The fewest clicks that move an exact number of one item between two slots, with what the cursor holds counted in.
 * Left and right clicks on either slot are the moves (a left click picks up or puts down everything that fits, a right
 * click picks up half rounded up or puts down one), so "40 of a 64 stack" is a short dance rather than 40 single
 * clicks. A breadth-first search over (cursor, destination) counts: the source holds the rest, so there are at most
 * 65 x 65 states, a few microseconds a plan.
 */
final class MovePlanner {
	/** Clicks a plan may have; past this, moves this exact aren't worth it. */
	private static final int MAX_CLICKS = 24;

	/** Which of the two slots a click is on, and with which button (0 left, 1 right). */
	record Step(boolean onDestination, int button) {
	}

	private MovePlanner() {
	}

	/**
	 * Clicks that take the cursor from {@code cursor} items, the source from {@code source} and the destination from
	 * {@code destination} to an empty cursor and {@code goal} items in the destination (all the same item; an empty slot
	 * takes it). {@code sourceMax}/{@code destinationMax} are how many each slot holds, {@code stackMax} how many the
	 * cursor can carry. Null if no plan of at most {@value #MAX_CLICKS} clicks gets there.
	 */
	static Step[] plan(int cursor, int source, int destination, int goal, int sourceMax, int destinationMax, int stackMax) {
		int total = cursor + source + destination;
		if (goal < 0 || goal > destinationMax || total - goal > sourceMax || cursor > stackMax) return null;
		if (cursor == 0 && destination == goal) return new Step[0];
		int width = destinationMax + 1;
		int states = (stackMax + 1) * width;
		int[] parent = new int[states];
		byte[] move = new byte[states];
		Arrays.fill(parent, -2);
		int start = cursor * width + destination, end = goal;
		parent[start] = -1;
		IntArrayList frontier = new IntArrayList(), next = new IntArrayList();
		frontier.add(start);
		for (int depth = 0; depth < MAX_CLICKS && !frontier.isEmpty(); depth++) {
			next.clear();
			for (int i = 0; i < frontier.size(); i++) {
				int state = frontier.getInt(i);
				int c = state / width, d = state % width, s = total - c - d;
				for (int m = 0; m < 4; m++) {
					boolean onDest = m >= 2;
					int button = m & 1;
					int slot = onDest ? d : s, max = onDest ? destinationMax : sourceMax;
					int nc, ns;
					if (c == 0) {
						if (slot == 0) continue;
						int taken = Math.min(stackMax, button == 0 ? slot : (slot + 1) / 2);
						nc = taken;
						ns = slot - taken;
					} else {
						int put = button == 0 ? Math.min(c, max - slot) : (slot < max ? 1 : 0);
						if (put == 0) continue;
						nc = c - put;
						ns = slot + put;
					}
					int nd = onDest ? ns : d;
					int to = nc * width + nd;
					if (parent[to] != -2) continue;
					parent[to] = state;
					move[to] = (byte) m;
					if (to == end) return path(parent, move, end);
					next.add(to);
				}
			}
			IntArrayList t = frontier;
			frontier = next;
			next = t;
		}
		return null;
	}

	private static Step[] path(int[] parent, byte[] move, int end) {
		int n = 0;
		for (int s = end; parent[s] != -1; s = parent[s]) n++;
		Step[] steps = new Step[n];
		for (int s = end; parent[s] != -1; s = parent[s]) steps[--n] = new Step(move[s] >= 2, move[s] & 1);
		return steps;
	}
}
