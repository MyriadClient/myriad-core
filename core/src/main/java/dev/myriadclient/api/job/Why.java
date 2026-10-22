package dev.myriadclient.api.job;

import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.MyriadId;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Why a step is stuck or failed: a {@link Code} everyone can match on, a detail for people, and the thing it's about
 * when there is one (a position, an item). The codes are an open set keyed by id: core's are the constants here, and an
 * addon declares its own beside its steps ({@code Why.Code.of("my-addon:no_schematic")}). The Jobs window shows a
 * code it doesn't know by its path, so nothing has to register.
 *
 * <pre>{@code
 * return Progress.stuck(Why.of(Why.NO_ITEM, "cobblestone", Items.COBBLESTONE));
 * ...
 * .whenStuck(Why.NO_ITEM, 20, why -> fetchFromChest(why.subject(Item.class)))
 * }</pre>
 *
 * Two reasons are equal when their code, detail and subject are.
 */
public final class Why {
	/** A reason everyone can name. */
	public record Code(MyriadId id) {
		public Code {
			Objects.requireNonNull(id);
		}

		/** {@code "my-addon:no_schematic"}. */
		public static Code of(String id) {
			return new Code(MyriadId.parse(id));
		}

		/** "no schematic": the path, for people. */
		public String describe() {
			return id.path().replace('_', ' ');
		}

		@Override
		public String toString() {
			return id.toString();
		}
	}

	// Core's codes. Stuck (may clear) unless said otherwise.
	/** Nothing in the inventory fits. */
	public static final Code NO_ITEM = core("no_item");
	/** No hotbar slot to put it in. */
	public static final Code NO_SLOT = core("no_slot");
	public static final Code OUT_OF_REACH = core("out_of_reach");
	/** Only faces you can't see, and the options want seen ones. */
	public static final Code NOT_VISIBLE = core("not_visible");
	/** Nothing to place against yet. */
	public static final Code NO_SUPPORT = core("no_support");
	/** An entity, or you, stands in the way. */
	public static final Code ENTITY_IN_WAY = core("entity_in_way");
	/** No click from here makes it face the right way; move. */
	public static final Code WRONG_ANGLE = core("wrong_angle");
	/** The packet budget is spent this tick. */
	public static final Code RATE_LIMITED = core("rate_limited");
	/** The chunk hasn't arrived. */
	public static final Code UNLOADED = core("unloaded");
	/** Nowhere beside you to place it, or the container or your inventory is full. */
	public static final Code NO_ROOM = core("no_room");
	/** Nothing to open there. */
	public static final Code NO_CONTAINER = core("no_container");
	public static final Code NO_ELYTRA = core("no_elytra");
	/** The wrong block is there and it mustn't be broken; the way is blocked. */
	public static final Code BLOCKED = core("blocked");
	public static final Code UNBREAKABLE = core("unbreakable");
	/** Breaking it would let a fluid in. */
	public static final Code FLUID = core("fluid");
	// Failed (won't clear).
	/** The server put the block back, or didn't answer. */
	public static final Code SERVER_REFUSED = core("server_refused");
	public static final Code TIMEOUT = core("timeout");
	/** Baritone isn't installed. */
	public static final Code NO_PATHING = core("no_pathing");
	/** Something no click can make, or a request a step can't carry out. */
	public static final Code UNSUPPORTED = core("unsupported");
	/** You left the world; every job ends with this. */
	public static final Code LEFT_WORLD = core("left_world");
	/** The module that started the job turned off. */
	public static final Code OWNER_OFF = core("owner_off");
	/** The player cancelled it, or it lost a race. */
	public static final Code CANCELLED = core("cancelled");
	/** A step threw; see {@link #cause()}. */
	public static final Code ERROR = core("error");

	private final Code code;
	private final String detail;
	private final @Nullable Object subject;
	private final @Nullable Throwable cause;

	private Why(Code code, String detail, @Nullable Object subject, @Nullable Throwable cause) {
		this.code = Objects.requireNonNull(code);
		this.detail = detail == null ? "" : detail;
		this.subject = subject;
		this.cause = cause;
	}

	public static Why of(Code code, String detail) {
		return new Why(code, detail, null, null);
	}

	public static Why of(Code code, String detail, @Nullable Object subject) {
		return new Why(code, detail, subject, null);
	}

	/** A step threw: {@link #ERROR} with the throwable as the cause and its message as the detail. */
	public static Why error(Throwable cause) {
		String message = cause.getMessage();
		return new Why(ERROR, message == null ? cause.getClass().getSimpleName() : message, null, cause);
	}

	/**
	 * The reason a job's {@code result()} (or a future that waited on one) failed: the {@link Job.JobException}'s,
	 * looking through the {@code CompletionException} a dependent stage wraps it in; {@link #error} for anything else.
	 * For {@code exceptionally} and {@code whenComplete} handlers.
	 */
	public static Why of(Throwable failure) {
		Throwable t = failure;
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
		return t instanceof Job.JobException j ? j.why() : error(t);
	}

	/**
	 * A placement check as a reason; null for {@code OK} and {@code PENDING} (that's running, not stuck).
	 * {@code OUT_OF_RANGE} is {@link #OUT_OF_REACH}, {@code OCCUPIED} is {@link #BLOCKED}, {@code UNAVAILABLE} is
	 * {@link #UNSUPPORTED}.
	 */
	public static @Nullable Why of(Placement.Check check, BlockPos pos) {
		Code c = switch (check) {
			case OK, PENDING -> null;
			case OUT_OF_RANGE -> OUT_OF_REACH;
			case ENTITY_IN_WAY -> ENTITY_IN_WAY;
			case NO_SUPPORT -> NO_SUPPORT;
			case NOT_VISIBLE -> NOT_VISIBLE;
			case RATE_LIMITED -> RATE_LIMITED;
			case OCCUPIED -> BLOCKED;
			case UNAVAILABLE -> UNSUPPORTED;
		};
		return c == null ? null : of(c, pos.toShortString(), pos);
	}

	/**
	 * Where a building position stands, as a reason; null where work goes on ({@code DONE}, {@code BREAK},
	 * {@code PLACE}, {@code WAITING}). {@code OUT_OF_RANGE} is {@link #OUT_OF_REACH}, {@code SUPPORTS_YOU} is
	 * {@link #ENTITY_IN_WAY} (you're the entity).
	 */
	public static @Nullable Why of(Build.Status status, BlockPos pos) {
		Code c = switch (status) {
			case DONE, BREAK, PLACE, WAITING -> null;
			case OUT_OF_RANGE -> OUT_OF_REACH;
			case NO_ITEM -> NO_ITEM;
			case NO_SUPPORT -> NO_SUPPORT;
			case NOT_VISIBLE -> NOT_VISIBLE;
			case ENTITY_IN_WAY, SUPPORTS_YOU -> ENTITY_IN_WAY;
			case BLOCKED -> BLOCKED;
			case UNBREAKABLE -> UNBREAKABLE;
			case FLUID -> FLUID;
			case WRONG_ANGLE -> WRONG_ANGLE;
		};
		return c == null ? null : of(c, pos.toShortString(), pos);
	}

	public Code code() {
		return code;
	}

	/** For people: "cobblestone", "the chest at 12 64 -3 closed". */
	public String detail() {
		return detail;
	}

	/** What it's about, if anything: a {@code BlockPos}, an {@code Item}, an entity. */
	public @Nullable Object subject() {
		return subject;
	}

	/** {@link #subject()} if it's a {@code type}, else null. */
	public <S> @Nullable S subject(Class<S> type) {
		return type.isInstance(subject) ? type.cast(subject) : null;
	}

	public @Nullable Throwable cause() {
		return cause;
	}

	public boolean is(Code code) {
		return this.code.equals(code);
	}

	/**
	 * This reason as an exception: thrown from a {@code run}, {@code peek} or {@code supply} step, it fails the step
	 * with this reason rather than {@link #ERROR}.
	 */
	public Job.JobException asException() {
		return new Job.JobException(this);
	}

	/** Whether this ends a step from outside (the player, a lost race, the owner, leaving), which retry and recover leave be. */
	boolean isCancellation() {
		return code.equals(CANCELLED) || code.equals(OWNER_OFF) || code.equals(LEFT_WORLD);
	}

	private static Code core(String path) {
		return new Code(MyriadId.of("myriad", path));
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Why w && w.code.equals(code) && w.detail.equals(detail) && Objects.equals(w.subject, subject);
	}

	@Override
	public int hashCode() {
		return Objects.hash(code, detail, subject);
	}

	/** "no item: cobblestone". */
	@Override
	public String toString() {
		return detail.isEmpty() ? code.describe() : code.describe() + ": " + detail;
	}
}
