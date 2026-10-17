package dev.myriadclient.impl.dev;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.combat.Crystals;
import dev.myriadclient.api.combat.Threats;
import dev.myriadclient.api.combat.Trajectory;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.util.Movement;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.api.world.Holes;
import dev.myriadclient.impl.event.MyriadEventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The DevConsole's {@code selftest}: turns every module on for a while and off again, one at a time, and reports any
 * that failed to turn on or whose handlers threw meanwhile; first it runs the world helpers once against the world
 * around you. Modules already on are left on (and still watched). A smoke test: it catches crashes, not wrong behaviour.
 */
final class SelfTest {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Dev");

	private final Minecraft mc = Minecraft.getInstance();
	private final int ticks;
	private final Deque<Module> queue = new ArrayDeque<>();
	private final List<String> failures = new ArrayList<>();
	private final List<String> selfDisabled = new ArrayList<>();
	private Module current;
	private boolean wasEnabled;
	private int left, tested;

	SelfTest(int ticks, Set<String> skip) {
		this.ticks = Math.max(1, ticks);
		for (Module m : Myriad.modules().values()) {
			if (!skip.contains(m.id().path()) && !skip.contains(m.id().toString())) queue.add(m);
		}
	}

	void start() {
		LOG.info("[dev] selftest: {} modules, {} ticks each", queue.size(), ticks);
		((MyriadEventBus) Myriad.events()).setFailureHook((where, t) -> failures.add((current == null ? "?" : current.id()) + ": " + where + ": " + t));
		helpers();
	}

	/** One tick; false once done. */
	boolean tick() {
		if (current != null && --left > 0) return true;
		if (current != null) finish();
		current = queue.poll();
		if (current == null) {
			report();
			return false;
		}
		wasEnabled = current.isEnabled();
		left = ticks;
		if (!wasEnabled) {
			current.setEnabledSilently(true);
			if (!current.isEnabled()) {
				// Myriad turns a module off again if onEnable throws; some modules turn themselves off on purpose.
				selfDisabled.add(current.id().toString());
				current = null;
				return true;
			}
		}
		return true;
	}

	private void finish() {
		tested++;
		if (!current.isEnabled()) selfDisabled.add(current.id().toString());
		else if (!wasEnabled) current.setEnabledSilently(false);
		current = null;
	}

	private void report() {
		((MyriadEventBus) Myriad.events()).setFailureHook(null);
		if (!selfDisabled.isEmpty()) LOG.info("[dev] selftest: turned themselves off: {}", String.join(", ", selfDisabled));
		for (String f : failures) LOG.warn("[dev] selftest: FAIL {}", f);
		LOG.info("[dev] selftest: {} modules run, {} failures", tested, failures.size());
	}

	/** Each world helper once, logging what it found. */
	private void helpers() {
		var p = mc.player;
		if (p == null) {
			failures.add("helpers: not in a world");
			return;
		}
		try {
			var holes = Holes.around(p.blockPosition(), 6);
			var bases = Crystals.bases(Reach.eyes(), 5, false);
			var launch = Trajectory.launch(p, new ItemStack(Items.SNOWBALL), p.getYRot(), p.getXRot());
			var path = Trajectory.simulate(launch, 100, p);
			LOG.info("[dev] selftest: helpers: {} holes, {} crystal bases, snowball lands {} after {} ticks, worst threat {}, base speed {}, "
					+ "surrounded {}, anti-cheat {}", holes.size(), bases.size(), path.end(), path.points().size() - 1, String.format(Locale.ROOT, "%.1f", Threats.worst()),
				String.format(Locale.ROOT, "%.4f", Movement.baseSpeed()), Holes.isSurrounded(p), Myriad.antiCheat().profile());
		} catch (RuntimeException e) {
			failures.add("helpers: " + e);
			LOG.warn("[dev] selftest: helpers threw", e);
		}
	}
}
