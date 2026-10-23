package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.ListenerFlag;
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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Draws {@link HighlightEvent} highlights: outlines, glows and fills around exact silhouettes.
 * <p>
 * Three layers, each with its own silhouette mask, drawn in this order: shapes added {@link HighlightEvent.Shapes#below()
 * below} the others (so a highlight of the block you're looking at can't hide highlighted chests behind it), shapes,
 * and entities over them all (so a wall of highlighted chests can't hide the highlighted players behind it).
 * <ul>
 * <li>Entities: vanilla's entity outline target, the one the glowing effect uses. Every entity layer (armour, held
 * items, capes) already passes the entity's outline colour along and is drawn into that target with its texture's
 * cut-outs, so a highlighted entity's whole shape costs no extra work: its outline colour is set to a highlight id and
 * merge group ({@link #encode}) and vanilla draws the mask. While Myriad owns the target in a frame, vanilla's own
 * outline post effect is skipped and glowing entities become highlights in a vanilla-like style.</li>
 * <li>Shapes (both layers): a mask of Myriad's own each. Boxes and block shapes are drawn into it per frame; retained meshes
 * ({@link HighlightEvent.Shapes#mesh}) stay on the GPU and are drawn with their highlight id applied at draw time,
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

	private static final int MAX_RADIUS = 64;
	/** Highlights per frame: their ids take 16 bits of the mask, the merge group the other 8. */
	static final int MAX_HIGHLIGHTS = 1 << 16;
	/** Layers, by index; shapes and below-shapes are drawn the same way, into masks of their own. */
	private static final int ENTITIES = 0, SHAPES = 1, BELOW = 2, HAND = 3;
	static final int[] SHAPE_LAYERS = {BELOW, SHAPES};
	/** Glowing entities while Myriad owns the outline target: close to vanilla's soft outline. */
	private static final HighlightStyle VANILLA_GLOW = HighlightStyle.OUTLINE.withOutlineWidth(1).withGlow(3).withGlowStrength(0.7f);

	private final HighlightEvent.Entity entityEvent = new HighlightEvent.Entity();
	private final HighlightEvent.Shapes shapesEvent = new HighlightEvent.Shapes();
	private final HighlightEvent.Hand handEvent = new HighlightEvent.Hand();
	private ListenerFlag entityListeners, shapeListeners, handListeners;

	private long frame;
	private boolean armed;
	private final HighlightTable highlights = new HighlightTable();
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
	private final Layer[] layers = {new Layer("entities"), new Layer("shapes"), new Layer("shapes below"), new Layer("hand")};
	/** Per shapes layer and the hand: its mask. */
	private final TextureTarget[] masks = new TextureTarget[4];
	/** The hand's data has a buffer of its own: it's written after the world's, in the same frame. */
	private MappableRingBuffer buffer, handBuffer;

	// The first-person hand, drawn after the world: its highlights are numbered from handBase (-1 while it has none).
	private boolean drawingHand;
	/** A preview drawing your hands now (see {@link #previewHands}). */
	private @Nullable PreviewHighlights previewHands;
	private int handBase = -1;
	private final Matrix4f handProjection = new Matrix4f();
	private final Matrix4f handTransform = new Matrix4f();
	private final Vector4f handCorner = new Vector4f();
	/** The hand layer's screen area while its parts come in (min x, min y, max x, max y), and one part's. */
	private final float[] handArea = new float[4], partArea = new float[4];

	private HighlightRenderer() {
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
		drawingHand = false;
		handBase = -1;
		asked.clear();
		askedColor.clear();
		for (Layer l : layers) l.reset();
		if (!Myriad.isReady()) {
			armed = false;
			return;
		}
		if (entityListeners == null) {
			entityListeners = Myriad.events().flag(HighlightEvent.Entity.class);
			shapeListeners = Myriad.events().flag(HighlightEvent.Shapes.class);
			handListeners = Myriad.events().flag(HighlightEvent.Hand.class);
		}
		// A ghost highlighted last frame keeps the pipeline on, as listeners do.
		armed = entityListeners.isSet() || shapeListeners.isSet() || handListeners.isSet() || frame - ghostFrame <= 2;
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
		if (!entityListeners.isSet()) return shown;
		if (!shown) {
			if (!entity.shouldRender(camX, camY, camZ) || !frustum.isVisible(entity.getBoundingBox().inflate(0.5))) return false;
		} else if (!EntityCullingCompat.ACTIVE) {
			return true;
		}
		HighlightEvent.EntityHighlight e = ask(entity, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
		asked.put(entity, e.style());
		askedColor.put(entity, e.color());
		if (e.style() == null || !e.style().throughWalls()) return shown;
		EntityCullingCompat.keepVisible(entity);
		return true;
	}

	/** Who highlights {@code entity}: the listeners, then the {@link HighlightEvent#ENTITY_HIGHLIGHT} hook if it's hooked. */
	private HighlightEvent.EntityHighlight ask(Entity entity, float tickDelta) {
		HighlightEvent.Entity e = Myriad.events().post(entityEvent.reset(entity, tickDelta));
		HighlightEvent.EntityHighlight asked = new HighlightEvent.EntityHighlight(entity, e.style(), e.color());
		if (!HighlightEvent.ENTITY_HIGHLIGHT.hooked()) return asked;
		HighlightEvent.EntityHighlight h = HighlightEvent.ENTITY_HIGHLIGHT.call(null, asked, x -> x);
		return h == null || h.style() == null || (h.color() >>> 24) == 0 ? new HighlightEvent.EntityHighlight(entity, null, 0) : h;
	}

	/** Who highlights {@code entity} in a preview, as in the world (the listeners, then the hook): a null style for nobody. */
	public HighlightEvent.EntityHighlight askPreview(Entity entity) {
		if (!entityListeners.isSet() && !HighlightEvent.ENTITY_HIGHLIGHT.hooked()) return new HighlightEvent.EntityHighlight(entity, null, 0);
		return ask(entity, 1);
	}

	/**
	 * A preview is drawing your hands ({@code preview}), or has done (null): their parts are asked about as in the world,
	 * and highlighted into the preview's texture.
	 */
	public void previewHands(@Nullable PreviewHighlights preview) {
		previewHands = preview;
	}

	/** An entity's render state was just built: ask who highlights it, and point its outline colour at the highlight. */
	public void entity(EntityRenderState state, Entity entity, float tickDelta, AABB cullingBox) {
		if (!armed) return;
		HighlightStyle style = null;
		int color = 0;
		if (asked.containsKey(entity)) {
			style = asked.get(entity);
			color = askedColor.getInt(entity);
		} else if (entityListeners.isSet()) {
			HighlightEvent.EntityHighlight e = ask(entity, tickDelta);
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
		if (id >= 0) state.outlineColor = encode(id, highlights.groupOf[id]);
	}

	/** Posts {@link HighlightEvent.Shapes} and submits the frame's boxes to the shapes masks. */
	public void collectShapes(SubmitNodeCollector collector, PoseStack poseStack, float tickDelta) {
		if (!armed || !shapeListeners.isSet()) return;
		Myriad.events().post(shapesEvent.reset(tickDelta));
		for (int l : SHAPE_LAYERS) collectShapes(l, collector, poseStack);
	}

	private void collectShapes(int layer, SubmitNodeCollector collector, PoseStack poseStack) {
		if (layers[layer].count == 0) return;
		// Clear the mask now: the boxes are drawn into it during the frame's outline pass, the meshes after.
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		TextureTarget mask = masks[layer];
		if (mask == null) mask = masks[layer] = new TextureTarget("Myriad highlight " + layers[layer].name, main.width, main.height, true, GpuFormat.RGBA8_UNORM);
		else if (mask.width != main.width || mask.height != main.height) mask.resize(main.width, main.height);
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(mask.getColorTexture(), new Vector4f(0), mask.getDepthTexture(), 0.0);
		masked[layer] = true;
		if (shapeColors[layer].isEmpty()) return;
		FloatArrayList boxes = shapeBoxes[layer];
		IntArrayList ids = shapeColors[layer];
		// Drawn later in the frame: hand the lists over and start new ones.
		shapeBoxes[layer] = new FloatArrayList(boxes.size());
		shapeColors[layer] = new IntArrayList(ids.size());
		collector.submitCustomGeometry(poseStack, layer == BELOW ? MyriadPipelines.HIGHLIGHT_SHAPES_BELOW : MyriadPipelines.HIGHLIGHT_SHAPES, (pose, buf) -> {
			for (int n = 0; n < ids.size(); n++) {
				int i = n * 6, c = ids.getInt(n);
				float x0 = boxes.getFloat(i), y0 = boxes.getFloat(i + 1), z0 = boxes.getFloat(i + 2);
				float x1 = boxes.getFloat(i + 3), y1 = boxes.getFloat(i + 4), z1 = boxes.getFloat(i + 5);
				quad(buf, pose, c, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
				quad(buf, pose, c, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
				quad(buf, pose, c, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
				quad(buf, pose, c, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
				quad(buf, pose, c, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
				quad(buf, pose, c, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
			}
		});
	}

	/** The shapes mask, for the shapes render type's output target. */
	public RenderTarget shapeMask() {
		return masks[SHAPES];
	}

	/** The below-shapes mask, for that render type's output target. */
	public RenderTarget belowMask() {
		return masks[BELOW];
	}

	private static void quad(VertexConsumer buf, PoseStack.Pose pose, int c, float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz) {
		buf.addVertex(pose, ax, ay, az).setColor(c);
		buf.addVertex(pose, bx, by, bz).setColor(c);
		buf.addVertex(pose, cx, cy, cz).setColor(c);
		buf.addVertex(pose, dx, dy, dz).setColor(c);
	}

	/** {@link HighlightEvent.Shapes#box}. Off-screen shapes are skipped, so callers can pass everything in range. */
	public void box(AABB box, HighlightStyle style, int color, boolean below) {
		if (!armed || style == null || (color >>> 24) == 0 || (frustum != null && !frustum.isVisible(box))) return;
		int layer = below ? BELOW : SHAPES;
		int id = shape(layer, style, color, box);
		if (id >= 0) shapeBox(layer, box, 0, 0, 0, encode(id, highlights.groupOf[id]));
	}

	/** {@link HighlightEvent.Shapes#block}. */
	public void block(BlockPos pos, HighlightStyle style, int color, double inflate, boolean below) {
		if (!armed || style == null || (color >>> 24) == 0) return;
		var level = Minecraft.getInstance().level;
		if (level == null) return;
		VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
		AABB bounds = (shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos)).inflate(inflate);
		if (frustum != null && !frustum.isVisible(bounds)) return;
		int layer = below ? BELOW : SHAPES;
		int id = shape(layer, style, color, bounds);
		if (id < 0) return;
		int c = encode(id, highlights.groupOf[id]);
		if (shape.isEmpty()) shapeBox(layer, new AABB(0, 0, 0, 1, 1, 1).inflate(inflate), pos.getX(), pos.getY(), pos.getZ(), c);
		else shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> shapeBox(layer, new AABB(x0, y0, z0, x1, y1, z1).inflate(inflate), pos.getX(), pos.getY(), pos.getZ(), c));
	}

	/** {@link HighlightEvent.Shapes#mesh}. */
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
	private int add(int layer, HighlightStyle style, int color, AABB gradientBox, AABB around) {
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
	public void camera(Vec3 pos, Matrix4fc modelView, Frustum frustum, boolean outlinesShown) {
		this.frustum = frustum;
		highlights.camera(pos, modelView);
		this.outlinesShown = outlinesShown;
	}

	/** The level projection, view bobbing and nausea included, as the frame is drawn with it. */
	public void projection(Matrix4fc projection) {
		highlights.projection.set(projection);
	}

	/** Whether this frame's entity outline target holds highlight ids (so vanilla mustn't post-process or blit it). */
	public boolean ownsOutlines() {
		return layers[ENTITIES].count > 0 && outlinesShown;
	}

	/**
	 * Right after the masks are drawn, before translucent terrain: draw retained meshes into the shapes mask, then for
	 * each layer resolve hidden pixels and run the row pass.
	 */
	public void afterMask() {
		for (Layer l : layers) l.prepared = false;
		boolean entities = ownsOutlines(), shapes = false;
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
		GpuBuffer gpuData = HighlightPasses.write(buffer, radius, boundary, highlights.data, 0, highlights.count);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
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

	/**
	 * The first-person hand is about to be submitted, with {@code projection} (the hand's own field of view): its parts
	 * are asked about as they're submitted ({@link #hand}) while anything listens for {@link HighlightEvent.Hand}.
	 */
	public void beginHand(Matrix4fc projection) {
		drawingHand = armed && handListeners.isSet();
		if (drawingHand) handProjection.set(projection);
	}

	/**
	 * A pass that drew the hand is over, but the hand's highlights aren't drawn yet: a shader pack's hand passes (Iris
	 * draws the hand inside the level) keep what they highlighted for {@link #endHand}, after the pack's image is done.
	 */
	public void pauseHand() {
		drawingHand = false;
	}

	/** Whether the hand is being submitted with highlights asked about (in the world, or a preview's). */
	public boolean drawingHand() {
		return drawingHand || previewHands != null && handListeners.isSet();
	}

	/**
	 * A part of the first-person hand is being submitted at {@code pose}: who highlights it, as the outline colour to
	 * submit it with (0 for nobody). {@code extents} visits points around its shape, relative to {@code pose}, for its
	 * screen area; it's only called when the part is highlighted.
	 */
	public int hand(HighlightEvent.Hand.Part part, InteractionHand hand, HumanoidArm side, ItemStack item, Matrix4fc pose,
					Consumer<Consumer<Vector3fc>> extents) {
		float tickDelta = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		if (previewHands != null) {
			if (!handListeners.isSet()) return 0;
			HighlightEvent.Hand e = Myriad.events().post(handEvent.reset(part, hand, side, item, tickDelta));
			return previewHands.add(e.style(), e.color());
		}
		if (!drawingHand) return 0;
		HighlightEvent.Hand e = Myriad.events().post(handEvent.reset(part, hand, side, item, tickDelta));
		if (e.style() == null || highlights.count >= MAX_HIGHLIGHTS) return 0;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int w = main.width, h = main.height;
		if (handBase < 0) {
			handBase = highlights.count;
			handArea[0] = handArea[1] = Float.MAX_VALUE;
			handArea[2] = handArea[3] = -Float.MAX_VALUE;
			TextureTarget mask = masks[HAND];
			if (mask == null) mask = masks[HAND] = new TextureTarget("Myriad highlight hand", w, h, true, GpuFormat.RGBA8_UNORM);
			else if (mask.width != w || mask.height != h) mask.resize(w, h);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(mask.getColorTexture(), new Vector4f(0), mask.getDepthTexture(), 0.0);
		}
		AABB none = new AABB(highlights.camX, highlights.camY, highlights.camZ, highlights.camX, highlights.camY, highlights.camZ);
		int id = add(HAND, e.style(), e.color(), none, none);
		if (id < 0) return 0;
		// Where it lands on screen: the hand's projection, the model-view it's drawn with, then its pose.
		handProjection.mul(RenderSystem.getModelViewStack(), handTransform).mul(pose);
		float[] r = partArea;
		r[0] = r[1] = Float.MAX_VALUE;
		r[2] = r[3] = -Float.MAX_VALUE;
		extents.accept(v -> {
			handTransform.transform(handCorner.set(v.x(), v.y(), v.z(), 1));
			float cw = Math.max(handCorner.w, 1e-4f);
			float px = (handCorner.x / cw * 0.5f + 0.5f) * w, py = (handCorner.y / cw * 0.5f + 0.5f) * h;
			r[0] = Math.min(r[0], px);
			r[1] = Math.min(r[1], py);
			r[2] = Math.max(r[2], px);
			r[3] = Math.max(r[3], py);
		});
		// Nothing visited (a model without extents): the whole screen, as for something partly behind the camera.
		if (r[0] > r[2]) {
			r[0] = r[1] = 0;
			r[2] = w;
			r[3] = h;
		}
		System.arraycopy(r, 0, highlights.data, id * HighlightTable.FLOATS + 8, 4);
		handArea[0] = Math.min(handArea[0], r[0]);
		handArea[1] = Math.min(handArea[1], r[1]);
		handArea[2] = Math.max(handArea[2], r[2]);
		handArea[3] = Math.max(handArea[3], r[3]);
		return encode(id - handBase, highlights.groupOf[id]);
	}

	/** The hand's mask: the outline target while the hand is drawn with highlights ({@link #handHighlighted}). */
	public RenderTarget handMask() {
		return masks[HAND];
	}

	/** Whether the hand is being drawn with highlights, so its outline pass goes into {@link #handMask}. */
	public boolean handHighlighted() {
		return drawingHand && handBase >= 0;
	}

	/**
	 * The hand is drawn: its highlights over it, from its own mask (spread, then composite; nothing hides the hand),
	 * including any a shader pack's hand passes collected earlier in the frame ({@link #pauseHand}).
	 */
	public void endHand() {
		drawingHand = false;
		if (handBase < 0) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int w = main.width, h = main.height;
		int x0 = Math.max(0, (int) Math.floor(handArea[0]) - 1), y0 = Math.max(0, (int) Math.floor(handArea[1]) - 1);
		int x1 = Math.min(w, (int) Math.ceil(handArea[2]) + 1), y1 = Math.min(h, (int) Math.ceil(handArea[3]) + 1);
		if (x1 <= x0 || y1 <= y0) return;
		Layer layer = layers[HAND];
		layer.area = new int[]{x0, y0, x1, y1};
		layer.anyDepthTested = false;
		float[] widths = highlights.fillData(handBase, highlights.count);
		int radius = Math.min(MAX_RADIUS, (int) Math.ceil(widths[0]) + 1);
		int boundary = Math.min(radius, (int) Math.ceil(widths[1]) + 1);
		handBuffer = HighlightPasses.ring(handBuffer, "Myriad hand highlights", highlights.count - handBase);
		GpuBuffer gpuData = HighlightPasses.write(handBuffer, radius, boundary, highlights.data, handBase, highlights.count);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuTimings.begin(GpuTimings.Pass.HAND);
		HighlightPasses.prepare(encoder, layer, masks[HAND], main, gpuData, radius, boundary, w, h);
		if (layer.prepared) {
			layer.prepared = false;
			HighlightPasses.composite(encoder, layer, main, gpuData);
		}
		GpuTimings.end(GpuTimings.Pass.HAND);
	}
}
