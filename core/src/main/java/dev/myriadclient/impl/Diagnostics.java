package dev.myriadclient.impl;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonState;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A plain-text report for bug reports: versions, addons, other mods, and every enabled module with only the settings
 * that differ from their defaults. Nothing personal: no server address, friends or account.
 */
public final class Diagnostics {
	/** Mods every install has; listing them adds noise. */
	private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "mixinextras");

	private Diagnostics() {
	}

	public static String build() {
		StringBuilder sb = new StringBuilder();
		sb.append("Myriad ").append(version("myriad")).append(" | Minecraft ").append(version("minecraft"))
			.append(" | Fabric Loader ").append(version("fabricloader")).append(" | Java ").append(Runtime.version())
			.append(" | ").append(System.getProperty("os.name")).append('\n');

		sb.append("\nAddons:\n");
		for (Addon a : Myriad.addons()) {
			sb.append("  ").append(a.id()).append(' ').append(a.version());
			if (a.state() == AddonState.FAILED) sb.append("  FAILED: ").append(a.failure());
			sb.append('\n');
		}

		List<ModContainer> others = FabricLoader.getInstance().getAllMods().stream()
			.filter(m -> m.getContainingMod().isEmpty())
			.filter(m -> !BUILT_IN.contains(m.getMetadata().getId()) && !m.getMetadata().getId().startsWith("fabric-"))
			.filter(m -> Myriad.addons().stream().noneMatch(a -> a.id().equals(m.getMetadata().getId())))
			.sorted(Comparator.comparing(m -> m.getMetadata().getId()))
			.toList();
		sb.append("\nOther mods (").append(others.size()).append("):\n");
		for (ModContainer m : others) sb.append("  ").append(m.getMetadata().getId()).append(' ').append(m.getMetadata().getVersion().getFriendlyString()).append('\n');

		List<Module> enabled = Myriad.modules().enabled();
		sb.append("\nEnabled modules (").append(enabled.size()).append("):\n");
		for (Module m : enabled) {
			sb.append("  ").append(m.id());
			boolean any = false;
			for (Setting<?> s : m.settings.all()) {
				if (!s.isSerializable() || s.isDefault()) continue;
				sb.append(any ? ", " : "  ").append(m.settings.keyOf(s)).append('=').append(s.valueString());
				any = true;
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}
}
