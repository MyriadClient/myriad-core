package dev.myriadclient.api.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Call it on the render thread only (the game's own thread: ticks, rendering, input, screens, and every event handler
 * but those of events marked {@link AnyThread}). From another thread, hand the work over with
 * {@code Minecraft.getInstance().execute(...)}. With {@code -Dmyriad.dev} set, core's services log a call from another
 * thread ({@link Threads#assertRender()}). See the Threads page of the docs.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RenderThread {
}
