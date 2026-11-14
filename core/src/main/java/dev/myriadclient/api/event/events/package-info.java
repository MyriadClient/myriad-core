/**
 * Every event Myriad posts. Only Myriad creates them; subscribe with {@link dev.myriadclient.api.event.Subscribe}.
 * They fire on the render thread, except those marked {@link dev.myriadclient.api.annotation.AnyThread}. Most are
 * posted only while something listens, so an event nobody handles costs nothing.
 * <p>
 * Several events are reused instances ({@code INSTANCE}, or one object reset for each post): read what you need in the
 * handler and don't keep the event.
 */
@NullMarked
package dev.myriadclient.api.event.events;

import org.jspecify.annotations.NullMarked;
