package dev.myriadclient.api.util;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Which thread code runs on. See {@link RenderThread}. */
@AnyThread
public final class Threads {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Threads");
	/** Whether {@link #assertRender()} checks: {@code -Dmyriad.dev}, set by the dev clients' launch configs. */
	private static final boolean CHECK = Boolean.getBoolean("myriad.dev");
	/** Call sites already reported, so a call made every tick logs once. */
	static final Set<StackTraceElement> REPORTED = ConcurrentHashMap.newKeySet();

	private Threads() {
	}

	/** Whether this is the render thread (true before the game has a client, when there's only the one). */
	public static boolean isRender() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null || mc.isSameThread();
	}

	/**
	 * With {@code -Dmyriad.dev} set, logs (once per call site, with the stack) a call that isn't on the render thread;
	 * otherwise nothing, at no cost. For the entry points of code that is {@link RenderThread} only.
	 */
	public static void assertRender() {
		if (!CHECK || isRender()) return;
		StackTraceElement[] stack = new Throwable().getStackTrace();
		StackTraceElement site = stack.length > 2 ? stack[2] : stack[stack.length - 1];
		if (REPORTED.add(site)) {
			LOG.error("{} is render thread only, called on {}", stack.length > 1 ? stack[1].getClassName() + "." + stack[1].getMethodName() : "?",
				Thread.currentThread().getName(), new IllegalStateException("called from another thread"));
		}
	}
}
