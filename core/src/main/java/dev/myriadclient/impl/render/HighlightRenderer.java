package dev.myriadclient.impl.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.WorldMesh;
import dev.myriadclient.impl.compat.EntityCullingCompat;
import dev.myriadclient.impl.render.HighlightPasses.Layer;
import dev.myriadclient.impl.render.HighlightPasses.MeshDraw;
import dev.myriadclient.impl.render.HighlightTable.GroupKey;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws shader highlights: outlines, glows and fills around exact silhouettes, for what {@link RenderRouter} hands it in
 * Shader mode ({@code RenderEvent}), ghosts and vanilla's glowing entities; {@link HandHighlights} adds your hand's.
 * <p>
 * Three layers, each with its own silhouette mask, drawn in this order: shapes shown {@code RenderEvent#below() below}
 * the others (so a highlight of the block you're looking at can't hide highlighted chests behind it), shapes,
 * and entities over them all (so a wall of highlighted chests can't hide the highlighted players behind it).
 * <ul>
 * <li>Entities: vanilla's entity outline target, the one the glowing effect uses. Every entity layer (armour, held
 * items, capes) already passes the entity's outline colour along and is drawn into that target with its texture's
 * cut-outs, so a highlighted entity's whole shape costs no extra work: its outline colour is set to a highlight id and
 * merge group ({@link #encode}) and vanilla draws the mask. While Myriad owns the target in a frame, vanilla's own
 * outline post effect is skipped and glowing entities become highlights in a vanilla-like style.</li>
 * <li>Shapes (both layers): a mask of Myriad's own each. Boxes and block shapes are drawn into it per frame; retained meshes
 * (a {@code ChunkCache}'s) stay on the GPU and are drawn with their highlight id applied at draw time,
 * so a module highlighting thousands of blocks uploads nothing per frame.</li>
 * </ul>
 * Highlights that look alike (same style and colour) share a merge group and connect: touching ones get one outline
 * around them all. Shapes that look alike also share one highlight entry per frame, however many there are. Where
 * different groups touch, the highlight added later outlines itself across the boundary.
 * <p>
 * Then, per layer, only over the screen area its highlights cover (scissored):
 * <ol>
 * <li>Resolve (only if some highlight isn't through walls): drop mask pixels hidden behind the world, comparing the
 * mask's depth with the scene's before translucent terrain is drawn (so water doesn't hide what's under it).</li>
 * <li>Spread: per pixel, the nearest mask pixel in its row within the reach (early-out from the centre), and for mask
 * pixels the nearest one of another group within the outline width.</li>
 * <li>Composite, after the world and before the hand: the nearest mask pixel in its column of row results, which is the
 * exact Euclidean distance; outline, glow and fill from that distance and the highlight's data.</li>
 * </ol>
 * Work scales with the reach in pixels, not its square. Per-highlight data (colours, gradient rectangle, style) lives
 * in a texel buffer.
 */
public final class HighlightRenderer {
	public static final HighlightRenderer INSTANCE = new HighlightRenderer();

	static final int MAX_RADIUS = 64;
	/** Highlights per frame: their ids take 16 bits of the mask, the merge group the other 8. */
	static final int MAX_HIGHLIGHTS = 1 << 16;
	/** Layers, by index; shapes and below-shapes are drawn the same way, into masks of their own. */
	static final int ENTITIES = 0, SHAPES = 1, BELOW = 2, HAND = 3;
	static final int[] SHAPE_LAYERS = {BELOW, SHAPES};
	/** Glowing entities while Myriad owns the outline target: close to vanilla's soft outline. */
	private static final HighlightStyle VANILLA_GLOW = HighlightStyle.OUTLINE.withOutlineWidth(1).withGlow(3).withGlowStrength(0.7f);


	private long frame;
	private boolean armed;
	final HighlightTable highlights = new HighlightTable();
	/** Shapes that look alike share one highlight per frame, per layer. */
	@SuppressWarnings("unchecked")
	private final Object2IntOpenHashMap<GroupKey>[] shapeIds = new Object2IntOpenHashMap[]{null, new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>()};
	/** Meshes sharing a style and palette share one run of consecutive ids (one per palette colour), per layer. */
	@SuppressWarnings("unchecked")
	private final Object2IntOpenHashMap<PaletteKey>[] paletteIds = new Object2IntOpenHashMap[]{null, new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>()};

	private record PaletteKey(HighlightStyle style, List<Integer> palette) {
	}

	/** Per shapes layer: geometry relative to the camera (min x/y/z, max x/y/z per box) and each box's encoded id. */
	private final FloatArrayList[] shapeBoxes = {null, new FloatArrayList(), new FloatArrayList()};
	private final IntArrayList[] shapeColors = {null, new IntArrayList(), new IntArrayList()};
	private final ObjectArrayList<MeshDraw> meshes = new ObjectArrayList<>();
	/** Per shapes layer: its mask was cleared for this frame. */
	private final boolean[] masked = new boolean[3];
	/** Entities already asked about this frame (while deciding whether to keep them from being culled). */
	private final Reference2ObjectOpenHashMap<Entity, HighlightStyle> asked = new Reference2ObjectOpenHashMap<>();
	private final Reference2IntOpenHashMap<Entity> askedColor = new Reference2IntOpenHashMap<>();

	// This frame's camera, captured before the level is drawn (its position and matrices go to the table).
	private boolean outlinesShown;
	private Frustum frustum;

	// GPU state.
	final Layer[] layers = {new Layer("entities"), new Layer("shapes"), new Layer("shapes below"), new Layer("hand")};
	/** Per shapes layer: its mask. */
	private final TextureTarget[] masks = new TextureTarget[3];
	private MappableRingBuffer buffer;
	private ByteBufferBuilder shapeMemory;
	/** Per shapes layer: its boxes' vertices. */
	private final GpuBuffer[] shapeVertices = new GpuBuffer[3];

	private HighlightRenderer() {
	}

	/** Resources reloaded: shaders may have changed. */
	public void resourcesReloaded() {
		HighlightPasses.fillsReloaded();
	}

	/** Advances every time the level is extracted; styles built from settings are cached per frame. */
	public long frame() {
		return frame;
	}

	/** Whether anything listens for highlights this frame (checked before doing any per-entity work). */
	public boolean isArmed() {
		return armed;
	}

	/** Start of a frame's extraction, before any entity is. */
	public void beginFrame() {
		frame++;
		GpuTimings.frame();
		highlights.clear();
		for (int l : SHAPE_LAYERS) {
			shapeIds[l].clear();
			paletteIds[l].clear();
			shapeBoxes[l].clear();
			shapeColors[l].clear();
			masked[l] = false;
		}
		meshes.clear();
		HandHighlights.INSTANCE.beginFrame();
		asked.clear();
		askedColor.clear();
		for (Layer l : layers) l.reset();
		if (!Myriad.isReady()) {
			armed = false;
			return;
		}
		RenderRouter.INSTANCE.beginFrame(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
		// A ghost highlighted last frame keeps the pipeline on, as requests do.
		armed = HandHighlights.INSTANCE.listening() || RenderRouter.INSTANCE.anyShader() || frame - ghostFrame <= 2;
	}

	/** The frame a ghost last asked for a highlight in. */
	private long ghostFrame = Long.MIN_VALUE / 2;

	/**
	 * A highlight for an entity drawn again where it isn't ({@link GhostRenderer}): an entity highlight around
	 * {@code box}, its outline colour to give the render state so every layer draws its shape into the mask; 0 if
	 * there's none this frame (the first frame a ghost asks, or too many highlights).
	 */
	public int ghost(HighlightStyle style, int color, AABB box) {
		ghostFrame = frame;
		if (!armed) return 0;
		int id = add(ENTITIES, style, color, box, box.inflate(1));
		return id < 0 ? 0 : encode(id, highlights.groupOf[id]);
	}

	/**
	 * Whether {@code entity} is drawn, given whether the game would draw it ({@code shown}). Occlusion culling (Sodium's
	 * per section, EntityCulling's traced) skips entities behind blocks, which through-walls highlights must still draw,
	 * so an entity on screen that's culled, or any entity on screen while EntityCulling (which culls later) is installed,
	 * is asked about here; the answer is kept for {@link #entity}.
	 */
	public boolean shouldRender(Entity entity, boolean shown, Frustum frustum, double camX, double camY, double camZ) {
		if (!RenderRouter.INSTANCE.anyEntities()) return shown;
		if (!shown) {
			if (!entity.shouldRender(camX, camY, camZ) || !frustum.isVisible(entity.getBoundingBox().inflate(0.5))) return false;
		} else if (!EntityCullingCompat.ACTIVE) {
			return true;
		}
		HighlightEvent.EntityHighlight e = ask(entity);
		asked.put(entity, e.style());
		askedColor.put(entity, e.color());
		if (e.style() == null || !e.style().throughWalls()) return shown;
		EntityCullingCompat.keepVisible(entity);
		return true;
	}

	/** How {@code entity} is highlighted: its {@code RenderEvent} request, then the {@link HighlightEvent#ENTITY_HIGHLIGHT} hook. */
	private HighlightEvent.EntityHighlight ask(Entity entity) {
		HighlightStyle requested = RenderRouter.INSTANCE.entityStyle(entity);
		return hooked(requested == null ? new HighlightEvent.EntityHighlight(entity, null, 0)
			: new HighlightEvent.EntityHighlight(entity, requested, RenderRouter.INSTANCE.entityColor(entity)));
	}

	/** {@code asked} after the {@link HighlightEvent#ENTITY_HIGHLIGHT} hook, if it's hooked: a null style for not at all. */
	public static HighlightEvent.EntityHighlight hooked(HighlightEvent.EntityHighlight asked) {
		if (!HighlightEvent.ENTITY_HIGHLIGHT.hooked()) return asked;
		HighlightEvent.EntityHighlight h = HighlightEvent.ENTITY_HIGHLIGHT.call(null, asked, x -> x);
		return h == null || h.style() == null || (h.color() >>> 24) == 0 ? new HighlightEvent.EntityHighlight(asked.entity(), null, 0) : h;
	}

	/** An entity's render state was just built: ask who highlights it, and point its outline colour at the highlight. */
	public void entity(EntityRenderState state, Entity entity, float tickDelta, AABB cullingBox) {
		if (!armed) return;
		HighlightStyle style = null;
		int color = 0;
		if (asked.containsKey(entity)) {
			style = asked.get(entity);
			color = askedColor.getInt(entity);
		} else if (RenderRouter.INSTANCE.anyEntities()) {
			HighlightEvent.EntityHighlight e = ask(entity);
			style = e.style();
			color = e.color();
		}
		if (style == null) {
			if (state.outlineColor == 0) return;
			style = VANILLA_GLOW;
			color = state.outlineColor;
		}
		// The silhouette can reach past the hitbox (held items, wings, swinging arms): use the renderer's culling box,
		// moved to where the entity is drawn, with a block of slack.
		Vec3 pos = entity.getPosition(tickDelta);
		double dx = pos.x - entity.getX(), dy = pos.y - entity.getY(), dz = pos.z - entity.getZ();
		AABB hitbox = entity.getBoundingBox().move(dx, dy, dz);
		int id = add(ENTITIES, style, color, hitbox, cullingBox.move(dx, dy, dz).inflate(1));
		if (id < 0) return;
		// Turned with its body, so a fill's pattern stays on it.
		float yaw = state instanceof LivingEntityRenderState living ? living.bodyRot : entity.getYRot(tickDelta);
		highlights.turn(id, (float) Math.toRadians(yaw));
		state.outlineColor = encode(id, highlights.groupOf[id]);
	}

	/** Takes the frame's shapes from {@link RenderRouter} and clears the masks they're drawn into. */
	public void collectShapes() {
		if (!armed || !RenderRouter.INSTANCE.anyShapes()) return;
		RenderRouter.INSTANCE.shapesTo(this);
		for (int l : SHAPE_LAYERS) clearMask(l);
	}

	private void clearMask(int layer) {
		if (layers[layer].count == 0) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		masks[layer] = HighlightPasses.clearedMask(masks[layer], "Myriad highlight " + layers[layer].name, main.width, main.height);
		masked[layer] = true;
	}

	/** The frame's boxes into their masks. */
	private void drawShapes(CommandEncoder encoder) {
		for (int l : SHAPE_LAYERS) {
			if (!masked[l] || shapeColors[l].isEmpty()) continue;
			if (shapeMemory == null) shapeMemory = new ByteBufferBuilder(4096);
			BufferBuilder buf = new BufferBuilder(shapeMemory, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
			FloatArrayList boxes = shapeBoxes[l];
			IntArrayList ids = shapeColors[l];
			for (int n = 0; n < ids.size(); n++) {
				int i = n * 6, c = ids.getInt(n);
				float x0 = boxes.getFloat(i), y0 = boxes.getFloat(i + 1), z0 = boxes.getFloat(i + 2);
				float x1 = boxes.getFloat(i + 3), y1 = boxes.getFloat(i + 4), z1 = boxes.getFloat(i + 5);
				quad(buf, c, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
				quad(buf, c, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
				quad(buf, c, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
				quad(buf, c, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
				quad(buf, c, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
				quad(buf, c, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
			}
			try (MeshData mesh = buf.buildOrThrow()) {
				shapeVertices[l] = HighlightPasses.drawShapes(encoder, masks[l], mesh, shapeVertices[l]);
			}
		}
	}

	private static void quad(VertexConsumer buf, int c, float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz) {
		buf.addVertex(ax, ay, az).setColor(c);
		buf.addVertex(bx, by, bz).setColor(c);
		buf.addVertex(cx, cy, cz).setColor(c);
		buf.addVertex(dx, dy, dz).setColor(c);
	}

	/** A box (world coordinates). Off-screen shapes are skipped, so callers can pass everything in range. */
	public void box(AABB box, HighlightStyle style, int color, boolean below) {
		if (!armed || style == null || (color >>> 24) == 0 || (frustum != null && !frustum.isVisible(box))) return;
		int layer = below ? BELOW : SHAPES;
		int id = shape(layer, style, color, box);
		if (id >= 0) shapeBox(layer, box, 0, 0, 0, encode(id, highlights.groupOf[id]));
	}

	/** The block at {@code pos} by its shape (a full cube for one without). */
	public void block(BlockPos pos, HighlightStyle style, int color, boolean below) {
		if (!armed || style == null || (color >>> 24) == 0) return;
		var level = Minecraft.getInstance().level;
		if (level == null) return;
		VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
		AABB bounds = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
		if (frustum != null && !frustum.isVisible(bounds)) return;
		int layer = below ? BELOW : SHAPES;
		int id = shape(layer, style, color, bounds);
		if (id < 0) return;
		int c = encode(id, highlights.groupOf[id]);
		if (shape.isEmpty()) shapeBox(layer, new AABB(0, 0, 0, 1, 1, 1), pos.getX(), pos.getY(), pos.getZ(), c);
		else shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> shapeBox(layer, new AABB(x0, y0, z0, x1, y1, z1), pos.getX(), pos.getY(), pos.getZ(), c));
	}

	/**
	 * A mesh's filled faces, which stay on the GPU; each vertex's red picks its colour from {@code palette} (up to 256).
	 */
	public void mesh(WorldMesh mesh, HighlightStyle style, int[] palette, boolean below) {
		if (!armed || style == null || palette.length == 0 || mesh == null || mesh.isEmpty()) return;
		if (frustum != null && !frustum.isVisible(mesh.bounds())) return;
		int layer = below ? BELOW : SHAPES;
		List<Integer> key = new ArrayList<>(palette.length);
		for (int c : palette) key.add(c);
		PaletteKey pk = new PaletteKey(style, key);
		int base = paletteIds[layer].getOrDefault(pk, -1);
		if (base < 0) {
			if (highlights.count + palette.length > MAX_HIGHLIGHTS || palette.length > 256) return;
			base = highlights.count;
			for (int c : palette) add(layer, style, c, mesh.bounds(), mesh.bounds());
			paletteIds[layer].put(pk, base);
		} else {
			for (int i = 0; i < palette.length; i++) highlights.grow(base + i, mesh.bounds());
		}
		meshes.add(new MeshDraw(mesh, base, layer));
	}

	/**
	 * The highlight shapes of this look use this frame in {@code layer} (one per style and colour), grown to cover
	 * {@code box}.
	 */
	private int shape(int layer, HighlightStyle style, int color, AABB box) {
		GroupKey key = new GroupKey(style, color);
		int id = shapeIds[layer].getOrDefault(key, -1);
		if (id >= 0) {
			highlights.grow(id, box);
			return id;
		}
		id = add(layer, style, color, box, box);
		if (id >= 0) shapeIds[layer].put(key, id);
		return id;
	}

	private void shapeBox(int layer, AABB b, double ox, double oy, double oz, int encoded) {
		// Relative to the camera while still in doubles, so boxes far from spawn keep their precision.
		FloatArrayList boxes = shapeBoxes[layer];
		boxes.add((float) (b.minX + ox - highlights.camX));
		boxes.add((float) (b.minY + oy - highlights.camY));
		boxes.add((float) (b.minZ + oz - highlights.camZ));
		boxes.add((float) (b.maxX + ox - highlights.camX));
		boxes.add((float) (b.maxY + oy - highlights.camY));
		boxes.add((float) (b.maxZ + oz - highlights.camZ));
		shapeColors[layer].add(encoded);
	}

	/** Records a highlight in {@code layer}; returns its id, or -1 when there are too many. */
	int add(int layer, HighlightStyle style, int color, AABB gradientBox, AABB around) {
		int id = highlights.add(layer, style, color, gradientBox, around);
		if (id < 0) return -1;
		Layer l = layers[layer];
		l.count++;
		l.anyDepthTested |= !style.throughWalls();
		if (style.fill().isCustom()) l.customFills.add(style.fill().shaderId());
		return id;
	}

	/**
	 * A highlight id and merge group as an opaque outline colour: the outline shader writes it to the mask's RGB
	 * unchanged (id low byte in red, high byte in green, group in blue).
	 */
	public static int encode(int id, int group) {
		return 0xFF000000 | (id & 0xFF) << 16 | (id >> 8 & 0xFF) << 8 | group & 0xFF;
	}

	/** The id {@link #encode} packed into an outline colour. */
	public static int decodeId(int argb) {
		return (argb >> 16 & 0xFF) | (argb >> 8 & 0xFF) << 8;
	}

	/** The merge group {@link #encode} packed into an outline colour. */
	public static int decodeGroup(int argb) {
		return argb & 0xFF;
	}

	/** The level camera, at the start of {@code LevelRenderer.render}. */
	public void camera(Vec3 pos, Matrix4fc viewRotation, Frustum frustum, boolean outlinesShown) {
		this.frustum = frustum;
		highlights.camera(pos, viewRotation);
		this.outlinesShown = outlinesShown;
	}

	/** The masks' clip space back to view space, for fills that read depth. */
	private final Matrix4f toView = new Matrix4f(), toWorld = new Matrix4f();

	/** The level projection, view bobbing and nausea included, as the frame is drawn with it. */
	public void projection(Matrix4fc projection) {
		highlights.projection.set(projection);
	}

	/** Whether this frame's entity outline target holds highlight ids (so vanilla mustn't post-process or blit it). */
	public boolean ownsOutlines() {
		return layers[ENTITIES].count > 0 && outlinesShown;
	}

	/**
	 * Right after the outline pass drew the entity mask, before translucent terrain: draw the frame's boxes and retained
	 * meshes into the shapes masks, then for each layer resolve hidden pixels and run the row pass.
	 */
	public void afterMask(boolean outlinesDrawn) {
		for (Layer l : layers) l.prepared = false;
		// Only once the outline pass has run: vanilla skips it (and clearing the target) in a frame where nothing was drawn
		// with an outline, though a highlight was asked for (a picked-up item's render state, made as it's taken, goes to
		// the pickup animation with its outline taken off), and the target still held an earlier frame's silhouettes.
		boolean entities = ownsOutlines() && outlinesDrawn, shapes = false;
		for (int l : SHAPE_LAYERS) shapes |= masked[l] && layers[l].count > 0;
		if (!entities && !shapes) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (main.getDepthTextureView() == null) return;
		int w = main.width, h = main.height;
		int[][] areas = highlights.screenAreas(layers.length, w, h);
		for (int l = 0; l < layers.length; l++) layers[l].area = areas[l];
		float[] widths = highlights.fillData(0, highlights.count);
		int radius = Math.min(MAX_RADIUS, (int) Math.ceil(widths[0]) + 1);
		int boundary = Math.min(radius, (int) Math.ceil(widths[1]) + 1);
		buffer = HighlightPasses.ring(buffer, "Myriad highlights", highlights.count);
		GpuBuffer gpuData = HighlightPasses.write(buffer, radius, boundary, highlights.data, 0, highlights.count, highlights.projection.invert(toView),
			highlights.viewToWorld(toWorld));
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (shapes) drawShapes(encoder);
		if (shapes && !meshes.isEmpty()) HighlightPasses.drawMeshes(encoder, gpuData, meshes, masks, masked, highlights);
		GpuTimings.end(GpuTimings.Pass.MASKS);
		for (int l : SHAPE_LAYERS) if (masked[l] && layers[l].count > 0) HighlightPasses.prepare(encoder, layers[l], masks[l], main, gpuData, radius, boundary, w, h);
		if (entities) HighlightPasses.prepare(encoder, layers[ENTITIES], Minecraft.getInstance().levelRenderer.entityOutlineTarget, main, gpuData, radius, boundary, w, h);
	}

	/** After the level is drawn, before the hand: below-shapes, shapes over them, then entities, onto the main target. */
	public void composite() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		CommandEncoder encoder = null;
		for (int i : new int[]{BELOW, SHAPES, ENTITIES}) {
			Layer layer = layers[i];
			if (!layer.prepared) continue;
			layer.prepared = false;
			if (encoder == null) {
				encoder = RenderSystem.getDevice().createCommandEncoder();
				GpuTimings.begin(GpuTimings.Pass.COMPOSITE);
			}
			HighlightPasses.composite(encoder, layer, main, buffer.currentBuffer());
		}
		if (encoder != null) GpuTimings.end(GpuTimings.Pass.COMPOSITE);
	}

}
