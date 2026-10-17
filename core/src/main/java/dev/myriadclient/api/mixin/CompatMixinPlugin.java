package dev.myriadclient.api.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Applies mixins only when another mod is (or isn't) installed, decided by package name, so one addon can carry
 * hooks for Sodium, Iris, Lithium or Baritone without crashing when they're missing.
 *
 * <p>Name it as your mixin config's plugin, then put mod-specific mixins under a {@code compat.<mod id>} package
 * (dashes in mod ids become underscores). {@code compat.no_<mod id>} applies only when the mod is absent, for the
 * vanilla version of a hook the other mod replaces.
 *
 * <pre>
 * "package": "com.example.addon.mixin",
 * "plugin": "dev.myriadclient.api.mixin.CompatMixinPlugin",
 * "client": ["WorldRendererMixin", "compat.sodium.SodiumBlockRendererMixin", "compat.no_sodium.BlockRenderMixin"]
 * </pre>
 */
public class CompatMixinPlugin implements IMixinConfigPlugin {
	private String mixinPackage = "";

	@Override
	public void onLoad(String mixinPackage) {
		this.mixinPackage = mixinPackage;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String relative = mixinClassName.startsWith(mixinPackage + ".") ? mixinClassName.substring(mixinPackage.length() + 1) : mixinClassName;
		return applies(relative, id -> FabricLoader.getInstance().isModLoaded(id));
	}

	/** The rule itself: whether a mixin at {@code relativeName} (below the config's package) should apply. */
	public static boolean applies(String relativeName, Predicate<String> isLoaded) {
		String[] parts = relativeName.split("\\.");
		// The last part is the class name; look for compat.<mod> in the packages before it.
		for (int i = 0; i + 2 < parts.length; i++) {
			if (!parts[i].equals("compat")) continue;
			String mod = parts[i + 1];
			boolean absent = mod.startsWith("no_");
			if (absent) mod = mod.substring(3);
			boolean loaded = isLoaded.test(mod) || isLoaded.test(mod.replace('_', '-'));
			return absent != loaded;
		}
		return true;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
