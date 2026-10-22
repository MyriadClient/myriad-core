package dev.myriadclient.api.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a method or type: safe to call from any thread. On an event: it may be posted on a thread other than the render
 * thread (the network thread, a chunk-building worker), so its handlers run there, must not touch the world or
 * services, and should only read and hand work over. {@code @Subscribe(thread = HandlerThread.RENDER)} delivers it on
 * the render thread instead, at the start of the next tick, too late to cancel it.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AnyThread {
}
