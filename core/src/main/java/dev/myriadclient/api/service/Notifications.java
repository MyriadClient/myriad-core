package dev.myriadclient.api.service;

import dev.myriadclient.api.annotation.AnyThread;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Toast notifications, drawn by the window manager overlay both in game and in the menu. A toast never shows
 * coordinates: three numbers in a row are shown as "(location hidden)", as a stream or a screenshot would give a place
 * away. Say where something is in words ("beside you", "where it fell"), if at all.
 *
 * <pre>{@code
 * Myriad.notifications().info("Waypoints", "Added Home");
 * Myriad.notifications().toast("Search", "Covered 40%")
 *     .replacing("search-progress")      // updates the one showing instead of stacking
 *     .lasting(1500)
 *     .show();
 * }</pre>
 */
@ApiStatus.NonExtendable
@AnyThread
public interface Notifications {
	/** How a notification is marked: its accent colour and icon. */
	enum Level {
		/** Something worth knowing; the theme's accent colour. */
		INFO,
		/** Something finished as hoped; green. */
		SUCCESS,
		/** Something to look at, though nothing failed; yellow. */
		WARNING,
		/** Something failed; red. */
		ERROR
	}

	/**
	 * Shows a notification. A notification with the same non-null {@code key} replaces the previous one instead of
	 * stacking (useful for repeated status updates). Sent again while it's still showing, the same notification (title,
	 * message and level, no key) is counted on the one showing ("×3") and stays up longer, instead of stacking. At most
	 * six show at once; the oldest goes first. Also posted as a {@code NotificationEvent}, with coordinates hidden.
	 *
	 * @param durationMs how long it stays up, in milliseconds
	 * @param key what it replaces, or null to stack
	 */
	void send(String title, String message, Level level, long durationMs, @Nullable String key);

	/** A notification to set up and {@link Toast#show()}: info level, 3 seconds, stacking, unless you say otherwise. */
	default Toast toast(String title, String message) {
		return new Toast(this, title, message);
	}

	/** Shows an info notification for 3 seconds. */
	default void info(String title, String message) {
		send(title, message, Level.INFO, 3000, null);
	}

	/** Shows a success notification for 3 seconds. */
	default void success(String title, String message) {
		send(title, message, Level.SUCCESS, 3000, null);
	}

	/** Shows a warning for 4 seconds. */
	default void warn(String title, String message) {
		send(title, message, Level.WARNING, 4000, null);
	}

	/** Shows an error for 5 seconds. */
	default void error(String title, String message) {
		send(title, message, Level.ERROR, 5000, null);
	}

	/** A notification being set up ({@link #toast}). */
	final class Toast {
		private final Notifications to;
		private final String title, message;
		private Level level = Level.INFO;
		private long durationMs = 3000;
		private @Nullable String key;

		private Toast(Notifications to, String title, String message) {
			this.to = to;
			this.title = title;
			this.message = message;
		}

		/** How it's marked ({@link Level#INFO} unless set). */
		public Toast level(Level level) {
			this.level = level;
			return this;
		}

		/** How long it stays up, in milliseconds. */
		public Toast lasting(long durationMs) {
			this.durationMs = durationMs;
			return this;
		}

		/** Replaces the one showing with the same key instead of stacking (a status that updates). */
		public Toast replacing(String key) {
			this.key = key;
			return this;
		}

		/** Shows it ({@link Notifications#send}). It can be shown again, or changed and shown. */
		public void show() {
			to.send(title, message, level, durationMs, key);
		}
	}
}
