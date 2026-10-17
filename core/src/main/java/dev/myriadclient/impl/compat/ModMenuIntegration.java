package dev.myriadclient.impl.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.ui.DesktopScreen;
import dev.myriadclient.impl.ui.WindowManager;

/** Mod Menu's "Configure" button opens the Myriad menu. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			WindowManager wm = (WindowManager) Myriad.ui();
			wm.refreshModules();
			return new DesktopScreen(wm);
		};
	}
}
