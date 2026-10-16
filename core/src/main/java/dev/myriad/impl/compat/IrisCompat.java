package dev.myriad.impl.compat;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * With a shader pack on, Iris draws the world with the pack's programs, and a pipeline it doesn't know keeps vanilla's
 * shader: drawn into the pack's buffers, the pack's later passes paint over it (Tracers vanished). Iris's API assigns
 * a pipeline one of the pack's programs instead, drawn like vanilla's own lines and shapes. Found reflectively, so
 * Myriad needn't depend on Iris.
 */
public final class IrisCompat {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Iris");

	private IrisCompat() {
	}

	/** Has Iris draw {@code pipeline} with the pack's program {@code program} (an {@code IrisProgram} name, e.g. LINES). */
	public static void assign(RenderPipeline pipeline, String program) {
		if (!FabricLoader.getInstance().isModLoaded("iris")) return;
		try {
			ClassLoader loader = IrisCompat.class.getClassLoader();
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", true, loader);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object irisProgram = Enum.valueOf((Class) Class.forName("net.irisshaders.iris.api.v0.IrisProgram", true, loader), program);
			Object instance = api.getMethod("getInstance").invoke(null);
			api.getMethod("assignPipeline", RenderPipeline.class, irisProgram.getClass()).invoke(instance, pipeline, irisProgram);
		} catch (ReflectiveOperationException | LinkageError | IllegalArgumentException e) {
			LOG.warn("Iris is installed but its API changed; {} may not show with a shader pack", pipeline.getLocation(), e);
		}
	}
}
