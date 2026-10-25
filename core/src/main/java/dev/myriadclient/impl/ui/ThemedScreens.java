package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.mixin.AbstractContainerScreenAccessor;
import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
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
			|| screen instanceof InventoryScreen);
	}

	/** Draws {@code screen}'s themed window in place of its texture; false to let the texture be drawn. */
	public static boolean background(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx) {
		if (!themed(screen)) return false;
		if (drawn) return true;
		drawn = true;
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> paint(c, screen));
		return true;
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
		if (inventory) {
			// Your figure's frame, and the arrow from the crafting grid to what it makes.
			c.roundRect(x + 26, y + 8, 49, 70, cr, theme.surface.argb());
			c.outline(x + 26, y + 8, 49, 70, cr, 1, theme.inactiveBorder.argb());
			c.text(FontFamily.MONO, size, Icons.ARROW_RIGHT, x + 138, y + 36 - c.textHeight(FontFamily.MONO, size) / 2, theme.textDim.argb());
		}

		if (b > 0) c.gradientOutline(x, y, w, h, r, b, theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
	}
}
