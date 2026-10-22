package dev.myriadclient.impl.dev;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobEngine;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.world.BlockScan;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.hook.HookManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What core's hot paths cost on this machine: {@code .bench} in dev runs, and the tests that hold a path to an
 * allocation budget. Each measurement runs warm, on the calling thread, and reports the mean time and bytes allocated
 * per call. Numbers drift between machines and runs; they're for comparing a change with what came before.
 */
public final class Bench {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final int WARMUP = 100_000, CALLS = 100_000;
	private static final Hook<Integer, Integer> HOOK = Hook.of("myriad:bench", "Bench", "");

	/** One measurement: mean nanoseconds and bytes allocated per call. */
	public record Result(String name, double nanos, long bytes) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%s %s %d B", name, nanos >= 10_000 ? String.format(Locale.ROOT, "%.1f µs", nanos / 1000)
				: String.format(Locale.ROOT, "%.1f ns", nanos), bytes);
		}
	}

	private Bench() {
	}

	/** {@code op} measured over a hundred thousand calls, after as many to warm up. */
	public static Result measure(String name, Supplier<Object> op) {
		return measure(name, WARMUP, CALLS, op);
	}

	/** {@code op} called {@code warmup} times, then measured over {@code calls}. */
	public static Result measure(String name, int warmup, int calls, Supplier<Object> op) {
		Object sink = null;
		for (int i = 0; i < warmup; i++) sink = op.get();
		long bytes = THREADS.getCurrentThreadAllocatedBytes(), t = System.nanoTime();
		for (int i = 0; i < calls; i++) sink = op.get();
		long took = System.nanoTime() - t, allocated = THREADS.getCurrentThreadAllocatedBytes() - bytes;
		// Read the result so the calls can't be optimised away.
		if (sink == Bench.class) throw new AssertionError();
		return new Result(name, (double) took / calls, allocated / calls);
	}

	/**
	 * Every measurement, in a world, on the render thread (the game stands still meanwhile, a few seconds): the parts
	 * that touch the world use where you stand and the block you hold. Everything runs twice and the second pass is
	 * reported, as the first also pays for compiling the code it measures. Each result goes to {@code out}.
	 */
	public static void run(Consumer<Result> out) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) throw new IllegalStateException("Join a world first");
		pass(player, level, r -> {
		});
		pass(player, level, out);
	}

	private static void pass(LocalPlayer player, ClientLevel level, Consumer<Result> out) {
		out.accept(bus());
		hooks(out);
		BlockPos feet = player.blockPosition();
		out.accept(measure("a block read through the level", () -> level.getBlockState(feet)));
		LevelChunk chunk = level.getChunkAt(feet);
		int[] found = new int[1];
		out.accept(measure("BlockScan over your chunk (stone)", 200, 1000, () -> {
			BlockScan.forEach(chunk, s -> s.is(Blocks.STONE), (pos, state) -> found[0]++);
			return found;
		}));
		out.accept(blueprint(player));
		out.accept(wall(player));
		out.accept(jobTick());
	}

	/** A post to five handlers, on a bus of its own. */
	private static Result bus() {
		MyriadEventBus bus = new MyriadEventBus();
		Counted event = new Counted();
		for (int i = 0; i < 5; i++) bus.listen(Counted.class, e -> e.n++);
		return measure("post to 5 handlers", () -> bus.post(event));
	}

	private static final class Counted {
		int n;
	}

	/** A hook call with nothing hooked, then through one contribution that passes it on. */
	private static void hooks(Consumer<Result> out) {
		HookManager hooks = new HookManager(new MyriadEventBus());
		Object caller = new Object();
		out.accept(measure("hook call, not hooked", () -> hooks.call(HOOK, caller, 1, i -> i)));
		hooks.addHook("myriad", HOOK, "Passes", Match.always(), c -> c.proceed());
		out.accept(measure("hook call, hooked", () -> hooks.call(HOOK, caller, 1, i -> i)));
	}

	/** The positions in reach of a 48 by 48 by 8 schematic around you. */
	private static Result blueprint(LocalPlayer player) {
		Map<BlockPos, Target> positions = new HashMap<>();
		BlockPos c = player.blockPosition();
		Target stone = Target.block(Blocks.STONE);
		for (int x = -24; x < 24; x++) for (int z = -24; z < 24; z++) for (int y = -4; y < 4; y++) positions.put(c.offset(x, y, z), stone);
		Blueprint blueprint = Blueprint.of(positions);
		Vec3 eyes = player.getEyePosition();
		int[] n = new int[1];
		return measure("Blueprint positions in reach (18,432)", 2000, 10_000, () -> {
			blueprint.near(eyes, 5, (pos, t) -> n[0]++);
			return n;
		});
	}

	/**
	 * Solving the clicks for a five by three wall of the block you hold, two blocks in front of you (nothing is sent);
	 * the name says what the first one came out as, since a refusal is quicker than a click.
	 */
	private static Result wall(LocalPlayer player) {
		Direction facing = player.getDirection();
		BlockPos base = player.blockPosition().relative(facing, 2);
		List<BlockPos> wall = new ArrayList<>();
		for (int across = -2; across <= 2; across++) for (int up = 0; up < 3; up++) wall.add(base.relative(facing.getClockWise(), across).above(up));
		BlockState block = player.getMainHandItem().getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState() : Blocks.STONE.defaultBlockState();
		Placement.Options options = Placement.Options.forServer();
		int slot = player.getInventory().getSelectedSlot();
		Placement.Check first = Myriad.placement().solve(wall.getFirst(), block, slot, options).check();
		return measure("placement: solve a 5 by 3 wall (" + first.name().toLowerCase(Locale.ROOT) + ")", 2000, 10_000, () -> {
			Placement.Solution last = null;
			for (BlockPos pos : wall) last = Myriad.placement().solve(pos, block, slot, options);
			return last;
		});
	}

	/** One tick of a job running fifty steps at once, on an engine of its own. */
	private static Result jobTick() {
		JobEngine engine = new JobEngine(new QuietHost());
		List<Step<Void>> steps = new ArrayList<>();
		for (int i = 0; i < 50; i++) steps.add(Step.until("Wait", () -> false, Integer.MAX_VALUE));
		Job<?> job = engine.start(Bench.class, Step.all("Bench", steps));
		try {
			return measure("job tick, 50 steps", 20_000, 20_000, () -> {
				engine.tick();
				return engine;
			});
		} finally {
			job.cancel();
			engine.tick();
		}
	}

	/** An engine host that does nothing: the bench's job asks no service for anything. */
	private static final class QuietHost implements JobEngine.Host {
		@Override
		public void release(Job<?> job) {
		}

		@Override
		public <E> Subscription listen(Class<E> type, Consumer<? super E> handler) {
			return () -> {
			};
		}

		@Override
		public void post(Object event) {
		}

		@Override
		public void failed(Job<?> root, Why why) {
		}

		@Override
		public void log(Job<?> job, String message) {
		}
	}
}
