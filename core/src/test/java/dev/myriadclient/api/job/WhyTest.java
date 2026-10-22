package dev.myriadclient.api.job;

import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The services' vocabularies in Why's, and the build step's "stuck on the commonest reason". */
class WhyTest {
	private static final BlockPos POS = new BlockPos(12, 64, -3);

	@Test
	void everyPlacementCheckHasAMeaning() {
		for (Placement.Check c : Placement.Check.values()) {
			Why w = Why.of(c, POS);
			switch (c) {
				case OK, PENDING -> assertNull(w, c.name());
				default -> {
					assertNotNull(w, c.name());
					assertEquals(POS, w.subject(BlockPos.class));
				}
			}
		}
		assertTrue(Why.of(Placement.Check.OUT_OF_RANGE, POS).is(Why.OUT_OF_REACH));
		assertTrue(Why.of(Placement.Check.OCCUPIED, POS).is(Why.BLOCKED));
		assertTrue(Why.of(Placement.Check.UNAVAILABLE, POS).is(Why.UNSUPPORTED));
	}

	@Test
	void everyBuildStatusHasAMeaning() {
		for (Build.Status s : Build.Status.values()) {
			Why w = Why.of(s, POS);
			switch (s) {
				case DONE, BREAK, PLACE, WAITING -> assertNull(w, s.name());
				default -> assertNotNull(w, s.name());
			}
		}
		assertTrue(Why.of(Build.Status.SUPPORTS_YOU, POS).is(Why.ENTITY_IN_WAY));
		assertTrue(Why.of(Build.Status.WRONG_ANGLE, POS).is(Why.WRONG_ANGLE));
	}

	@Test
	void reasonsReadAndCompare() {
		Why w = Why.of(Why.NO_ITEM, "cobblestone");
		assertEquals("no item: cobblestone", w.toString());
		assertEquals(w, Why.of(Why.NO_ITEM, "cobblestone"));
		assertFalse(w.equals(Why.of(Why.NO_ITEM, "dirt")));
		assertEquals("no schematic", Why.of(Why.Code.of("my-addon:no_schematic"), "").toString());
		CompletableFuture<Void> failed = CompletableFuture.failedFuture(new Job.JobException(w));
		CompletionException wrapped = new CompletionException(new Job.JobException(w));
		assertEquals(w, Why.of(wrapped));
		assertTrue(Why.of(new IllegalStateException("boom")).is(Why.ERROR));
		assertTrue(failed.isCompletedExceptionally());
	}

	@Test
	void aBuildIsStuckOnlyWhenNothingInReachCanGoOn() {
		Target cobble = new Target() {
			@Override
			public boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
				return false;
			}

			@Override
			public int preference(ItemStack stack) {
				return -1;
			}

			@Override
			public String describe() {
				return "cobblestone";
			}
		};
		List<Build.Step> stuck = List.of(new Build.Step(POS, cobble, Build.Status.NO_ITEM), new Build.Step(POS.above(), cobble, Build.Status.NO_ITEM),
			new Build.Step(POS.east(), cobble, Build.Status.OUT_OF_RANGE));
		Progress<Void> p = Steps.Builds.stuckOrRunning(stuck, 3);
		assertTrue(p.isStuck());
		assertEquals("no item: ×2 cobblestone", p.why().toString());
		assertEquals(POS, p.why().subject());

		List<Build.Step> working = List.of(new Build.Step(POS, cobble, Build.Status.NO_ITEM), new Build.Step(POS.above(), cobble, Build.Status.PLACE));
		assertTrue(Steps.Builds.stuckOrRunning(working, 2).isRunning());
		assertTrue(Steps.Builds.stuckOrRunning(List.of(), 5).why().is(Why.OUT_OF_REACH));
		assertTrue(Steps.Builds.stuckOrRunning(List.of(), 0).isRunning());
	}
}
