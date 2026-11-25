package dev.myriadclient.impl.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.ui.BackdropEffect;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.impl.render.font.FontManager;
import dev.myriadclient.impl.render.font.Glyph;
import dev.myriadclient.impl.render.font.SizedFont;
import dev.myriadclient.impl.render.preview.PreviewRenderer;
import dev.myriadclient.impl.render.title.TitleSceneState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The implementation of {@link Canvas}. Shapes are signed-distance quads drawn by one shader (core/ui.vsh, ui.fsh) with
 * a fat vertex format. Quads are recorded into batches that break only on texture changes; each batch becomes one
 * element of vanilla's GUI render state (see {@link UiBatch}), so it draws in order with vanilla items and text.
 */
public final class UiRenderer implements Canvas {
	private static final int FILL = 0, OUTLINE = 1, SHADOW = 2, GLYPH = 3, IMAGE = 4, BACKDROP = 5, FROST = 6;
	private static final int FLOATS_PER_VERTEX = UiBatch.FLOATS_PER_VERTEX;
	private static final int MAX_QUADS = 16384;

	private static UiRenderer instance;

	private final Minecraft mc = Minecraft.getInstance();
	private final FontManager fonts = new FontManager();
	private final FrameBlur blur = new FrameBlur();
	/**
	 * Frost: a see-through widget on an effect background shows that background with its effect blurred. The effect is
	 * drawn over the whole screen once a frame ({@link #fxTarget}, from its {@code effectFull} pipeline) and blurred
	 * ({@link #fxBlur}); each frosted widget puts the background together again from it (ui.fsh's FROST).
	 */
	private final FrameBlur fxBlur = new FrameBlur();
	private @Nullable TextureTarget fxTarget;
	private @Nullable GpuBuffer fxInfo;
	private final java.util.Map<Integer, RenderPipeline> effectFullPipelines = new java.util.HashMap<>();
	/** Whether a widget was frosted this frame, so the effect is drawn and blurred before the GUI. */
	private boolean frostWanted;
	/** The effect background drawn last: its colour (unfaded) and how faded it was. */
	private int fxColor;
	private float fxOpacity = 1f;
	private final Deque<State> stack = new ArrayDeque<>();
	private final GpuTextureView white;

	private float[] data = new float[1024 * 4 * FLOATS_PER_VERTEX];
	private int size;
	private int quads;
	private GpuTextureView texture;
	private boolean usesBlur;
	/** The batch's frosted backgrounds, in GUI coordinates (see {@link UiBatch#backdrops}); null for none. */
	private @Nullable List<ScreenRectangle> batchBackdrops;
	private float minX, minY, maxX, maxY;
	/** The same without shadows: what a frosted background over this batch has to blur (a shadow under it needn't be). */
	private float solidMinX, solidMinY, solidMaxX, solidMaxY;
	private boolean active;

	// Frame state
	private GuiGraphicsExtractor context;
	private int fbW, fbH, guiScale;
	private boolean blurEnabled;
	private int blurPasses = 3;
	private float blurOffset = 2.5f;
	/** The pipeline of the batch being recorded, and the one the next quad wants (an effect's, for its backgrounds). */
	private RenderPipeline pipeline = MyriadPipelines.UI, wantPipeline = MyriadPipelines.UI;
	/** The background effect in use, or null; its pipelines by number, and the effects whose shader didn't compile. */
	private @org.jetbrains.annotations.Nullable BackdropEffect effect;
	private final java.util.Map<Integer, RenderPipeline> effectPipelines = new java.util.HashMap<>();
	private final java.util.Set<Integer> brokenEffects = new java.util.HashSet<>();
	/** The theme's colours and the effect's options, a texel each, for its shader (Sampler0); what they were last written as. */
	private @org.jetbrains.annotations.Nullable GpuTexture optionsTexture;
	private @org.jetbrains.annotations.Nullable GpuTextureView optionsView;
	private final int[] optionsWritten = new int[OPTION_TEXELS];
	private static final int OPTION_TEXELS = 4 + BackdropEffect.MAX_OPTIONS;
	/** The texture the pieces of the background being drawn take (an effect's options), or null. */
	private @org.jetbrains.annotations.Nullable GpuTextureView pieceTexture;
	/** Params.w of the vertices being written: 0, or a surface fill's flags. */
	private float quadFlags;
	/** The last backdrop drawn (framebuffer pixels), so a fill drawn right over it is taken as a window background; w < 0 for none. */
	private float bdX, bdY, bdW = -1, bdH;
	/** The effect background drawn last (fxW -1: none), and the fills frosted over it, as x0 y0 x1 y1 corner runs. */
	private float fxX, fxY, fxW = -1, fxH;
	private final it.unimi.dsi.fastutil.floats.FloatArrayList frosted = new it.unimi.dsi.fastutil.floats.FloatArrayList();
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
		if (frostWanted) {
			frostWanted = false;
			drawEffectFrame();
		}
		GpuGarbage.tick();
	}

	/** The effect over the whole screen, blurred, for this frame's frosted widgets (see {@link #fxBlur}). */
	private void drawEffectFrame() {
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (fxTarget == null || fxTarget.width != main.width || fxTarget.height != main.height) {
			TextureTarget old = fxTarget;
			fxTarget = new TextureTarget("Myriad effect frame", main.width, main.height, GpuFormat.RGBA8_UNORM, null);
			if (old != null) GpuGarbage.close(old::destroyBuffers);
		}
		if (fxInfo == null) fxInfo = RenderSystem.getDevice().createBuffer(() -> "Myriad effect info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
		BackdropEffect e = effect;
		RenderPipeline pipeline = e != null && optionsView != null ? effectFullPipelines.computeIfAbsent(e.number(), n -> MyriadPipelines.uiEffectFull(n, e.shader())) : null;
		var compiled = pipeline != null ? RenderSystem.getCompiledPipelineNullable(pipeline) : null;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(fxInfo.slice(), Std140Builder.onStack(stack, 16).putVec4(effectTime(), 0, 0, 0).get());
		}
		// Cleared, then the effect alone (premultiplied); one that didn't compile leaves it clear (a frosted widget shows the
		// background without it).
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad effect frame", fxTarget.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))) {
			if (compiled != null) {
				GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
				pass.setPipeline(compiled);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("FxInfo", fxInfo);
				pass.setUniform("Sampler0", optionsView, sampler);
				pass.setUniform("Sampler1", white, sampler);
				pass.draw(3, 1, 0, 0);
			}
		}
		fxBlur.from(fxTarget.getColorTextureView(), main.width, main.height);
		fxBlur.request(blurPasses, blurOffset);
		fxBlur.runIfRequested();
	}

	/**
	 * Blurs the screen as drawn so far, for the frosted backgrounds drawn next (GuiRendererMixin, between draws): those in
	 * {@code area} (GUI coordinates).
	 */
	public void blurAgain(ScreenRectangle area) {
		blur.again(area.left() * guiScale, area.top() * guiScale, area.right() * guiScale, area.bottom() * guiScale);
	}

	/**
	 * What of {@code element} a frosted background drawn over it would show blurred (GUI coordinates): one of this
	 * renderer's batches without its shadows (null if it's only shadows), anything else its bounds.
	 */
	public static @Nullable ScreenRectangle solidBounds(GuiElementRenderState element) {
		return element instanceof UiBatch b ? b.solid : element.bounds();
	}

	/** {@code element}'s frosted backgrounds in GUI coordinates if it's one of this renderer's batches with any, else null. */
	public static @Nullable List<ScreenRectangle> backdrops(GuiElementRenderState element) {
		return element instanceof UiBatch b ? b.backdrops : null;
	}

	/** This frame's blur is of {@code view}, a picture covering the screen ({@link FrameBlur#from}). */
	public void blurFrom(GpuTextureView view, int width, int height) {
		blur.from(view, width, height);
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

	/** Whether {@link #surface} fills have an effect now. */
	private boolean textured() {
		return effect != null;
	}

	/**
	 * The background effect of {@link #surface} fills and the menus' backdrop, or null for none; {@code colors} the
	 * theme's accent, secondary, text and window background, for its shader. Its options are written for it each frame
	 * they change.
	 */
	public void setEffect(@org.jetbrains.annotations.Nullable BackdropEffect effect, List<Setting<?>> options, int accent, int secondary,
			int text, int background, boolean moving) {
		this.effect = effect != null && effectPipeline(effect) != null ? effect : null;
		this.effectMoving = moving;
		if (this.effect == null) return;
		int[] texels = new int[OPTION_TEXELS];
		texels[0] = accent;
		texels[1] = secondary;
		texels[2] = text;
		texels[3] = background;
		for (int i = 0; i < options.size() && i < BackdropEffect.MAX_OPTIONS; i++) texels[4 + i] = optionTexel(options.get(i));
		writeOptions(texels);
	}

	/** Whether effects move (the theme's animations are on); off, their time stands still. */
	private boolean effectMoving = true;

	/** An effect's time: {@link #clock}, or 0 while it stands still. */
	private float effectTime() {
		return effectMoving ? clock() : 0;
	}

	/** Setting {@code s} as an option texel (ARGB): a number 0 to 1 across its range in red, a switch 1 or 0, a colour as it is, a choice its index / 255. */
	private static int optionTexel(Setting<?> s) {
		float x;
		switch (s) {
			case DoubleSetting d -> x = (float) ((d.get() - d.min()) / Math.max(1e-9, d.max() - d.min()));
			case IntSetting n -> x = (n.get() - n.min()) / (float) Math.max(1, n.max() - n.min());
			case BoolSetting b -> x = b.get() ? 1 : 0;
			case ColorSetting c -> {
				return c.argb();
			}
			case EnumSetting<?> e -> x = ((Enum<?>) e.get()).ordinal() / 255f;
			case ChoiceSetting c -> x = Math.max(0, c.options().indexOf(c.get())) / 255f;
			default -> x = 0;
		}
		int v = Math.round(Math.clamp(x, 0f, 1f) * 255);
		return 0xFF000000 | v << 16;
	}

	private void writeOptions(int[] texels) {
		if (optionsTexture != null && Arrays.equals(texels, optionsWritten)) return;
		var device = RenderSystem.getDevice();
		if (optionsTexture == null) {
			optionsTexture = device.createTexture("Myriad effect options", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
				GpuFormat.RGBA8_UNORM, OPTION_TEXELS, 1, 1, 1);
			optionsView = device.createTextureView(optionsTexture);
		}
		ByteBuffer px = MemoryUtil.memAlloc(OPTION_TEXELS * 4);
		try {
			for (int c : texels) px.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
			px.flip();
			device.createCommandEncoder().writeToTexture(optionsTexture, px, 0, 0, 0, 0, OPTION_TEXELS, 1);
		} finally {
			MemoryUtil.memFree(px);
		}
		System.arraycopy(texels, 0, optionsWritten, 0, OPTION_TEXELS);
	}

	/** {@code e}'s pipeline, compiled now if it hasn't been; null if its shader didn't compile (said once in the log). */
	private @org.jetbrains.annotations.Nullable RenderPipeline effectPipeline(BackdropEffect e) {
		if (brokenEffects.contains(e.number())) return null;
		RenderPipeline p = effectPipelines.computeIfAbsent(e.number(), n -> MyriadPipelines.uiEffect(n, e.shader()));
		if (RenderSystem.getCompiledPipelineNullable(p) != null) return p;
		brokenEffects.add(e.number());
		org.slf4j.LoggerFactory.getLogger("Myriad/UI").error("The background effect {} ({}) didn't compile (its errors are above); drawn as none", e.name(), e.shader());
		return null;
	}

	/** Resources reloaded: effects that didn't compile get another go. */
	public void effectsReloaded() {
		brokenEffects.clear();
		effectPipelines.clear();
		effectFullPipelines.clear();
	}

	/**
	 * A background that stays plain whatever the theme's effect (the bar, whose text has to read): a fill, even right over a
	 * {@link #backdrop} of its shape, and not a widget on the window drawn before it.
	 */
	public void plainSurface(float x, float y, float w, float h, int color) {
		plainOverBackdrop();
		rect(x, y, w, h, color);
	}

	/**
	 * The next fill over the {@link #backdrop} just drawn is a widget (a menu's frosted button, a panel over the game),
	 * not a window's background: it stays plain, whatever the theme's effect.
	 */
	public void plainOverBackdrop() {
		bdW = -1;
	}

	/** Seconds, going round every ten minutes: an effect's time. */
	private static float clock() {
		return (System.currentTimeMillis() % 600_000L) / 1000f;
	}

	/**
	 * A window or bar background: a fill in {@code color} with the theme's background effect ({@link #setEffect}), widgets
	 * drawn over it as over any background. On another canvas, or with no effect, a plain {@link Canvas#roundRect}. A fill drawn right over a
	 * {@link #backdrop} of its shape is taken as one of these by itself.
	 */
	public static void surface(Canvas c, float x, float y, float w, float h, float radius, int color) {
		if (c instanceof UiRenderer u && u.textured()) u.surface(x, y, w, h, radius, color);
		else c.roundRect(x, y, w, h, radius, color);
	}

	private void surface(float x, float y, float w, float h, float radius, int color) {
		float s = state.scale, r = radius * s;
		surfacePx(px(x), py(y), w * s, h * s, r, r, r, r, color);
	}

	/** A background with the effect, in framebuffer pixels (see {@link #surface(Canvas, float, float, float, float, float, int)}). */
	private void surfacePx(float x, float y, float w, float h, float tl, float tr, float br, float bl, int color) {
		bdW = -1;
		// Widgets drawn on it next frost over its effect (frostsOverEffect).
		fxX = x;
		fxY = y;
		fxW = textured() ? w : -1;
		fxH = h;
		frosted.clear();
		fxColor = color;
		fxOpacity = state.alpha;
		// The fill's own colour, unfaded; the fade (window opacity, animations) goes as Params.y.
		float opacity = state.alpha;
		state.alpha = 1f;
		// Params.z the effect's time; flags 1 textured.
		float seed = effectTime(), u = 0, v = 0;
		int flags = 1, c2 = color;
		// The effect's shader draws the background, whole: widgets draw over it as over any background.
		RenderPipeline fx = effect != null && optionsView != null ? effectPipeline(effect) : null;
		if (fx != null) {
			wantPipeline = fx;
			pieceTexture = optionsView;
		}
		quadFlags = flags;
		box(x, y, w, h, tl, tr, br, bl, color, c2, FILL, opacity, seed, u, v, u, v, pieceTexture);
		wantPipeline = MyriadPipelines.UI;
		pieceTexture = null;
		state.alpha = opacity;
		quadFlags = 0;
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
		bdW = -1;
		fxW = -1;
		frosted.clear();
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
		float s = state.scale, r = radius * s;
		if (blurEnabled) backdropPx(px(x), py(y), w * s, h * s, r, r, r, r, opacity);
		// Blurred or not, the fill drawn next in this shape is a window's background (see box).
		bdX = px(x);
		bdY = py(y);
		bdW = w * s;
		bdH = h * s;
	}

	/**
	 * A frosted background in framebuffer pixels: what's drawn under it, blurred. Over something already in this batch, it
	 * starts the next, so the blur it shows can take that in (the GUI's drawing is split there and blurred again, see
	 * GuiRendererMixin).
	 */
	private void backdropPx(float bx, float by, float bw, float bh, float tl, float tr, float br, float bl, float opacity) {
		if (quads > 0 && bx < maxX && minX < bx + bw && by < maxY && minY < by + bh) flush();
		blur.request(blurPasses, blurOffset);
		usesBlur = true;
		int c = (Math.round(Math.clamp(opacity, 0f, 1f) * 255) << 24) | 0xFFFFFF;
		int quadsBefore = quads;
		box(bx, by, bw, bh, tl, tr, br, bl, c, c, BACKDROP, 0, 0, 0, 0, 0, 0, null);
		if (quads == quadsBefore) return;
		float x0 = Math.max(bx, state.clipX0), y0 = Math.max(by, state.clipY0), x1 = Math.min(bx + bw, state.clipX1), y1 = Math.min(by + bh, state.clipY1);
		int gx0 = (int) Math.floor(x0 / guiScale), gy0 = (int) Math.floor(y0 / guiScale);
		int gx1 = (int) Math.ceil(x1 / guiScale), gy1 = (int) Math.ceil(y1 / guiScale);
		if (gx1 <= gx0 || gy1 <= gy0) return;
		if (batchBackdrops == null) batchBackdrops = new ArrayList<>(4);
		batchBackdrops.add(new ScreenRectangle(gx0, gy0, gx1 - gx0, gy1 - gy0));
	}

	/**
	 * A see-through fill lying straight on the effect background just drawn (a row, a card, a button) shows the effect
	 * blurred through it, as an element with a backdrop filter would, rather than sharp ({@link #frostPx}). Not one over
	 * another such fill (a hover tint, a bar on a row), nor a thin line.
	 */
	private boolean frostsOverEffect(float x, float y, float w, float h, float radius, int color) {
		int alpha = color >>> 24;
		if (!blurEnabled || fxW < 0 || alpha == 0 || alpha == 255 || w < 6 || h < 6) return false;
		// What shows of it (a row scrolled half out of a list frosts all the way).
		float x1 = Math.min(x + w, state.clipX1), y1 = Math.min(y + h, state.clipY1);
		x = Math.max(x, state.clipX0);
		y = Math.max(y, state.clipY0);
		w = x1 - x;
		h = y1 - y;
		if (w <= 0 || h <= 0) return false;
		if (x < fxX - 0.5f || y < fxY - 0.5f || x + w > fxX + fxW + 0.5f || y + h > fxY + fxH + 0.5f) return false;
		// Over another by their shapes, not their boxes: a wheel's buttons sit round its disc, inside the disc's box.
		for (int i = 0; i < frosted.size(); i += 5) {
			if (roundedOverlap(x, y, x + w, y + h, radius, frosted.getFloat(i), frosted.getFloat(i + 1), frosted.getFloat(i + 2), frosted.getFloat(i + 3),
				frosted.getFloat(i + 4))) return false;
		}
		frosted.add(x);
		frosted.add(y);
		frosted.add(x + w);
		frosted.add(y + h);
		frosted.add(radius);
		return true;
	}

	/**
	 * Whether two rounded boxes (corners {@code ra}, {@code rb}) overlap: their boxes shrunk by their corners are closer
	 * than the corners add up to. Exact for circles and evenly rounded boxes.
	 */
	static boolean roundedOverlap(float ax0, float ay0, float ax1, float ay1, float ra, float bx0, float by0, float bx1, float by1, float rb) {
		ra = Math.max(0, Math.min(ra, Math.min(ax1 - ax0, ay1 - ay0) / 2));
		rb = Math.max(0, Math.min(rb, Math.min(bx1 - bx0, by1 - by0) / 2));
		float dx = Math.max(0, Math.max((bx0 + rb) - (ax1 - ra), (ax0 + ra) - (bx1 - rb)));
		float dy = Math.max(0, Math.max((by0 + rb) - (ay1 - ra), (ay0 + ra) - (by1 - rb)));
		float reach = ra + rb - 0.5f;
		return reach > 0 ? dx * dx + dy * dy < reach * reach : dx == 0 && dy == 0;
	}

	/** The effect background under a widget, again, with its effect blurred (see {@link #fxBlur}). */
	private void frostPx(float x, float y, float w, float h, float tl, float tr, float br, float bl) {
		frostWanted = true;
		blur.request(blurPasses, blurOffset);
		// The background's own colour and fade, as it was drawn.
		float alpha = state.alpha;
		state.alpha = 1f;
		box(x, y, w, h, tl, tr, br, bl, fxColor, fxColor, FROST, fxOpacity, 0, 0, 0, 0, 0, fxBlur.output());
		state.alpha = alpha;
		usesBlur = true;
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

	/** The title screen's scene, filling (x, y, w, h). */
	public void titleScene(TitleSceneState.View view, float x, float y, float w, float h) {
		flush();
		int x0 = Math.round(px(x) / guiScale), y0 = Math.round(py(y) / guiScale);
		int x1 = Math.round(px(x + w) / guiScale), y1 = Math.round(py(y + h) / guiScale);
		if (x1 <= x0 || y1 <= y0) return;
		context.guiRenderState.addPicturesInPictureState(TitleSceneState.of(view, x0, y0, x1, y1, context.scissorStack.peek()));
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
		if (type == FILL && quadFlags == 0 && tex == null && c1 == c2) {
			// A fill drawn right over a backdrop of its shape is a window's background, whoever draws it: textured.
			if (bdW >= 0 && textured() && Math.abs(x - bdX) < 0.5f && Math.abs(y - bdY) < 0.5f && Math.abs(w - bdW) < 0.5f && Math.abs(h - bdH) < 0.5f) {
				surfacePx(x, y, w, h, tl, tr, br, bl, c1);
				return;
			}
			bdW = -1;
			if (frostsOverEffect(x, y, w, h, Math.min(Math.min(tl, tr), Math.min(br, bl)), c1)) frostPx(x, y, w, h, tl, tr, br, bl);
		}
		c1 = color(c1);
		c2 = color(c2);
		if ((c1 >>> 24) == 0 && (c2 >>> 24) == 0) return;
		ensure(tex);
		vertex(x, y, 0, 0, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u0, v0);
		vertex(x, y + h, 0, h, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u0, v1);
		vertex(x + w, y + h, w, h, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u1, v1);
		vertex(x + w, y, w, 0, w, h, tl, tr, br, bl, c1, c2, type, param, angle, u1, v0);
		if (type == SHADOW) includeShadow(x, y, x + w, y + h);
		else include(x, y, x + w, y + h);
		quads++;
	}

	private void ensure(GpuTextureView tex) {
		if (!active) throw new IllegalStateException("Canvas used outside of UiRenderer.begin()/end()");
		if (wantPipeline != pipeline) {
			flush();
			pipeline = wantPipeline;
		}
		if (tex != null && texture != null && tex != texture) flush();
		if (quads >= MAX_QUADS) flush();
		if (tex != null) texture = tex;
		int needed = size + 4 * FLOATS_PER_VERTEX;
		if (needed > data.length) data = Arrays.copyOf(data, Math.max(needed, data.length * 2));
	}

	private void include(float x0, float y0, float x1, float y1) {
		includeShadow(x0, y0, x1, y1);
		solidMinX = Math.min(solidMinX, Math.max(x0, state.clipX0));
		solidMinY = Math.min(solidMinY, Math.max(y0, state.clipY0));
		solidMaxX = Math.max(solidMaxX, Math.min(x1, state.clipX1));
		solidMaxY = Math.max(solidMaxY, Math.min(y1, state.clipY1));
	}

	private void includeShadow(float x0, float y0, float x1, float y1) {
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
		d[i++] = type; d[i++] = param; d[i++] = angle; d[i++] = quadFlags;
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

	/**
	 * How far right of the middle of its advance {@code text}'s ink is centred, in UI units: an icon glyph's drawing often
	 * sits off its advance, so one centred by {@link #textWidth} looks a pixel or two off. Subtract it to centre the ink.
	 */
	public float inkOffset(FontFamily family, float size, String text) {
		if (text == null || text.isEmpty()) return 0;
		SizedFont f = font(family, size);
		float pen = 0, left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			Glyph g = f.glyph(cp);
			if (g == null) continue;
			// The cell is the ink with the same padding all round, so its middle is the ink's.
			if (g.visible()) {
				left = Math.min(left, pen + g.offsetX());
				right = Math.max(right, pen + g.offsetX() + g.cellWidth());
			}
			pen += g.advance();
		}
		return left > right ? 0 : ((left + right) / 2 - pen / 2) / state.scale;
	}

	/**
	 * How far below the middle of its line {@code text}'s ink is centred, in UI units (an icon glyph's drawing needn't
	 * sit in the middle of the line either). Subtract it to centre the ink.
	 */
	public float inkOffsetY(FontFamily family, float size, String text) {
		if (text == null || text.isEmpty()) return 0;
		SizedFont f = font(family, size);
		float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			Glyph g = f.glyph(cp);
			if (g == null || !g.visible()) continue;
			top = Math.min(top, g.offsetY());
			bottom = Math.max(bottom, g.offsetY() + g.cellHeight());
		}
		return top > bottom ? 0 : ((top + bottom) / 2 - f.lineHeight / 2f) / state.scale;
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
			ScreenRectangle solid = null;
			if (solidMaxX > solidMinX && solidMaxY > solidMinY) {
				int sx0 = (int) Math.floor(solidMinX / guiScale), sy0 = (int) Math.floor(solidMinY / guiScale);
				int sx1 = (int) Math.ceil(solidMaxX / guiScale), sy1 = (int) Math.ceil(solidMaxY / guiScale);
				solid = new ScreenRectangle(sx0, sy0, Math.max(1, sx1 - sx0), Math.max(1, sy1 - sy0));
			}
			context.guiRenderState.addGuiElement(new UiBatch(Arrays.copyOf(data, size), quads * 4, textures,
				new ScreenRectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)), pipeline, batchBackdrops, solid));
		}
		resetBatch();
	}

	private void resetBatch() {
		size = 0;
		quads = 0;
		texture = null;
		usesBlur = false;
		batchBackdrops = null;
		minX = minY = Float.MAX_VALUE;
		solidMinX = solidMinY = Float.MAX_VALUE;
		solidMaxX = solidMaxY = -Float.MAX_VALUE;
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
