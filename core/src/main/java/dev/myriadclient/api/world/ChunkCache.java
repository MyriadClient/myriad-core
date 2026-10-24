package dev.myriadclient.api.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.BlockUpdateEvent;
import dev.myriadclient.api.event.events.ChunkEvent;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.MeshBuilder;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.render.RenderLook;
import dev.myriadclient.api.render.WorldMesh;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.api.util.TickBudget;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Something worked out once per chunk and kept until that chunk changes: the storage blocks in it, its safe holes, the
 * ores of a search. Instead of rescanning everything around you every few ticks, a chunk is computed when it loads or
 * comes into range, and again only when a block in it changes. With a {@link Builder#mesh mesher} each chunk also keeps a
 * {@link WorldMesh}, so what you found is drawn every frame without a render handler.
 *
 * <pre>{@code
 * private final IntSetting range = sgGeneral.integer("Range").defaultValue(8).range(1, 32).build();
 *
 * private final ChunkCache<List<BlockPos>> debris = ChunkCache.of(this, chunk -> {
 *         List<BlockPos> found = new ArrayList<>();
 *         BlockScan.forEach(chunk, s -> s.is(Blocks.ANCIENT_DEBRIS), (pos, state) -> found.add(pos.immutable()));
 *         return found.isEmpty() ? null : found;          // null: nothing to keep for this chunk
 *     })
 *     .range(range::get)                                  // chunks around the player (default: render distance)
 *     .mesh((found, mesh) -> {                            // optional: draw it, rebuilt only when the chunk changes
 *         for (BlockPos pos : found) mesh.blockShape(pos, fill.argb(), line.argb(), ShapeMode.BOTH, true);
 *     })
 *     .build();
 *
 * public MyModule() {
 *     ...
 *     settings.onAnyChanged(s -> debris.remeshAll());     // colours changed: redraw, no rescan needed
 * }
 * }</pre>
 *
 * Built with {@link #of(Module, Compute)}, the cache runs while its module is enabled and frees everything when it's
 * disabled. Work is spread over ticks within a time budget, nearest chunks first; the theme changing re-meshes
 * automatically. Everything runs on the client thread, so compute functions can read the world freely.
 * <p>
 * Keep the compute function to facts about the world, and turn settings into looks in the mesher: then a colour or
 * shape setting only needs {@link #remeshAll()}, which never touches the world. Call {@link #invalidateAll()} when a
 * setting changes what is found.
 *
 * @param <T> what is kept per chunk
 */
public final class ChunkCache<T> {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/ChunkCache");

	/** Works out the value for a loaded chunk, or null to keep nothing for it. */
	@FunctionalInterface
	public interface Compute<T> {
		@Nullable T compute(LevelChunk chunk);
	}

	/** Draws a chunk's value into its mesh, in world coordinates. Called again after {@link #remeshAll()}. */
	@FunctionalInterface
	public interface Mesher<T> {
		void mesh(T value, MeshBuilder mesh);
	}

	/** A chunk's value as boxes, each in a colour (ARGB), for {@link Builder#boxes}. */
	@FunctionalInterface
	public interface Boxes<T> {
		void boxes(T value, BoxSink out);
	}

	/** Takes a chunk's boxes ({@link Boxes}), in world coordinates. */
	@FunctionalInterface
	public interface BoxSink {
		void box(AABB box, int color);
	}

	private final String name;
	private final Compute<T> compute;
	private final @Nullable Mesher<T> mesher;
	/** Built with {@link Builder#boxes}: the look its meshes are made in, the colours their vertices index, and when the look last changed. */
	private final @Nullable RenderLook look;
	private final IntArrayList palette = new IntArrayList();
	private int lookStamp;
	private final IntSupplier range;
	private final boolean neighbours;
	private final boolean async;
	private final TickBudget budget;

	private final Long2ObjectOpenHashMap<Entry<T>> entries = new Long2ObjectOpenHashMap<>();
	/** Chunks with work pending (compute or remesh). */
	private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
	/** The queue sorted nearest first, kept until the queue or the centre changes. */
	private long @Nullable [] sortedQueue;
	/** Results of chunks computed on the worker pool, applied on the render thread. */
	private final Queue<Object[]> computed = new ConcurrentLinkedQueue<>();
	private int inFlight;
	private final List<Subscription> subscriptions = new ArrayList<>();
	private boolean running;
	private @Nullable ClientLevel level;
	private int centerX, centerZ, lastRange = -1;
	private int paletteStamp;
	private int valueCount;
	private boolean loggedFailure;
	private boolean meshesVisible = true;

	private static final class Entry<T> {
		@Nullable T value;
		@Nullable WorldMesh mesh;
		boolean compute = true, remesh;
		/** Async: a computation is running for this chunk; a change meanwhile sets {@link #compute} again. */
		boolean computing;
	}

	private ChunkCache(Builder<T> b) {
		this.name = b.name;
		this.compute = b.compute;
		this.look = b.look;
		this.mesher = b.boxes != null ? boxMesher(b.boxes) : b.mesher;
		if (look != null) meshesVisible = RenderMode.current() == RenderMode.BOX;
		this.range = b.range;
		this.neighbours = b.neighbours;
		this.async = b.async;
		this.budget = TickBudget.of((long) (b.budgetMillis * 1_000_000));
	}

	/** A cache that runs while {@code owner} is enabled. */
	public static <T> Builder<T> of(Module owner, Compute<T> compute) {
		return new Builder<>(Objects.requireNonNull(owner), compute);
	}

	/** A cache you {@link #start()} and {@link #stop()} yourself (from a service, a panel, a HUD element). */
	public static <T> Builder<T> standalone(Compute<T> compute) {
		return new Builder<>(null, compute);
	}

	// ---- reading ---------------------------------------------------------------------------------------------------

	/** The value for a chunk, or null if it has none (yet). */
	public @Nullable T get(int chunkX, int chunkZ) {
		Entry<T> e = entries.get(ChunkPos.pack(chunkX, chunkZ));
		return e == null ? null : e.value;
	}

	/** The value for the chunk holding {@code pos}. */
	public @Nullable T get(BlockPos pos) {
		return get(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** Runs {@code action} on every chunk's value (chunks without one are skipped). */
	public void forEach(Consumer<? super T> action) {
		for (Entry<T> e : entries.values()) if (e.value != null) action.accept(e.value);
	}

	/** How many chunks hold a value. */
	public int size() {
		return valueCount;
	}

	/** Chunks still waiting to be computed or meshed (async: those being computed included). 0 once the cache has caught up. */
	public int pending() {
		return queue.size() + inFlight;
	}

	public boolean isRunning() {
		return running;
	}

	// ---- invalidating ----------------------------------------------------------------------------------------------

	/** Recomputes one chunk on a coming tick. */
	public void invalidate(int chunkX, int chunkZ) {
		markCompute(ChunkPos.pack(chunkX, chunkZ));
	}

	/** Recomputes the chunk holding {@code pos}. */
	public void invalidate(BlockPos pos) {
		invalidate(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** Recomputes every chunk: call it when a setting changes what the compute function finds. */
	public void invalidateAll() {
		for (Long2ObjectMap.Entry<Entry<T>> en : entries.long2ObjectEntrySet()) {
			en.getValue().compute = true;
			queue.add(en.getLongKey());
			sortedQueue = null;
		}
	}

	/**
	 * Whether the chunks' meshes are drawn (they are by default). Turn it off when the meshes only serve as highlight
	 * silhouettes ({@link #highlight}), e.g. in a module's outline mode, and back on for its box mode.
	 */
	public void setMeshesVisible(boolean visible) {
		meshesVisible = visible;
		for (Entry<T> e : entries.values()) if (e.mesh != null) e.mesh.setVisible(visible);
	}

	/**
	 * Highlights every chunk mesh's filled faces in this frame's {@link HighlightEvent.Shapes}: the meshes stay on the GPU,
	 * so this costs nothing per block, however many there are. Each vertex picks its colour from {@code palette} by
	 * index ({@link HighlightEvent.Shapes#paletteColor}); build the meshes with those colours. Meshes off screen are
	 * skipped.
	 *
	 * @deprecated Build the cache with {@link Builder#boxes} and hand it to {@code RenderEvent#chunks}: core meshes and
	 * shows it in the render mode the player picked. Removed in 1.0.
	 */
	@Deprecated(since = "0.11.0", forRemoval = true)
	@SuppressWarnings("removal")
	public void highlight(HighlightEvent.Shapes event, HighlightStyle style, int... palette) {
		for (Entry<T> e : entries.values()) if (e.mesh != null && !e.mesh.isEmpty()) event.mesh(e.mesh, style, palette);
	}

	/**
	 * Meshes of {@link Boxes}: in {@link RenderMode#BOX}, boxes in the look (shown as they are); in
	 * {@link RenderMode#SHADER}, their faces as silhouettes, each vertex's colour an index into {@link #palette} (the
	 * colours as they come), for {@code RenderEvent#chunks} to highlight.
	 */
	@SuppressWarnings("removal")
	private Mesher<T> boxMesher(Boxes<T> boxes) {
		return (value, mesh) -> {
			if (RenderMode.current() == RenderMode.BOX) {
				boxes.boxes(value, (box, color) -> look.box(mesh, box, color, 1));
				return;
			}
			boxes.boxes(value, (box, color) -> mesh.boxFill(box, HighlightEvent.Shapes.paletteColor(paletteIndex(color)), false));
		};
	}

	/** {@code color}'s place in the palette: added if it's new and there's room, else the first colour's. */
	private int paletteIndex(int color) {
		int i = palette.indexOf(color);
		if (i >= 0) return i;
		if (palette.size() >= 256) return 0;
		palette.add(color);
		return palette.size() - 1;
	}

	/** Built with {@link Builder#boxes}: its look, or null. */
	@ApiStatus.Internal
	public @Nullable RenderLook look() {
		return look;
	}

	/** The colours a {@link Builder#boxes} cache's silhouettes index. */
	@ApiStatus.Internal
	public int[] palette() {
		return palette.isEmpty() ? new int[]{0} : palette.toIntArray();
	}

	/** Each chunk's mesh that has something in it. */
	@ApiStatus.Internal
	public void forEachMesh(Consumer<WorldMesh> action) {
		for (Entry<T> e : entries.values()) if (e.mesh != null && !e.mesh.isEmpty()) action.accept(e.mesh);
	}

	/** Rebuilds every chunk's mesh from its kept value, without reading the world: call it when a look changes. */
	public void remeshAll() {
		if (mesher == null) return;
		for (Long2ObjectMap.Entry<Entry<T>> en : entries.long2ObjectEntrySet()) {
			en.getValue().remesh = true;
			queue.add(en.getLongKey());
			sortedQueue = null;
		}
	}

	private void markCompute(long key) {
		Entry<T> e = entries.get(key);
		if (e == null) return;
		e.compute = true;
		queue.add(key);
		sortedQueue = null;
	}

	// ---- lifecycle -------------------------------------------------------------------------------------------------

	/** Starts listening and computing. Caches made with {@link #of} do this when their module turns on. */
	public void start() {
		if (running) return;
		running = true;
		paletteStamp = SettingColor.paletteStamp();
		var bus = Myriad.events();
		subscriptions.add(bus.listen(TickEvent.Post.class, e -> tick()));
		subscriptions.add(bus.listen(ChunkEvent.Loaded.class, this::onLoaded));
		subscriptions.add(bus.listen(ChunkEvent.Unloaded.class, e -> drop(e.pos().pack())));
		subscriptions.add(bus.listen(BlockUpdateEvent.class, this::onBlock));
		subscriptions.add(bus.listen(WorldEvent.class, e -> reset()));
	}

	/** Stops and frees everything (values and meshes). */
	public void stop() {
		if (!running) return;
		running = false;
		for (Subscription s : subscriptions) s.unsubscribe();
		subscriptions.clear();
		reset();
	}

	private void reset() {
		for (Entry<T> e : entries.values()) if (e.mesh != null) e.mesh.close();
		entries.clear();
		queue.clear();
		sortedQueue = null;
		computed.clear();
		inFlight = 0;
		valueCount = 0;
		level = null;
		lastRange = -1;
	}

	// ---- events ----------------------------------------------------------------------------------------------------

	private void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			if (level != null) reset();
			return;
		}
		if (mc.level != level) {
			reset();
			level = mc.level;
		}
		int r = Math.max(0, range.getAsInt());
		int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
		if (cx != centerX || cz != centerZ || r != lastRange) refreshArea(cx, cz, r);
		if (mesher != null) {
			int stamp = SettingColor.paletteStamp();
			if (stamp != paletteStamp) {
				paletteStamp = stamp;
				remeshAll();
			}
		}
		if (look != null) {
			int stamp = look.meshStamp();
			if (stamp != lookStamp) {
				lookStamp = stamp;
				palette.clear();
				setMeshesVisible(RenderMode.current() == RenderMode.BOX);
				remeshAll();
			}
		}
		process();
	}

	private boolean inRange(int x, int z) {
		return lastRange >= 0 && Math.abs(x - centerX) <= lastRange && Math.abs(z - centerZ) <= lastRange;
	}

	/** The player moved to another chunk or the range changed: forget chunks now out of range, pick up new ones. */
	private void refreshArea(int cx, int cz, int r) {
		centerX = cx;
		centerZ = cz;
		lastRange = r;
		for (ObjectIterator<Long2ObjectMap.Entry<Entry<T>>> it = entries.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
			Long2ObjectMap.Entry<Entry<T>> en = it.next();
			long key = en.getLongKey();
			if (inRange(ChunkPos.getX(key), ChunkPos.getZ(key))) continue;
			free(en.getValue());
			queue.remove(key);
			sortedQueue = null;
			it.remove();
		}
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				long key = ChunkPos.pack(x, z);
				if (!entries.containsKey(key) && level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
					entries.put(key, new Entry<>());
					queue.add(key);
					sortedQueue = null;
		sortedQueue = null;
				}
			}
		}
	}

	private void onLoaded(ChunkEvent.Loaded e) {
		if (level == null) return;
		int x = e.pos().x(), z = e.pos().z();
		long key = ChunkPos.pack(x, z);
		if (inRange(x, z)) {
			entries.computeIfAbsent(key, k -> new Entry<>()).compute = true;
			queue.add(key);
		sortedQueue = null;
		}
		if (neighbours) {
			markCompute(ChunkPos.pack(x - 1, z));
			markCompute(ChunkPos.pack(x + 1, z));
			markCompute(ChunkPos.pack(x, z - 1));
			markCompute(ChunkPos.pack(x, z + 1));
		}
	}

	private void onBlock(BlockUpdateEvent e) {
		// Posted just before the change is applied; the chunk is recomputed on a later tick, after it.
		BlockPos pos = e.pos();
		int x = pos.getX() >> 4, z = pos.getZ() >> 4;
		markCompute(ChunkPos.pack(x, z));
		if (!neighbours) return;
		int lx = pos.getX() & 15, lz = pos.getZ() & 15;
		if (lx == 0) markCompute(ChunkPos.pack(x - 1, z));
		else if (lx == 15) markCompute(ChunkPos.pack(x + 1, z));
		if (lz == 0) markCompute(ChunkPos.pack(x, z - 1));
		else if (lz == 15) markCompute(ChunkPos.pack(x, z + 1));
	}

	private void drop(long key) {
		Entry<T> e = entries.remove(key);
		queue.remove(key);
		sortedQueue = null;
		if (e != null) free(e);
	}

	private void free(Entry<T> e) {
		if (e.value != null) valueCount--;
		e.value = null;
		if (e.mesh != null) e.mesh.close();
		e.mesh = null;
	}

	// ---- work ------------------------------------------------------------------------------------------------------

	/** Works through the queue, nearest chunks first, until the tick's time budget runs out. */
	private void process() {
		applyComputed();
		if (queue.isEmpty()) return;
		budget.reset();
		long[] keys = sortedQueue;
		if (keys == null) {
			keys = queue.toLongArray();
			if (keys.length > 1) {
				int cx = centerX, cz = centerZ;
				LongArrays.quickSort(keys, (a, b) -> Integer.compare(distSq(a, cx, cz), distSq(b, cx, cz)));
			}
			sortedQueue = keys;
		}
		for (long key : keys) {
			if (!queue.remove(key)) continue;
			Entry<T> e = entries.get(key);
			if (e != null) work(key, e);
			if (!budget.hasTime()) break;
		}
		// What's left stays sorted; what was done is skipped next time by the queue check.
		if (queue.isEmpty()) sortedQueue = null;
	}

	/** Async: takes the values the workers finished, and meshes them within this tick's budget. */
	private void applyComputed() {
		Object[] done;
		while ((done = computed.poll()) != null) {
			long key = (Long) done[0];
			Entry<T> e = entries.get(key);
			inFlight--;
			if (e == null) continue;
			e.computing = false;
			if (e.compute) {
				// It changed while being computed: again, with the new contents.
				queue.add(key);
				sortedQueue = null;
				continue;
			}
			@SuppressWarnings("unchecked") T value = (T) done[1];
			setValue(e, value);
			if (e.remesh) {
				queue.add(key);
				sortedQueue = null;
			}
		}
	}

	private void setValue(Entry<T> e, @Nullable T value) {
		if ((e.value == null) != (value == null)) valueCount += value == null ? -1 : 1;
		e.value = value;
		e.remesh = mesher != null;
	}

	private static int distSq(long key, int cx, int cz) {
		int dx = ChunkPos.getX(key) - cx, dz = ChunkPos.getZ(key) - cz;
		return dx * dx + dz * dz;
	}

	private void work(long key, Entry<T> e) {
		int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
		if (e.compute) {
			e.compute = false;
			LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
			if (chunk == null) {
				drop(key);
				return;
			}
			if (async) {
				if (e.computing) return;
				e.computing = true;
				inFlight++;
				Async.run(() -> {
					T value;
					try {
						value = compute.compute(chunk);
					} catch (Throwable t) {
						failed("compute", t);
						value = null;
					}
					computed.add(new Object[]{key, value});
				});
				return;
			}
			T value;
			try {
				value = compute.compute(chunk);
			} catch (Throwable t) {
				failed("compute", t);
				value = null;
			}
			setValue(e, value);
		}
		if (e.remesh) {
			e.remesh = false;
			T value = e.value;
			if (value == null) {
				if (e.mesh != null) e.mesh.clear();
				return;
			}
			if (e.mesh == null) {
				e.mesh = WorldMesh.create();
				e.mesh.setVisible(meshesVisible);
			}
			try {
				// Chunk-relative origin: positions stay small, so the mesh is exact anywhere in the world.
				e.mesh.build(x << 4, 0, z << 4, m -> mesher.mesh(value, m));
			} catch (Throwable t) {
				failed("mesh", t);
				e.mesh.clear();
			}
		}
	}

	private void failed(String stage, Throwable t) {
		if (loggedFailure) return;
		loggedFailure = true;
		LOG.error("ChunkCache '{}' failed to {} a chunk (further failures are not logged)", name, stage, t);
	}

	// ---- builder ---------------------------------------------------------------------------------------------------

	public static final class Builder<T> {
		private final @Nullable Module owner;
		private final Compute<T> compute;
		private String name;
		private @Nullable Mesher<T> mesher;
		private @Nullable Boxes<T> boxes;
		private @Nullable RenderLook look;
		private IntSupplier range = () -> Minecraft.getInstance().options.getEffectiveRenderDistance();
		private boolean neighbours;
		private boolean async;
		private double budgetMillis = 2;

		private Builder(@Nullable Module owner, Compute<T> compute) {
			this.owner = owner;
			this.compute = Objects.requireNonNull(compute);
			this.name = owner == null ? "standalone" : owner.name();
		}

		/** Draws each chunk's value into a mesh kept on the GPU. */
		public Builder<T> mesh(Mesher<T> mesher) {
			this.mesher = mesher;
			return this;
		}

		/**
		 * Each chunk's value as boxes in colours, shown in {@code look} in the {@link RenderMode} that's on: hand the cache to
		 * {@code RenderEvent#chunks} each frame. The meshes are made again when the look or the mode changes (and after
		 * {@link #remeshAll()}, for a change of colours); instead of {@link #mesh}.
		 */
		public Builder<T> boxes(Boxes<T> boxes, RenderLook look) {
			this.boxes = Objects.requireNonNull(boxes);
			this.look = Objects.requireNonNull(look);
			return this;
		}

		/** How many chunks around the player to cover (read every tick, so a setting works). Default: render distance. */
		public Builder<T> range(IntSupplier chunks) {
			this.range = chunks;
			return this;
		}

		/**
		 * Recompute a chunk when the chunks beside it load or change at the shared edge too: for computes that look
		 * one block past the chunk (holes, which check the blocks around them).
		 */
		public Builder<T> neighbours() {
			this.neighbours = true;
			return this;
		}

		/** Milliseconds of work per tick (default 2). At least one chunk is processed each tick regardless. */
		/**
		 * Runs the compute function on Myriad's worker pool instead of within the tick budget, for scans that take
		 * long (many block types over many chunks): a chunk's blocks are read while the game may be changing them, so
		 * the function must only read the chunk (never the level or entities) and must cope with a block changing under
		 * it; a chunk that changes meanwhile is computed again. Meshing stays on the render thread.
		 */
		public Builder<T> async() {
			this.async = true;
			return this;
		}

		public Builder<T> budget(double millis) {
			this.budgetMillis = millis;
			return this;
		}

		/** A name for log messages. Defaults to the module's name. */
		public Builder<T> name(String name) {
			this.name = name;
			return this;
		}

		public ChunkCache<T> build() {
			ChunkCache<T> cache = new ChunkCache<>(this);
			if (owner != null) owner.whileEnabled(cache::start, cache::stop);
			return cache;
		}
	}
}
