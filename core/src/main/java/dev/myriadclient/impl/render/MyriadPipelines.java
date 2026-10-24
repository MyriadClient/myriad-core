package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.myriadclient.impl.compat.IrisCompat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Myriad's render pipelines and the vertex format of the UI renderer. Registered with vanilla's static pipelines so
 * they compile with the shaders on every resource reload.
 */
public final class MyriadPipelines {
	/** One UI quad corner: see {@code shaders/core/ui.vsh}. Only Position is a vanilla attribute; the rest Myriad writes. */
	public static final VertexFormat UI_FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Local", GpuFormat.RG32_FLOAT)
		.addAttribute("Size", GpuFormat.RG32_FLOAT)
		.addAttribute("Radii", GpuFormat.RGBA32_FLOAT)
		.addAttribute("Color1", GpuFormat.RGBA32_FLOAT)
		.addAttribute("Color2", GpuFormat.RGBA32_FLOAT)
		.addAttribute("Params", GpuFormat.RGBA32_FLOAT)
		.addAttribute("TexCoord", GpuFormat.RG32_FLOAT)
		.addAttribute("Clip", GpuFormat.RGBA32_FLOAT)
		.build();

	public static final RenderPipeline UI = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(id("pipeline/ui"))
		.withVertexShader(id("core/ui"))
		.withFragmentShader(id("core/ui"))
		.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
		.withVertexBinding(0, UI_FORMAT)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withCull(false)
		.build());

	private static final BindGroupLayout BLUR_LAYOUT = BindGroupLayout.builder()
		.withSampler("InSampler")
		.withUniform("BlurInfo", UniformType.UNIFORM_BUFFER)
		.build();

	public static final RenderPipeline BLUR_DOWN = RenderPipelines.register(blur("blur_down"));
	public static final RenderPipeline BLUR_UP = RenderPipelines.register(blur("blur_up"));

	// World shapes. Depth is reversed in 26.x (1 is near), so "in front" is GREATER_THAN_OR_EQUAL and a positive bias
	// pulls shapes towards the camera, keeping faces and edges that lie on a block face from z-fighting with it: what a
	// module or addon draws needs no nudging of its own. As much as vanilla gives its decals (block cracks).
	private static final DepthStencilState WORLD_DEPTH = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1f, 10f);
	private static final DepthStencilState THROUGH_WALLS = new DepthStencilState(CompareOp.ALWAYS_PASS, false);

	public static final RenderPipeline WORLD_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/world_quads")).withDepthStencilState(WORLD_DEPTH).build());
	public static final RenderPipeline WORLD_QUADS_XRAY = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/world_quads_xray")).withDepthStencilState(THROUGH_WALLS).build());
	// Shape builders' faces (boxes): wound anticlockwise seen from outside, so the back ones are culled and a see-through
	// box shows one layer of fill, not its front and back faces stacked.
	public static final RenderPipeline WORLD_FACES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/world_faces")).withDepthStencilState(WORLD_DEPTH).withCull(true).build());
	public static final RenderPipeline WORLD_FACES_XRAY = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/world_faces_xray")).withDepthStencilState(THROUGH_WALLS).withCull(true).build());
	public static final RenderPipeline WORLD_LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(id("pipeline/world_lines")).withDepthStencilState(WORLD_DEPTH).build());
	public static final RenderPipeline WORLD_LINES_XRAY = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(id("pipeline/world_lines_xray")).withDepthStencilState(THROUGH_WALLS).build());

	// Retained meshes (WorldMesh, ChunkCache): the same shapes, but the vertex shaders add ModelOffset (the mesh origin
	// relative to the camera), so one GPU buffer is drawn frame after frame without being rebuilt. Their quads come from
	// a MeshBuilder, so they're faces too.
	public static final RenderPipeline MESH_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/mesh_quads")).withVertexShader(id("core/mesh_color")).withDepthStencilState(WORLD_DEPTH).withCull(true).build());
	public static final RenderPipeline MESH_QUADS_XRAY = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/mesh_quads_xray")).withVertexShader(id("core/mesh_color")).withDepthStencilState(THROUGH_WALLS).withCull(true).build());
	public static final RenderPipeline MESH_LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(id("pipeline/mesh_lines")).withVertexShader(id("core/mesh_lines")).withDepthStencilState(WORLD_DEPTH).build());
	public static final RenderPipeline MESH_LINES_XRAY = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(id("pipeline/mesh_lines_xray")).withVertexShader(id("core/mesh_lines")).withDepthStencilState(THROUGH_WALLS).build());

	// Entity models in a flat colour (Chams, ESP): submitted in the entity vertex format, the shader only reads position
	// and colour.
	public static final RenderPipeline MODEL_FILL = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/model_fill")).withVertexBinding(0, DefaultVertexFormat.ENTITY)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)).build());
	public static final RenderPipeline MODEL_FILL_XRAY = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(id("pipeline/model_fill_xray")).withVertexBinding(0, DefaultVertexFormat.ENTITY).withDepthStencilState(THROUGH_WALLS).build());

	// Highlights (see HighlightRenderer). Shapes draw their highlight id into vanilla's entity outline target, like
	// glowing entities do, nearest first; the passes after read it back as a mask.
	public static final RenderPipeline HIGHLIGHT_SHAPES_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(id("pipeline/highlight_shapes"))
		.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
		.withVertexShader("core/position_color")
		.withFragmentShader("core/position_color")
		.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withCull(false)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build());
	// Drawn in the frame's outline pass (IS_OUTLINE), into Myriad's own shapes mask rather than vanilla's entity one.
	public static final RenderType HIGHLIGHT_SHAPES = RenderType.create("myriad_highlight_shapes", RenderSetup.builder(HIGHLIGHT_SHAPES_PIPELINE)
		.setOutputTarget(new OutputTarget("myriad_highlight_shapes", () -> HighlightRenderer.INSTANCE.shapeMask()))
		.setOutline(RenderSetup.OutlineProperty.IS_OUTLINE).createRenderSetup());
	/** The same, into the mask of shapes drawn below the others ({@code RenderEvent#below()}). */
	public static final RenderType HIGHLIGHT_SHAPES_BELOW = RenderType.create("myriad_highlight_shapes_below", RenderSetup.builder(HIGHLIGHT_SHAPES_PIPELINE)
		.setOutputTarget(new OutputTarget("myriad_highlight_shapes_below", () -> HighlightRenderer.INSTANCE.belowMask()))
		.setOutline(RenderSetup.OutlineProperty.IS_OUTLINE).createRenderSetup());

	private static final BindGroupLayout HIGHLIGHT_DATA = BindGroupLayout.builder()
		.withUniform("Highlights", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
		.build();
	// Retained meshes into the shapes mask: positions relative to the mesh origin (ModelOffset), the highlight id from
	// the colour modulator plus each vertex's palette index (highlight_mask.fsh).
	public static final RenderPipeline HIGHLIGHT_MESH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(id("pipeline/highlight_mesh"))
		.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
		.withBindGroupLayout(HIGHLIGHT_DATA)
		.withVertexShader(id("core/mesh_color"))
		.withFragmentShader(id("core/highlight_mask"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withCull(false)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build());
	public static final RenderPipeline HIGHLIGHT_RESOLVE = RenderPipelines.register(highlight("highlight_resolve",
		BindGroupLayout.builder().withSampler("Mask").withSampler("MaskDepth").withSampler("SceneDepth").build(), ColorTargetState.DEFAULT));
	public static final RenderPipeline HIGHLIGHT_SPREAD = RenderPipelines.register(highlight("highlight_spread",
		BindGroupLayout.builder().withSampler("InSampler").build(), new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_UNORM, ColorTargetState.WRITE_ALL)));
	public static final RenderPipeline HIGHLIGHT_GLOW_ROW = RenderPipelines.register(highlight("highlight_glow_row",
		BindGroupLayout.builder().withSampler("InSampler").build(), new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_UNORM, ColorTargetState.WRITE_ALL)));
	public static final RenderPipeline HIGHLIGHT_GLOW_COLUMN = RenderPipelines.register(highlight("highlight_glow_column",
		BindGroupLayout.builder().withSampler("InSampler").build(), new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_UNORM, ColorTargetState.WRITE_ALL)));
	public static final RenderPipeline HIGHLIGHT_COMPOSITE = RenderPipelines.register(highlight("highlight_composite",
		BindGroupLayout.builder().withSampler("SpreadSampler").withSampler("GlowSampler").build(), new ColorTargetState(BlendFunction.TRANSLUCENT)));

	public static final RenderType QUADS = RenderType.create("myriad_quads", RenderSetup.builder(WORLD_QUADS).sortOnUpload().createRenderSetup());
	public static final RenderType QUADS_XRAY = RenderType.create("myriad_quads_xray", RenderSetup.builder(WORLD_QUADS_XRAY).sortOnUpload().createRenderSetup());
	public static final RenderType FACES = RenderType.create("myriad_faces", RenderSetup.builder(WORLD_FACES).sortOnUpload().createRenderSetup());
	public static final RenderType FACES_XRAY = RenderType.create("myriad_faces_xray", RenderSetup.builder(WORLD_FACES_XRAY).sortOnUpload().createRenderSetup());
	public static final RenderType LINES = RenderType.create("myriad_lines", RenderSetup.builder(WORLD_LINES).createRenderSetup());
	public static final RenderType LINES_XRAY = RenderType.create("myriad_lines_xray", RenderSetup.builder(WORLD_LINES_XRAY).createRenderSetup());
	public static final RenderType MODEL = RenderType.create("myriad_model_fill", RenderSetup.builder(MODEL_FILL).createRenderSetup());
	public static final RenderType MODEL_XRAY = RenderType.create("myriad_model_fill_xray", RenderSetup.builder(MODEL_FILL_XRAY).createRenderSetup());

	private MyriadPipelines() {
	}

	/** Loads the class, registering the pipelines; call before the first resource reload. */
	public static void init() {
		// Shapes and lines drawn in the world (Tracers, boxes) as a shader pack draws vanilla's.
		IrisCompat.assign(WORLD_LINES, "LINES");
		IrisCompat.assign(WORLD_LINES_XRAY, "LINES");
		IrisCompat.assign(WORLD_QUADS, "BASIC");
		IrisCompat.assign(WORLD_QUADS_XRAY, "BASIC");
		IrisCompat.assign(WORLD_FACES, "BASIC");
		IrisCompat.assign(WORLD_FACES_XRAY, "BASIC");
	}

	private static RenderPipeline blur(String name) {
		return RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(id("pipeline/" + name))
			.withVertexShader(id("core/blur"))
			.withFragmentShader(id("core/" + name))
			.withBindGroupLayout(BLUR_LAYOUT)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
	}

	/** An addon's fill: the composite pass with its fragment shader, for highlights whose fill is {@code shaderId}. */
	public static RenderPipeline highlightFill(int shaderId, Identifier shader) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(id("pipeline/highlight_fill_" + shaderId))
			.withVertexShader(id("core/blur"))
			.withFragmentShader(shader)
			.withShaderDefine("MYRIAD_FILL_ID", shaderId)
			.withBindGroupLayout(BindGroupLayout.builder().withSampler("SpreadSampler").withSampler("GlowSampler").build())
			.withBindGroupLayout(HIGHLIGHT_DATA)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build());
	}

	/** A full-screen pass over a highlight mask (scissored to the highlights by the caller). */
	private static RenderPipeline highlight(String name, BindGroupLayout samplers, ColorTargetState target) {
		return RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(id("pipeline/" + name))
			.withVertexShader(id("core/blur"))
			.withFragmentShader(id("core/" + name))
			.withBindGroupLayout(samplers)
			.withBindGroupLayout(HIGHLIGHT_DATA)
			.withColorTargetState(target)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("myriad", path);
	}
}
