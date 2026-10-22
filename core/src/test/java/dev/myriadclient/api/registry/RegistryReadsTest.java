package dev.myriadclient.api.registry;

import dev.myriadclient.FakeApi;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.util.MyriadId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a registry is what the menu, the key binds and the bar do every frame or key press: it allocates nothing,
 * and a change shows in the next read. Prints what a read costs, with about as many modules as core and Essentials.
 */
class RegistryReadsTest {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	static final class Plain extends Module {
		Plain(String name) {
			super(Tags.MISC, name, "");
		}
	}

	private FakeApi api;

	@BeforeEach
	void setUp() {
		api = FakeApi.install();
		for (int g = 0; g < 5; g++) api.groups().register(new ModuleGroup(MyriadId.of("test", "group_" + g), "Group " + g, ""), "test");
		for (int i = 0; i < 70; i++) {
			Plain m = new Plain("Module " + i);
			if (i % 7 != 0) m.assignGroup(api.groups().get(MyriadId.of("test", "group_" + i % 5)).orElseThrow());
			api.modules().register(m, "test");
		}
	}

	@AfterEach
	void tearDown() {
		FakeApi.uninstall();
	}

	@Test
	void readsAllocateNothing() {
		long[] values = measure(() -> api.modules().values());
		long[] groups = measure(() -> api.modules().groups());
		long[] loop = measure(() -> {
			int n = 0;
			for (Module m : api.modules()) n += m.isEnabled() ? 1 : 0;
			return n;
		});
		System.out.printf("[registry] values() %d ns %d B, groups() %d ns %d B, for-each %d ns %d B%n",
			values[0], values[1], groups[0], groups[1], loop[0], loop[1]);
		assertEquals(0, values[1], "values() allocated");
		assertEquals(0, groups[1], "groups() allocated");
	}

	@Test
	void aChangeShowsInTheNextRead() {
		Collection<Module> before = api.modules().values();
		List<ModuleGroup> groupsBefore = api.modules().groups();
		assertSame(before, api.modules().values());
		assertSame(groupsBefore, api.modules().groups());
		Plain extra = new Plain("Extra");
		extra.assignGroup(ModuleGroup.addonWindow("other", "Other"));
		api.modules().register(extra, "other");
		assertNotSame(before, api.modules().values());
		assertEquals(71, api.modules().values().size());
		assertTrue(api.modules().groups().contains(extra.group()));
		api.modules().unregister(extra.id());
		assertEquals(70, api.modules().values().size());
		assertEquals(groupsBefore, api.modules().groups());
	}

	/** Average nanoseconds and bytes allocated per call, warm. */
	private static long[] measure(java.util.function.Supplier<Object> read) {
		Object sink = null;
		for (int i = 0; i < 20_000; i++) sink = read.get();
		int n = 100_000;
		long bytes = THREADS.getCurrentThreadAllocatedBytes(), t = System.nanoTime();
		for (int i = 0; i < n; i++) sink = read.get();
		long took = System.nanoTime() - t, allocated = THREADS.getCurrentThreadAllocatedBytes() - bytes;
		if (sink == null) throw new AssertionError();
		return new long[]{took / n, allocated / n};
	}
}
