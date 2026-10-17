package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.ListenerFlag;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.WorldMesh;
import dev.myriadclient.impl.compat.EntityCullingCompat;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

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

	/** vec4s per highlight in the data buffer: top colour, bottom colour, gradient rectangle, shape, fill. */
	private static final int TEXELS = 5;
	/** vec4s before the first highlight: (radius, GUI scale, count, boundary radius), (seconds, 0, 0, 0). */
	private static final int HEADER = 2;
	private static final int FLOATS = TEXELS * 4;
	private static final int MAX_RADIUS = 64;
	/** Highlights per frame: their ids take 16 bits of the mask, the merge group the other 8. */
	static final int MAX_HIGHLIGHTS = 1 << 16;
	/** Layers, by index; shapes and below-shapes ({@link #isShapes}) are drawn the same way, into masks of their own. */
	private static final int ENTITIES = 0, SHAPES = 1, BELOW = 2;
	private static final int[] SHAPE_LAYERS = {BELOW, SHAPES};
	/** Glowing entities while Myriad owns the outline target: close to vanilla's soft outline. */
	private static final HighlightStyle VANILLA_GLOW = HighlightStyle.OUTLINE.withOutlineWidth(1).withGlow(3).withGlowStrength(0.7f);
	private static final Matrix4f IDENTITY = new Matrix4f();

	private final HighlightEvent.Entity entityEvent = new HighlightEvent.Entity();
	private final HighlightEvent.Shapes shapesEvent = new HighlightEvent.Shapes();
	private ListenerFlag entityListeners, shapeListeners;

	// This frame's highlights, by id.
	private long frame;
	private boolean armed;
	private int count;
	private HighlightStyle[] styles = new HighlightStyle[64];
	private int[] colors = new int[64];
	private int[] groupOf = new int[64];
	private byte[] layerOf = new byte[64];
	/** Per highlight: the box the gradient spans, then a box sure to contain the whole silhouette (12 doubles). */
	private double[] bounds = new double[12 * 64];
	/** Per highlight: distance from the camera to its nearest part, for distance scaling. */
	private double[] nearest = new double[64];
	private float[] data = new float[FLOATS * 64];
	/** This frame's merge groups: highlights that look alike (same style and colour) share one, and connect. */
	private final Object2IntOpenHashMap<GroupKey> groups = new Object2IntOpenHashMap<>();
	/** Shapes that look alike share one highlight per frame, per layer. */
	@SuppressWarnings("unchecked")
	private final Object2IntOpenHashMap<GroupKey>[] shapeIds = new Object2IntOpenHashMap[]{null, new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>()};
	/** Meshes sharing a style and palette share one run of consecutive ids (one per palette colour), per layer. */
	@SuppressWarnings("unchecked")
	private final Object2IntOpenHashMap<PaletteKey>[] paletteIds = new Object2IntOpenHashMap[]{null, new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>()};

	private record GroupKey(HighlightStyle style, int rgb) {
	}

	private record PaletteKey(HighlightStyle style, List<Integer> palette) {
	}

	/** A retained mesh to draw into a shapes mask, with the first id of its palette. */
	private record MeshDraw(WorldMesh mesh, int baseId, int layer) {
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

	// This frame's camera, captured before the level is drawn.
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f view = new Matrix4f();
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f corner = new Vector4f();
	private double camX, camY, camZ;
	private boolean outlinesShown;
	private Frustum frustum;

	// GPU state.
	private final Layer[] layers = {new Layer("entities"), new Layer("shapes"), new Layer("shapes below")};
	/** Per shapes layer: its mask. */
	private final TextureTarget[] masks = new TextureTarget[3];
	private MappableRingBuffer buffer;

	/** Addons' fills: shader id number to its composite pass. */
	private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<RenderPipeline> FILLS = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
	private static final long START = System.nanoTime();

	/** Registers an addon's fill (see {@link HighlightStyle.Fill#custom}): its shader becomes one more composite pass. */
	public static void registerFill(int shaderId, net.minecraft.resources.Identifier shader) {
		FILLS.put(shaderId, MyriadPipelines.highlightFill(shaderId, shader));
	}

	/** One mask's passes and results. */
	private static final class Layer {
		final String name;
		TextureTarget resolved, spread;
		/** Addons' fills used by this layer's highlights this frame. */
		final it.unimi.dsi.fastutil.ints.IntRBTreeSet customFills = new it.unimi.dsi.fastutil.ints.IntRBTreeSet();
		int count;
		boolean anyDepthTested, prepared;
		int[] area;
		final int[] compositeRect = new int[4];

		Layer(String name) {
			this.name = name;
		}

		void reset() {
			customFills.clear();
			count = 0;
			anyDepthTested = false;
			prepared = false;
			area = null;
		}
	}

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
		count = 0;
		groups.clear();
		for (int l : SHAPE_LAYERS) {
			shapeIds[l].clear();
			paletteIds[l].clear();
			shapeBoxes[l].clear();
			shapeColors[l].clear();
			masked[l] = false;
		}
		meshes.clear();
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
		}
		armed = entityListeners.isSet() || shapeListeners.isSet();
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
		HighlightEvent.Entity e = Myriad.events().post(entityEvent.reset(entity, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)));
		asked.put(entity, e.style());
		askedColor.put(entity, e.color());
		if (e.style() == null || !e.style().throughWalls()) return shown;
		EntityCullingCompat.keepVisible(entity);
		return true;
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
			HighlightEvent.Entity e = Myriad.events().post(entityEvent.reset(entity, tickDelta));
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
		if (id >= 0) state.outlineColor = encode(id, groupOf[id]);
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

	private static void quad(com.mojang.blaze3d.vertex.VertexConsumer buf, PoseStack.Pose pose, int c, float ax, float ay, float az, float bx, float by, float bz,
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
		if (id >= 0) shapeBox(layer, box, 0, 0, 0, encode(id, groupOf[id]));
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
		int c = encode(id, groupOf[id]);
		if (shape.isEmpty()) shapeBox(layer, new AABB(0, 0, 0, 1, 1, 1).inflate(inflate), pos.getX(), pos.getY(), pos.getZ(), c);
		else shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> shapeBox(layer, new AABB(x0, y0, z0, x1, y1, z1).inflate(inflate), pos.getX(), pos.getY(), pos.getZ(), c));
	}

	/** {@link HighlightEvent.Shapes#mesh}. */
	public void mesh(WorldMesh mesh, HighlightStyle style, int[] palette, boolean below) {
		if (!armed || style == null || palette.length == 0 || mesh == null || mesh.isEmpty()) return;
		if (frustum != null && !frustum.isVisible(mesh.bounds())) return;
		int layer = below ? BELOW : SHAPES;
		List<Integer> key = new java.util.ArrayList<>(palette.length);
		for (int c : palette) key.add(c);
		PaletteKey pk = new PaletteKey(style, key);
		int base = paletteIds[layer].getOrDefault(pk, -1);
		if (base < 0) {
			if (count + palette.length > MAX_HIGHLIGHTS || palette.length > 256) return;
			base = count;
			for (int c : palette) add(layer, style, c, mesh.bounds(), mesh.bounds());
			paletteIds[layer].put(pk, base);
		} else {
			for (int i = 0; i < palette.length; i++) grow(base + i, mesh.bounds());
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
			grow(id, box);
			return id;
		}
		id = add(layer, style, color, box, box);
		if (id >= 0) shapeIds[layer].put(key, id);
		return id;
	}

	private void shapeBox(int layer, AABB b, double ox, double oy, double oz, int encoded) {
		// Relative to the camera while still in doubles, so boxes far from spawn keep their precision.
		FloatArrayList boxes = shapeBoxes[layer];
		boxes.add((float) (b.minX + ox - camX));
		boxes.add((float) (b.minY + oy - camY));
		boxes.add((float) (b.minZ + oz - camZ));
		boxes.add((float) (b.maxX + ox - camX));
		boxes.add((float) (b.maxY + oy - camY));
		boxes.add((float) (b.maxZ + oz - camZ));
		shapeColors[layer].add(encoded);
	}

	/** Records a highlight in {@code layer}; returns its id, or -1 when there are too many. */
	private int add(int layer, HighlightStyle style, int color, AABB gradientBox, AABB around) {
		if (count >= MAX_HIGHLIGHTS) return -1;
		int id = count++;
		if (styles.length < count) {
			int n = styles.length * 2;
			styles = Arrays.copyOf(styles, n);
			colors = Arrays.copyOf(colors, n);
			groupOf = Arrays.copyOf(groupOf, n);
			layerOf = Arrays.copyOf(layerOf, n);
			nearest = Arrays.copyOf(nearest, n);
			bounds = Arrays.copyOf(bounds, n * 12);
			data = Arrays.copyOf(data, n * FLOATS);
		}
		GroupKey key = new GroupKey(style, color & 0xFFFFFF);
		int group = groups.getInt(key);
		if (group == 0) groups.put(key, group = groups.size() % 255 + 1);
		styles[id] = style;
		colors[id] = color;
		groupOf[id] = group;
		layerOf[id] = (byte) layer;
		putBox(id * 12, gradientBox);
		putBox(id * 12 + 6, around);
		nearest[id] = distance(gradientBox);
		Layer l = layers[layer];
		l.count++;
		l.anyDepthTested |= !style.throughWalls();
		if (style.fill().isCustom()) l.customFills.add(style.fill().shaderId());
		return id;
	}

	/** Grows highlight {@code id}'s boxes to cover {@code box}; its widths follow its nearest part. */
	private void grow(int id, AABB box) {
		int b = id * 12;
		for (int o : new int[]{b, b + 6}) {
			bounds[o] = Math.min(bounds[o], box.minX);
			bounds[o + 1] = Math.min(bounds[o + 1], box.minY);
			bounds[o + 2] = Math.min(bounds[o + 2], box.minZ);
			bounds[o + 3] = Math.max(bounds[o + 3], box.maxX);
			bounds[o + 4] = Math.max(bounds[o + 4], box.maxY);
			bounds[o + 5] = Math.max(bounds[o + 5], box.maxZ);
		}
		nearest[id] = Math.min(nearest[id], distance(box));
	}

	private double distance(AABB box) {
		return Math.sqrt(box.distanceToSqr(new Vec3(camX, camY, camZ)));
	}

	private void putBox(int b, AABB box) {
		bounds[b] = box.minX;
		bounds[b + 1] = box.minY;
		bounds[b + 2] = box.minZ;
		bounds[b + 3] = box.maxX;
		bounds[b + 4] = box.maxY;
		bounds[b + 5] = box.maxZ;
	}

	/** Fills in the shader data of every highlight; returns {reach, outline} in pixels, the most any highlight needs. */
	private float[] fillData() {
		float scale = (float) Minecraft.getInstance().getWindow().getGuiScale();
		float maxReach = 0, maxOutline = 0;
		for (int id = 0; id < count; id++) {
			HighlightStyle style = styles[id];
			int color = colors[id], i = id * FLOATS;
			float alpha = (color >>> 24) / 255f;
			putColor(i, color, alpha);
			if (style.hasGradient()) putColor(i + 4, style.gradient(), alpha * (style.gradient() >>> 24) / 255f);
			else System.arraycopy(data, i, data, i + 4, 4);
			// i + 8..11: the gradient rectangle, from screenArea.
			// Thinner with distance (to half), but an outline never drops below a pixel.
			float far = style.widthScale(nearest[id]);
			float outline = style.outlineWidth() * scale * far, glow = style.glow() * scale * far;
			if (outline > 0) outline = Math.max(1, outline);
			data[i + 12] = outline;
			data[i + 13] = glow;
			data[i + 14] = style.glowStrength();
			data[i + 15] = style.fillOpacity();
			data[i + 16] = style.fill().shaderId();
			data[i + 17] = style.dotSpacing() * scale;
			data[i + 18] = style.dotSize() * scale * 0.5f;
			// Bit 0: hidden parts are dropped; above it the merge group (the mesh mask shader reads it from here).
			data[i + 19] = (style.throughWalls() ? 0 : 1) + 2 * groupOf[id];
			maxReach = Math.max(maxReach, outline + glow);
			maxOutline = Math.max(maxOutline, outline);
		}
		return new float[]{maxReach, maxOutline};
	}

	private void putColor(int i, int argb, float alpha) {
		data[i] = (argb >> 16 & 0xFF) / 255f;
		data[i + 1] = (argb >> 8 & 0xFF) / 255f;
		data[i + 2] = (argb & 0xFF) / 255f;
		data[i + 3] = alpha;
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
		camX = pos.x;
		camY = pos.y;
		camZ = pos.z;
		view.set(modelView);
		this.outlinesShown = outlinesShown;
	}

	/** The level projection, view bobbing and nausea included, as the frame is drawn with it. */
	public void projection(Matrix4fc projection) {
		this.projection.set(projection);
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
		screenArea(w, h);
		float[] widths = fillData();
		int radius = Math.min(MAX_RADIUS, (int) Math.ceil(widths[0]) + 1);
		int boundary = Math.min(radius, (int) Math.ceil(widths[1]) + 1);
		GpuBuffer gpuData = upload(radius, boundary);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (shapes && !meshes.isEmpty()) drawMeshes(encoder, gpuData);
		for (int l : SHAPE_LAYERS) if (masked[l] && layers[l].count > 0) prepare(encoder, layers[l], masks[l], main, gpuData, radius, w, h);
		if (entities) prepare(encoder, layers[ENTITIES], Minecraft.getInstance().levelRenderer.entityOutlineTarget, main, gpuData, radius, w, h);
	}

	/** Retained meshes into the shapes mask: their quads, with each vertex's palette index added to the base id. */
	private void drawMeshes(CommandEncoder encoder, GpuBuffer gpuData) {
		// Inside the level pass: the model-view matrix is the camera's view, as for MeshRenderer.
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		int maxIndices = 0;
		for (MeshDraw d : meshes) maxIndices = Math.max(maxIndices, Math.max(d.mesh().indexCount(0), d.mesh().indexCount(2)));
		if (maxIndices == 0) return;
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = indices.getBuffer(maxIndices);
		GpuBufferSlice[] transforms = new GpuBufferSlice[meshes.size()];
		for (int i = 0; i < meshes.size(); i++) {
			MeshDraw d = meshes.get(i);
			WorldMesh m = d.mesh();
			// The base id rides in the colour modulator, the same bytes as an encoded outline colour.
			Vector4f base = new Vector4f((d.baseId() & 0xFF) / 255f, (d.baseId() >> 8 & 0xFF) / 255f, 0, 1);
			Vector3f offset = new Vector3f((float) (m.originX() - camX), (float) (m.originY() - camY), (float) (m.originZ() - camZ));
			transforms[i] = RenderSystem.getDynamicUniforms().writeTransform(modelView, base, offset, IDENTITY);
		}
		for (int layer : SHAPE_LAYERS) {
			if (!masked[layer]) continue;
			drawMeshes(encoder, gpuData, layer, transforms, indexBuffer, indices.type());
		}
	}

	private void drawMeshes(CommandEncoder encoder, GpuBuffer gpuData, int layer, GpuBufferSlice[] transforms, GpuBuffer indexBuffer,
							com.mojang.blaze3d.IndexType indexType) {
		boolean any = false;
		for (MeshDraw d : meshes) any |= d.layer() == layer;
		if (!any) return;
		TextureTarget mask = masks[layer];
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad highlight meshes", mask.getColorTextureView(), Optional.empty(),
			mask.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(MyriadPipelines.HIGHLIGHT_MESH);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Highlights", gpuData);
			pass.setIndexBuffer(indexBuffer, indexType);
			for (int i = 0; i < meshes.size(); i++) {
				if (meshes.get(i).layer() != layer) continue;
				WorldMesh m = meshes.get(i).mesh();
				pass.setUniform("DynamicTransforms", transforms[i]);
				for (int part : new int[]{0, 2}) {
					GpuBuffer vertices = m.buffer(part);
					int n = m.indexCount(part);
					if (vertices == null || n == 0) continue;
					pass.setVertexBuffer(0, vertices.slice());
					pass.drawIndexed(n, 1, 0, 0, 0);
				}
			}
		}
	}

	private void prepare(CommandEncoder encoder, Layer layer, RenderTarget mask, RenderTarget main, GpuBuffer gpuData, int radius, int w, int h) {
		if (mask == null || mask.getColorTextureView() == null || layer.area == null) return;
		resize(layer, w, h);
		GpuSampler nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		GpuTextureView source = mask.getColorTextureView();
		int[] area = layer.area;
		if (layer.anyDepthTested) {
			try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_RESOLVE, layer.resolved.getColorTextureView(), gpuData, grow(area, 2 * radius, 2 * radius, w, h))) {
				pass.bindTexture("Mask", source, nearestSampler);
				pass.bindTexture("MaskDepth", mask.getDepthTextureView(), nearestSampler);
				pass.bindTexture("SceneDepth", main.getDepthTextureView(), nearestSampler);
				pass.draw(3, 1, 0, 0);
			}
			source = layer.resolved.getColorTextureView();
		}
		try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_SPREAD, layer.spread.getColorTextureView(), gpuData, grow(area, radius, 2 * radius, w, h))) {
			pass.bindTexture("InSampler", source, nearestSampler);
			pass.draw(3, 1, 0, 0);
		}
		System.arraycopy(grow(area, radius, radius, w, h), 0, layer.compositeRect, 0, 4);
		layer.prepared = true;
	}

	/** After the level is drawn, before the hand: below-shapes, shapes over them, then entities, onto the main target. */
	public void composite() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		CommandEncoder encoder = null;
		for (int i : new int[]{BELOW, SHAPES, ENTITIES}) {
			Layer layer = layers[i];
			if (!layer.prepared) continue;
			layer.prepared = false;
			if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
			composite(encoder, MyriadPipelines.HIGHLIGHT_COMPOSITE, layer, main);
			// Addons' fills, each over its own highlights' insides.
			for (int fill : layer.customFills) {
				RenderPipeline pipeline = FILLS.get(fill);
				if (pipeline != null) composite(encoder, pipeline, layer, main);
			}
		}
	}

	private void composite(CommandEncoder encoder, RenderPipeline pipeline, Layer layer, RenderTarget main) {
		try (RenderPass pass = begin(encoder, pipeline, main.getColorTextureView(), buffer.currentBuffer(), layer.compositeRect)) {
			pass.bindTexture("SpreadSampler", layer.spread.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	private RenderPass begin(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView target, GpuBuffer gpuData, int[] rect) {
		RenderPass pass = encoder.createRenderPass(() -> "Myriad highlights", target, Optional.empty(), null, OptionalDouble.empty());
		pass.setPipeline(pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("Highlights", gpuData);
		pass.enableScissor(rect[0], rect[1], rect[2], rect[3]);
		return pass;
	}

	/**
	 * Projects every highlight with this frame's camera: fills in the gradient rectangles and each layer's framebuffer
	 * area {x0, y0, x1, y1} that holds its silhouettes (null if none is on screen). Pixel rows count up from the bottom
	 * of the image (OpenGL) or down from the top (Vulkan), the same way texel rows and gl_FragCoord do on each, so the
	 * shaders and scissor agree either way.
	 */
	private void screenArea(int w, int h) {
		projection.mul(view, viewProjection);
		float[] r = new float[4];
		int[][] box = new int[layers.length][];
		for (int l = 0; l < box.length; l++) box[l] = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int id = 0; id < count; id++) {
			int b = id * 12, i = id * FLOATS;
			if (!project(b, w, h, r)) {
				r[0] = 0;
				r[1] = 0;
				r[2] = w;
				r[3] = h;
			}
			data[i + 8] = r[0];
			data[i + 9] = r[1];
			data[i + 10] = r[2];
			data[i + 11] = r[3];
			if (!project(b + 6, w, h, r)) {
				r[0] = 0;
				r[1] = 0;
				r[2] = w;
				r[3] = h;
			}
			int[] a = box[layerOf[id]];
			a[0] = Math.min(a[0], (int) Math.floor(r[0]));
			a[1] = Math.min(a[1], (int) Math.floor(r[1]));
			a[2] = Math.max(a[2], (int) Math.ceil(r[2]));
			a[3] = Math.max(a[3], (int) Math.ceil(r[3]));
		}
		for (int l = 0; l < layers.length; l++) {
			int[] a = box[l];
			int x0 = Math.max(0, a[0] - 1), y0 = Math.max(0, a[1] - 1), x1 = Math.min(w, a[2] + 1), y1 = Math.min(h, a[3] + 1);
			layers[l].area = x1 <= x0 || y1 <= y0 ? null : new int[]{x0, y0, x1, y1};
		}
	}

	/** The pixel rectangle around the box at {@code bounds[b]}; false if part of it is behind the camera. */
	private boolean project(int b, int w, int h, float[] out) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int k = 0; k < 8; k++) {
			double x = bounds[b + ((k & 1) == 0 ? 0 : 3)], y = bounds[b + ((k & 2) == 0 ? 1 : 4)], z = bounds[b + ((k & 4) == 0 ? 2 : 5)];
			viewProjection.transform(corner.set((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1));
			if (corner.w <= 1e-4f) return false;
			float px = (corner.x / corner.w * 0.5f + 0.5f) * w, py = (corner.y / corner.w * 0.5f + 0.5f) * h;
			minX = Math.min(minX, px);
			minY = Math.min(minY, py);
			maxX = Math.max(maxX, px);
			maxY = Math.max(maxY, py);
		}
		out[0] = minX;
		out[1] = minY;
		out[2] = maxX;
		out[3] = maxY;
		return true;
	}

	/** {@code area} grown by {@code dx}, {@code dy} and clamped to the screen, as a scissor {x, y, width, height}. */
	private static int[] grow(int[] area, int dx, int dy, int w, int h) {
		int x0 = Math.max(0, area[0] - dx), y0 = Math.max(0, area[1] - dy);
		int x1 = Math.min(w, area[2] + dx), y1 = Math.min(h, area[3] + dy);
		return new int[]{x0, y0, x1 - x0, y1 - y0};
	}

	/** Writes the header and every highlight to this frame's slot of the data buffer. */
	private GpuBuffer upload(int radius, int boundary) {
		int bytes = (HEADER + count * TEXELS) * 16;
		if (buffer == null || buffer.size() < bytes) {
			if (buffer != null) GpuGarbage.close(buffer);
			int size = Math.max(4096, Integer.highestOneBit(bytes - 1) << 1);
			buffer = new MappableRingBuffer(() -> "Myriad highlights", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, size);
		} else {
			buffer.rotate();
		}
		GpuBuffer gpu = buffer.currentBuffer();
		try (GpuBufferSlice.MappedView view = gpu.map(false, true)) {
			FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
			out.put(radius).put((float) Minecraft.getInstance().getWindow().getGuiScale()).put(count).put(boundary);
			out.put((float) ((System.nanoTime() - START) / 1e9 % 3600)).put(0).put(0).put(0);
			out.put(data, 0, count * FLOATS);
		}
		return gpu;
	}

	private static void resize(Layer layer, int w, int h) {
		if (layer.spread == null) {
			layer.resolved = new TextureTarget("Myriad highlights resolved (" + layer.name + ")", w, h, false, GpuFormat.RGBA8_UNORM);
			layer.spread = new TextureTarget("Myriad highlights spread (" + layer.name + ")", w, h, false, GpuFormat.RGBA16_UNORM);
		} else if (layer.spread.width != w || layer.spread.height != h) {
			layer.resolved.resize(w, h);
			layer.spread.resize(w, h);
		}
	}
}
