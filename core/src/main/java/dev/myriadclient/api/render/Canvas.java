package dev.myriadclient.api.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Myriad's 2D drawing surface: anti-aliased SDF shapes, gradient borders, drop shadows, frosted-glass backdrops and
 * crisp TTF text, all batched into a few draw calls.
 * <p>
 * Coordinates are in UI units. The window manager sets the unit-to-pixel scale (theme "UI scale"), and
 * {@link #translate}/{@link #scale} stack on top of it. Colours are ARGB ints (see {@code ColorUtil}); angles are in
 * degrees, 0 = left-to-right, 90 = top-to-bottom.
 */
@ApiStatus.NonExtendable
public interface Canvas {
	// ---- shapes ---------------------------------------------------------------------------------------------------

	void rect(float x, float y, float w, float h, int color);

	void roundRect(float x, float y, float w, float h, float radius, int color);

	/** Per-corner radii: top-left, top-right, bottom-right, bottom-left. */
	void roundRect(float x, float y, float w, float h, float tl, float tr, float br, float bl, int color);

	void gradientRect(float x, float y, float w, float h, float radius, int from, int to, float angle);

	void outline(float x, float y, float w, float h, float radius, float thickness, int color);

	/** A border whose colour runs from {@code from} to {@code to} along {@code angle} (Hyprland's border gradient). */
	void gradientOutline(float x, float y, float w, float h, float radius, float thickness, int from, int to, float angle);

	/** A soft drop shadow around the box; {@code size} is how far it spreads. */
	void shadow(float x, float y, float w, float h, float radius, float size, int color);

	/**
	 * Frosted glass: the blurred scene behind the box, masked to its rounded shape. Draw a translucent fill on top to
	 * tint it. Does nothing if blur is disabled in the theme.
	 */
	void backdrop(float x, float y, float w, float h, float radius, float opacity);

	void circle(float cx, float cy, float radius, int color);

	void line(float x1, float y1, float x2, float y2, float thickness, int color);

	/** Draws a texture region; {@code u}/{@code v} are 0..1. */
	void texture(Identifier texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint);

	/** Draws a region of a GPU texture (e.g. one an addon created itself); {@code u}/{@code v} are 0..1. */
	void texture(GpuTextureView texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint);

	/** Draws an item icon with its count and durability bar, {@code size} units square. */
	default void item(ItemStack stack, float x, float y, float size) {
		item(stack, x, y, size, true);
	}

	/** Draws an item icon; {@code overlay} adds the vanilla count and durability bar. */
	void item(ItemStack stack, float x, float y, float size, boolean overlay);

	// ---- text -----------------------------------------------------------------------------------------------------

	/** Draws text with the theme's default font and size. Supports {@code §} colour codes. Returns the width. */
	float text(String text, float x, float y, int color);

	float text(FontFamily font, float size, String text, float x, float y, int color);

	float textWidth(String text);

	float textWidth(FontFamily font, float size, String text);

	/** Line height of the default font. */
	float textHeight();

	float textHeight(FontFamily font, float size);

	FontFamily defaultFont();

	float defaultFontSize();

	/** Truncates with an ellipsis to fit {@code maxWidth}. */
	default String ellipsize(FontFamily font, float size, String text, float maxWidth) {
		if (textWidth(font, size, text) <= maxWidth) return text;
		String dots = "…";
		float dw = textWidth(font, size, dots);
		int end = text.length();
		while (end > 0 && textWidth(font, size, text.substring(0, end)) + dw > maxWidth) end--;
		return text.substring(0, end) + dots;
	}

	// ---- state ----------------------------------------------------------------------------------------------------

	/** Saves translation, scale, clip and alpha. */
	void push();

	void pop();

	void translate(float x, float y);

	void scale(float s);

	/** Current units-to-pixels factor (including the UI scale). */
	float pixelScale();

	/** Clips drawing to the box, intersected with the current clip. Balanced by {@link #pop()}. */
	void clip(float x, float y, float w, float h);

	/** Multiplies the alpha of everything drawn until {@link #pop()}. */
	void alpha(float alpha);

	/**
	 * The vanilla GUI context, for things the canvas doesn't cover. Call {@link #flush()} before drawing with it so
	 * vanilla elements land above what the canvas drew so far.
	 */
	GuiGraphicsExtractor drawContext();

	/** Submits batched geometry now. */
	void flush();

	/** Milliseconds since the previous frame, for animations. */
	float frameDeltaMs();
}
