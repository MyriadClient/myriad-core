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
 * and {@link #runIfRequested} computes it from the rendered world just before the GUI draws.
 */
public final class FrameBlur {
	private static final int MAX_LEVELS = 6;
	private static final int UBO_SIZE = new Std140SizeCalculator().putVec2().putFloat().putFloat().get();

	private final TextureTarget[] levels = new TextureTarget[MAX_LEVELS + 1];
	private final GpuBuffer[] info = new GpuBuffer[MAX_LEVELS * 2 + 1];
	private int width, height;
	private boolean requested;
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

	private void pass(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView from, int fromW, int fromH, TextureTarget to, int uboIndex, boolean opaque) {
		GpuBuffer ubo = info[uboIndex % info.length];
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec2(0.5f / fromW, 0.5f / fromH).putFloat(offset).putFloat(opaque ? 1 : 0).get();
			encoder.writeToBuffer(ubo.slice(), data);
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad blur", to.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("BlurInfo", ubo);
			pass.setUniform("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
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
		for (int i = 0; i < info.length; i++) {
			if (info[i] == null) {
				info[i] = RenderSystem.getDevice().createBuffer(() -> "Myriad blur info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
			}
		}
	}
}
