package dev.myriadclient.api.hook;

import org.jspecify.annotations.Nullable;

/**
 * Handles a {@link Hook}: gives its answer for a call, or asks the next handler ({@link HookCall#proceed()}) and
 * adjusts what it says.
 *
 * <pre>{@code
 * call -> call.input().speed() > 0 ? myCurve(call.input()) : call.proceed()
 * }</pre>
 */
@FunctionalInterface
public interface HookHandler<I, O extends @Nullable Object> {
	/**
	 * The answer for {@code call}. A throw (a {@code RuntimeException} or {@code LinkageError}) turns this handler off
	 * for the session with a notice, and the call goes on to the next handler.
	 */
	O handle(HookCall<I, O> call);
}
