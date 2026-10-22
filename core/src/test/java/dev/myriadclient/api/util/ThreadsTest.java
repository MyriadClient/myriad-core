package dev.myriadclient.api.util;

import dev.myriadclient.harness.Harness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Threads#assertRender()} with {@code -Dmyriad.dev} (the test task sets it): an off-thread call is reported once per call site. */
class ThreadsTest {
	private static void renderOnly() {
		Threads.assertRender();
	}

	@Test
	void aCallFromAnotherThreadIsReportedOncePerCallSite() throws Exception {
		try (Harness h = Harness.start()) {
			assertTrue(Threads.isRender());
			int before = Threads.REPORTED.size();
			renderOnly();
			assertEquals(before, Threads.REPORTED.size(), "the render thread isn't reported");
			Thread network = new Thread(() -> {
				assertFalse(Threads.isRender());
				for (int i = 0; i < 3; i++) renderOnly();
			}, "network");
			network.start();
			network.join();
			assertEquals(before + 1, Threads.REPORTED.size(), "one call site, reported once");
			assertTrue(Threads.REPORTED.stream().anyMatch(s -> s.getMethodName().contains("lambda") || s.getClassName().contains("ThreadsTest")));
		}
	}
}
