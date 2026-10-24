package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.impl.render.font.FontManager;
import dev.myriadclient.impl.render.font.Glyph;
import dev.myriadclient.impl.render.font.SizedFont;
import dev.myriadclient.impl.render.preview.PreviewRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The implementation of {@link Canvas}. Shapes are signed-distance quads drawn by one shader (core/ui.vsh, ui.fsh) with
 * a fat vertex format. Quads are recorded into batches that break only on texture changes; each batch becomes one
 * element of vanilla's GUI render state (see {@link UiBatch}), so it draws in order with vanilla items and text.
 */
public final class UiRenderer implements Canvas {
	private static final int FILL = 0, OUTLINE = 1, SHADOW = 2, GLYPH = 3, IMAGE = 4, BACKDROP = 5;
	private static final int FLOATS_PER_VERTEX = UiBatch.FLOATS_PER_VERTEX;
	private static final int MAX_QUADS = 16384;

	private static UiRenderer instance;

	private final Minecraft mc = Minecraft.getInstance();
	private final FontManager fonts = new FontManager();
	private final FrameBlur blur = new FrameBlur();
	private final Deque<State> stack = new ArrayDeque<>();
	private final GpuTextureView white;

	private float[] data = new float[1024 * 4 * FLOATS_PER_VERTEX];
	private int size;
	private int quads;
	private GpuTextureView texture;
	private boolean usesBlur;
	private float minX, minY, maxX, maxY;
	private boolean active;

	// Frame state
	private GuiGraphicsExtractor context;
	private int fbW, fbH, guiScale;
	private boolean blurEnabled;
	private int blurPasses = 3;
	private float blurOffset = 2.5f;
	private long lastBegin;
	private float frameDelta;

	// Transform/clip/alpha state
	private State state = new State();

	// Theme defaults
	private FontFamily defaultFont = FontFamily.SANS;
	private float defaultSize = 7.5f;

	/** The renderer if it has been created (it needs the GPU device, so it is created on first draw). */
	public static UiRenderer peek() {
		return instance;
	}

	public static UiRenderer get() {
		if (instance == null) instance = new UiRenderer();
		return instance;
	}

	private UiRenderer() {
		GpuTexture tex = RenderSystem.getDevice().createTexture("Myriad white", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
			GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
		ByteBuffer px = MemoryUtil.memAlloc(4);
		try {
			px.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(tex, px, 0, 0, 0, 0, 1, 1);
		} finally {
			MemoryUtil.memFree(px);
		}
		white = RenderSystem.getDevice().createTextureView(tex);
	}

	/** Computes the frame's blur if anything drew a backdrop; called by the GUI renderer just before it draws. */
	public void beforeGuiDraw() {
		blur.runIfRequested();
		GpuGarbage.tick();
	}

	public void setDefaults(FontFamily font, float size) {
		this.defaultFont = font;
		this.defaultSize = size;
	}

	public void setBlur(boolean enabled, int passes, float offset) {
		this.blurEnabled = enabled;
		this.blurPasses = passes;
		this.blurOffset = offset;
	}

	/** Starts recording into {@code ctx}. {@code pixelScale} is framebuffer pixels per UI unit. */
	public UiRenderer begin(GuiGraphicsExtractor ctx, float pixelScale) {
		if (active) throw new IllegalStateException("UiRenderer.begin() called twice");
		active = true;
		context = ctx;
		fbW = mc.getWindow().getWidth();
		fbH = mc.getWindow().getHeight();
		guiScale = Math.max(1, mc.getWindow().getGuiScale());
		long now = System.nanoTime();
		frameDelta = lastBegin == 0 ? 16f : Math.min(100f, (now - lastBegin) / 1_000_000f);
		lastBegin = now;
		state = new State();
		state.scale = pixelScale;
		state.clipX1 = fbW;
		state.clipY1 = fbH;
		stack.clear();
		resetBatch();
		return this;
	}

	public void end() {
		flush();
		active = false;
		context = null;
	}

	public boolean isActive() {
		return active;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Transform helpers

	private float px(float x) {
		return x * state.scale + state.tx;
	}

	private float py(float y) {
		return y * state.scale + state.ty;
	}

	private int color(int argb) {
		if (state.alpha >= 1f) return argb;
		return (Math.round((argb >>> 24) * state.alpha) << 24) | (argb & 0xFFFFFF);
	}

	private boolean culled(float x, float y, float w, float h) {
		return x > state.clipX1 || y > state.clipY1 || x + w < state.clipX0 || y + h < state.clipY0;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Shapes

	@Override
	public void rect(float x, float y, float w, float h, int color) {
		roundRect(x, y, w, h, 0, 0, 0, 0, color);
	}

	@Override
	public void roundRect(float x, float y, float w, float h, float radius, int color) {
		roundRect(x, y, w, h, radius, radius, radius, radius, color);
	}

	@Override
	public void roundRect(float x, float y, float w, float h, float tl, float tr, float br, float bl, int color) {
		float s = state.scale;
		box(px(x), py(y), w * s, h * s, tl * s, tr * s, br * s, bl * s, color, color, FILL, 0, 0, 0, 0, 0, 0, null);
	}

	@Override
	public void gradientRect(float x, float y, float w, float h, float radius, int from, int to, float angle) {
		float s = state.scale, r = radius * s;
		box(px(x), py(y), w * s, h * s, r, r, r, r, from, to, FILL, 0, (float) Math.toRadians(angle), 0, 0, 0, 0, null);
	}

	@Override
	public void outline(float x, float y, float w, float h, float radius, float thickness, int color) {
		gradientOutline(x, y, w, h, radius, thickness, color, color, 0);
	}

	@Override
	public void gradientOutline(float x, float y, float w, float h, float radius, float thickness, int from, int to, float angle) {
		float s = state.scale, r = radius * s;
		box(px(x), py(y), w * s, h * s, r, r, r, r, from, to, OUTLINE, Math.max(1f, thickness * s), (float) Math.toRadians(angle), 0, 0, 0, 0, null);
	}

	@Override
	public void shadow(float x, float y, float w, float h, float radius, float size, int color) {
		float s = state.scale, r = radius * s, sz = size * s;
		box(px(x) - sz, py(y) - sz, w * s + sz * 2, h * s + sz * 2, r, r, r, r, color, color, SHADOW, sz, 0, 0, 0, 0, 0, null);
	}

	@Override
	public void backdrop(float x, float y, float w, float h, float radius, float opacity) {
		if (!blurEnabled) return;
		blur.request(blurPasses, blurOffset);
		usesBlur = true;
		float s = state.scale, r = radius * s;
		int c = (Math.round(Math.clamp(opacity, 0f, 1f) * 255) << 24) | 0xFFFFFF;
		box(px(x), py(y), w * s, h * s, r, r, r, r, c, c, BACKDROP, 0, 0, 0, 0, 0, 0, null);
	}

	@Override
	public void circle(float cx, float cy, float radius, int color) {
		roundRect(cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	@Override
	public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
		float ax = px(x1), ay = py(y1), bx = px(x2), by = py(y2);
		float dx = bx - ax, dy = by - ay;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.01f) return;
		float th = Math.max(1f, thickness * state.scale);
		float nx = -dy / len * th / 2, ny = dx / len * th / 2;
		ensure(null);
		int c = color(color);
		vertex(ax - nx, ay - ny, 0, 0, len, th, 0, 0, 0, 0, c, c, FILL, 0, 0, 0, 0);
		vertex(ax + nx, ay + ny, 0, th, len, th, 0, 0, 0, 0, c, c, FILL, 0, 0, 0, 0);
		vertex(bx + nx, by + ny, len, th, len, th, 0, 0, 0, 0, c, c, FILL, 0, 0, 0, 0);
		vertex(bx - nx, by - ny, len, 0, len, th, 0, 0, 0, 0, c, c, FILL, 0, 0, 0, 0);
		include(Math.min(ax, bx) - th, Math.min(ay, by) - th, Math.max(ax, bx) + th, Math.max(ay, by) + th);
		quads++;
	}

	@Override
	public void texture(Identifier id, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint) {
		texture(mc.getTextureManager().getTexture(id).getTextureView(), x, y, w, h, u0, v0, u1, v1, tint);
	}

	@Override
	public void texture(GpuTextureView view, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint) {
		float s = state.scale;
		box(px(x), py(y), w * s, h * s, 0, 0, 0, 0, tint, tint, IMAGE, 0, 0, u0, v0, u1, v1, view);
	}

	@Override
	public void preview(Preview preview, float x, float y, float w, float h) {
		flush();
		int x0 = Math.round(px(x) / guiScale), y0 = Math.round(py(y) / guiScale);
		int x1 = Math.round(px(x + w) / guiScale), y1 = Math.round(py(y + h) / guiScale);
		if (x1 <= x0 || y1 <= y0) return;
		// Inside this renderer's clip and the screen's own.
		ScreenRectangle scissor = context.scissorStack.peek();
		if (clipping()) {
			int cx0 = Math.floorDiv(state.clipX0, guiScale), cy0 = Math.floorDiv(state.clipY0, guiScale);
			ScreenRectangle clip = new ScreenRectangle(cx0, cy0, -Math.floorDiv(-state.clipX1, guiScale) - cx0, -Math.floorDiv(-state.clipY1, guiScale) - cy0);
			scissor = scissor == null ? clip : scissor.intersection(clip);
			if (scissor == null) return;
		}
		context.guiRenderState.addPicturesInPictureState(PreviewRenderer.state(preview, x0, y0, x1, y1, state.scale / guiScale, scissor));
	}

	@Override
	public void item(ItemStack stack, float x, float y, float size, boolean overlay) {
		if (stack.isEmpty()) return;
		flush();
		var pose = context.pose();
		pose.pushMatrix();
		pose.translate(px(x) / guiScale, py(y) / guiScale);
		float k = size * state.scale / guiScale / 16f;
		pose.scale(k, k);
		boolean clipping = clipping();
		if (clipping) {
			context.enableScissor(Math.floorDiv(state.clipX0, guiScale), Math.floorDiv(state.clipY0, guiScale),
				-Math.floorDiv(-state.clipX1, guiScale), -Math.floorDiv(-state.clipY1, guiScale));
		}
		context.item(stack, 0, 0);
		if (overlay) context.itemDecorations(mc.font, stack, 0, 0);
		if (clipping) context.disableScissor();
		pose.popMatrix();
	}

	private void box(float x, float y, float w, float h, float tl, float tr, float br, float bl, int c1, int c2,
					 int type, float param, float angle, float u0, float v0, float u1, float v1, GpuTextureView tex) {
		if (w <= 0 || h <= 0 || culled(x, y, w, h)) return;
		c1 = color(c1);
		c2 = color(c2);
		if ((c1 >>> 24) == 0 && (c2 >>> 24) == 0) return;
		ensure(tex);
		vertex(x, y, 0, 0, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u0, v0);
		vertex(x, y + h, 0, h, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u0, v1);
		vertex(x + w, y + h, w, h, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u1, v1);
		vertex(x + w, y, w, 0, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u1, v0);
		include(x, y, x + w, y + h);
		quads++;
	}

	private void ensure(GpuTextureView tex) {
		if (!active) throw new IllegalStateException("Canvas used outside of UiRenderer.begin()/end()");
		if (tex != null && texture != null && tex != texture) flush();
		if (quads >= MAX_QUADS) flush();
		if (tex != null) texture = tex;
		int needed = size + 4 * FLOATS_PER_VERTEX;
		if (needed > data.length) data = Arrays.copyOf(data, Math.max(needed, data.length * 2));
	}

	private void include(float x0, float y0, float x1, float y1) {
		minX = Math.min(minX, Math.max(x0, state.clipX0));
		minY = Math.min(minY, Math.max(y0, state.clipY0));
		maxX = Math.max(maxX, Math.min(x1, state.clipX1));
		maxY = Math.max(maxY, Math.min(y1, state.clipY1));
	}

	private void vertex(float x, float y, float lx, float ly, float w, float h, float tl, float tr, float br, float bl,
						int c1, int c2, int type, float param, float angle, float u, float v) {
		float[] d = data;
		int i = size;
		d[i++] = x; d[i++] = y;
		d[i++] = lx; d[i++] = ly;
		d[i++] = w; d[i++] = h;
		d[i++] = tl; d[i++] = tr; d[i++] = br; d[i++] = bl;
		d[i++] = (c1 >> 16 & 255) / 255f; d[i++] = (c1 >> 8 & 255) / 255f; d[i++] = (c1 & 255) / 255f; d[i++] = (c1 >>> 24) / 255f;
		d[i++] = (c2 >> 16 & 255) / 255f; d[i++] = (c2 >> 8 & 255) / 255f; d[i++] = (c2 & 255) / 255f; d[i++] = (c2 >>> 24) / 255f;
		d[i++] = type; d[i++] = param; d[i++] = angle; d[i++] = 0;
		d[i++] = u; d[i++] = v;
		d[i++] = state.clipX0; d[i++] = state.clipY0; d[i++] = state.clipX1; d[i++] = state.clipY1;
		size = i;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Text

	private SizedFont font(FontFamily family, float size) {
		return fonts.get(family, Math.round(size * state.scale));
	}

	@Override
	public float text(String text, float x, float y, int color) {
		return text(defaultFont, defaultSize, text, x, y, color);
	}

	@Override
	public float text(FontFamily family, float size, String text, float x, float y, int color) {
		if (text == null || text.isEmpty()) return 0;
		SizedFont f = font(family, size);
		float startX = Math.round(px(x));
		float cx = startX;
		float top = Math.round(py(y));
		if (top > state.clipY1 || top + f.lineHeight < state.clipY0) return f.width(text) / state.scale;
		int base = color(color);
		int current = base;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '§' && i < text.length()) {
				ChatFormatting fmt = ChatFormatting.getByCode(text.charAt(i++));
				if (fmt == null || fmt == ChatFormatting.RESET) current = base;
				else if (TextColor.fromLegacyFormat(fmt) instanceof TextColor tc) current = (base & 0xFF000000) | tc.getValue();
				continue;
			}
			Glyph g = f.glyph(cp);
			if (g == null) continue;
			if (g.visible()) {
				float gx = cx + g.offsetX(), gy = top + g.offsetY();
				float gw = g.cellWidth(), gh = g.cellHeight();
				if (!culled(gx, gy, gw, gh)) {
					ensure(g.texture());
					vertex(gx, gy, 0, 0, gw, gh, 0, 0, 0, 0, current, current, GLYPH, 0, 0, g.u0(), g.v0());
					vertex(gx, gy + gh, 0, 0, gw, gh, 0, 0, 0, 0, current, current, GLYPH, 0, 0, g.u0(), g.v1());
					vertex(gx + gw, gy + gh, 0, 0, gw, gh, 0, 0, 0, 0, current, current, GLYPH, 0, 0, g.u1(), g.v1());
					vertex(gx + gw, gy, 0, 0, gw, gh, 0, 0, 0, 0, current, current, GLYPH, 0, 0, g.u1(), g.v0());
					include(gx, gy, gx + gw, gy + gh);
					quads++;
				}
			}
			cx += g.advance();
		}
		return (cx - startX) / state.scale;
	}

	@Override
	public float textWidth(String text) {
		return textWidth(defaultFont, defaultSize, text);
	}

	@Override
	public float textWidth(FontFamily family, float size, String text) {
		if (text == null || text.isEmpty()) return 0;
		return font(family, size).width(text) / state.scale;
	}

	@Override
	public float textHeight() {
		return textHeight(defaultFont, defaultSize);
	}

	@Override
	public float textHeight(FontFamily family, float size) {
		return font(family, size).lineHeight / state.scale;
	}

	@Override
	public FontFamily defaultFont() {
		return defaultFont;
	}

	@Override
	public float defaultFontSize() {
		return defaultSize;
	}

	// ------------------------------------------------------------------------------------------------------------
	// State

	@Override
	public void push() {
		stack.push(state);
		state = state.copy();
	}

	@Override
	public void pop() {
		state = stack.pop();
	}

	/** Stack depth, so callers can recover from a panel that threw mid-render. */
	public int depth() {
		return stack.size();
	}

	public void restoreDepth(int depth) {
		while (stack.size() > depth) pop();
	}

	/** Asks for the frame blur up front (call before drawing windows), so backdrops have it. */
	public void captureBlur() {
		if (blurEnabled) blur.request(blurPasses, blurOffset);
	}

	@Override
	public void translate(float x, float y) {
		state.tx += x * state.scale;
		state.ty += y * state.scale;
	}

	@Override
	public void scale(float s) {
		state.scale *= s;
	}

	@Override
	public float pixelScale() {
		return state.scale;
	}

	@Override
	public void clip(float x, float y, float w, float h) {
		float x0 = px(x), y0 = py(y), x1 = px(x + w), y1 = py(y + h);
		state.clipX0 = Math.max(state.clipX0, (int) Math.floor(x0));
		state.clipY0 = Math.max(state.clipY0, (int) Math.floor(y0));
		state.clipX1 = Math.min(state.clipX1, (int) Math.ceil(x1));
		state.clipY1 = Math.min(state.clipY1, (int) Math.ceil(y1));
	}

	@Override
	public void alpha(float alpha) {
		state.alpha *= Math.clamp(alpha, 0f, 1f);
	}

	@Override
	public GuiGraphicsExtractor drawContext() {
		return context;
	}

	@Override
	public float frameDeltaMs() {
		return frameDelta;
	}

	private boolean clipping() {
		return state.clipX0 > 0 || state.clipY0 > 0 || state.clipX1 < fbW || state.clipY1 < fbH;
	}

	@Override
	public void flush() {
		if (quads == 0) return;
		if (maxX > minX && maxY > minY) {
			int x0 = (int) Math.floor(minX / guiScale), y0 = (int) Math.floor(minY / guiScale);
			int x1 = (int) Math.ceil(maxX / guiScale), y1 = (int) Math.ceil(maxY / guiScale);
			GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			GpuTextureView blurView = usesBlur ? blur.output() : white;
			TextureSetup textures = TextureSetup.doubleTexture(texture != null ? texture : white, sampler, blurView, sampler);
			context.guiRenderState.addGuiElement(new UiBatch(Arrays.copyOf(data, size), quads * 4, textures,
				new ScreenRectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0))));
		}
		resetBatch();
	}

	private void resetBatch() {
		size = 0;
		quads = 0;
		texture = null;
		usesBlur = false;
		minX = minY = Float.MAX_VALUE;
		maxX = maxY = -Float.MAX_VALUE;
	}

	private static final class State {
		float tx, ty, scale = 1f, alpha = 1f;
		int clipX0, clipY0, clipX1, clipY1;

		State copy() {
			State s = new State();
			s.tx = tx;
			s.ty = ty;
			s.scale = scale;
			s.alpha = alpha;
			s.clipX0 = clipX0;
			s.clipY0 = clipY0;
			s.clipX1 = clipX1;
			s.clipY1 = clipY1;
			return s;
		}
	}
}
