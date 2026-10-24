package dev.myriadclient.impl.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.ListenerFlag;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.render.RenderLook;
import dev.myriadclient.api.util.Entities;
import dev.myriadclient.api.world.ChunkCache;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws what modules ask for through {@link RenderEvent}, in the mode that's on: posts the event at the start of a
 * frame's extraction and keeps the requests, then in {@link RenderMode#SHADER} hands them to {@link HighlightRenderer}
 * (entities as their outline colour is set, shapes with the frame's other shapes) and in {@link RenderMode#BOX} draws
 * them as boxes in the frame's {@link Render3DEvent}.
 */
public final class RenderRouter implements RenderEvent.Sink {
	public static final RenderRouter INSTANCE = new RenderRouter();

	private record Shape(@Nullable AABB box, @Nullable BlockPos pos, RenderLook look, int color, boolean below) {
	}

	private record Box(AABB box, RenderLook look, int color) {
	}

	private final RenderEvent event = new RenderEvent();
	private @Nullable ListenerFlag listeners;
	private RenderMode mode = RenderMode.SHADER;
	private float tickDelta;
	// Shader mode: entities by look (the first request wins), and shapes.
	private final Map<Entity, HighlightStyle> entityStyles = new IdentityHashMap<>();
	private final Object2IntOpenHashMap<Entity> entityColors = new Object2IntOpenHashMap<>();
	private final List<Shape> shapes = new ArrayList<>();
	private final List<ChunkCache<?>> caches = new ArrayList<>();
	// Box mode.
	private final List<Box> boxes = new ArrayList<>();

	private RenderRouter() {
	}

	/** Start of a frame's extraction, before any entity is: asks the modules what they show. */
	public void beginFrame(float tickDelta) {
		entityStyles.clear();
		entityColors.clear();
		shapes.clear();
		caches.clear();
		boxes.clear();
		if (listeners == null) listeners = Myriad.events().flag(RenderEvent.class);
		if (!listeners.isSet() || Minecraft.getInstance().level == null) return;
		mode = RenderMode.current();
		this.tickDelta = tickDelta;
		Myriad.events().post(event.reset(this, mode, tickDelta));
	}

	/** Whether anything asked for a shader highlight this frame. */
	boolean anyShader() {
		return !entityStyles.isEmpty() || !shapes.isEmpty() || !caches.isEmpty();
	}

	boolean anyEntities() {
		return !entityStyles.isEmpty();
	}

	boolean anyShapes() {
		return !shapes.isEmpty() || !caches.isEmpty();
	}

	/** The highlight asked for {@code entity} this frame, or null. */
	@Nullable HighlightStyle entityStyle(Entity entity) {
		return entityStyles.get(entity);
	}

	int entityColor(Entity entity) {
		return entityColors.getInt(entity);
	}

	/** Hands the frame's shapes to the highlight renderer. */
	void shapesTo(HighlightRenderer renderer) {
		for (Shape s : shapes) {
			HighlightStyle style = s.look().style();
			if (s.box() != null) renderer.box(s.box(), style, s.color(), s.below());
			else renderer.block(s.pos(), style, s.color(), s.below());
		}
		for (ChunkCache<?> cache : caches) {
			RenderLook look = cache.look();
			if (look == null) continue;
			HighlightStyle style = look.style();
			int[] palette = cache.palette();
			cache.forEachMesh(mesh -> renderer.mesh(mesh, style, palette, false));
		}
	}

	@Override
	public void entity(Entity entity, RenderLook look, int color) {
		if (mode == RenderMode.SHADER) {
			if (entityStyles.putIfAbsent(entity, look.style()) == null) entityColors.put(entity, color);
			return;
		}
		// The entity hook decides here as it does for highlights (a friend's colour, or none).
		if (HighlightEvent.ENTITY_HIGHLIGHT.hooked()) {
			HighlightEvent.EntityHighlight h = HighlightEvent.ENTITY_HIGHLIGHT.call(null, new HighlightEvent.EntityHighlight(entity, look.style(), color), x -> x);
			if (h == null || h.style() == null || (h.color() >>> 24) == 0) return;
			color = h.color();
		}
		boxes.add(new Box(Entities.lerpedBox(entity, tickDelta), look, color));
	}

	@Override
	public void box(AABB box, RenderLook look, int color, boolean below) {
		if (mode == RenderMode.SHADER) shapes.add(new Shape(box, null, look, color, below));
		else boxes.add(new Box(box, look, color));
	}

	@Override
	public void block(BlockPos pos, RenderLook look, int color, boolean below) {
		if (mode == RenderMode.SHADER) {
			shapes.add(new Shape(null, pos.immutable(), look, color, below));
			return;
		}
		var level = Minecraft.getInstance().level;
		BlockState state = level.getBlockState(pos);
		VoxelShape shape = state.getShape(level, pos);
		// One box around the shape: simple, as boxes are.
		boxes.add(new Box(shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos), look, color));
	}

	@Override
	public void chunks(ChunkCache<?> cache) {
		// Box mode: the cache shows its own meshes.
		if (mode == RenderMode.SHADER && cache.look() != null) caches.add(cache);
	}

	/** Box mode: the frame's boxes, with everything else modules draw. */
	@Subscribe(priority = Priority.HIGH)
	private void onRender3D(Render3DEvent e) {
		if (boxes.isEmpty()) return;
		for (Box b : boxes) b.look().box(e.shapes(), b.box(), b.color());
	}
}
