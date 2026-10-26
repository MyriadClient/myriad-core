package dev.myriadclient.impl.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A font set's baked glyphs dropped without choosing its providers again (see ThemedText). */
@Mixin(FontSet.class)
public interface FontSetAccessor {
	@Invoker("resetTextures")
	void myriad$resetTextures();

	/** Code points by advance, for obfuscated text; resetTextures empties it. */
	@Accessor("glyphsByWidth")
	Int2ObjectMap<IntList> myriad$glyphsByWidth();
}
