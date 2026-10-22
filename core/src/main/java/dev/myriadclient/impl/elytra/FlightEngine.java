package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.elytra.Flight;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What {@link AltitudeFlight} and {@link ControlFlight} share: the pilot that flies by its own rotation (the camera stays
 * the player's), opening the elytra the vanilla way, No Durability and the catch (the engine runs for this flight, with
 * the owner's policy and messages), Preserve, a spare before the worn elytra can't last the way down, riding out
 * setbacks, and landing.
 */
abstract class FlightEngine implements Flight, Elytra.Pilot, NoDurability.Owner {
	protected static final Minecraft mc = Minecraft.getInstance();

	/**
	 * Setbacks: this many within {@code STEADY_WINDOW} ticks and it glides steadily for {@code STEADY_TICKS}; this many
	 * within {@code STUCK_WINDOW} and it says it's stuck (the owner decides whether to land). Grim sends a setback again
	 * every couple of ticks until the client takes it: ones within {@code SAME_SETBACK} ticks of the last counted are that one.
	 */
	private static final int STEADY_SETBACKS = 3, STEADY_WINDOW = 60, STEADY_TICKS = 60, SAME_SETBACK = 10, STUCK_SETBACKS = 10, STUCK_WINDOW = 600;
	/**
	 * Elytra uses the way down takes: the server wears a point every second of gliding, and landing descends about this
	 * many blocks a second; with a margin.
	 */
	private static final double LAND_DESCENT = 3, LAND_MARGIN = 30;

	private final NoDurability.Owner owner;
	protected final NoDurability noDur;
	private final List<Consumer<@Nullable Why>> landedListeners = new ArrayList<>(1);

	protected float heading = Float.NaN;
	protected double speed = 20, height = Double.NaN, climb = Double.NaN;
	private double preserve;

	private boolean running;
	/** Flying with {@link #spoof}'s rotation this tick. */
	protected boolean spoofing;
	/** Open the elytra this tick, and jump this tick (a take-off); whether jump was pressed last tick. */
	protected boolean wantOpen, takeOffJump;
	private boolean jumpedLastTick, preserveWarned;
	/** The server set you back since the last tick. */
	private volatile boolean flagged;
	/** Coming down to land, and why (null: asked to); down after a landing, until the next glide. */
	protected boolean landing;
	private boolean down;
	private @Nullable Why landWhy;
	/** Tick counts of recent setbacks (one per run Grim resends), and ticks left gliding steadily after a run of them. */
	private final ArrayDeque<Integer> setbacks = new ArrayDeque<>();
	protected int steadyTicks;

	private State state = State.DOWN;
	private @Nullable Why why;

	FlightEngine(NoDurability.Owner owner) {
		this.owner = owner;
		this.noDur = Myriad.elytra().noDurability(this);
	}

	// ---- Flight ---------------------------------------------------------------------------------------------------

	@Override
	public Flight heading(float yaw) {
		heading = yaw;
		return this;
	}

	@Override
	public Flight speed(double blocksPerSecond) {
		speed = Math.max(0, blocksPerSecond);
		return this;
	}

	@Override
	public Flight height(double y) {
		height = y;
		return this;
	}

	@Override
	public Flight climb(double blocksPerSecond) {
		climb = blocksPerSecond;
		return this;
	}

	@Override
	public Flight preserve(double percent) {
		preserve = percent;
		return this;
	}

	@Override
	public Flight onLanded(Consumer<@Nullable Why> landed) {
		landedListeners.add(landed);
		return this;
	}

	@Override
	public void start() {
		if (running) stop();
		running = true;
		reset();
		Myriad.elytra().addPilot(this);
		noDur.enable();
		Myriad.inventory().spare(this, this::wornDown);
		Myriad.events().subscribe(this);
	}

	@Override
	public void stop() {
		if (!running) return;
		running = false;
		Myriad.events().unsubscribe(this);
		noDur.disable();
		Myriad.elytra().removePilot(this);
		Myriad.inventory().spare(this, null);
		reset();
	}

	@Override
	public void land() {
		comeDown(null);
	}

	@Override
	public State state() {
		return state;
	}

	@Override
	public @Nullable Why why() {
		return why;
	}

	private void reset() {
		spoofing = wantOpen = takeOffJump = flagged = landing = down = false;
		landWhy = null;
		setbacks.clear();
		steadyTicks = 0;
		state = State.DOWN;
		why = null;
		resetFlight();
	}

	// ---- for the engine -------------------------------------------------------------------------------------------

	@Override
	public boolean noDurability() {
		return owner.noDurability();
	}

	@Override
	public boolean equipBeforeStart() {
		return owner.equipBeforeStart();
	}

	@Override
	public void info(String message) {
		owner.info(message);
	}

	@Override
	public void warn(String message) {
		owner.warn(message);
	}

	/** Flying with its own rotation: the glide, walking and the take-off jump follow it. */
	@Override
	public boolean steers() {
		return spoofing;
	}

	// ---- the type's ----------------------------------------------------------------------------------------------

	/** Back to the start of a flight: on start and stop. */
	protected abstract void resetFlight();

	/** This tick's flight while the elytra is open (and not landing gently is the type's to do, too). */
	protected abstract State glide();

	/** Not gliding (and able to): open the elytra or take off, or not; what that makes the state. */
	protected abstract State notGliding();

	/** A setback came in. */
	protected void setBack() {
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	/** Before the tick's actions (No Durability's restart sends held ping answers, which must go first). */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 10)
	private void onTick(TickEvent.Pre e) {
		wantOpen = takeOffJump = false;
		if (mc.player == null || mc.level == null) {
			noDur.tick();
			return;
		}
		if (Float.isNaN(heading)) heading = mc.player.getYRot();
		fly();
		freshElytra();
		noDur.tick();
	}

	private void fly() {
		var p = mc.player;
		if (flagged) {
			flagged = false;
			countSetback(p.tickCount);
			setBack();
		}
		if (landing && (p.onGround() || p.isInWater())) landed();
		if (down && p.onGround() && !p.isFallFlying()) {
			spoofing = false;
			set(State.DOWN, why);
			return;
		}
		if (!canGlide()) {
			spoofing = false;
			stuck(ItemInfo.isGlider(p.getItemBySlot(EquipmentSlot.CHEST)) || chestSwaps() ? Why.of(Why.BLOCKED, "can't glide here")
				: Why.of(Why.NO_ELYTRA, "no elytra to fly with"));
			return;
		}
		if (!p.isFallFlying()) {
			spoofing = false;
			State s = notGliding();
			if (s != State.STUCK) set(s, null);
			return;
		}
		down = false;
		if (!landing && wearingOut()) {
			int spare = spareElytra();
			if (spare < 0) comeDown(Why.of(Why.NO_ELYTRA, "last elytra nearly worn out"));
			else if (Myriad.inventory().prepareClick()) Myriad.inventory().move(spare, ItemInfo.inventoryIndex(EquipmentSlot.CHEST));
		}
		State s = glide();
		if (landing) set(State.LANDING, landWhy);
		else if (setbacks.size() >= STUCK_SETBACKS) set(State.STUCK, Why.of(Why.SERVER_REFUSED, "set back " + setbacks.size() + " times in 30 s"));
		else if (s != State.STUCK) set(s, null);
	}

	private void set(State s, @Nullable Why w) {
		state = s;
		why = w;
	}

	/** Stuck for {@code w} this tick. */
	protected void stuck(Why w) {
		set(State.STUCK, w);
	}

	@Subscribe
	private void onInput(InputEvent e) {
		if (takeOffJump) e.jump = true;
		// Vanilla opens the elytra when jump goes from released to pressed in the air: release it a tick, then press it.
		// Grim checks exactly that (a start with jump released the tick before), and sets back a start sent on its own.
		if (wantOpen) e.jump = !jumpedLastTick;
		jumpedLastTick = e.jump;
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onSetBack(PacketEvent.Receive e) {
		flagged = true;
	}

	/** A run of setbacks steadies the flight for a while. */
	private void countSetback(int now) {
		if (!setbacks.isEmpty() && now - setbacks.peekLast() < SAME_SETBACK) return;
		setbacks.addLast(now);
		while (!setbacks.isEmpty() && now - setbacks.peekFirst() > STUCK_WINDOW) setbacks.removeFirst();
		int recent = 0;
		for (int t : setbacks) if (now - t <= STEADY_WINDOW) recent++;
		if (recent >= STEADY_SETBACKS) steadyTicks = STEADY_TICKS;
	}

	// ---- landing --------------------------------------------------------------------------------------------------

	/** Comes down to land; null: asked to. Said to the owner when it's the flight's own decision. */
	private void comeDown(@Nullable Why w) {
		if (landing) return;
		landing = true;
		landWhy = w;
		if (w != null) warn("Coming down to land: " + w.detail());
	}

	private void landed() {
		landing = false;
		down = true;
		Why w = landWhy;
		landWhy = null;
		set(State.DOWN, w);
		for (Consumer<@Nullable Why> l : List.copyOf(landedListeners)) l.accept(w);
	}

	// ---- shared ---------------------------------------------------------------------------------------------------

	/** Flies (and tells the server) with this rotation, leaving the camera alone. */
	protected void spoof(float yaw, float pitch) {
		spoofing = true;
		Myriad.rotations().request(owner, Mth.wrapDegrees(yaw), pitch, Rotations.PRIORITY_HIGH + 50);
	}

	/** Whether No Durability swaps a chestplate on for its breaks (where Grim simulates the glide); elsewhere it breaks the glide with two starts. */
	protected boolean chestSwaps() {
		return noDurability() && AntiCheat.inEffect().simulatesGlide();
	}

	/** Whether the player could glide right now: an elytra on (or swappable on), and nothing vanilla forbids it for. */
	private boolean canGlide() {
		var p = mc.player;
		if (p.getAbilities().flying || p.isPassenger() || p.isInWater() || p.onClimbable() || p.hasEffect(MobEffects.LEVITATION)) return false;
		// Not with an elytra one hit from breaking: the server refuses a glide start with it.
		return ItemInfo.canGlide(p.getItemBySlot(EquipmentSlot.CHEST)) || (chestSwaps() && Myriad.elytra().chestSwap().canGlide());
	}

	/** An elytra worn down to Preserve: put aside, to be mended. */
	private boolean wornDown(ItemStack s) {
		return ItemInfo.isGlider(s) && s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < preserve;
	}

	/**
	 * Preserve: an elytra worn down to it comes off for the freshest other one, in mid-air too (one elytra for another
	 * keeps the glide), but not while No Durability is swapping the chest slot: its swaps pick a fresh one themselves.
	 * Said once, on the ground, when there's none to swap in.
	 */
	private void freshElytra() {
		var p = mc.player;
		if (!wornDown(p.getItemBySlot(EquipmentSlot.CHEST))) {
			preserveWarned = false;
			return;
		}
		if (noDur.swapping() || p.isCreative()) return;
		int with = Myriad.inventory().bestInInventory(s -> ItemInfo.isGlider(s) && !ItemInfo.isBound(s) ? 1 + ItemInfo.durabilityFraction(s) : 0);
		if (with < 0) {
			if (!preserveWarned && p.onGround()) {
				warn("Elytra worn down to Preserve, and no other to fly with.");
				preserveWarned = true;
			}
			return;
		}
		if (Myriad.inventory().prepareClick()) Myriad.inventory().move(with, ItemInfo.inventoryIndex(EquipmentSlot.CHEST));
	}

	/** Whether the worn elytra has fewer uses left than gliding down from here takes. */
	private boolean wearingOut() {
		ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
		return ItemInfo.isGlider(chest) && chest.isDamageableItem() && ItemInfo.durability(chest) < landingUses();
	}

	/** The inventory index of the freshest other elytra with more uses left than landing from here takes, or -1. */
	private int spareElytra() {
		double need = landingUses();
		return Myriad.inventory().bestInInventory(s -> ItemInfo.isGlider(s) && !ItemInfo.isBound(s) && (!s.isDamageableItem() || ItemInfo.durability(s) > need)
			? 1 + ItemInfo.durabilityFraction(s) : 0);
	}

	private double landingUses() {
		return aboveGround() / LAND_DESCENT + LAND_MARGIN;
	}

	protected double aboveGround() {
		var p = mc.player;
		return p.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
	}

	/** Whether every chunk within {@code distance} blocks along {@code dir} has loaded. */
	protected boolean loadedAhead(Vec3 dir, double distance) {
		var p = mc.player;
		for (double d = 0; d <= distance; d += 8) {
			if (!mc.level.hasChunk(Mth.floor(p.getX() + dir.x * d) >> 4, Mth.floor(p.getZ() + dir.z * d) >> 4)) return false;
		}
		return true;
	}

	/**
	 * The highest ground (any block that stops movement, trees and water included) within {@code distance} blocks
	 * along {@code dir}, and a few blocks to each side; only chunks that have loaded count.
	 */
	protected double groundAhead(Vec3 dir, double distance) {
		var p = mc.player;
		double best = Double.NEGATIVE_INFINITY;
		for (double d = 0; d <= distance; d += 4) {
			for (int side = -4; side <= 4; side += 4) {
				int x = Mth.floor(p.getX() + dir.x * d - dir.z * side), z = Mth.floor(p.getZ() + dir.z * d + dir.x * side);
				if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
				best = Math.max(best, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
			}
		}
		return best;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + " for " + owner;
	}
}
