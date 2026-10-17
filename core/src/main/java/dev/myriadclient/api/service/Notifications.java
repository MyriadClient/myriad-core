package dev.myriadclient.api.service;

import org.jetbrains.annotations.ApiStatus;
/**
 * Toast notifications, drawn by the window manager overlay both in game and on the desktop.
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
public interface Notifications {
	enum Level {
		INFO, SUCCESS, WARNING, ERROR
	}

	/**
	 * Shows a notification. A notification with the same non-null {@code key} replaces the previous one instead of
	 * stacking (useful for repeated status updates). Sent again while it's still showing, the same notification (title,
	 * message and level, no key) is counted on the one showing ("×3") and stays up longer, instead of stacking.
	 */
	void send(String title, String message, Level level, long durationMs, String key);

	/** A notification to set up and {@link Toast#show()}: info level, 3 seconds, stacking, unless you say otherwise. */
	default Toast toast(String title, String message) {
		return new Toast(this, title, message);
	}

	default void info(String title, String message) {
		send(title, message, Level.INFO, 3000, null);
	}

	default void success(String title, String message) {
		send(title, message, Level.SUCCESS, 3000, null);
	}

	default void warn(String title, String message) {
		send(title, message, Level.WARNING, 4000, null);
	}

	default void error(String title, String message) {
		send(title, message, Level.ERROR, 5000, null);
	}

	/** A notification being set up ({@link #toast}). */
	final class Toast {
		private final Notifications to;
		private final String title, message;
		private Level level = Level.INFO;
		private long durationMs = 3000;
		private String key;

		private Toast(Notifications to, String title, String message) {
			this.to = to;
			this.title = title;
			this.message = message;
		}

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

		public void show() {
			to.send(title, message, level, durationMs, key);
		}
	}
}
