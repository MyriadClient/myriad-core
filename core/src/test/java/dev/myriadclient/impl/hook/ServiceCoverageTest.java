package dev.myriadclient.impl.hook;

import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every method of a replaceable service, defaults included, is handed on by core's router (to the replacement in
 * effect) and by the service's {@code Forwarding} base (to core): a method one of them misses silently runs the
 * interface's default instead, which for {@code use} or {@code solve} does nothing. Only the conveniences listed here
 * may stay defaults: overloads that call a method that is handed on.
 */
class ServiceCoverageTest {
	private static final List<String> CONVENIENCES = List.of(
		"Placement.place/3", "Placement.canPlace/2",
		"Rotations.request/4", "Rotations.request/5", "Rotations.lookAt/3", "Rotations.lookAt/4", "Rotations.isFacing/3");

	@Test
	void routersAndForwardingBasesHandOnEveryMethod() throws Exception {
		Map<Class<?>, List<Class<?>>> services = Map.of(
			Rotations.class, List.of(ServiceRouters.RotationsRouter.class, Rotations.Forwarding.class),
			Inventory.class, List.of(ServiceRouters.InventoryRouter.class, Inventory.Forwarding.class),
			Placement.class, List.of(ServiceRouters.PlacementRouter.class, Placement.Forwarding.class),
			Breaking.class, List.of(ServiceRouters.BreakingRouter.class, Breaking.Forwarding.class));
		List<String> missing = new ArrayList<>();
		for (var e : services.entrySet()) {
			for (Method m : e.getKey().getMethods()) {
				if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() != e.getKey()) continue;
				if (m.isDefault() && CONVENIENCES.contains(e.getKey().getSimpleName() + "." + m.getName() + "/" + m.getParameterCount())) continue;
				for (Class<?> impl : e.getValue()) {
					try {
						impl.getDeclaredMethod(m.getName(), m.getParameterTypes());
					} catch (NoSuchMethodException x) {
						missing.add(impl.getName().replace('$', '.') + " doesn't hand on " + m.getName());
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), String.join("\n", missing));
	}
}
