package dev.myriadclient.impl.hook;

import dev.myriadclient.api.hook.Contribution;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.event.MyriadEventBus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookManagerTest {
	private static final Hook<Integer, String> HOOK = Hook.of("test:hook", "Test", "");

	private record Caller(MyriadId id) implements Identified {
	}

	private static final Caller RECAST = new Caller(MyriadId.of("essentials", "recast"));
	private static final Caller ALTITUDE = new Caller(MyriadId.of("essentials", "altitude"));

	private final HookManager hooks = new HookManager(new MyriadEventBus());

	private String call(Object caller) {
		return hooks.call(HOOK, caller, 1, i -> "default");
	}

	@Test
	void unhookedRunsTheDefault() {
		assertFalse(hooks.hooked(HOOK));
		assertEquals("default", call(RECAST));
	}

	@Test
	void mostSpecificRunsFirstAndCanProceed() {
		hooks.addHook("a", HOOK, "General", Match.always(), c -> "general>" + c.proceed());
		hooks.addHook("b", HOOK, "For Recast", Match.caller("essentials:recast"), c -> "recast>" + c.proceed());
		assertTrue(hooks.hooked(HOOK));
		assertEquals("recast>general>default", call(RECAST));
		assertEquals("general>default", call(ALTITUDE));
		assertEquals("general>default", call(null));
		assertEquals(List.of("b:hook_for_recast", "a:hook_general"), hooks.chain(HOOK, RECAST).stream().map(c -> c.id().toString()).toList());
	}

	@Test
	void inputCanChangeOnTheWayDown() {
		hooks.addHook("a", HOOK, "Doubles", Match.always(), c -> c.proceed(c.input() * 2));
		assertEquals("2", hooks.call(HOOK, null, 1, String::valueOf));
	}

	@Test
	void profileAndServerNarrow() {
		hooks.addHook("a", HOOK, "Grim", Match.always().on(AntiCheat.Profile.GRIM), c -> "grim");
		hooks.addHook("a", HOOK, "6b6t", Match.always().server("6b6t.org"), c -> "6b6t");
		// The tests run as vanilla, nowhere.
		assertEquals("default", call(null));
	}

	@Test
	void disabledAndFailedAreSkipped() {
		Contribution broken = hooks.addHook("a", HOOK, "Broken", Match.caller("essentials:recast"), c -> {
			throw new IllegalStateException("boom");
		});
		Contribution off = hooks.addHook("b", HOOK, "Off", Match.always(), c -> "off");
		off.setEnabled(false);
		assertEquals("default", call(RECAST));
		assertTrue(broken.failed());
		assertFalse(hooks.hooked(HOOK));
		off.setEnabled(true);
		assertEquals("off", call(RECAST));
	}

	@Test
	void matchServerTakesSubdomains() {
		Match m = Match.always().server("2B2T.org");
		assertTrue(m.test(null, null, null, AntiCheat.Profile.VANILLA, "2b2t.org"));
		assertTrue(m.test(null, null, null, AntiCheat.Profile.VANILLA, "connect.2b2t.org"));
		assertFalse(m.test(null, null, null, AntiCheat.Profile.VANILLA, "not2b2t.org"));
		assertFalse(m.test(null, null, null, AntiCheat.Profile.VANILLA, null));
		assertEquals("connect.2b2t.org", Match.normalize("Connect.2b2t.org:25565"));
		assertTrue(Match.caller(Caller.class).test(null, Caller.class, null, AntiCheat.Profile.GRIM, null));
		assertTrue(Match.addon("essentials").test(null, null, "essentials", AntiCheat.Profile.GRIM, null));
		assertFalse(Match.addon("essentials").test(null, null, "other", AntiCheat.Profile.GRIM, null));
	}

	private static final class Turning extends Rotations.Forwarding {
		int activated, deactivated;
		final Rotations wrapped;

		Turning(Rotations core) {
			super(core);
			wrapped = core;
		}

		@Override
		public boolean isRotating() {
			return true;
		}

		@Override
		public void activated() {
			activated++;
		}

		@Override
		public void deactivated() {
			deactivated++;
		}
	}

	@Test
	void replacementWrapsCoreAndCanBeTurnedOff() {
		Rotations core = new Rotations.Forwarding(null_rotations()) {
		};
		hooks.registerCore(Service.ROTATIONS, core);
		assertSame(core, hooks.current(Service.ROTATIONS));
		Turning[] made = new Turning[1];
		Contribution c = hooks.addReplacement("a", Service.ROTATIONS, "Mine", Match.always(), r -> made[0] = new Turning(r));
		assertSame(made[0], hooks.current(Service.ROTATIONS));
		assertSame(core, made[0].wrapped);
		assertEquals(1, made[0].activated);
		assertTrue(hooks.current(Service.ROTATIONS).isRotating());
		c.setEnabled(false);
		assertSame(core, hooks.current(Service.ROTATIONS));
		assertEquals(1, made[0].deactivated);
		c.setEnabled(true);
		assertSame(made[0], hooks.current(Service.ROTATIONS), "made once, kept");
		assertTrue(hooks.active(Service.ROTATIONS).isPresent());
	}

	@Test
	void anAddonsReplacementServesOnlyItsOwnCallers() {
		List<String> sent = new ArrayList<>();
		Rotations core = new Rotations.Forwarding(null_rotations()) {
			@Override
			public void request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent) {
				sent.add("core:" + ((Caller) owner).id().path() + "@" + yaw);
			}
		};
		hooks.registerCore(Service.ROTATIONS, core);
		hooks.addReplacement("other", Service.ROTATIONS, "Other's own", Match.addon("other"), r -> new Rotations.Forwarding(r) {
			@Override
			public void request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent) {
				sent.add("other");
				super.request(owner, yaw + 1, pitch, priority, options, afterSent);
			}
		});
		Caller aura = new Caller(MyriadId.of("other", "aura"));
		assertSame(core, hooks.current(Service.ROTATIONS, RECAST), "Essentials keeps core's");
		assertSame(core, hooks.current(Service.ROTATIONS), "and so does everything else");

		Rotations router = hooks.router(Service.ROTATIONS);
		router.request(aura, 10, 0, Rotations.PRIORITY_NORMAL);
		router.request(RECAST, 20, 0, Rotations.PRIORITY_NORMAL);
		assertEquals(List.of("other", "core:aura@11.0", "core:recast@20.0"), sent);
	}

	/** A stand-in for core's rotations, never called. */
	private static Rotations null_rotations() {
		return (Rotations) Proxy.newProxyInstance(Rotations.class.getClassLoader(), new Class<?>[]{Rotations.class}, (p, m, a) -> {
			throw new UnsupportedOperationException(m.getName());
		});
	}
}
