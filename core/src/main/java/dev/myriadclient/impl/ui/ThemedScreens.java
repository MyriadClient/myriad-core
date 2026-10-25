package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.mixin.AbstractContainerScreenAccessor;
import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft's inventory and storage screens (chests, barrels, ender chests, shulker boxes, hoppers, dispensers) drawn in
 * the theme while Theme → Preferences → Themed Screens is on: a window's surface, border and title where the screen's
 * texture went, each slot a cell. Only the drawing changes: the screen, its slots and every click stay
 * Minecraft's, so the server sees nothing different and other mods' slot work still lines up. Drawn in the screen's
 * GUI pixels, in place of its background texture, so Minecraft's items and highlights go on top as before.
 */
public final class ThemedScreens {
	/** A slot's cell around its 16-pixel item, and where it starts from the item's corner: slots are 18 apart, so a pixel between. */
	private static final float CELL = 17, CELL_AT = -0.5f;
	/** The theme icon font's chevrons (Font Awesome), for the recipe book's pages. */
	private static final String CHEVRON_LEFT = "\uf053", CHEVRON_RIGHT = "\uf054";
	/** Its book (Font Awesome), for the button that opens the recipe book. */
	private static final String BOOK = "\uf02d";
	/** And its arrows, flame, plus and cross for the workstations, with the flame's colour. */
	private static final String ARROW = "\uf061", ARROW_DOWN = "\uf063", FIRE = "\uf06d", PLUS = "\uf067", CROSS = "\uf00d";
	private static final int FLAME = 0xFFFAB387;
	/** Whether this frame's background is drawn already: a screen's texture can come in more than one piece (a chest's). */
	private static boolean drawn;

	private ThemedScreens() {
	}

	public static void beginBackground() {
		drawn = false;
	}

	/** Whether {@code screen} is drawn in the theme. */
	public static boolean themed(AbstractContainerScreen<?> screen) {
		return Myriad.isReady() && theme().themedScreens.get()
			&& (screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen || screen instanceof HopperScreen || screen instanceof DispenserScreen
			|| screen instanceof InventoryScreen || screen instanceof CraftingScreen || screen instanceof AbstractFurnaceScreen<?> || screen instanceof ItemCombinerScreen<?>
			|| screen instanceof GrindstoneScreen || screen instanceof BrewingStandScreen || screen instanceof CrafterScreen);
	}

	/** Draws {@code screen}'s themed window in place of its texture; false to let the texture be drawn. */
	public static boolean background(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx) {
		if (!themed(screen)) return false;
		if (drawn) return true;
		drawn = true;
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> paint(c, screen));
		return true;
	}

	/**
	 * What a workstation's texture shows besides its slots, in the theme: a bigger cell for what it makes, the arrow to it,
	 * the "+" between an anvil's inputs, and the dim outlines a furnace's flame and arrow and a brewing stand's fuel and
	 * progress fill over (see {@link #spritePart}). Places are Minecraft's, in the screen's own pixels.
	 */
	private static void decorate(UiRenderer c, ThemeSettings t, AbstractContainerScreen<?> screen, float x, float y) {
		int dim = ColorUtil.withAlpha(t.textDim.argb(), 0x70);
		switch (screen) {
			case CraftingScreen s -> {
				resultCell(c, t, x + 124, y + 35);
				centred(c, ARROW, x + 90, y + 35, 22, 15, dim);
			}
			case AbstractFurnaceScreen<?> s -> {
				resultCell(c, t, x + 116, y + 35);
				centred(c, FIRE, x + 56, y + 36, 14, 14, dim);
				centred(c, ARROW, x + 79, y + 34, 24, 16, dim);
			}
			// Its redstone mark stands where the arrow would.
			case CrafterScreen s -> resultCell(c, t, x + 134, y + 35);
			case GrindstoneScreen s -> {
				resultCell(c, t, x + 129, y + 34);
				centred(c, ARROW, x + 92, y + 31, 28, 21, dim);
			}
			case SmithingScreen s -> centred(c, ARROW, x + 65, y + 46, 28, 21, dim);
			case ItemCombinerScreen<?> s -> {
				// An anvil.
				centred(c, PLUS, x + 49, y + 47, 22, 16, dim);
				centred(c, ARROW, x + 99, y + 45, 28, 21, dim);
			}
			case BrewingStandScreen s -> {
				c.roundRect(x + 60, y + 44, 18, 4, 2, ColorUtil.withAlpha(t.textDim.argb(), 50));
				centred(c, ARROW_DOWN, x + 97, y + 16, 9, 28, dim);
			}
			default -> {
			}
		}
	}

	/** A larger cell under what a workstation makes, as Minecraft frames it. */
	private static void resultCell(UiRenderer c, ThemeSettings t, float slotX, float slotY) {
		float r = cellRounding(t);
		c.roundRect(slotX - 4.5f, slotY - 4.5f, 25, 25, r, t.surface.argb());
		c.outline(slotX - 4.5f, slotY - 4.5f, 25, 25, r, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
	}

	/**
	 * Part of a progress sprite (Minecraft draws {@code w} by {@code h} of it at {@code x, y}, from {@code u, v} in its
	 * {@code texW} by {@code texH}): a furnace's flame and arrow, a brewing stand's fuel and progress, the outline
	 * {@link #decorate} drew filled in the theme as far as Minecraft would. False to let Minecraft draw it (the bubbles).
	 */
	public static boolean spritePart(GuiGraphicsExtractor ctx, Identifier sprite, int texW, int texH, int u, int v, int x, int y, int w, int h) {
		if (!Myriad.isReady() || !theme().themedScreens.get()) return false;
		String path = sprite.getPath();
		// The whole sprite's box, of which x, y, w, h is the part shown.
		int fx = x - u, fy = y - v;
		if (path.endsWith("/lit_progress")) return part(ctx, x, y, w, h, c -> centred(c, FIRE, fx, fy, texW, texH, FLAME));
		if (path.endsWith("/burn_progress")) return part(ctx, x, y, w, h, c -> centred(c, ARROW, fx, fy, texW, texH, theme().accent.argb()));
		if (path.equals("container/brewing_stand/brew_progress")) return part(ctx, x, y, w, h, c -> centred(c, ARROW_DOWN, fx, fy, texW, texH, theme().accent.argb()));
		if (path.equals("container/brewing_stand/fuel_length")) return part(ctx, x, y, w, h, c -> {
			ThemeSettings t = theme();
			c.gradientRect(fx, fy, Math.max(w, texH), texH, texH / 2f, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), 0);
		});
		return false;
	}

	/** Draws within {@code x, y, w, h} only. */
	private static boolean part(GuiGraphicsExtractor ctx, int x, int y, int w, int h, java.util.function.Consumer<UiRenderer> draw) {
		if (w <= 0 || h <= 0) return true;
		return posed(ctx, c -> {
			c.push();
			c.clip(x, y, w, h);
			draw.accept(c);
			c.pop();
		});
	}

	/** Lights the slot under the mouse in the theme's accent, in place of Minecraft's highlight; false to leave it be. */
	public static boolean highlight(AbstractContainerScreen<?> screen, @Nullable Slot hovered, GuiGraphicsExtractor ctx) {
		if (!themed(screen)) return false;
		if (hovered == null || !hovered.isHighlightable()) return true;
		AbstractContainerScreenAccessor s = (AbstractContainerScreenAccessor) screen;
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> {
			ThemeSettings theme = theme();
			float x = s.myriad$leftPos() + hovered.x + CELL_AT, y = s.myriad$topPos() + hovered.y + CELL_AT, r = cellRounding(theme);
			c.roundRect(x, y, CELL, CELL, r, ColorUtil.withAlpha(theme.accent.argb(), 0x38));
			c.outline(x, y, CELL, CELL, r, 1, ColorUtil.withAlpha(theme.accent.argb(), 0xB0));
		});
		return true;
	}

	/**
	 * A tooltip's box around its contents at {@code x, y} ({@code w} by {@code h}), as the desktop draws its tooltips: the
	 * window's backdrop, background (a little more solid), smaller rounding and border. Minecraft's text and pictures go
	 * on it as before. False to let Minecraft draw it.
	 */
	public static boolean tooltip(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
		if (!Myriad.isReady() || !theme().themedScreens.get()) return false;
		// A tooltip can be drawn moved (a widget's); the renderer doesn't follow the pose by itself.
		float dx = ctx.pose().m20(), dy = ctx.pose().m21();
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> {
			ThemeSettings t = theme();
			float bx = x - 4 + dx, by = y - 4 + dy, bw = w + 8, bh = h + 8, r = Math.min(t.rounding.get(), 6);
			c.backdrop(bx, by, bw, bh, r, 1);
			c.roundRect(bx, by, bw, bh, r, ColorUtil.withAlpha(t.windowBackground.argb(), Math.max(0xE0, ColorUtil.alpha(t.windowBackground.argb()))));
			c.gradientOutline(bx, by, bw, bh, r, Math.max(1, t.borderSize.get()), t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.get().floatValue());
		});
		return true;
	}

	/**
	 * One of the sprites Minecraft draws around its screens, by name, in the theme at {@code x, y} ({@code w} by {@code h}):
	 * the recipe book's recipe slots (dim red where it can't be made, a second edge for one with alternatives), its tabs
	 * (the selected one in the accent), its alternatives' popup, filter, page arrows and search box, the button opening it, and the cards of the inventory's effect list (an
	 * ambient one, from a beacon, with a dim border). False to let Minecraft draw it.
	 */
	public static boolean sprite(GuiGraphicsExtractor ctx, Identifier sprite, int x, int y, int w, int h) {
		if (!Myriad.isReady() || !theme().themedScreens.get()) return false;
		String path = sprite.getPath();
		return switch (path) {
			case "recipe_book/slot_craftable", "recipe_book/slot_many_craftable", "recipe_book/slot_uncraftable", "recipe_book/slot_many_uncraftable" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = cellRounding(t);
				if (path.contains("many")) c.outline(x + 2, y + 2, w - 2, h - 2, r, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0xA0));
				c.roundRect(x, y, w - 2, h - 2, r, t.surface.argb());
				if (path.contains("uncraftable")) c.roundRect(x, y, w - 2, h - 2, r, ColorUtil.withAlpha(0xFFF38BA8, 0x40));
				c.outline(x, y, w - 2, h - 2, r, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x80));
			});
			// A tab squashes up and down while it has new recipes (Minecraft scales it about its middle): its box is mapped
			// through that rather than the renderer scaling, which is the same both ways.
			case "recipe_book/tab", "recipe_book/tab_selected" -> {
				var pose = ctx.pose();
				float bx = pose.m00() * x + pose.m20(), by = pose.m11() * y + pose.m21(), bw = pose.m00() * w, bh = pose.m11() * h;
				MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> {
					ThemeSettings t = theme();
					float r = Math.min(t.rounding.get(), 4);
					c.roundRect(bx, by, bw, bh, r, ColorUtil.withAlpha(t.windowBackground.argb(), Math.max(0xE0, ColorUtil.alpha(t.windowBackground.argb()))));
					if (path.endsWith("selected")) c.gradientOutline(bx, by, bw, bh, r, 1, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.get().floatValue());
					else c.outline(bx, by, bw, bh, r, 1, t.inactiveBorder.argb());
				});
				yield true;
			}
			case "recipe_book/overlay_recipe" -> posed(ctx, c -> window(c, theme(), x, y, w, h, false));
			case "recipe_book/filter_enabled", "recipe_book/filter_enabled_highlighted", "recipe_book/filter_disabled", "recipe_book/filter_disabled_highlighted",
			     "recipe_book/furnace_filter_enabled", "recipe_book/furnace_filter_enabled_highlighted", "recipe_book/furnace_filter_disabled",
			     "recipe_book/furnace_filter_disabled_highlighted" -> posed(ctx, c -> {
				// Showing only what you can make: the filter in the accent.
				ThemeSettings t = theme();
				boolean on = path.contains("enabled"), hover = path.endsWith("highlighted");
				float r = Math.min(t.rounding.get(), 4);
				c.roundRect(x, y, w, h, r, hover ? t.surfaceHover.argb() : t.surface.argb());
				c.outline(x, y, w, h, r, 1, on ? t.accent.argb() : t.inactiveBorder.argb());
				centred(c, Icons.FILTER, x, y, w, h, on ? t.accent.argb() : t.textDim.argb());
			});
			// The button that opens and closes the recipe book.
			case "recipe_book/button", "recipe_book/button_highlighted" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				boolean hover = path.endsWith("highlighted");
				float r = Math.min(t.rounding.get(), 4);
				c.roundRect(x, y, w, h, r, hover ? t.surfaceHover.argb() : t.surface.argb());
				c.outline(x, y, w, h, r, 1, hover ? t.accent.argb() : t.inactiveBorder.argb());
				centred(c, BOOK, x, y, w, h, hover ? t.accent.argb() : t.text.argb());
			});
			case "recipe_book/page_forward", "recipe_book/page_forward_highlighted", "recipe_book/page_backward", "recipe_book/page_backward_highlighted" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				centred(c, path.contains("forward") ? CHEVRON_RIGHT : CHEVRON_LEFT, x, y, w, h, path.endsWith("highlighted") ? t.text.argb() : t.textDim.argb());
			});
			// What a workstation can't do with what's in it (an anvil's too costly repair, say).
			case "container/anvil/error", "container/smithing/error", "container/grindstone/error" -> posed(ctx, c -> centred(c, CROSS, x, y, w, h, 0xFFF38BA8));
			case "container/anvil/text_field", "container/anvil/text_field_disabled" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = Math.min(t.rounding.get(), 4);
				c.roundRect(x, y, w, h, r, path.endsWith("disabled") ? ColorUtil.withAlpha(t.surface.argb(), 0x80) : t.surface.argb());
				c.outline(x, y, w, h, r, 1, t.inactiveBorder.argb());
			});
			// Only on a screen drawn in the theme: vanilla's other text boxes keep their look.
			case "widget/text_field", "widget/text_field_highlighted" -> Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen && themed(screen)
				&& posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = Math.min(t.rounding.get(), 4);
				c.roundRect(x, y, w, h, r, t.surface.argb());
				if (path.endsWith("highlighted")) c.gradientOutline(x, y, w, h, r, 1, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.get().floatValue());
				else c.outline(x, y, w, h, r, 1, t.inactiveBorder.argb());
			});
			case "container/inventory/effect_background", "container/inventory/effect_background_ambient" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = Math.min(t.rounding.get(), 6);
				c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(t.windowBackground.argb(), Math.max(0xE0, ColorUtil.alpha(t.windowBackground.argb()))));
				if (path.endsWith("ambient")) c.outline(x, y, w, h, r, 1, t.inactiveBorder.argb());
				else c.gradientOutline(x, y, w, h, r, Math.max(1, t.borderSize.get()), t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.get().floatValue());
			});
			default -> false;
		};
	}

	/** An icon from the theme's icon font, centred in the box at {@code x, y}. */
	private static void centred(UiRenderer c, String icon, float x, float y, float w, float h, int color) {
		float size = c.defaultFontSize();
		c.text(FontFamily.MONO, size, icon, x + (w - c.textWidth(FontFamily.MONO, size, icon)) / 2, y + (h - c.textHeight(FontFamily.MONO, size)) / 2, color);
	}

	/** The recipe book's own window, in place of its texture at {@code x, y} ({@code w} by {@code h}); false to let Minecraft draw it. */
	public static boolean recipeBook(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
		if (!Myriad.isReady() || !theme().themedScreens.get()) return false;
		return posed(ctx, c -> window(c, theme(), x, y, w, h, true));
	}

	/** A window as the desktop draws one: shadow (if {@code shadow} and the theme has one), backdrop, surface and border. */
	private static void window(UiRenderer c, ThemeSettings t, float x, float y, float w, float h, boolean shadow) {
		float r = Math.min(t.rounding.get(), h / 2), b = t.borderSize.get();
		if (shadow && t.shadow.get()) c.shadow(x, y, w, h, r, t.shadowRange.get(), t.shadowColor.argb());
		c.backdrop(x, y, w, h, r, 1);
		c.roundRect(x, y, w, h, r, t.windowBackground.argb());
		if (b > 0) c.gradientOutline(x, y, w, h, r, b, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.get().floatValue());
	}

	/** Paints where Minecraft's drawing is moved and scaled to (a tab's wiggle, a widget's place), which the renderer doesn't follow by itself. */
	private static boolean posed(GuiGraphicsExtractor ctx, java.util.function.Consumer<UiRenderer> draw) {
		var pose = ctx.pose();
		float dx = pose.m20(), dy = pose.m21(), scale = pose.m00();
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> {
			c.translate(dx, dy);
			if (scale != 1) c.scale(scale);
			draw.accept(c);
		});
		return true;
	}

	private static float cellRounding(ThemeSettings theme) {
		return Math.min(theme.rounding.get(), 3);
	}

	private static ThemeSettings theme() {
		return MyriadImpl.get().windowManager().theme();
	}

	private static void paint(UiRenderer c, AbstractContainerScreen<?> screen) {
		ThemeSettings theme = theme();
		AbstractContainerScreenAccessor s = (AbstractContainerScreenAccessor) screen;
		float x = s.myriad$leftPos(), y = s.myriad$topPos(), w = s.myriad$imageWidth(), h = s.myriad$imageHeight();
		float r = Math.min(theme.rounding.get(), h / 2), b = theme.borderSize.get();
		if (theme.shadow.get()) c.shadow(x, y, w, h, r, theme.shadowRange.get(), theme.shadowColor.argb());
		c.backdrop(x, y, w, h, r, 1);
		c.roundRect(x, y, w, h, r, theme.windowBackground.argb());

		boolean inventory = screen instanceof InventoryScreen;
		float size = c.defaultFontSize();
		String title = c.ellipsize(FontFamily.SANS_BOLD, size, screen.getTitle().getString(), w - 16);
		c.text(FontFamily.SANS_BOLD, size, title, x + s.myriad$titleLabelX(), y + s.myriad$titleLabelY(), theme.text.argb());
		if (!inventory) c.text(s.myriad$playerInventoryTitle().getString(), x + s.myriad$inventoryLabelX(), y + s.myriad$inventoryLabelY(), theme.textDim.argb());

		float cr = cellRounding(theme);
		int edge = ColorUtil.withAlpha(theme.inactiveBorder.argb(), 0x50);
		for (Slot slot : screen.getMenu().slots) {
			if (!slot.isActive()) continue;
			c.roundRect(x + slot.x + CELL_AT, y + slot.y + CELL_AT, CELL, CELL, cr, theme.surface.argb());
			c.outline(x + slot.x + CELL_AT, y + slot.y + CELL_AT, CELL, CELL, cr, 1, edge);
		}
		decorate(c, theme, screen, x, y);
		if (inventory) {
			// Your figure's frame, and the arrow from the crafting grid to what it makes.
			c.roundRect(x + 26, y + 8, 49, 70, cr, theme.surface.argb());
			c.outline(x + 26, y + 8, 49, 70, cr, 1, theme.inactiveBorder.argb());
			c.text(FontFamily.MONO, size, Icons.ARROW_RIGHT, x + 138, y + 36 - c.textHeight(FontFamily.MONO, size) / 2, theme.textDim.argb());
		}

		if (b > 0) c.gradientOutline(x, y, w, h, r, b, theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
	}
}
