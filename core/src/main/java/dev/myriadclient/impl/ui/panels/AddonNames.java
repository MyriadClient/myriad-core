package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.module.Module;

/** Short, human names for where things come from ("Essentials" rather than "Myriad Essentials"). */
public final class AddonNames {
	private AddonNames() {
	}

	public static String of(String addonId) {
		if (addonId == null) return "?";
		for (Addon a : Myriad.addons()) {
			if (!a.id().equals(addonId)) continue;
			String n = a.name();
			return n.startsWith("Myriad ") && n.length() > 7 ? n.substring(7) : n;
		}
		return addonId;
	}

	static String of(Module m) {
		return of(Myriad.modules().ownerOf(m));
	}
}
