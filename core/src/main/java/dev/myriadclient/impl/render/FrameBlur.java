package dev.myriadclient.impl.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Blurs the scene once per frame (dual Kawase: downsample N levels, upsample back to half resolution). Every frosted
 * window samples the same texture instead of copying and blurring once per window.
 * <p>
 * The UI is recorded before anything is drawn, so windows ask for the blur with {@link #request} while they record,
 * and {@link #runIfRequested} computes it from the rendered world just before the GUI draws. A frosted background drawn
 * over other UI has it computed again from the screen as drawn so far ({@link #again}), so it blurs what's under it.
 */
public final class FrameBlur {
	private static final int MAX_LEVELS = 6;
	private static final int UBO_SIZE = new Std140SizeCalculator().putVec2().putFloat().putFloat().get();

	private final TextureTarget[] levels = new TextureTarget[MAX_LEVELS + 1];
	/** Where {@link #again} downsamples to first (level 0's size), so level 0 only changes where it's redone. */
	private @Nullable TextureTarget scratch;
	private final GpuBuffer[] info = new GpuBuffer[MAX_LEVELS * 2 + 1];
	/** What each of {@link #info} holds now (half texel x, y, offset, opaque). */
	private final float[][] written = new float[MAX_LEVELS * 2 + 1][];
	private int width, height;
	private boolean requested, ran;
	private int passes = 3;
	private float offset = 2.5f;
	private @Nullable GpuTextureView source;
	private int sourceWidth, sourceHeight;

	/** This frame's blur is of {@code view}, a picture covering the screen, as the screen itself has nothing in it yet. */
	public void from(GpuTextureView view, int width, int height) {
		source = view;
		sourceWidth = width;
		sourceHeight = height;
	}

	/** The texture windows sample; valid for this frame once the blur has been requested. */
	public GpuTextureView output() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		resize(main.width, main.height);
		return levels[0].getColorTextureView();
	}

	/**
	 * Asks for the blur this frame.
	 *
	 * @param passes 1..6, more = wider blur
	 * @param offset sample spread per pass
	 */
	public void request(int passes, float offset) {
		requested = true;
		this.passes = Math.clamp(passes, 1, MAX_LEVELS);
		this.offset = offset;
	}

	/** Blurs the main target into {@link #output()} if a window asked for it this frame. Called before the GUI draws. */
	public void runIfRequested() {
		GpuTextureView picture = source;
		source = null;
		ran = requested;
		if (!requested) return;
		requested = false;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		resize(main.width, main.height);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Level 0 starts as the scene at half resolution; a down pass from the main target does that.
		// A picture's see-through parts stay so (what's under a frosted window shows there); the world's alpha means nothing.
		if (picture != null) pass(encoder, MyriadPipelines.BLUR_DOWN, picture, sourceWidth, sourceHeight, levels[0], 0, false);
		else pass(encoder, MyriadPipelines.BLUR_DOWN, main.getColorTextureView(), main.width, main.height, levels[0], 0, true);
		int ubo = 1;
		for (int i = 0; i < passes; i++) pass(encoder, MyriadPipelines.BLUR_DOWN, levels[i].getColorTextureView(), levels[i].width, levels[i].height, levels[i + 1], ubo++, false);
		for (int i = passes; i > 0; i--) pass(encoder, MyriadPipelines.BLUR_UP, levels[i].getColorTextureView(), levels[i].width, levels[i].height, levels[i - 1], ubo++, false);
	}

	/**
	 * Blurs the main target as drawn so far into {@link #output()} (between parts of the GUI's drawing), if the blur ran
	 * this frame: only where it's about to be shown, framebuffer pixels {@code x0..x1}, {@code y0..y1} from the top left
	 * (and as far round it as the blur reaches); elsewhere the output keeps the blur from before.
	 */
	public void again(int x0, int y0, int x1, int y1) {
		if (!ran) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		resize(main.width, main.height);
		// How far a pixel of the result reaches into the levels: each level halves, and each pass samples about
		// offset + 1 of its pixels away.
		int reach = (int) Math.ceil((offset + 2) * (1 << (passes + 1)));
		int ex0 = Math.max(0, x0), ey0 = Math.max(0, y0), ex1 = Math.min(main.width, x1), ey1 = Math.min(main.height, y1);
		x0 = Math.max(0, x0 - reach);
		y0 = Math.max(0, y0 - reach);
		x1 = Math.min(main.width, x1 + reach);
		y1 = Math.min(main.height, y1 + reach);
		if (x1 <= x0 || y1 <= y0) return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		area = new int[]{x0, y0, x1, y1};
		int[] exact = {ex0, ey0, ex1, ey1};
		try {
			pass(encoder, MyriadPipelines.BLUR_DOWN, main.getColorTextureView(), main.width, main.height, scratch, 0, true);
			int ubo = 1;
			for (int i = 0; i < passes; i++) {
				TextureTarget from = i == 0 ? scratch : levels[i];
				pass(encoder, MyriadPipelines.BLUR_DOWN, from.getColorTextureView(), from.width, from.height, levels[i + 1], ubo++, false);
			}
			for (int i = passes; i > 0; i--) {
				// Level 0 only where it's asked for: around it, what's there stays the blur that was (a widget there may still
				// be drawn on it).
				if (i == 1) area = exact;
				pass(encoder, MyriadPipelines.BLUR_UP, levels[i].getColorTextureView(), levels[i].width, levels[i].height, levels[i - 1], ubo++, false);
			}
		} finally {
			area = null;
		}
	}

	/** While {@link #again} runs: the part redone, full-resolution pixels x0 y0 x1 y1 from the top left. */
	private int @Nullable [] area;

	private void pass(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView from, int fromW, int fromH, TextureTarget to, int uboIndex, boolean opaque) {
		int slot = uboIndex % info.length;
		GpuBuffer ubo = info[slot];
		// Written only when what it holds changes: the same passes run several times a frame (again), and writing a buffer
		// the GPU may still be reading from the last run makes the driver wait for it.
		float[] want = {0.5f / fromW, 0.5f / fromH, offset, opaque ? 1 : 0};
		if (!java.util.Arrays.equals(written[slot], want)) {
			written[slot] = want;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec2(want[0], want[1]).putFloat(want[2]).putFloat(want[3]).get();
				encoder.writeToBuffer(ubo.slice(), data);
			}
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad blur", to.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("BlurInfo", ubo);
			pass.setUniform("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			if (area != null) {
				// The same part of this level (each is a fraction of full size), a pixel over; scissors count from the bottom.
				float sx = (float) to.width / width, sy = (float) to.height / height;
				int ax0 = Math.max(0, (int) Math.floor(area[0] * sx) - 1), ax1 = Math.min(to.width, (int) Math.ceil(area[2] * sx) + 1);
				int ay0 = Math.max(0, (int) Math.floor(area[1] * sy) - 1), ay1 = Math.min(to.height, (int) Math.ceil(area[3] * sy) + 1);
				pass.enableScissor(ax0, to.height - ay1, Math.max(0, ax1 - ax0), Math.max(0, ay1 - ay0));
			}
			pass.draw(3, 1, 0, 0);
		}
	}

	private void resize(int fbW, int fbH) {
		if (fbW == width && fbH == height && levels[0] != null) return;
		width = fbW;
		height = fbH;
		for (int i = 0; i <= MAX_LEVELS; i++) {
			int w = Math.max(1, fbW >> (i + 1)), h = Math.max(1, fbH >> (i + 1));
			// New targets rather than resizing these: this frame's UI may already have recorded draws sampling the old
			// view (the window was resized between recording and drawing), so it's freed a few frames late.
			TextureTarget old = levels[i];
			levels[i] = new TextureTarget("Myriad blur " + i, w, h, GpuFormat.RGBA8_UNORM, null);
			if (old != null) GpuGarbage.close(old::destroyBuffers);
		}
		TextureTarget oldScratch = scratch;
		scratch = new TextureTarget("Myriad blur scratch", levels[0].width, levels[0].height, GpuFormat.RGBA8_UNORM, null);
		if (oldScratch != null) GpuGarbage.close(oldScratch::destroyBuffers);
		for (int i = 0; i < info.length; i++) {
			if (info[i] == null) {
				info[i] = RenderSystem.getDevice().createBuffer(() -> "Myriad blur info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
			}
		}
	}
}
