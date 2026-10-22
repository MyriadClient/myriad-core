package dev.myriadclient.impl.hook;

import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.dev.Bench;
import dev.myriadclient.impl.event.MyriadEventBus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hooked call works out who it's for once per caller, not per call: with the chain cached, all a call allocates is
 * its place in the chain, and finding which replacement serves a caller allocates nothing. Prints what a call costs,
 * unhooked and hooked, for a module and for a plain object.
 */
class HookCallsTest {
	private static final Hook<Integer, Integer> HOOK = Hook.of("test:calls", "Calls", "");

	private record Caller(MyriadId id) implements Identified {
	}

	private static final class Plain {
	}

	private final HookManager hooks = new HookManager(new MyriadEventBus());

	@Test
	void aHookedCallAllocatesOnlyItsPlaceInTheChain() {
		Caller module = new Caller(MyriadId.of("essentials", "recast"));
		Plain plain = new Plain();
		hooks.setOwnerResolver(c -> "essentials");
		Bench.Result unhooked = Bench.measure("unhooked", () -> hooks.call(HOOK, module, 1, i -> i));
		hooks.addHook("a", HOOK, "Passes", Match.always(), c -> c.proceed());
		Bench.Result byModule = Bench.measure("hooked, a module", () -> hooks.call(HOOK, module, 1, i -> i));
		Bench.Result byObject = Bench.measure("hooked, an object", () -> hooks.call(HOOK, plain, 1, i -> i));
		System.out.printf("[hooks] %s; %s; %s%n", unhooked, byModule, byObject);
		assertEquals(0, unhooked.bytes(), "an unhooked call allocated");
		// The call handed to the contribution: no key, no lambda, no call for the default at the end.
		assertTrue(byModule.bytes() <= 40, "a hooked call allocated " + byModule.bytes() + " B");
		assertTrue(byObject.bytes() <= 40, "a hooked call allocated " + byObject.bytes() + " B");
	}

	@Test
	void anotherCallerStillGetsItsOwnChain() {
		hooks.addHook("a", HOOK, "Recast", Match.caller("essentials:recast"), c -> 2);
		Caller recast = new Caller(MyriadId.of("essentials", "recast")), altitude = new Caller(MyriadId.of("essentials", "altitude"));
		for (int i = 0; i < 100; i++) {
			assertEquals(2, hooks.call(HOOK, recast, 1, x -> x));
			assertEquals(1, hooks.call(HOOK, altitude, 1, x -> x));
			assertEquals(1, hooks.call(HOOK, new Plain(), 1, x -> x));
		}
	}

	@Test
	void findingAScopedReplacementAllocatesNothing() {
		Rotations core = (Rotations) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Rotations.class}, (p, m, a) -> null);
		hooks.registerCore(Service.ROTATIONS, core);
		Rotations[] mine = new Rotations[1];
		hooks.addReplacement("other", Service.ROTATIONS, "Other's own", Match.addon("other"), r -> mine[0] = new Rotations.Forwarding(r) {
		});
		Caller aura = new Caller(MyriadId.of("other", "aura")), recast = new Caller(MyriadId.of("essentials", "recast"));
		assertSame(mine[0], hooks.current(Service.ROTATIONS, aura));
		assertSame(core, hooks.current(Service.ROTATIONS, recast));
		Bench.Result served = Bench.measure("scoped", () -> hooks.current(Service.ROTATIONS, aura));
		Bench.Result notServed = Bench.measure("not scoped", () -> hooks.current(Service.ROTATIONS, recast));
		System.out.printf("[hooks] routing: %s; %s%n", served, notServed);
		assertEquals(0, served.bytes(), "routing allocated");
		assertEquals(0, notServed.bytes(), "routing allocated");
	}
}
