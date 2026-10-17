package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.MovementPacketsEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick rotation requests, arbitrated per axis and written into the movement packet. A turn speed steps from the
 * rotation the server last saw; when requests stop, a rotation that was turning eases back to the real view at the
 * same speed. With move fix, the movement keys are re-pressed relative to the sent yaw at input time and that yaw is
 * then frozen for the tick, so the movement the client simulates is the movement the server sees.
 */
public final class RotationManager implements Rotations {
	private record Request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent) {
	}

	/** What one axis does this tick: the value to send, the winning request (if any), and how fast it may turn. */
	private record Axis(float value, Request winner, float speed) {
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final List<Request> requests = new ArrayList<>();
	private boolean rotating;
	private float serverYaw, serverPitch;
	/** Easing back after a turning request ended: the speed, and whether it still needs move fix. 0 = not easing. */
	private float returnSpeed;
	private boolean returnMoveFix;
	/** The yaw the player moves with this tick under move fix, frozen at input time; NaN when not fixing. */
	private float fixYaw = Float.NaN;
	private final List<Runnable> sentCallbacks = new ArrayList<>();

	@Override
	public synchronized void request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent) {
		if (Float.isNaN(yaw) && Float.isNaN(pitch)) return;
		requests.add(new Request(owner, yaw, Float.isNaN(pitch) ? pitch : Mth.clamp(pitch, -90, 90), priority, options == null ? Options.INSTANT : options, afterSent));
	}

	/** Highest priority request with a value on this axis; the first one on ties. */
	private Request winner(boolean yawAxis) {
		Request best = null;
		for (Request r : requests) {
			float v = yawAxis ? r.yaw : r.pitch;
			if (!Float.isNaN(v) && (best == null || r.priority > best.priority)) best = r;
		}
		return best;
	}

	private Axis plan(boolean yawAxis, float real) {
		Request w = winner(yawAxis);
		float from = yawAxis ? serverYaw : serverPitch;
		if (w != null) {
			float target = yawAxis ? w.yaw : w.pitch;
			return new Axis(turn(w.owner, from, target, w.options.turnSpeed(), yawAxis), w, w.options.turnSpeed());
		}
		if (returnSpeed > 0) return new Axis(turn(null, from, real, returnSpeed, yawAxis), null, returnSpeed);
		return new Axis(real, null, 0);
	}

	/** {@link #step}, or what the {@link Rotations#TURN} hook makes of it for {@code owner}'s request. */
	private static float turn(Object owner, float from, float to, float speed, boolean yawAxis) {
		if (!TURN.hooked()) return step(from, to, speed, yawAxis);
		Float v = TURN.call(owner, new Turn(from, to, speed, yawAxis), t -> step(t.from(), t.to(), t.speed(), t.yaw()));
		return v == null || v.isNaN() ? step(from, to, speed, yawAxis) : yawAxis ? v : Mth.clamp(v, -90, 90);
	}

	/** Moves {@code from} towards {@code to} by at most {@code speed} degrees (0 = all the way); yaw wraps around. */
	static float step(float from, float to, float speed, boolean wraps) {
		float delta = wraps ? Mth.wrapDegrees(to - from) : to - from;
		if (speed <= 0 || Math.abs(delta) <= speed) return from + delta;
		return from + Math.copySign(speed, delta);
	}

	/** Re-presses the movement keys relative to the yaw being sent, before the player moves. */
	@Subscribe(priority = Priority.LOWEST)
	private synchronized void onInput(InputEvent e) {
		fixYaw = Float.NaN;
		if (mc.player == null) return;
		Request w = winner(true);
		boolean fix = w != null ? w.options.moveFix() : returnSpeed > 0 && returnMoveFix;
		if (!fix) return;
		float real = mc.player.getYRot();
		float sent = pinned != null ? pinned[0] : plan(true, real).value;
		fixYaw = sent;
		if (!e.isMoving() || Math.abs(Mth.wrapDegrees(sent - real)) < 1f) return;
		int[] keys = fixKeys(real, sent, e.forward, e.backward, e.left, e.right);
		e.forward = keys[0] > 0;
		e.backward = keys[0] < 0;
		e.left = keys[1] < 0;
		e.right = keys[1] > 0;
	}

	/**
	 * The movement keys that, pressed while facing {@code sentYaw}, go closest to where the pressed keys would go
	 * while facing {@code realYaw}. Returns {forward (1, 0, -1), strafe (1 right, 0, -1 left)}.
	 */
	static int[] fixKeys(float realYaw, float sentYaw, boolean forward, boolean backward, boolean left, boolean right) {
		int f = (forward ? 1 : 0) - (backward ? 1 : 0), s = (right ? 1 : 0) - (left ? 1 : 0);
		if (f == 0 && s == 0) return new int[]{0, 0};
		// Yaw grows clockwise seen from above, so strafing right is +90 degrees from forward.
		float wanted = realYaw + (float) Math.toDegrees(Math.atan2(s, f));
		int[] best = null;
		float bestError = Float.MAX_VALUE;
		for (int cf = -1; cf <= 1; cf++) {
			for (int cs = -1; cs <= 1; cs++) {
				if (cf == 0 && cs == 0) continue;
				float error = Math.abs(Mth.wrapDegrees(sentYaw + (float) Math.toDegrees(Math.atan2(cs, cf)) - wanted));
				if (error < bestError - 1e-3f) {
					bestError = error;
					best = new int[]{cf, cs};
				}
			}
		}
		return best;
	}

	/** The yaw the local player walks, jumps and swims with this tick, or NaN to use its own. */
	public float movementYaw() {
		return fixYaw;
	}

	@Subscribe(priority = Priority.LOW)
	private synchronized void onMovement(MovementPacketsEvent e) {
		float yaw = Float.isNaN(fixYaw) ? plan(true, e.yaw).value : fixYaw;
		Axis pitch = plan(false, e.pitch);
		Request yawWinner = winner(true);

		e.yaw = continuous(yaw);
		float realPitch = e.pitch;
		e.pitch = pitch.value;
		if (pinned != null) {
			// An action this tick already went out with a rotation: this packet carries exactly that one.
			e.yaw = pinned[0];
			e.pitch = pinned[1];
		}
		// (A 0.01° nudge to carry the rotation doesn't count.)
		rotating = Math.abs(Mth.wrapDegrees(e.yaw - (mc.player != null ? mc.player.getYRot() : e.yaw))) > 0.01f || Math.abs(e.pitch - realPitch) > 0.02f;

		// Ease back afterwards at the speed of whatever was turning; instant requests snap back as before.
		Request turning = yawWinner != null ? yawWinner : pitch.winner;
		if (turning != null) {
			returnSpeed = turning.options.turnSpeed();
			returnMoveFix = turning.options.moveFix();
		} else if (!rotating) {
			returnSpeed = 0;
		}

		// Asked to carry a rotation and nothing changed since the last one sent: nudge the pitch so one goes out (a
		// pinned rotation already had it).
		if (sendRotation && pinned == null) e.pitch = nudged(e.yaw, e.pitch);
		sendRotation = false;
		pinned = null;

		sentCallbacks.clear();
		for (Request r : requests) {
			if (r.afterSent == null) continue;
			boolean yawOk = Float.isNaN(r.yaw) || yawWinner == r && Math.abs(Mth.wrapDegrees(e.yaw - r.yaw)) < 0.01f;
			boolean pitchOk = Float.isNaN(r.pitch) || pitch.winner == r && Math.abs(e.pitch - r.pitch) < 0.01f;
			if (yawOk && pitchOk) sentCallbacks.add(r.afterSent);
		}
		requests.clear();
	}

	@Subscribe
	private synchronized void onMovementSent(MovementPacketsEvent.Post e) {
		fixYaw = Float.NaN;
	}

	/**
	 * Runs the callbacks of requests whose rotation was sent last tick, at the start of this one: the server already
	 * faces that way, and actions go out before this tick's movement, where vanilla sends them (Grim flags actions sent
	 * after a movement packet; see ActionTiming).
	 */
	@Subscribe(priority = ACT_PRIORITY)
	private void onTickStart(TickEvent.Pre e) {
		List<Runnable> run;
		synchronized (this) {
			if (sentCallbacks.isEmpty()) return;
			run = new ArrayList<>(sentCallbacks);
			sentCallbacks.clear();
		}
		for (Runnable r : run) r.run();
	}

	/** Where core's services act at the start of a tick: after held actions go out, before modules. */
	static final int ACT_PRIORITY = 900;

	/** Every rotation that reaches the server, including ones features send directly. */
	@Subscribe(priority = Priority.LOWEST)
	private synchronized void onSend(PacketEvent.Send e) {
		if (!e.isCancelled() && e.packet() instanceof ServerboundMovePlayerPacket p && p.hasRotation()) {
			serverYaw = p.getYRot(0);
			serverPitch = p.getXRot(0);
		}
	}

	@Subscribe
	private synchronized void onTick(TickEvent.Post e) {
		// A tick without a movement packet (e.g. while riding) shouldn't leave the fix or a pin on.
		fixYaw = Float.NaN;
		pinned = null;
	}

	/**
	 * {@code yaw} as the turn from the last yaw sent, never more than half a turn: a yaw kept continuous with the camera
	 * instead jumps a whole turn once the camera, looking round, crosses the opposite of a rotation being held (Grim's
	 * AimModulo360), and so does handing back to the camera afterwards.
	 */
	private float continuous(float yaw) {
		return continuous(serverYaw, yaw);
	}

	static float continuous(float lastSent, float yaw) {
		return lastSent + Mth.wrapDegrees(yaw - lastSent);
	}

	/** The pitch to send so the packet carries a rotation: nudged 0.01° if nothing changed since the last one sent. */
	private float nudged(float yaw, float pitch) {
		if (Mth.wrapDegrees(yaw - serverYaw) != 0 || pitch != serverPitch) return pitch;
		return pitch >= 89.99f ? pitch - 0.01f : pitch + 0.01f;
	}

	/** The rotation this tick's movement packet carries, once an action has gone out with it; null until then. */
	private float[] pinned;

	@Override
	public synchronized float[] rotationForAction() {
		if (pinned == null) {
			if (mc.player == null) return new float[]{serverYaw, serverPitch};
			float real = mc.player.getYRot();
			float yaw = Float.isNaN(fixYaw) ? plan(true, real).value : fixYaw;
			// Exactly the value the movement packet will hold.
			yaw = continuous(yaw);
			float pitch = plan(false, mc.player.getXRot()).value;
			pinned = new float[]{yaw, sendRotation ? nudged(yaw, pitch) : pitch};
		}
		return pinned.clone();
	}

	/**
	 * Item-use packets carry a rotation of their own, which the server turns you to before using the item, and Grim
	 * (BadPacketsJ) wants it equal to the tick's. Thrown items (a pearl flies where the packet aims) keep their aim, and
	 * this tick's movement packet carries it too; any other item use gets the rotation this tick sends anyway (see
	 * {@link #rotationForAction()}), so a module turning you isn't interrupted. Later uses this tick get the same one.
	 */
	@Subscribe(priority = Priority.HIGH, packets = ServerboundUseItemPacket.class)
	private void onSendAction(PacketEvent.Send e) {
		if (!(e.packet() instanceof ServerboundUseItemPacket p) || mc.player == null) return;
		float[] r = useRotation(p.getHand(), p.getYRot(), p.getXRot());
		if (p.getYRot() != r[0] || p.getXRot() != r[1]) e.setPacket(new ServerboundUseItemPacket(p.getHand(), p.getSequence(), r[0], r[1]));
	}

	/** The rotation an item use in {@code hand}, made facing {@code yaw}/{@code pitch}, goes out with (see above). */
	private float[] useRotation(InteractionHand hand, float yaw, float pitch) {
		ItemStack used = mc.player.getItemInHand(hand);
		boolean aimed = used.getItem() instanceof ProjectileItem && !used.is(Items.FIREWORK_ROCKET);
		return aimed ? pinTo(yaw, pitch) : rotationForAction();
	}

	/** The hand of the item use being made right now, or null. */
	private volatile InteractionHand usingHand;

	/** Called around {@code MultiPlayerGameMode.useItem}: {@code hand} while it runs, null after. */
	public void usingItem(InteractionHand hand) {
		usingHand = hand;
	}

	/**
	 * Joined as 1.17 to 1.20.5, ViaFabricPlus sends your position and rotation just before each item use (the use
	 * packet had no rotation then; the server and Grim take it from this one), with the camera's rotation. It gets the
	 * use's rotation instead, so the server doesn't turn to the camera and back within the tick while a module holds a
	 * rotation (and a thrown item flies where it was aimed).
	 */
	@Subscribe(priority = Priority.HIGH, packets = ServerboundMovePlayerPacket.PosRot.class)
	private void onSendUsePosition(PacketEvent.Send e) {
		InteractionHand hand = usingHand;
		if (hand == null || !(e.packet() instanceof ServerboundMovePlayerPacket.PosRot p) || mc.player == null) return;
		float[] r = useRotation(hand, p.getYRot(0), p.getXRot(0));
		if (p.getYRot(0) == r[0] && p.getXRot(0) == r[1]) return;
		e.setPacket(new ServerboundMovePlayerPacket.PosRot(p.getX(0), p.getY(0), p.getZ(0), r[0], r[1], p.isOnGround(), p.horizontalCollision()));
	}

	private synchronized float[] pinTo(float yaw, float pitch) {
		if (pinned == null) pinned = new float[]{yaw, sendRotation ? nudged(yaw, pitch) : pitch};
		return pinned.clone();
	}

	/** A rotation must go out in this tick's movement packet (see {@link Rotations#sendRotationThisTick()}). */
	private volatile boolean sendRotation;

	@Override
	public void sendRotationThisTick() {
		sendRotation = true;
	}

	@Override
	public boolean isRotating() {
		return rotating;
	}

	@Override
	public float serverYaw() {
		return serverYaw;
	}

	@Override
	public float serverPitch() {
		return serverPitch;
	}

	@Override
	public float[] anglesTo(Vec3 target) {
		return MathUtil.anglesTo(mc.player.getEyePosition(), target);
	}
}
