package dev.myriadclient.api.util;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A shared pool of background threads for slow work (files, network, heavy searches) so addons don't each start
 * their own. Never touch the world or the player from these threads: hop back with {@link #onRenderThread}.
 *
 * <pre>{@code
 * Async.supply(() -> loadSchematic(file)).thenAccept(s -> Async.onRenderThread(() -> start(s)));
 * }</pre>
 */
public final class Async {
	private static final AtomicInteger THREADS = new AtomicInteger();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 6), r -> {
		Thread t = new Thread(r, "Myriad Worker " + THREADS.incrementAndGet());
		t.setDaemon(true);
		return t;
	});

	private Async() {
	}

	public static ExecutorService executor() {
		return POOL;
	}

	public static CompletableFuture<Void> run(Runnable task) {
		return CompletableFuture.runAsync(task, POOL);
	}

	public static <T> CompletableFuture<T> supply(Supplier<T> task) {
		return CompletableFuture.supplyAsync(task, POOL);
	}

	/** Runs {@code task} on the render thread (now if already there, otherwise at the next opportunity). */
	public static void onRenderThread(Runnable task) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) task.run();
		else mc.execute(task);
	}
}
