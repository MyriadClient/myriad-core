package dev.myriadclient.api.event.events;

import dev.myriadclient.api.annotation.AnyThread;
import dev.myriadclient.api.service.Notifications;
import org.jetbrains.annotations.ApiStatus;

/**
 * A notification was sent (by core, a module or an addon, through {@link Notifications}), as it shows: coordinates in
 * it already hidden. For keeping a log of what modules said. Posted on the thread that sent it, which may not be the
 * render thread. Not cancellable: it has already been shown.
 *
 * @param title   the title, usually the module's or feature's name
 * @param message the text, with any coordinates in it already hidden
 * @param level   how important it is
 */
@AnyThread
@ApiStatus.AvailableSince("2.0")
public record NotificationEvent(String title, String message, Notifications.Level level) {
}
