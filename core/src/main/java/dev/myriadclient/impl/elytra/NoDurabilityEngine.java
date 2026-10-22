package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.InteractEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * No Durability for an elytra, and its catch of a glide dropped in mid-air (on while Search flies). The same engine as
 * Myriad Essentials' Elytra Fly, kept here so Stash Hunter flies without Essentials.
 * <p>
 * The server wears an elytra by one for every 20 ticks of gliding without a break. With a
 * chestplate in your off hand or hotbar, the elytra is swapped for it (using it, as a right-click equips armour)
 * while you glide, so the server stops the glide on its next tick; the client keeps gliding through that (see
 * {@link GlideHolder}), and Grim doesn't notice. Every 8 ticks the elytra goes back on just long enough to start the
 * glide again (jump pressed, as vanilla starts one), and comes off again. The server never glides for more than a
 * tick in one go, so it never wears the elytra. Rockets you use meanwhile go out in those moments, when the server
 * takes them.
 * <p>
 * Meanwhile the server takes you as falling, and counts every block you descend as fall distance; only rising again
 * clears it (or gliding long enough at a gentle slope once the elytra's back on). Landing, or hitting something,
 * with that count would cost all of it. So swaps only run with room around you: below, and along the way you're flying
 * for as long as the server takes to hear from you, or, once you've come down more than a safe fall since you last
 * rose, for as long as it then takes to glide that count away too. The elytra is back on and the server gliding before
 * you could land or hit anything. Nor while you'd fly into a chunk Grim doesn't know you have: it counts a chunk as yours
 * only once you've answered the ping after it, which the hold holds back, and sets you back in one it doesn't (2b2t
 * sends chunks just in time in new terrain, and fast flight reaches them during a hold). The swapping waits for those
 * chunks, the elytra on meanwhile, and a hold about to reach one is cut short (see {@link GrimChunks}). The server's
 * equip sound for each swap is muted. Eating (or anything you hold to use) pauses the swapping: Grim stops what you're
 * using each time the chestplate is used, so the elytra goes back on at the next start, and swapping picks up again
 * once you're done.
 * <p>
 * <b>Elsewhere</b> (an anti-cheat that doesn't simulate the glide, such as 6b6t's NoCheatPlus, or none) the swap fails:
 * a chestplate on mid-glide is checked as walking at once, and the glide hold has no pings to hold. There it breaks the
 * glide with two glide starts instead: one sent while you glide stops the glide there and then (vanilla's answer to one
 * it can't start), you hold still for that tick (no movement goes out while the server isn't gliding you), and the
 * other starts it again before the next tick's movement. A server tick between the two resets its count; if none falls
 * between them (both in one of its ticks), that break is lost, and nothing is set back: the stop doesn't wait for a
 * tick, so the start always finds the glide stopped. A break the server took shows (its tick ended with you not
 * gliding, and it says so); one it doesn't say so for within a round trip is made again at once, and it and the next
 * {@link GlideBreaks#LONG_BREAKS} hold still two ticks, as the timing that lost one changes slowly (6b6t lost about one in five,
 * often twice running). No chestplate needed. Grim, for its part, flags the two starts and the still tick.
 */
final class NoDurabilityEngine implements NoDurability {
	private final Owner fly;
	private final Minecraft mc = Minecraft.getInstance();
	private final GlideBreaks breaks = new GlideBreaks();
	private final GlideCatch catcher;

	NoDurabilityEngine(Owner fly) {
		this.fly = fly;
		catcher = new GlideCatch(fly);
	}

	/** Ticks between glide restarts: well under vanilla's 80-tick floating kick and the 20 ticks that wear the elytra. */
	private static final int INTERVAL = 8;
	/** How far ahead (ticks) a hold is cut short for a chunk Grim doesn't know, and the margin on your speed for it. */
	private static final int BAIL_TICKS = 3;
	/** How long after a swap waited for chunks Altitude keeps its speed down. */
	private static final int CHUNKS_LATE_TICKS = 100;
	private static final double PATH_MARGIN = 1.3;
	/** Give up if the server hasn't stopped the glide this long after a swap (it refused it). */
	private static final int CLEAR_TIMEOUT = 40;
	/** Ticks of gliding with room below before the first swap. */
	private static final int ARM_TICKS = 10;
	/** Coming down more than this since you last rose, the server's count of your fall would hurt on landing. */
	static final double SAFE_DESCENT = 3;
	/**
	 * Ticks of gliding the server needs, elytra back on, before it lets go of that count: it only does while it sees you
	 * descend gently, and its idea of your speed (a free fall while it wasn't gliding you) takes a while to settle.
	 */
	private static final int FORGET_TICKS = 30;

	private boolean swapping, startNow, rocketWanted, firing, warned;
	/** Said why the counterpart isn't at hand (once, until it is). */
	private boolean pairWarned;
	/**
	 * Stopping in mid-air: the elytra is back on, and the glide starts again once the server's stop for the last swap
	 * is in (held meanwhile, so you keep gliding) and not straight after the last start; {@link #finishWait} counts the
	 * ticks spent waiting for it.
	 */
	private boolean finishing;
	private int finishWait;
	/** How far you've come down, as the server counts it, since you last rose; and ticks of gliding since swapping. */
	private double descent;
	private int glidingTicks;
	/** Something to eat or hold to use was used meanwhile: swapping stops at the next start, so it can go then. */
	private boolean stopForUse;
	private InteractionHand rocketHand = InteractionHand.MAIN_HAND;
	/** The hotbar slot a waiting rocket was used from (it may have been a silent swap, as Middle Click's are). */
	private int rocketSlot = -1;
	private int armTicks, sinceStart;
	/**
	 * Altitude: the elytra is back on for the next start, which waits for the server to have it on. On and the start in
	 * one tick, 2b2t ignored a start now and then on Altitude's long fast glides, each one a run of setbacks.
	 */
	private boolean equipping;
	/** Swaps put off, and holds cut short, for chunks Grim doesn't know you have yet (see {@link #pathKnown}). */
	private int chunkWaits, chunkBails;
	/** Ticks since the last of those, and why it was (for the record: how far ahead, and what Grim lacked). */
	private int sinceChunkWait = Integer.MAX_VALUE;
	private String lastChunkWait = "";
	/**
	 * When the last start went out ({@code Clock.millis()}, 0 for none yet this round). Now and then 2b2t
	 * ignores one: no glide comes back, and neither the server nor Grim glides you while the client does, so Grim sets
	 * you back every second or two. A setback with no glide back since the start says so; the elytra then goes on, the
	 * start goes again once the server has it ({@link #recovering}), and the swapping waits for the server's glide
	 * ({@link #confirming}).
	 */
	private long startMs;
	private volatile boolean setBack;
	private boolean recovering, confirming;
	private int lostStarts;

	@Override
	public void enable() {
		breaks.end();
		ENABLED.remove(this);
		ENABLED.add(this);
		Myriad.events().subscribe(this);
		Myriad.events().subscribe(breaks);
		swapping = startNow = rocketWanted = stopForUse = finishing = equipping = recovering = confirming = setBack = false;
		descent = 0;
		armTicks = 0;
		catcher.stop();
		GrimChunks.start();
	}

	@Override
	public void disable() {
		if (mc.player != null && mc.level != null) {
			// No next tick to wait for: start the glide again now rather than drop out of it in mid-air.
			finish(true);
			// Turning off mid-swap can start the glide again, and the owner no longer hears the input event that presses
			// jump with it (Grim's ElytraB): press it from a listener of its own, for that one tick.
			if (startNow) pressJumpOnce();
		}
		swapping = startNow = equipping = recovering = confirming = false;
		catcher.stop();
		GlideHolder.disarm(this);
		GrimChunks.stop();
		breaks.end();
		Myriad.events().unsubscribe(this);
		Myriad.events().unsubscribe(breaks);
		ENABLED.remove(this);
	}

	/** Whether the elytra and chestplate are being swapped (or the elytra put back for good) right now. */
	@Override
	public boolean swapping() {
		return swapping;
	}

	/** Before any of this tick's actions, after the owner's flight: a restart sends held ping answers, which must come first (Grim's Post). */
	@Override
	public void tick() {
		startNow = false;
		if (sinceChunkWait < Integer.MAX_VALUE) sinceChunkWait++;
		if (mc.player == null || mc.level == null) {
			swapping = false;
			return;
		}
		// Another engine runs (two would each send their starts, often in the same tick, where they cancel out).
		if (active() != this) {
			finish();
			breaks.end();
			return;
		}
		catcher.tick(mc.player, descent);
		tickNoDurability();
	}

	/**
	 * The engine that runs, of those enabled: the last enabled for a pilot's flight (a flight's own, such as an addon's
	 * auto flight), else the last enabled (one that keeps the elytra whoever flies, like Essentials' Elytra Swap).
	 */
	private static @Nullable NoDurabilityEngine active() {
		NoDurabilityEngine last = null, pilot = null;
		for (NoDurabilityEngine n : ENABLED) {
			last = n;
			if (n.fly instanceof Elytra.Pilot p && ElytraManager.flying(p)) pilot = n;
		}
		return pilot != null ? pilot : last;
	}

	private static void pressJumpOnce() {
		Subscription[] once = new Subscription[1];
		once[0] = Myriad.events().listen(InputEvent.class, Priority.LOWEST, e -> {
			e.jump = true;
			once[0].unsubscribe();
		});
	}

	// ---- No Durability ----------------------------------------------------------------------------------------------

	private void tickNoDurability() {
		LocalPlayer p = mc.player;
		boolean wasSetBack = setBack;
		setBack = false;
		trackDescent(p);
		if (!fly.noDurability()) {
			finish();
			breaks.end();
			return;
		}
		// Where the glide isn't simulated, restarting it mid-air spares the elytra; Grim flags that, so there a chestplate goes on.
		if (!AntiCheat.inEffect().simulatesGlide()) {
			finish();
			breaks.tick(p, wasSetBack);
			return;
		}
		breaks.end();
		if (!swapping) {
			arm(p);
			return;
		}
		sinceStart++;
		if (finishing) {
			finish();
			return;
		}
		// Landing, water, or the ground (below or ahead) close enough that the server could see you land without the
		// elytra.
		if (!p.isFallFlying() || p.onGround() || p.isInWater() || !room(false)) {
			finish();
			return;
		}
		// About to fly into a chunk Grim won't count as yours until the held answers go out: start again now.
		if (GlideHolder.clearHeld(this) && !pathKnown(p, BAIL_TICKS)) {
			chunkBails++;
			chunkWait();
			finish(true);
			return;
		}
		if (wasSetBack && !equipping && !confirming && startLost()) {
			lostStarts++;
			recovering = true;
			GlideHolder.release(this);
			equip();
			return;
		}
		if (equipping) {
			equipped(p);
			return;
		}
		if (confirming) {
			confirm(p);
			return;
		}
		nextStart(p);
	}

	/** Not swapping yet: the first swap, once you've glided with room below for a while and everything it needs is there. */
	private void arm(LocalPlayer p) {
		// Not while you eat (or use anything held): a swap would stop it (see onUse).
		boolean ready = p.isFallFlying() && ChestSwapper.elytraWorn() && !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.isUsingItem()
			&& !ElytraManager.pilotHoldsGlide() && room(true);
		armTicks = ready ? armTicks + 1 : 0;
		if (armTicks < ARM_TICKS) return;
		if (!chestplateAtHand(true)) return;
		if (!ChestSwapper.ready()) {
			warnOnce("No Durability can't swap armour with Curse of Binding.");
			return;
		}
		if (!pathKnown(p, cycleTicks())) {
			chunkWait();
			return;
		}
		if (!GlideHolder.arm(this)) return;
		ChestSwapper.swap();
		swapping = true;
		sinceStart = 0;
		startMs = 0;
	}

	/** The elytra was put back on for the next start: the start once the server has it on. */
	private void equipped(LocalPlayer p) {
		if (!(ChestSwapper.elytraWorn() && ChestSwapper.settled()) && sinceStart <= INTERVAL + CLEAR_TIMEOUT) return;
		if (recovering) {
			// The start alone; the chestplate once the server glides.
			recovering = false;
			restart(false);
			confirming = true;
		} else {
			swapAgainOrStop(p);
		}
	}

	/** After a start made again for a lost one: the chestplate once the server glides, or the swapping stops. */
	private void confirm(LocalPlayer p) {
		if (GlideHolder.glidingSince(startMs)) {
			confirming = false;
			if (pathKnown(p, cycleTicks())) {
				ChestSwapper.swap();
				sinceStart = 0;
			} else {
				chunkWait();
				finish();
				armTicks = ARM_TICKS;
			}
		} else if (sinceStart > 2 * roundTripTicks() + 10) {
			confirming = false;
			finish();
		}
	}

	/** The next start, once the server's stop for the last swap is in and it's due. */
	private void nextStart(LocalPlayer p) {
		boolean cleared = GlideHolder.cleared(this);
		boolean due = sinceStart >= INTERVAL || rocketWanted || stopForUse || GlideHolder.exposed(this);
		// Not two starts in a row (Grim's ElytraC).
		if (cleared && due && sinceStart >= 2) {
			// Something to eat is waiting: this start puts the elytra back for good, and the swapping stops.
			if (stopForUse) finish();
			else if (fly.equipBeforeStart()) equip();
			else swapAgainOrStop(p);
		} else if (!cleared && sinceStart > INTERVAL + CLEAR_TIMEOUT) {
			finish();
		}
	}

	/**
	 * The start, with the chestplate back on for another round if Grim knows every chunk you'll fly through meanwhile;
	 * otherwise the elytra stays on and the swapping stops until it does.
	 */
	private void swapAgainOrStop(LocalPlayer p) {
		if (pathKnown(p, cycleTicks())) {
			restart(true);
		} else {
			chunkWait();
			finish();
			// Swapping again as soon as Grim knows the chunks (the elytra on until then), not after a fresh arming.
			armTicks = ARM_TICKS;
		}
	}

	/** The engines enabled, oldest first; one of them runs ({@link #active}). */
	private static final CopyOnWriteArrayList<NoDurabilityEngine> ENABLED = new CopyOnWriteArrayList<>();

	/** Whether a break holds the local player still this tick: no movement, so none goes out. */
	public static boolean holdsStill() {
		NoDurabilityEngine n = active();
		return n != null && n.breaks.stopped();
	}

	/** Whether the local player counts as gliding through the breaks. */
	public static boolean holdsGlide() {
		NoDurabilityEngine n = active();
		return n != null && n.breaks.holding();
	}

	/** A setback came in: handled on the next tick (see {@link #startMs}). */
	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onSetBack(PacketEvent.Receive e) {
		setBack = true;
	}

	/** Whether the last start has had time to come back as a glide from the server, and hasn't. */
	private boolean startLost() {
		return startMs > 0 && Clock.millis() - startMs > Myriad.server().ping() + 150 && !GlideHolder.glidingSince(startMs);
	}

	/** Whether swaps have had to wait for chunks lately (Altitude slows down for them). */
	@Override
	public boolean chunksLate() {
		return sinceChunkWait <= CHUNKS_LATE_TICKS;
	}

	private void chunkWait() {
		if (sinceChunkWait > 0) chunkWaits++;
		sinceChunkWait = 0;
		lastChunkWait = pathWhy(mc.player, cycleTicks());
	}

	/** Ticks of a round of swapping: from the chestplate going on until the start after it, and the answers out. */
	private static int cycleTicks() {
		return INTERVAL + 2 * roundTripTicks() + 4;
	}

	/**
	 * Whether Grim knows the client has every chunk you'll be in over the next {@code ticks}, at your speed and a
	 * margin (see {@link GrimChunks}): a hold that runs into one it doesn't would get you set back.
	 */
	private static boolean pathKnown(LocalPlayer p, int ticks) {
		return pathWhy(p, ticks) == null;
	}

	/** What Grim lacks first along the path, and how many ticks ahead; null if nothing. */
	private static String pathWhy(LocalPlayer p, int ticks) {
		Vec3 v = p.getDeltaMovement().scale(PATH_MARGIN);
		for (int t = 0; t <= ticks; t++) {
			String why = GrimChunks.why(p.getX() + v.x * t, p.getZ() + v.z * t);
			if (why != null) return why + " " + t;
		}
		return null;
	}

	/** Altitude: the elytra back on, the start once the server has it (see {@link #equipping}). */
	private void equip() {
		ChestSwapper.restoreElytra();
		equipping = true;
	}

	/**
	 * Starts the glide again: the held ping answers go first (Grim sees the stop), the elytra goes on, the start is
	 * sent with jump pressed this tick and released the tick before (as vanilla starts one; Grim's ElytraB), a waiting
	 * rocket is used while the server takes it, and with {@code swapBack} the chestplate goes back on a couple of ticks
	 * later.
	 */
	private void restart(boolean swapBack) {
		equipping = false;
		GlideHolder.release(this);
		if (!ChestSwapper.startGlide()) {
			finish();
			return;
		}
		if (rocketWanted) fireRocket();
		startNow = true;
		sinceStart = 0;
		startMs = Clock.millis();
		if (swapBack) ChestSwapper.swap();
	}

	void finish() {
		finish(false);
	}

	/**
	 * Stops swapping, with the elytra back on and gliding again if the server stopped the glide. Never by dropping out of
	 * the glide in mid-air, which would land you with every block fallen since the server last glided: in the air it
	 * waits (unless {@code now}) for the server's stop for the last swap, which may still be on its way, and starts the
	 * glide again once it's in; it lets go without a start if none comes within a round trip (the elytra was back on in
	 * time, so the server never stopped).
	 */
	void finish(boolean now) {
		if (!swapping) return;
		LocalPlayer p = mc.player;
		boolean inAir = p.isFallFlying() && !p.onGround() && !p.isInWater();
		boolean cleared = GlideHolder.cleared(this);
		if (inAir && !now && (!cleared || sinceStart < 2)) {
			if (!finishing) {
				finishing = true;
				finishWait = 0;
				ChestSwapper.restoreElytra();
			}
			if (cleared || ++finishWait <= roundTripTicks() + 4) return;
		}
		swapping = finishing = equipping = recovering = confirming = false;
		armTicks = 0;
		if (inAir && cleared) {
			restart(false);
		} else {
			GlideHolder.release(this);
			ChestSwapper.restoreElytra();
			// The server had stopped the glide: so does the client, where you are.
			if (cleared && p.isFallFlying()) p.stopFallFlying();
		}
		GlideHolder.disarm(this);
		rocketWanted = stopForUse = false;
	}

	/** Ticks for a packet to reach the server and its answer to come back, with a little slack. */
	private static int roundTripTicks() {
		return Mth.clamp(Myriad.server().ping() / 50 + 2, 2, 12);
	}

	/** While swapping: jump only as the press that starts the glide again (released the tick before). Same for a catch. */
	@Subscribe(priority = Priority.LOWEST)
	private void onInput(InputEvent e) {
		if (catcher.catching()) e.jump = catcher.press();
		else if (swapping || startNow) e.jump = startNow;
	}

	/**
	 * A rocket used meanwhile waits for the next start: the server only attaches rockets while it sees you gliding.
	 * Something you hold to use (food, a potion, a bow) waits for the swapping to stop: Grim stops whatever you're using
	 * each time you use another item (each swap) or change slot, so you'd never finish eating. It's used once the elytra
	 * is back on for good, at the next start (Auto Eat tries again, and holding right click uses it again).
	 */
	@Subscribe
	private void onUse(InteractEvent.Item e) {
		if (!swapping || firing) return;
		ItemStack stack = mc.player.getItemInHand(e.hand());
		if (stack.is(Items.FIREWORK_ROCKET)) {
			e.cancel();
			rocketWanted = true;
			rocketHand = e.hand();
			rocketSlot = e.hand() == InteractionHand.MAIN_HAND ? Myriad.inventory().serverSlot() : -1;
		} else if (stack.getUseDuration(mc.player) > 0) {
			e.cancel();
			stopForUse = true;
		}
	}

	/** Uses the waiting rocket, from the hand or hotbar slot it was used from (or any rockets in the hotbar). */
	private void fireRocket() {
		rocketWanted = false;
		var inv = mc.player.getInventory();
		int slot = rocketSlot;
		if (rocketHand == InteractionHand.MAIN_HAND && (slot < 0 || !inv.getItem(slot).is(Items.FIREWORK_ROCKET))) slot = Myriad.inventory().findInHotbar(s -> s.is(Items.FIREWORK_ROCKET));
		boolean offHand = rocketHand == InteractionHand.OFF_HAND && mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET);
		if (!offHand && slot < 0) return;
		firing = true;
		try {
			if (offHand) mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
			else Myriad.inventory().silentSwap(slot, () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
		} finally {
			firing = false;
		}
	}

	/**
	 * Whether the chestplate (or, with it on, the elytra) is at hand to swap with, bringing it in from the inventory if
	 * not (see ChestSwapper.ensurePair); says once why it isn't when it can't be.
	 */
	@Override
	public boolean chestplateAtHand(boolean mayStop) {
		NoDurabilityEngine m = this;
		switch (ChestSwapper.ensurePair(mayStop)) {
			case READY -> {
				m.pairWarned = false;
				return true;
			}
			case MISSING -> m.pairWarning(ChestSwapper.elytraWorn() ? "No Durability needs a chestplate in your inventory." : "No Durability can't find the elytra to put back on.");
			case NO_ROOM -> m.pairWarning("No Durability needs a free hotbar slot (or one with a spare elytra) for the chestplate.");
			case WAITING -> {
			}
		}
		return false;
	}

	private void pairWarning(String message) {
		if (!pairWarned) fly.warn(message);
		pairWarned = true;
	}

	private void warnOnce(String message) {
		if (!warned) fly.warn(message);
		warned = true;
	}

	/**
	 * Keeps {@link #descent} as the server counts it: every block down adds, rising clears it, as does landing, and so
	 * does gliding (not swapping) long enough at a gentle slope.
	 */
	private void trackDescent(LocalPlayer p) {
		double dy = p.getY() - p.yo;
		if (dy > 0 || p.onGround() || p.isInWater()) descent = 0;
		else descent -= dy;
		glidingTicks = swapping ? 0 : glidingTicks + 1;
		if (!swapping && glidingTicks >= FORGET_TICKS && p.getDeltaMovement().y > -0.5) descent = 0;
	}

	/**
	 * Whether there's room enough that the server won't see you land, or hit something, while it isn't gliding you:
	 * a few blocks below plus how far you fall (or rise) in a round trip, and nothing along the way you're flying for
	 * that long (rising ground, a wall, a tree). Swapping stops when there isn't, in time for the elytra to be back on
	 * and the server gliding first.
	 */
	private boolean room(boolean arming) {
		LocalPlayer p = mc.player;
		Vec3 v = p.getDeltaMovement();
		int roundTrip = roundTripTicks();
		// Once the server counts a fall that would hurt, it needs time to forget it before you touch anything: for the
		// last swap's stop to come in, the glide to start again, and the server to glide a while.
		double ticks = roundTrip + 4 + (arming ? 2 : 0) + (descent > SAFE_DESCENT ? roundTrip + FORGET_TICKS : 0);
		double need = 6 + (v.y < 0 ? -v.y * ticks : v.y * (roundTrip / 2.0 + 2)) + (arming ? 2 : 0);
		AABB box = p.getBoundingBox();
		// Chunks you'd reach that haven't come in: the client stops you there until they do, which shouldn't happen
		// while the server isn't gliding you.
		for (double t = 0; t <= ticks; t += 4) {
			if (!mc.level.hasChunk(Mth.floor(p.getX() + v.x * t) >> 4, Mth.floor(p.getZ() + v.z * t) >> 4)) return false;
		}
		if (!mc.level.noBlockCollision(p, box.expandTowards(0, -need, 0))) return false;
		// Along the way: the whole hitbox swept to where you'll be, and a couple of blocks under that path.
		return mc.level.noBlockCollision(p, box.expandTowards(v.x * ticks, Math.min(0, v.y * ticks) - 2, v.z * ticks));
	}
}
