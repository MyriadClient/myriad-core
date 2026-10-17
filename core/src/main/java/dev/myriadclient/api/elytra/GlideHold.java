package dev.myriadclient.api.elytra;

import org.jetbrains.annotations.ApiStatus;

/**
 * Keeps the local player gliding through a moment the server stops the glide, without Grim noticing: the trick behind
 * {@link NoDurability} and a bounce.
 * <p>
 * The server stops a glide (it sees the elytra come off, or you land) by sending your entity flags with the gliding bit
 * cleared. Grim sends one of its pings just before those flags, and only takes the glide as stopped once that ping is
 * answered. So from the ping that came in right before the clear, the answers are held back: Grim keeps expecting
 * gliding, and the client keeps gliding (the clear isn't applied). The owner then sends the answers
 * ({@link #release}) just before it starts gliding again, so Grim sees the stop and then the new start, in order. One
 * owner at a time; held answers go out anyway after a second, so a forgotten hold can't time you out.
 */
@ApiStatus.NonExtendable
public interface GlideHold {
	/** Holds the next glide clear for {@code owner}; false if another owner has it. */
	boolean arm(Object owner);

	/** Lets go: any held answers are sent, and nothing more is held for {@code owner}. */
	void disarm(Object owner);

	/**
	 * Sends the held answers and ends this hold (staying armed for the next clear). Call it right before starting the
	 * glide again: Grim then sees the stop before the start.
	 */
	void release(Object owner);

	/** Whether the server has stopped the glide and the client kept gliding through it (Grim doesn't know yet). */
	boolean clearHeld(Object owner);

	/**
	 * Whether a clear came in that Grim already knows about (its ping was answered before it could be held, or a
	 * teleport sent the answers): the glide has to be started again at once.
	 */
	boolean exposed(Object owner);

	/** Whether a clear has come in (held or exposed), so the next start may go. */
	boolean cleared(Object owner);

	/**
	 * Whether the server has stopped the glide since the last start went out (or never had it going): a stop that came
	 * in answer to that start, or the start had time to be answered and wasn't. Where starts aren't checked against held
	 * pings (vanilla, NoCheatPlus) this keeps two starts from crossing.
	 */
	boolean stoppedSinceStart(Object owner);

	/** Whether the server has said it glides you since {@code ms} ({@code System.currentTimeMillis()}). */
	boolean glidingSince(long ms);
}
