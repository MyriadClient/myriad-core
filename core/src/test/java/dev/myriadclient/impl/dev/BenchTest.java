package dev.myriadclient.impl.dev;

import dev.myriadclient.harness.Harness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code .bench} runs every measurement in a world without failing, and prints them (the harness's world is tiny). */
class BenchTest {
	@Test
	void everyMeasurementRuns() {
		try (Harness h = Harness.start()) {
			for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) h.level.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
			h.standAt(0.5, 64, 0.5, 0, 0);
			List<Bench.Result> results = new ArrayList<>();
			Bench.run(results::add);
			results.forEach(r -> System.out.println("[bench] " + r));
			assertEquals(8, results.size());
			for (Bench.Result r : results) assertTrue(r.nanos() > 0, r.name());
		}
	}
}
