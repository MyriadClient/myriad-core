package dev.myriadclient.harness;

import com.mojang.authlib.GameProfile;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.api.util.RateCounter;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.util.Clock;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.stats.StatsCounter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A Minecraft client just real enough to drive core's services the way the game does, with no window and no server:
 * a real {@link LocalPlayer} (vanilla's own tick, input and physics) in a {@link FakeLevel}, core's own services wired as at startup (no addons, config or UI),
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
	/** Every registry a client has once it joins: the built-in ones, and vanilla's data-driven ones as a server sends them. */
	static RegistryAccess.Frozen registries;

	public final Minecraft mc;
	public final FakeLevel level;
	public final LocalPlayer player;
	public final MyriadImpl myriad;
	private final EmbeddedChannel channel = new EmbeddedChannel();
	private final List<Sent> sent = new ArrayList<>();
	private final List<Runnable> nextTick = new ArrayList<>();
	private final List<Runnable> everyTick = new ArrayList<>();
	private int tick;
	/** The services' clock (see {@link Clock}): starts well past zero, and moves {@link #TICK_MS} a tick. */
	private long millis = 1_000_000;
	public static final long TICK_MS = 50;

	private Harness() {
		boot();
		mc = Fields.allocate(Minecraft.class);
		Fields.set(Minecraft.class, "instance", mc);
		level = FakeLevel.create(lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD),
			registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
		ClientPacketListener listener = Fields.allocate(ClientPacketListener.class);
		Fields.set(listener, "localGameProfile", new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Tester"));
		Fields.set(listener, "playerInfoMap", new java.util.HashMap<>());
		Fields.set(listener, "registryAccess", registries);
		Fields.set(listener, "clientLoaded", true);
		Fields.set(listener, "enabledFeatures", net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
		Connection connection = new Connection(PacketFlow.CLIENTBOUND);
		Fields.set(connection, "channel", channel);
		Fields.set(listener, "connection", connection);
		// The game's own defaults (the test's working folder has no options.txt).
		Fields.set(mc, "options", new Options(mc, new File(".")));
		Fields.set(mc, "soundManager", Fields.allocate(net.minecraft.client.sounds.SoundManager.class));
		Fields.set(level, "connection", listener);
		Fields.set(level, "registryAccess", registries);
		Fields.set(level, "damageSources", new DamageSources(registries));
		Fields.set(level, "palettedContainerFactory", PalettedContainerFactory.create(registries));
		Fields.set(level, "minecraft", mc);
		player = new LocalPlayer(mc, level, listener, new StatsCounter(), null, Input.EMPTY, false, null);
		player.input = new KeyboardInput(mc.options);
		// No sound engine, so no ambient sounds (underwater, biome, bubble columns).
		Fields.set(player, "ambientSoundHandlers", new ArrayList<>());
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
		Fields.set(mc, "gui", FakeGui.create());
		// The game's tutorial, at its "done" step (options.txt is absent, so it's at its default).
		Fields.set(mc, "tutorial", new net.minecraft.client.tutorial.Tutorial(mc, mc.options));
		Clock.use(() -> millis);
		Fields.set(RateCounter.class, "millis", (LongSupplier) () -> millis);
		myriad = MyriadImpl.forHarness();
	}

	/** A fresh client and Myriad; close it after. */
	public static Harness start() {
		return new Harness();
	}

	/** The game's registries, once per JVM, frozen, with tags and item components bound as a server's resources would bind them. */
	private static synchronized void boot() {
		if (booted) return;
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Fabric leaves the built-in registries open for mods until they've initialised; there are none here.
		Fields.callStatic(BuiltInRegistries.class, "freeze");
		// Tags (which tools mine what, which blocks are climbable) from the game's own data, as a server loads them.
		try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()))) {
			TagLoader.loadTagsForExistingRegistries(resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
				.forEach(Registry.PendingTags::apply);
		}
		lookup = VanillaRegistries.createLookup();
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(p -> p.apply());
		Map<ResourceKey<? extends Registry<?>>, Registry<?>> all = new HashMap<>();
		BuiltInRegistries.REGISTRY.forEach(r -> all.put(r.key(), r));
		lookup.listRegistries().forEach(r -> all.put(r.key(), frozenCopy(r)));
		registries = new RegistryAccess.ImmutableRegistryAccess(all).freeze();
		booted = true;
	}

	/** Stands the player at {@code x y z} (on whatever is below), looking along {@code yaw}/{@code pitch}, settled. */
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
		// Ticks of the player's own until it has settled (gravity comes after the move, so landing shows a tick late, and
		// vanilla reports it): what they sent isn't the test's.
		for (int i = 0; i < 5; i++) {
			int before = channel.outboundMessages().size();
			player.tick();
			if (channel.outboundMessages().size() == before) break;
		}
		collect();
		sent.removeIf(s -> s.tick() == tick);
		return this;
	}

	/** Holds the movement keys named (forward, back, left, right, jump, sneak, sprint) down, the others up. */
	public Harness keys(String... names) {
		var o = mc.options;
		Map<String, KeyMapping> all = Map.of("forward", o.keyUp, "back", o.keyDown, "left", o.keyLeft, "right", o.keyRight,
			"jump", o.keyJump, "sneak", o.keyShift, "sprint", o.keySprint);
		for (KeyMapping k : all.values()) k.setDown(false);
		for (String n : names) {
			KeyMapping k = all.get(n);
			if (k == null) throw new IllegalArgumentException("No key " + n + "; one of " + all.keySet());
			k.setDown(true);
		}
		return this;
	}

	/** Puts {@code stack} in inventory slot {@code index} (0-8 the hotbar). */
	public Harness give(int index, ItemStack stack) {
		player.getInventory().setItem(index, stack);
		return this;
	}

	/** A frozen registry holding what {@code lookup} lists (damage types, enchantments, ...). */
	@SuppressWarnings("unchecked")
	private static <T> Registry<T> frozenCopy(HolderLookup.RegistryLookup<T> lookup) {
		MappedRegistry<T> registry = new MappedRegistry<>((ResourceKey<? extends Registry<T>>) (ResourceKey<?>) lookup.key(), Lifecycle.stable());
		lookup.listElements().forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
		return registry.freeze();
	}

	/** Runs {@code action} at the start of the next tick, after core's own tick-start work, as a module's tick handler would. */
	public Harness onTick(Runnable action) {
		nextTick.add(action);
		return this;
	}

	/** Runs {@code action} at the start of every tick from now on, as a module's tick handler would. */
	public Harness everyTick(Runnable action) {
		everyTick.add(action);
		return this;
	}

	/**
	 * One game tick, in the game's order: the tick-start event (the services, then anything {@link #onTick} asked for),
	 * the game mode's tick, the player's own tick (its input from {@link #keys}, movement and physics on the fake level,
	 * and the movement packet: none while you stand still and don't turn, as in vanilla), the tick-end packet, the
	 * tick-end event.
	 */
	public Harness tick() {
		tick++;
		millis += TICK_MS;
		Ticks.advance();
		Myriad.events().post(TickEvent.Pre.INSTANCE);
		List<Runnable> actions = new ArrayList<>(everyTick);
		actions.addAll(nextTick);
		nextTick.clear();
		for (Runnable a : actions) a.run();
		mc.gameMode.tick();
		player.tick();
		player.connection.send(ServerboundClientTickEndPacket.INSTANCE);
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

	/**
	 * The server sets the block at {@code pos}, as its block update does: while the client's own guess there is
	 * unanswered it's filed away until the acknowledgement, as vanilla does.
	 */
	public Harness serverBlock(BlockPos pos, BlockState state) {
		receive(new ClientboundBlockUpdatePacket(pos, state));
		level.setServerVerifiedBlockState(pos, state, Block.UPDATE_ALL);
		return this;
	}

	/** The server acknowledges every block action up to {@code sequence}, as vanilla handles it. */
	public Harness ack(int sequence) {
		receive(new ClientboundBlockChangedAckPacket(sequence));
		level.handleBlockChangedAck(sequence);
		return this;
	}

	/**
	 * The server opens a single chest's menu with {@code contents} in its first slots, as vanilla handles its open-screen
	 * and contents packets: the player's open menu becomes it, filled, and core hears both as its mixins tell it.
	 */
	public ChestMenu serverOpensChest(int syncId, List<ItemStack> contents) {
		ChestMenu menu = ChestMenu.threeRows(syncId, player.getInventory());
		receive(new ClientboundOpenScreenPacket(syncId, MenuType.GENERIC_9x3, Component.literal("Chest")));
		player.containerMenu = menu;
		myriad.containerTracker().onOpened(syncId);
		List<ItemStack> all = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			all.add(i < contents.size() ? contents.get(i) : i < 27 ? ItemStack.EMPTY : menu.slots.get(i).getItem().copy());
		}
		int state = menu.incrementStateId();
		receive(new ClientboundContainerSetContentPacket(syncId, state, all, ItemStack.EMPTY));
		menu.initializeContents(state, all, ItemStack.EMPTY);
		myriad.containerTracker().onContents(syncId);
		return menu;
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

	/** The kinds of packet sent in tick {@code t}, in order ("SetCarriedItem", "MovePlayer", ...), leaving out the tick-end packet every tick has. */
	public List<String> kinds(int t) {
		List<String> out = new ArrayList<>();
		for (Sent s : sent()) if (s.tick() == t && !(s.packet() instanceof ServerboundClientTickEndPacket)) out.add(kind(s.packet()));
		return out;
	}

	/** The look (yaw, pitch) each movement packet so far carried, or the one before when it carried none, per tick. */
	public List<float[]> looks() {
		List<float[]> out = new ArrayList<>();
		float yaw = Float.NaN, pitch = Float.NaN;
		for (ServerboundMovePlayerPacket p : sent(ServerboundMovePlayerPacket.class)) {
			yaw = p.getYRot(yaw);
			pitch = p.getXRot(pitch);
			out.add(new float[]{yaw, pitch});
		}
		return out;
	}

	/** Forgets what was sent so far. */
	public Harness clearSent() {
		collect();
		sent.clear();
		return this;
	}

	/** Moves the clock on {@code ms} without a tick (the server taking a while to answer, within a tick). */
	public Harness pass(long ms) {
		millis += ms;
		return this;
	}

	/** The services' clock now. */
	public long millis() {
		return millis;
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
		Clock.use(null);
		Fields.set(RateCounter.class, "millis", (LongSupplier) () -> System.nanoTime() / 1_000_000L);
		Fields.set(Minecraft.class, "instance", null);
		channel.finishAndReleaseAll();
	}
}
