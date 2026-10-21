package dev.myriadclient.impl.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** New addons' windows go together where there's room, else onto workspaces of their own. */
class GroupPlacementTest {
	private static final int CAPACITY = 8;

	/** Tiled windows on workspaces 1..9 (index 0 is the HUD). */
	private static int[] counts(int... perWorkspace) {
		int[] c = new int[10];
		System.arraycopy(perWorkspace, 0, c, 1, perWorkspace.length);
		return c;
	}

	private static int[] repeat(int value, int times) {
		int[] a = new int[times];
		Arrays.fill(a, value);
		return a;
	}

	@Test
	void firstAddonFillsWorkspaceOne() {
		assertArrayEquals(repeat(1, 6), GroupPlacement.place(6, counts(), CAPACITY));
	}

	@Test
	void bigAddonGetsItsOwnWorkspaceInsteadOfCrowdingTheFirst() {
		// Essentials' 5 groups on 1; Boze brings 7 windows.
		assertArrayEquals(repeat(2, 7), GroupPlacement.place(7, counts(5), CAPACITY));
	}

	@Test
	void smallAddonGoesWhereThereIsRoom() {
		assertArrayEquals(new int[]{1}, GroupPlacement.place(1, counts(6), CAPACITY));
		assertArrayEquals(new int[]{2}, GroupPlacement.place(1, counts(8), CAPACITY));
	}

	@Test
	void addonsTooBigForOneWorkspaceSpillOntoTheNextEmptyOne() {
		// 10 windows at 8 a workspace: 8 on the first empty workspace, 2 on the next, never back onto workspace 1.
		int[] expected = {2, 2, 2, 2, 2, 2, 2, 2, 3, 3};
		assertArrayEquals(expected, GroupPlacement.place(10, counts(6), CAPACITY));
	}

	@Test
	void aFullDesktopStillPlacesEverything() {
		int[] full = counts(8, 8, 8, 8, 8, 8, 8, 8, 7);
		assertArrayEquals(new int[]{9, 9}, GroupPlacement.place(2, full, CAPACITY));
		int[] crowded = counts(9, 9, 9, 9, 9, 9, 9, 9, 9);
		crowded[4] = 8;
		assertArrayEquals(new int[]{4, 4}, GroupPlacement.place(2, crowded, CAPACITY));
	}
}
