package dev.myriadclient.impl.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** New addons' windows go beside their categories while there's room, else onto workspaces of their own. */
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
		assertArrayEquals(repeat(1, 6), GroupPlacement.place(repeat(-1, 6), counts(), CAPACITY));
	}

	@Test
	void bigAddonGetsItsOwnWorkspaceInsteadOfCrowdingTheFirst() {
		// Essentials' 6 categories on 1; Boze brings 7 windows, 5 of them in categories already on 1.
		int[] siblings = {1, 1, 1, 1, 1, -1, -1};
		assertArrayEquals(repeat(2, 7), GroupPlacement.place(siblings, counts(6), CAPACITY));
	}

	@Test
	void smallAddonJoinsItsCategory() {
		assertArrayEquals(new int[]{1}, GroupPlacement.place(new int[]{1}, counts(6), CAPACITY));
		// Its categories on two workspaces, both with room: each beside its own.
		assertArrayEquals(new int[]{1, 2}, GroupPlacement.place(new int[]{1, 2}, counts(6, 7), CAPACITY));
	}

	@Test
	void smallAddonInANewCategoryGoesWhereThereIsRoom() {
		assertArrayEquals(new int[]{1}, GroupPlacement.place(new int[]{-1}, counts(6), CAPACITY));
		assertArrayEquals(new int[]{2}, GroupPlacement.place(new int[]{-1}, counts(8), CAPACITY));
	}

	@Test
	void addonsTooBigForOneWorkspaceSpillOntoTheNextEmptyOne() {
		// 10 windows at 8 a workspace: 8 on the first empty workspace, 2 on the next, never back onto workspace 1.
		int[] expected = {2, 2, 2, 2, 2, 2, 2, 2, 3, 3};
		assertArrayEquals(expected, GroupPlacement.place(repeat(-1, 10), counts(6), CAPACITY));
	}

	@Test
	void aFullDesktopStillPlacesEverything() {
		int[] full = counts(8, 8, 8, 8, 8, 8, 8, 8, 7);
		assertArrayEquals(new int[]{9, 9}, GroupPlacement.place(new int[]{-1, -1}, full, CAPACITY));
		int[] crowded = counts(9, 9, 9, 9, 9, 9, 9, 9, 9);
		crowded[4] = 8;
		assertArrayEquals(new int[]{4, 4}, GroupPlacement.place(new int[]{-1, -1}, crowded, CAPACITY));
	}
}
