/**
 * The event bus ({@code Myriad.events()}): {@link dev.myriadclient.api.event.Subscribe} handlers on a subscribed object
 * or class, {@link dev.myriadclient.api.event.EventBus#listen lambda listeners},
 * {@link dev.myriadclient.api.event.Priority priorities} and {@link dev.myriadclient.api.event.Cancellable cancellable}
 * events. The events Myriad posts are in
 * {@link dev.myriadclient.api.event.events}; any object can be an event, so an addon can post its own for others to
 * hear.
 * <p>
 * A module's {@code @Subscribe} methods are subscribed while it's on, so most addons never call
 * {@link dev.myriadclient.api.event.EventBus#subscribe} themselves.
 */
@NullMarked
package dev.myriadclient.api.event;

import org.jspecify.annotations.NullMarked;
