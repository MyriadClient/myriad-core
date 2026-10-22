package dev.myriadclient.harness;

import com.mojang.authlib.GameProfile;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Minecraft client just real enough to drive core's services the way the game does, with no window and no server:
 * a real {@link LocalPlayer} in a {@link FakeLevel}, core's own services wired as at startup (no addons, config or UI),
 * and a connection whose packets go through the real send path (Myriad's mixins included) into a list, each with the
 * tick it went out in. {@link #tick()} runs a game tick in the game's order: tick start, the movement packet, tick end.
 * Incoming packets reach the services as they do in game, as {@code PacketEvent.Receive}; what a packet would change
 * on the client (a slot, a block) the test sets itself.
 *
 * <pre>{@code
 * try (Harness h = Harness.start()) {
 *     h.onTick(() -> Myriad.inventory().select(3));
 *     h.tick();
 *     assertEquals(List.of(SetCarriedItem, MovePlayer), h.kinds());
 * }
 * }</pre>
 * <p>
 * Runs under Fabric's JUnit loader (Knot), so mixins apply. One at a time: Minecraft and Myriad are singletons.
 */
public final class Harness implements AutoCloseable {
	/** A packet sent, with the harness tick it went out in (1 for the first {@link #tick()}). */
	public record Sent(int tick, Packet<?> packet) {
	}

	private static boolean booted;
	/** Vanilla's data-driven registries (dimension types, enchantments, ...), as the built-in data defines them. */
	static HolderLookup.Provider lookup;

	public final Minecraft mc;
	public final FakeLevel level;
	public final LocalPlayer player;
	public final MyriadImpl myriad;
	private final EmbeddedChannel channel = new EmbeddedChannel();
	private final List<Sent> sent = new ArrayList<>();
	private final List<Runnable> nextTick = new ArrayList<>();
	private int tick;

	private Harness() {
		boot();
		mc = Fields.allocate(Minecraft.class);
		Fields.set(Minecraft.class, "instance", mc);
		level = FakeLevel.create(lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD));
		ClientPacketListener listener = Fields.allocate(ClientPacketListener.class);
		Fields.set(listener, "localGameProfile", new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Tester"));
		Fields.set(listener, "playerInfoMap", new java.util.HashMap<>());
		Fields.set(listener, "enabledFeatures", net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
		Connection connection = new Connection(PacketFlow.CLIENTBOUND);
		Fields.set(connection, "channel", channel);
		Fields.set(listener, "connection", connection);
		// The game's own defaults (the test's working folder has no options.txt).
		Fields.set(mc, "options", new Options(mc, new File(".")));
		Fields.set(mc, "soundManager", Fields.allocate(net.minecraft.client.sounds.SoundManager.class));
		Fields.set(level, "connection", listener);
		player = new LocalPlayer(mc, level, listener, new StatsCounter(), null, Input.EMPTY, false, null);
		level.add(player);
		Fields.set(mc, "player", player);
		Fields.set(mc, "level", level);
		Fields.set(mc, "gameMode", new MultiPlayerGameMode(mc, listener));
		// The camera follows the player, as it does unless something (Freecam) takes it.
		Camera camera = Fields.allocate(Camera.class);
		Fields.set(camera, "entity", player);
		GameRenderer renderer = Fields.allocate(GameRenderer.class);
		Fields.set(renderer, "mainCamera", camera);
		Fields.set(mc, "gameRenderer", renderer);
		myriad = MyriadImpl.forHarness();
	}

	/** A fresh client and Myriad; close it after. */
	public static Harness start() {
		return new Harness();
	}

	/** The game's registries, once per JVM, with item components bound as a server's resources would bind them. */
	private static synchronized void boot() {
		if (booted) return;
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		lookup = VanillaRegistries.createLookup();
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(p -> p.apply());
		booted = true;
	}

	/** Stands the player at {@code x y z}, on the ground, looking along {@code yaw}/{@code pitch}. */
	public Harness standAt(double x, double y, double z, float yaw, float pitch) {
		player.setPos(x, y, z);
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.setOnGround(true);
		// What the server last had: the movement packet compares against these.
		Fields.set(player, "xLast", x);
		Fields.set(player, "yLast", y);
		Fields.set(player, "lastOnGround", true);
		Fields.set(player, "zLast", z);
		Fields.set(player, "yRotLast", yaw);
		Fields.set(player, "xRotLast", pitch);
		return this;
	}

	/** Puts {@code stack} in inventory slot {@code index} (0-8 the hotbar). */
	public Harness give(int index, ItemStack stack) {
		player.getInventory().setItem(index, stack);
		return this;
	}

	/** Runs {@code action} at the start of the next tick, after core's own tick-start work, as a module's tick handler would. */
	public Harness onTick(Runnable action) {
		nextTick.add(action);
		return this;
	}

	/** One game tick: the tick-start event (the services and anything {@link #onTick} asked for), the movement packet, the tick-end event. */
	public Harness tick() {
		tick++;
		Ticks.advance();
		Myriad.events().post(TickEvent.Pre.INSTANCE);
		List<Runnable> actions = List.copyOf(nextTick);
		nextTick.clear();
		for (Runnable a : actions) a.run();
		Fields.call(player, "sendPosition");
		Myriad.events().post(TickEvent.Post.INSTANCE);
		collect();
		return this;
	}

	public Harness ticks(int n) {
		for (int i = 0; i < n; i++) tick();
		return this;
	}

	/** Delivers {@code packet} from the server, as the connection does: through {@code PacketEvent.Receive}. */
	public Harness receive(Packet<?> packet) {
		Myriad.events().post(new PacketEvent.Receive(packet));
		return this;
	}

	/** Every packet sent so far, in order. */
	public List<Sent> sent() {
		collect();
		return List.copyOf(sent);
	}

	/** The packets of {@code type} sent so far, in order. */
	public <P> List<P> sent(Class<P> type) {
		List<P> out = new ArrayList<>();
		for (Sent s : sent()) if (type.isInstance(s.packet())) out.add(type.cast(s.packet()));
		return out;
	}

	/** The kinds of packet sent in tick {@code t}, in order ("SetCarriedItem", "MovePlayer", ...). */
	public List<String> kinds(int t) {
		List<String> out = new ArrayList<>();
		for (Sent s : sent()) if (s.tick() == t) out.add(kind(s.packet()));
		return out;
	}

	/** Forgets what was sent so far. */
	public Harness clearSent() {
		collect();
		sent.clear();
		return this;
	}

	public int currentTick() {
		return tick;
	}

	/** A packet's short kind: its class name without "Serverbound" and "Packet" (and the movement packets' variants as one). */
	public static String kind(Packet<?> p) {
		String n = p.getClass().getSimpleName();
		if (n.startsWith("Pos") || n.startsWith("Rot") || n.startsWith("StatusOnly")) n = "MovePlayer";
		if (p.getClass().getEnclosingClass() != null && p.getClass().getEnclosingClass().getSimpleName().equals("ServerboundMovePlayerPacket")) n = "MovePlayer";
		return n.replace("Serverbound", "").replace("Packet", "");
	}

	private void collect() {
		Object out;
		while ((out = channel.readOutbound()) != null) {
			if (out instanceof Packet<?> p) sent.add(new Sent(tick, p));
		}
	}

	@Override
	public void close() {
		MyriadImpl.uninstall();
		Fields.set(Minecraft.class, "instance", null);
		channel.finishAndReleaseAll();
	}
}
