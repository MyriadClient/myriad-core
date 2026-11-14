package dev.myriadclient.impl.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.InputQuirks;

/** Ctrl shortcuts in Myriad's screens (paste, clear, Ctrl+P), which take Cmd too on a Mac, as Minecraft's own text fields do. */
public final class Shortcuts {
	private Shortcuts() {
	}

	/** Whether {@code mods} hold Ctrl, or Cmd on a Mac. */
	public static boolean ctrl(int mods) {
		return (mods & (InputConstants.MOD_CONTROL | InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER)) != 0;
	}
}
