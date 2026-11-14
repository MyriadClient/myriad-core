package dev.myriadclient.api.ui.draw;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.myriadclient.api.ui.widget.Preview;
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
 * degrees, 0 = left-to-right, 90 = top-to-bottom. A shape with zero size, or fully transparent, draws nothing.
 * <p>
 * You don't make a canvas: the UI hands one to {@link dev.myriadclient.api.ui.Panel#render} (translated and clipped to
 * the panel's content), to HUD elements and bar widgets, and {@link dev.myriadclient.api.ui.Ui#draw} lends one inside
 * vanilla rendering. It's only valid during that call, on the render thread; drawing outside one throws
 * {@link IllegalStateException}. Leave it as you found it: balance every {@link #push()} with a {@link #pop()}.
 *
 * <pre>{@code
 * public void render(Canvas c, float width, float height, float mouseX, float mouseY) {
 *     ThemeSettings theme = Myriad.ui().theme();
 *     boolean hover = mouseX >= 4 && mouseY >= 4 && mouseX < width - 4 && mouseY < 24;
 *     c.roundRect(4, 4, width - 8, 20, 4, hover ? theme.surfaceHover.argb() : theme.surface.argb());
 *     c.text("Hello", 10, 4 + (20 - c.textHeight()) / 2, theme.text.argb());
 *     c.push();
 *     c.clip(4, 30, width - 8, height - 34);  // nothing drawn below spills out of this box
 *     c.alpha(0.5f);                           // everything until pop() at half opacity
 *     c.gradientRect(4, 30, width - 8, 40, 4, theme.accent.argb(), theme.secondary.argb(), 0);
 *     c.pop();
 * }
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Canvas {
	// ---- shapes ---------------------------------------------------------------------------------------------------

	/** Fills a box with {@code color}. */
	void rect(float x, float y, float w, float h, int color);

	/** Fills a box with rounded corners, all of {@code radius} UI units. */
	void roundRect(float x, float y, float w, float h, float radius, int color);

	/** Per-corner radii: top-left, top-right, bottom-right, bottom-left. */
	void roundRect(float x, float y, float w, float h, float tl, float tr, float br, float bl, int color);

	/** Fills a rounded box with a linear gradient from {@code from} to {@code to}, running along {@code angle}. */
	void gradientRect(float x, float y, float w, float h, float radius, int from, int to, float angle);

	/**
	 * A border {@code thickness} UI units wide (at least one pixel) just inside the box's edge, following its rounded
	 * corners.
	 */
	void outline(float x, float y, float w, float h, float radius, float thickness, int color);

	/** A border whose colour runs from {@code from} to {@code to} along {@code angle} (Hyprland's border gradient). */
	void gradientOutline(float x, float y, float w, float h, float radius, float thickness, int from, int to, float angle);

	/**
	 * A soft drop shadow around the box; {@code size} is how far it spreads beyond it, in UI units. Draw it before the
	 * box it belongs to.
	 */
	void shadow(float x, float y, float w, float h, float radius, float size, int color);

	/**
	 * Frosted glass: the blurred scene behind the box, masked to its rounded shape, at {@code opacity} (0-1). Draw a
	 * translucent fill on top to tint it. Does nothing if blur is disabled in the theme, or with no world to blur.
	 */
	void backdrop(float x, float y, float w, float h, float radius, float opacity);

	/** Fills a circle centred at ({@code cx}, {@code cy}). */
	void circle(float cx, float cy, float radius, int color);

	/** A straight line {@code thickness} UI units wide (at least one pixel), with square ends. */
	void line(float x1, float y1, float x2, float y2, float thickness, int color);

	/**
	 * Draws a region of a texture (a resource location loaded by Minecraft's texture manager) stretched over the box;
	 * {@code u}/{@code v} are 0..1. {@code tint} multiplies its colours (0xFFFFFFFF for none).
	 */
	void texture(Identifier texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint);

	/** Draws a region of a GPU texture (e.g. one an addon created itself); {@code u}/{@code v} are 0..1. */
	void texture(GpuTextureView texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int tint);

	/** Draws an item icon with its count and durability bar, {@code size} units square. */
	default void item(ItemStack stack, float x, float y, float size) {
		item(stack, x, y, size, true);
	}

	/**
	 * Draws an item icon {@code size} units square, with Minecraft's item renderer; {@code overlay} adds the vanilla
	 * count and durability bar. An empty stack draws nothing. It flushes the batch first, so it lands above what was
	 * drawn so far.
	 */
	void item(ItemStack stack, float x, float y, float size, boolean overlay);

	/** Draws {@code preview}'s subject in 3D in the given box (what {@link Preview#render} does; for drawing one yourself). */
	void preview(Preview preview, float x, float y, float w, float h);

	// ---- text -----------------------------------------------------------------------------------------------------

	/**
	 * Draws text with the theme's default font and size, its top-left at ({@code x}, {@code y}). Supports {@code §}
	 * colour codes ({@code §r} goes back to {@code color}). Returns the width drawn, in UI units.
	 */
	float text(String text, float x, float y, int color);

	/**
	 * Draws text in {@code font} at {@code size} UI units (the font's pixel size is {@code size} times
	 * {@link #pixelScale()}, kept within 4-128 pixels), its top-left at ({@code x}, {@code y}), snapped to whole pixels
	 * so it stays crisp. Supports {@code §} colour codes. Returns the width drawn, in UI units.
	 */
	float text(FontFamily font, float size, String text, float x, float y, int color);

	/** Width of {@code text} in the default font and size, in UI units. */
	float textWidth(String text);

	/** Width of {@code text} in {@code font} at {@code size}, in UI units. */
	float textWidth(FontFamily font, float size, String text);

	/** Line height of the default font. */
	float textHeight();

	/** Line height of {@code font} at {@code size}, in UI units: how far apart lines go. */
	float textHeight(FontFamily font, float size);

	/** The theme's font ({@code ThemeSettings.fontFamily()}), what {@link #text(String, float, float, int)} uses. */
	FontFamily defaultFont();

	/** The theme's font size in UI units ({@code ThemeSettings.fontSize}); scale it for smaller or larger text. */
	float defaultFontSize();

	/**
	 * {@code text} as is if it fits in {@code maxWidth} UI units, or cut short with an ellipsis so it does (just the
	 * ellipsis if nothing else fits).
	 */
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

	/** Restores what the matching {@link #push()} saved. Popping more than was pushed throws. */
	void pop();

	/** Moves the origin by ({@code x}, {@code y}) in the current units, until {@link #pop()}. */
	void translate(float x, float y);

	/** Multiplies the size of a unit by {@code s} (around the current origin), until {@link #pop()}. */
	void scale(float s);

	/** Current units-to-pixels factor (including the UI scale): framebuffer pixels per unit. */
	float pixelScale();

	/** Clips drawing to the box, intersected with the current clip. Balanced by {@link #pop()}. */
	void clip(float x, float y, float w, float h);

	/** Multiplies the alpha of everything drawn until {@link #pop()} by {@code alpha} (clamped to 0-1). */
	void alpha(float alpha);

	/**
	 * The vanilla GUI context, for things the canvas doesn't cover. Call {@link #flush()} before drawing with it so
	 * vanilla elements land above what the canvas drew so far. It works in Minecraft's GUI pixels, not UI units, and
	 * knows nothing of this canvas's translation, scale or clip.
	 */
	GuiGraphicsExtractor drawContext();

	/** Submits batched geometry now, so what's drawn after (by vanilla, say) lands above it. */
	void flush();

	/** Milliseconds since the previous frame, for animations (at most 100). */
	float frameDeltaMs();
}
