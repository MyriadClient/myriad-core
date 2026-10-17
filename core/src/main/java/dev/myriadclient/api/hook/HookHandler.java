package dev.myriadclient.api.hook;

/**
 * Handles a {@link Hook}: gives its answer for a call, or asks the next handler ({@link HookCall#proceed()}) and
 * adjusts what it says.
 *
 * <pre>{@code
 * call -> call.input().speed() > 0 ? myCurve(call.input()) : call.proceed()
 * }</pre>
 */
@FunctionalInterface
public interface HookHandler<I, O> {
	O handle(HookCall<I, O> call);
}
