package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.mixin.AbstractContainerScreenAccessor;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.service.ContainerTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.AbstractMountInventoryScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
	/** And its arrows, flame, plus and cross for the workstations. */
	private static final String ARROW = "\uf061", ARROW_DOWN = "\uf063", FIRE = "\uf06d", PLUS = "\uf067", CROSS = "\uf00d", CHECK = "\uf00c";
	/** Where a brewing stand's fuel bar and progress arrow go in the theme (x, y, w, h in its screen): under its blaze powder and its ingredient. */
	private static final int[] BREW_FUEL = {17, 37, 16, 3}, BREW_ARROW = {79, 36, 16, 13};
	/** Its bubbles in their 12 by 29 box (x, y, radius), bottom to top. */
	private static final float[][] BUBBLES = {{4, 25, 1.5f}, {8, 19.5f, 2}, {3.5f, 13, 1.5f}, {7.5f, 6.5f, 2}, {4, 1.5f, 1.2f}};
	/** How far left the theme moves an anvil's name box, to centre it (Minecraft's sits right of an icon the theme doesn't draw). */
	public static final int ANVIL_NAME_SHIFT = 26;
	/** A swap bar cell around its 16-pixel icon, 20 apart, as the hotbar's; the gap between the bar and the window. */
	private static final float TAB_CELL = 18, TAB_STEP = 20, BAR_GAP = 3;
	/** Which of a creative inventory's tab bars (1 above, 2 below) are drawn this frame. */
	private static int tabBars;
	/** The swap bar as last drawn: on which screen, its tabs and where each was (x, y, w, h), for clicks. */
	private static @Nullable AbstractContainerScreen<?> tabScreen;
	private static @Nullable List<ContainerTabs.Tab> tabList;
	private static final List<float[]> tabRects = new ArrayList<>();
	/** Whether this frame's background is drawn already: a screen's texture can come in more than one piece (a chest's). */
	private static boolean drawn;

	private ThemedScreens() {
	}

	public static void beginBackground() {
		drawn = false;
		tabBars = 0;
	}

	/** Whether {@code screen} is drawn in the theme. */
	public static boolean themed(AbstractContainerScreen<?> screen) {
		return Myriad.isReady() && theme().themedScreens.get()
			&& (screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen || screen instanceof HopperScreen || screen instanceof DispenserScreen
			|| screen instanceof InventoryScreen || screen instanceof CraftingScreen || screen instanceof AbstractFurnaceScreen<?> || screen instanceof ItemCombinerScreen<?>
			|| screen instanceof GrindstoneScreen || screen instanceof BrewingStandScreen || screen instanceof CrafterScreen || screen instanceof StonecutterScreen
			|| screen instanceof EnchantmentScreen || screen instanceof MerchantScreen || screen instanceof AbstractMountInventoryScreen<?> || screen instanceof BeaconScreen
			|| screen instanceof LoomScreen || screen instanceof CartographyTableScreen || screen instanceof CreativeModeInventoryScreen);
	}

	/**
	 * Draws {@code screen}'s themed window in place of its {@code texture} (which tells a creative inventory's tabs
	 * apart); false to let the texture be drawn.
	 */
	public static boolean background(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx, Identifier texture) {
		if (!themed(screen)) return false;
		if (drawn) return true;
		drawn = true;
		AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> paint(c, screen, texture.getPath()));
		tabTooltip(ctx);
		return true;
	}

	/**
	 * The swap bar over a screen not drawn in the theme (its window Minecraft's), drawn in the theme over it: the bar
	 * belongs to the swap, not to the screens' look.
	 */
	public static void plainTabs(AbstractContainerScreen<?> screen, GuiGraphicsExtractor ctx) {
		if (!Myriad.isReady() || themed(screen)) return;
		AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> tabs(c, theme(), screen, a.myriad$leftPos(), a.myriad$topPos(), 0));
		tabTooltip(ctx);
	}

	/** The swap bar cell under the mouse says what it is. */
	private static void tabTooltip(GuiGraphicsExtractor ctx) {
		if (tabList == null || tabRects.isEmpty()) return;
		double[] m = mouse();
		double mx = m[0], my = m[1];
		for (int i = 0; i < tabRects.size() && i < tabList.size(); i++) {
			float[] rr = tabRects.get(i);
			if (mx < rr[0] || mx >= rr[0] + rr[2] || my < rr[1] || my >= rr[1] + rr[3]) continue;
			ContainerTabs.Tab tab = tabList.get(i);
			ContainerTabs tabs = MyriadImpl.get().containerTabs();
			String name = !tabs.reachable(tab) ? tab.name() + " (out of reach)" : tabs.isHeld(tab) ? tab.name() + " (right-click to close)" : tab.name();
			ctx.setTooltipForNextFrame(Component.literal(name), (int) mx, (int) my);
			return;
		}
	}

	/**
	 * What a workstation's texture shows besides its slots, in the theme: a bigger cell for what it makes, the arrow to it,
	 * the "+" between an anvil's inputs, and the dim outlines a furnace's flame and arrow and a brewing stand's fuel and
	 * progress fill over (see {@link #spritePart}). Places are Minecraft's, in the screen's own pixels.
	 */
	private static void decorate(UiRenderer c, ThemeSettings t, AbstractContainerScreen<?> screen, String texture, float x, float y) {
		int dim = ColorUtil.withAlpha(t.textDim.argb(), 0x70);
		switch (screen) {
			case CraftingScreen s -> {
				resultCell(c, t, x + 124, y + 35);
				centred(c, ARROW, x + 90, y + 35, 22, 15, dim);
			}
			case AbstractFurnaceScreen<?> s -> {
				resultCell(c, t, x + 116, y + 35);
				centred(c, FIRE, x + 56, y + 36, 16, 14, dim);
				centred(c, ARROW, x + 79, y + 34, 24, 16, dim);
			}
			// Its recipes' list and scroll track.
			case StonecutterScreen s -> {
				resultCell(c, t, x + 143, y + 33);
				inset(c, t, x + 51, y + 13, 66, 56);
				c.roundRect(x + 119, y + 14, 12, 56, 3, ColorUtil.withAlpha(t.textDim.argb(), 30));
			}
			// The offers' list and scroll track, and the arrow between what you give and what you get.
			case MerchantScreen s -> {
				inset(c, t, x + 4, y + 17, 89, 142);
				c.roundRect(x + 94, y + 18, 6, 140, 3, ColorUtil.withAlpha(t.textDim.argb(), 30));
				resultCell(c, t, x + 220, y + 37);
				centred(c, ARROW, x + 184, y + 36, 28, 16, dim);
			}
			// Its patterns' list and scroll track.
			case LoomScreen s -> {
				resultCell(c, t, x + 143, y + 57);
				inset(c, t, x + 59, y + 12, 58, 58);
				c.roundRect(x + 119, y + 13, 12, 56, 3, ColorUtil.withAlpha(t.textDim.argb(), 30));
			}
			// The map's preview.
			case CartographyTableScreen s -> {
				resultCell(c, t, x + 145, y + 39);
				inset(c, t, x + 66, y + 13, 68, 68);
			}
			// The scroll track; on the inventory tab your figure's frame, on the search tab its field.
			case CreativeModeInventoryScreen s -> {
				c.roundRect(x + 175, y + 18, 12, 112, 3, ColorUtil.withAlpha(t.textDim.argb(), 30));
				if (texture.endsWith("tab_inventory.png")) {
					c.roundRect(x + 73, y + 6, 32, 43, cellRounding(t), t.surface.argb());
					c.outline(x + 73, y + 6, 32, 43, cellRounding(t), 1, t.inactiveBorder.argb());
				} else if (texture.endsWith("tab_item_search.png")) {
					float r = Math.min(t.rounding.get(), 4);
					c.roundRect(x + 79, y + 4, 88, 12, r, t.surface.argb());
					c.outline(x + 79, y + 4, 88, 12, r, 1, t.inactiveBorder.argb());
				}
			}
			// The animal's frame, as your figure's is in the inventory.
			case AbstractMountInventoryScreen<?> s -> {
				c.roundRect(x + 26, y + 18, 52, 52, cellRounding(t), t.surface.argb());
				c.outline(x + 26, y + 18, 52, 52, cellRounding(t), 1, t.inactiveBorder.argb());
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
			// Its fuel under the blaze powder, its progress under the ingredient (Minecraft has both off to the side).
			case BrewingStandScreen s -> {
				c.roundRect(x + BREW_FUEL[0], y + BREW_FUEL[1], BREW_FUEL[2], BREW_FUEL[3], BREW_FUEL[3] / 2f, ColorUtil.withAlpha(t.textDim.argb(), 50));
				centred(c, ARROW_DOWN, x + BREW_ARROW[0], y + BREW_ARROW[1], BREW_ARROW[2], BREW_ARROW[3], dim);
			}
			default -> {
			}
		}
	}

	/** A sunken area behind a list (a stonecutter's recipes, a villager's offers). */
	private static void inset(UiRenderer c, ThemeSettings t, float x, float y, float w, float h) {
		float r = cellRounding(t);
		c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(0xFF000000, 0x30));
		c.outline(x, y, w, h, r, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
	}

	/** A larger cell under what a workstation makes, as Minecraft frames it. */
	private static void resultCell(UiRenderer c, ThemeSettings t, float slotX, float slotY) {
		float r = cellRounding(t);
		c.roundRect(slotX - 4.5f, slotY - 4.5f, 25, 25, r, t.surface.argb());
		c.outline(slotX - 4.5f, slotY - 4.5f, 25, 25, r, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
	}

	/**
	 * Part of a progress sprite (Minecraft draws {@code w} by {@code h} of it at {@code x, y}, from {@code u, v} in its
	 * {@code texW} by {@code texH}): a furnace's flame and arrow, a brewing stand's fuel, progress and bubbles, the outline
	 * {@link #decorate} drew filled in the theme as far as Minecraft would. False to let Minecraft draw it.
	 */
	public static boolean spritePart(GuiGraphicsExtractor ctx, Identifier sprite, int texW, int texH, int u, int v, int x, int y, int w, int h) {
		if (!Myriad.isReady() || !theme().themedScreens.get()) return false;
		String path = sprite.getPath();
		// The whole sprite's box, of which x, y, w, h is the part shown.
		int fx = x - u, fy = y - v;
		// As wide as the slot above it, so the flame centres under it; it burns down from the top.
		if (path.endsWith("/lit_progress")) return part(ctx, fx, y, texW + 2, h, c -> centred(c, FIRE, fx, fy, texW + 2, texH, theme().yellow.argb()));
		if (path.endsWith("/burn_progress")) return part(ctx, x, y, w, h, c -> centred(c, ARROW, fx, fy, texW, texH, theme().accent.argb()));
		// Its bubbles, rising as it brews: little circles in the accent, shown as far up as Minecraft would.
		if (path.equals("container/brewing_stand/bubbles")) return part(ctx, x, y, w, h, c -> {
			int color = ColorUtil.withAlpha(theme().accent.argb(), 0xC0);
			for (float[] b : BUBBLES) c.circle(fx + b[0], fy + b[1], b[2], color);
		});
		// A brewing stand's, moved where decorate put their outlines (from Minecraft's places, fx, fy): the same share filled.
		// A mount's chest slots: under the theme's cells.
		if (path.endsWith("/chest_slots")) return screenThemed();
		// A villager's experience towards its next level, and what this trade would add.
		if (path.equals("container/villager/experience_bar_current") || path.equals("container/villager/experience_bar_result")) {
			boolean result = path.endsWith("result");
			return w <= 0 || posed(ctx, c -> {
				ThemeSettings t = theme();
				if (result) c.roundRect(x, y + 1, w, h - 2, (h - 2) / 2f, ColorUtil.withAlpha(t.accent.argb(), 0x70));
				else c.gradientRect(x, y + 1, Math.max(w, h - 2), h - 2, (h - 2) / 2f, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), 0);
			});
		}
		if (path.equals("container/brewing_stand/brew_progress")) {
			int left = fx - 97, top = fy - 16;
			float bx = left + BREW_ARROW[0], by = top + BREW_ARROW[1], bh = BREW_ARROW[3] * h / (float) texH;
			return part(ctx, (int) bx, (int) by, BREW_ARROW[2], Math.round(bh), c -> centred(c, ARROW_DOWN, bx, by, BREW_ARROW[2], BREW_ARROW[3], theme().accent.argb()));
		}
		if (path.equals("container/brewing_stand/fuel_length")) {
			int left = fx - 60, top = fy - 44;
			float bw = Math.max(BREW_FUEL[3], BREW_FUEL[2] * w / (float) texW);
			return w <= 0 || posed(ctx, c -> {
				ThemeSettings t = theme();
				c.gradientRect(left + BREW_FUEL[0], top + BREW_FUEL[1], bw, BREW_FUEL[3], BREW_FUEL[3] / 2f, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), 0);
			});
		}
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
	 * the recipe book's recipe slots (dim red where it can't be made, a dot for one with alternatives), its tabs
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
				c.roundRect(x, y, w - 2, h - 2, r, t.surface.argb());
				if (path.contains("uncraftable")) c.roundRect(x, y, w - 2, h - 2, r, ColorUtil.withAlpha(t.red.argb(), 0x40));
				c.outline(x, y, w - 2, h - 2, r, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x80));
				// More than one recipe makes it: a dot in its corner.
				if (path.contains("many")) c.circle(x + w - 6, y + 3.5f, 1.25f, t.accent.argb());
			});
			// A tab squashes up and down while it has new recipes (Minecraft scales it about its middle): its box is mapped
			// through that rather than the renderer scaling, which is the same both ways.
			// A tab's cell in the bar beside the book (recipeBook), the selected one highlighted as the hotbar's. Minecraft
			// draws the selected one 2 further out, and squashes a tab up and down about its middle while it has new
			// recipes: its box is mapped through that by hand.
			case "recipe_book/tab", "recipe_book/tab_selected" -> {
				var pose = ctx.pose();
				boolean selected = path.endsWith("selected");
				float tx = x + (selected ? 2 : 0);
				float bx = pose.m00() * (tx + 2) + pose.m20(), by = pose.m11() * (y + 2) + pose.m21(), bw = pose.m00() * (w - 4), bh = pose.m11() * (h - 4);
				MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> cell(c, theme(), bx, by, bw, bh, selected, !selected && hovered(bx, by, bw, bh)));
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
			// A stonecutter's recipe buttons, the selected one in the accent.
			case "container/stonecutter/recipe", "container/stonecutter/recipe_highlighted", "container/stonecutter/recipe_selected" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = cellRounding(t);
				boolean selected = path.endsWith("selected"), hover = path.endsWith("highlighted");
				c.roundRect(x + 0.5f, y + 0.5f, w - 1, h - 1, r, selected ? ColorUtil.withAlpha(t.accent.argb(), 0x38) : hover ? t.surfaceHover.argb() : t.surface.argb());
				c.outline(x + 0.5f, y + 0.5f, w - 1, h - 1, r, 1, selected || hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
			});
			// A loom's pattern buttons, the selected one in the accent.
			case "container/loom/pattern", "container/loom/pattern_highlighted", "container/loom/pattern_selected" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = cellRounding(t);
				boolean selected = path.endsWith("selected"), hover = path.endsWith("highlighted");
				c.roundRect(x, y, w, h, r, selected ? ColorUtil.withAlpha(t.accent.argb(), 0x38) : hover ? t.surfaceHover.argb() : t.surface.argb());
				c.outline(x, y, w, h, r, 1, selected || hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
			});
			// A beacon's buttons (its powers, confirm and cancel) and the icons of the last two.
			case "container/beacon/button", "container/beacon/button_highlighted", "container/beacon/button_selected", "container/beacon/button_disabled" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = Math.min(t.rounding.get(), 4);
				boolean selected = path.endsWith("selected"), hover = path.endsWith("highlighted"), off = path.endsWith("disabled");
				c.roundRect(x, y, w, h, r, selected ? ColorUtil.withAlpha(t.accent.argb(), 0x38) : off ? ColorUtil.withAlpha(t.surface.argb(), 0x50) : hover ? t.surfaceHover.argb() : t.surface.argb());
				c.outline(x, y, w, h, r, 1, selected || hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
			});
			case "container/beacon/confirm" -> posed(ctx, c -> centred(c, CHECK, x, y, w, h, theme().accent.argb()));
			case "container/beacon/cancel" -> posed(ctx, c -> centred(c, CROSS, x, y, w, h, theme().red.argb()));
			// A creative inventory's tabs, in a bar above the window and one below as the hotbar holds its items (Minecraft's
			// tab is 26 by 32, reaching 4 into the window), the selected one highlighted as the hotbar's.
			case String tab when tab.startsWith("container/creative_inventory/tab_") -> posed(ctx, c -> {
				ThemeSettings t = theme();
				boolean selected = tab.contains("_selected"), top = tab.contains("tab_top");
				// Under the row's first tab drawn: the selected one is drawn after the window, the rest before.
				int bar = top ? 1 : 2;
				if ((tabBars & bar) == 0 && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreenAccessor a) {
					tabBars |= bar;
					window(c, t, a.myriad$leftPos(), top ? y + 1 : y + 4 + BAR_GAP, a.myriad$imageWidth(), 24, false);
				}
				cell(c, t, x + 2, top ? y + 2 : y + 4 + BAR_GAP + 1, 22, selected, hovered(x + 2, top ? y + 2 : y + 8, 22, 22));
			});
			// List scroll thumbs.
			case "container/creative_inventory/scroller", "container/creative_inventory/scroller_disabled",
			     "container/stonecutter/scroller", "container/stonecutter/scroller_disabled", "container/villager/scroller", "container/villager/scroller_disabled",
			     "container/loom/scroller", "container/loom/scroller_disabled" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				c.roundRect(x + 1, y, w - 2, h, (w - 2) / 2f, path.endsWith("disabled") ? ColorUtil.withAlpha(t.textDim.argb(), 0x60) : t.accent.argb());
			});
			// An enchanting table's three choices and the level each costs.
			case "container/enchanting_table/enchantment_slot", "container/enchanting_table/enchantment_slot_highlighted",
			     "container/enchanting_table/enchantment_slot_disabled" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = Math.min(t.rounding.get(), 4);
				boolean off = path.endsWith("disabled"), hover = path.endsWith("highlighted");
				c.roundRect(x, y + 0.5f, w, h - 1, r, off ? ColorUtil.withAlpha(t.surface.argb(), 0x50) : hover ? t.surfaceHover.argb() : t.surface.argb());
				c.outline(x, y + 0.5f, w, h - 1, r, 1, hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
			});
			case "container/enchanting_table/level_1", "container/enchanting_table/level_2", "container/enchanting_table/level_3",
			     "container/enchanting_table/level_1_disabled", "container/enchanting_table/level_2_disabled", "container/enchanting_table/level_3_disabled" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				boolean off = path.endsWith("disabled");
				String level = path.substring("container/enchanting_table/level_".length(), "container/enchanting_table/level_".length() + 1);
				c.circle(x + w / 2f, y + h / 2f, w / 2f - 1, off ? ColorUtil.withAlpha(t.textDim.argb(), 0x40) : ColorUtil.withAlpha(t.accent.argb(), 0x40));
				float size = c.defaultFontSize();
				c.text(FontFamily.SANS_BOLD, size, level, x + (w - c.textWidth(FontFamily.SANS_BOLD, size, level)) / 2, y + (h - c.textHeight(FontFamily.SANS_BOLD, size)) / 2,
					off ? t.textDim.argb() : t.accent.argb());
			});
			// A villager's offer: the arrow from price to goods, the cross over one sold out, the line through a discounted price.
			case "container/villager/trade_arrow", "container/villager/trade_arrow_out_of_stock" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				centred(c, ARROW, x, y, w, h, path.endsWith("stock") ? ColorUtil.withAlpha(t.red.argb(), 0xC0) : t.textDim.argb());
			});
			case "container/villager/out_of_stock" -> posed(ctx, c -> centred(c, CROSS, x, y, w, h, theme().red.argb()));
			case "container/villager/discount_strikethrough" -> posed(ctx, c -> c.rect(x, y + h / 2f - 0.5f, w, 1, theme().red.argb()));
			case "container/villager/experience_bar_background" -> posed(ctx, c -> c.roundRect(x, y + 1, w, h - 2, (h - 2) / 2f, ColorUtil.withAlpha(theme().textDim.argb(), 50)));
			// A horse's, llama's or other mount's slots: the theme's cells are drawn under every slot already.
			case String slot when slot.startsWith("container/") && slot.endsWith("/slot") || slot.equals("container/slot") -> screenThemed();
			// What a workstation can't do with what's in it (an anvil's too costly repair, say).
			case "container/anvil/error", "container/smithing/error", "container/grindstone/error", "container/loom/error" -> posed(ctx, c -> centred(c, CROSS, x, y, w, h, theme().red.argb()));
			case "container/anvil/text_field", "container/anvil/text_field_disabled" -> posed(ctx, c -> {
				ThemeSettings t = theme();
				float r = Math.min(t.rounding.get(), 4), fx = x - ANVIL_NAME_SHIFT;
				c.roundRect(fx, y, w, h, r, path.endsWith("disabled") ? ColorUtil.withAlpha(t.surface.argb(), 0x80) : t.surface.argb());
				c.outline(fx, y, w, h, r, 1, t.inactiveBorder.argb());
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

	/** Whether the open screen is drawn in the theme: some sprites (a button, a text box, a slot) are themed only there. */
	private static boolean screenThemed() {
		return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen && themed(screen);
	}

	/**
	 * A button on a screen drawn in the theme (a villager's offers) or the pause menu, as a theme button, its label Minecraft's; {@code argb}
	 * is the tint Minecraft gives it (its fade). False elsewhere, or for another sprite.
	 */
	public static boolean button(GuiGraphicsExtractor ctx, Identifier sprite, int x, int y, int w, int h, int argb) {
		String path = sprite.getPath();
		// The pause menu's too: its buttons are the theme's while Themed Screens is on, and Myriad's own title screen (with
		// Minecraft's title screen, its pause menu is Minecraft's too).
		boolean pause = Myriad.isReady() && theme().themedScreens.get() && theme().titleScreen.get() && Minecraft.getInstance().gui.screen() instanceof PauseScreen;
		if (!path.startsWith("widget/button") || !screenThemed() && !pause) return false;
		return posed(ctx, c -> {
			ThemeSettings t = theme();
			boolean off = path.endsWith("disabled"), hover = path.endsWith("highlighted");
			float r = Math.min(t.rounding.get(), 4);
			c.push();
			c.alpha(ColorUtil.alpha(argb) / 255f);
			c.roundRect(x, y, w, h, r, off ? ColorUtil.withAlpha(t.surface.argb(), 0x50) : hover ? t.surfaceHover.argb() : t.surface.argb());
			c.outline(x, y, w, h, r, 1, hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
			c.pop();
		});
	}

	/**
	 * Text a themed screen draws in Minecraft's dark label grey or its enchanting table's browns and yellow, in the
	 * theme's text colours instead (they're unreadable on a dark window). Others are left as they are.
	 */
	public static int textColor(int argb) {
		if (!Myriad.isReady() || !screenThemed()) return argb;
		ThemeSettings t = theme();
		return switch (argb & 0xFFFFFF) {
			case 0x404040 -> t.textDim.argb();
			// The enchanting table's: its choices' words, the one under the mouse, and the levels they cost.
			case 0x685E4A -> t.textDim.argb();
			case 0x342F25 -> ColorUtil.withAlpha(t.textDim.argb(), 0x70);
			case 0xFFFF80 -> t.text.argb();
			case 0x80FF20 -> t.accent.argb();
			case 0x407F10 -> ColorUtil.withAlpha(t.textDim.argb(), 0x90);
			default -> argb;
		};
	}

	/** An icon from the theme's icon font, centred in the box at {@code x, y}. */
	private static void centred(UiRenderer c, String icon, float x, float y, float w, float h, int color) {
		float size = c.defaultFontSize();
		c.text(FontFamily.MONO, size, icon, x + (w - c.textWidth(FontFamily.MONO, size, icon)) / 2, y + (h - c.textHeight(FontFamily.MONO, size)) / 2, color);
	}

	/** The recipe book's own window, in place of its texture at {@code x, y} ({@code w} by {@code h}), and the bar its {@code tabs} tabs stand in; false to let Minecraft draw it. */
	public static boolean recipeBook(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int tabs) {
		if (!Myriad.isReady() || !theme().themedScreens.get()) return false;
		return posed(ctx, c -> {
			ThemeSettings t = theme();
			window(c, t, x, y, w, h, true);
			// Minecraft's tabs are 35 by 27, 27 apart from 3 below the book's top, here out to BAR_GAP left of it.
			if (tabs > 0) window(c, t, x - 30 - recipeTabsOut(), y + 3, 35, tabs * 27, false);
		});
	}

	/** How much further out than Minecraft's the recipe book's tabs stand (Minecraft tucks them 5 under the book): 0 unthemed. */
	public static int recipeTabsOut() {
		return Myriad.isReady() && theme().themedScreens.get() ? 5 + (int) BAR_GAP : 0;
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

	private static void paint(UiRenderer c, AbstractContainerScreen<?> screen, String texture) {
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
		// Titles centred over the window, but your inventory's (Crafting), over its grid beside your figure.
		float tx = !inventory ? x + (w - c.textWidth(FontFamily.SANS_BOLD, size, title)) / 2 : x + s.myriad$titleLabelX();
		// A villager's, a beacon's and the creative inventory's screens draw their own labels (a villager's level beside its
		// name, a beacon's powers, the tab's name), only recoloured (textColor).
		boolean ownLabels = screen instanceof MerchantScreen || screen instanceof BeaconScreen || screen instanceof CreativeModeInventoryScreen;
		if (!ownLabels) c.text(FontFamily.SANS_BOLD, size, title, tx, y + s.myriad$titleLabelY(), theme.text.argb());
		if (!inventory && !ownLabels) c.text(s.myriad$playerInventoryTitle().getString(), x + s.myriad$inventoryLabelX(), y + s.myriad$inventoryLabelY(), theme.textDim.argb());

		float cr = cellRounding(theme);
		int edge = ColorUtil.withAlpha(theme.inactiveBorder.argb(), 0x50);
		for (Slot slot : screen.getMenu().slots) {
			if (!slot.isActive()) continue;
			c.roundRect(x + slot.x + CELL_AT, y + slot.y + CELL_AT, CELL, CELL, cr, theme.surface.argb());
			c.outline(x + slot.x + CELL_AT, y + slot.y + CELL_AT, CELL, CELL, cr, 1, edge);
		}
		decorate(c, theme, screen, texture, x, y);
		if (inventory) {
			// Your figure's frame, and the arrow from the crafting grid to what it makes.
			c.roundRect(x + 26, y + 8, 49, 70, cr, theme.surface.argb());
			c.outline(x + 26, y + 8, 49, 70, cr, 1, theme.inactiveBorder.argb());
			c.text(FontFamily.MONO, size, Icons.ARROW_RIGHT, x + 138, y + 36 - c.textHeight(FontFamily.MONO, size) / 2, theme.textDim.argb());
		}

		if (b > 0) c.gradientOutline(x, y, w, h, r, b, theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
		tabs(c, theme, screen, x, y, b);
	}

	/**
	 * The swap bar above the window (ContainerTabs), as the hotbar is drawn: a bar of cells, one per container to switch
	 * to (your head for your inventory), the one showing highlighted, one out of reach dim. To the window's left when
	 * there's no room above. Where the cells were is kept for clicks. Not over the creative inventory, whose own tabs stand
	 * there.
	 */
	private static void tabs(UiRenderer c, ThemeSettings t, AbstractContainerScreen<?> screen, float x, float y, float b) {
		tabRects.clear();
		tabScreen = screen;
		if (screen instanceof CreativeModeInventoryScreen) return;
		ContainerTabs bar = MyriadImpl.get().containerTabs();
		var tabs = bar.tabs();
		tabList = null;
		if (tabs.size() < 2) return;
		// As many in a row as the window is wide (a column as it's high, to its left), more rows above (columns further out).
		AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
		int perRow = Math.max(1, (a.myriad$imageWidth() - 2) / (int) TAB_STEP), perColumn = Math.max(1, (a.myriad$imageHeight() - 2) / (int) TAB_STEP);
		int rows = Mth.ceil(tabs.size() / (float) perRow);
		boolean side = y - BAR_GAP - (rows * TAB_STEP + 2) < 2;
		int across = side ? Math.min(tabs.size(), perColumn) : Math.min(tabs.size(), perRow), lines = Mth.ceil(tabs.size() / (float) across);
		// Wrapped, it's as long as the window's side, its cells still filling from the start.
		float length = lines > 1 ? side ? a.myriad$imageHeight() : a.myriad$imageWidth() : across * TAB_STEP + 2, depth = lines * TAB_STEP + 2;
		float bx = side ? x - BAR_GAP - depth : x, by = side ? y : y - BAR_GAP - depth;
		window(c, t, bx, by, side ? depth : length, side ? length : depth, false);
		for (int i = 0; i < tabs.size(); i++) {
			ContainerTabs.Tab tab = tabs.get(i);
			int along = i % across, line = i / across;
			float cx = side ? bx + 2 + line * TAB_STEP : bx + 2 + along * TAB_STEP, cy = side ? by + 2 + along * TAB_STEP : by + 2 + line * TAB_STEP;
			boolean current = bar.isCurrent(tab);
			c.push();
			if (!bar.reachable(tab)) c.alpha(0.45f);
			cell(c, t, cx, cy, TAB_CELL, current, !current && hovered(cx, cy, TAB_CELL, TAB_CELL));
			c.item(tab.icon(), cx + 1, cy + 1, 16, false);
			c.pop();
			tabRects.add(new float[]{cx, cy, TAB_CELL, TAB_CELL});
		}
		tabList = tabs;
	}

	/** A bar's cell, as the hotbar's: the selected one in the accent. */
	private static void cell(UiRenderer c, ThemeSettings t, float x, float y, float size, boolean selected, boolean hover) {
		cell(c, t, x, y, size, size, selected, hover);
	}

	private static void cell(UiRenderer c, ThemeSettings t, float x, float y, float w, float h, boolean selected, boolean hover) {
		float r = cellRounding(t);
		c.roundRect(x, y, w, h, r, hover ? t.surfaceHover.argb() : t.surface.argb());
		if (selected) {
			c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(t.accent.argb(), 0x40));
			c.outline(x, y, w, h, r, 1, t.accent.argb());
		} else if (hover) c.outline(x, y, w, h, r, 1, ColorUtil.withAlpha(t.accent.argb(), 0x90));
	}

	/** The mouse in GUI pixels. */
	private static double[] mouse() {
		var mc = Minecraft.getInstance();
		var window = mc.getWindow();
		return new double[]{mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth(), mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight()};
	}

	private static boolean hovered(float x, float y, float w, float h) {
		double[] m = mouse();
		return m[0] >= x && m[0] < x + w && m[1] >= y && m[1] < y + h;
	}

	/** How far a creative inventory's tab icon moves to sit in its cell in the bar (up for the top row's, down for the bottom's). */
	public static int creativeIconShift(boolean top) {
		return Myriad.isReady() && theme().themedScreens.get() ? top ? -4 : 4 : 0;
	}

	/** A click at {@code mx, my} on {@code screen}'s swap bar switches to that tab; true if it was on the bar (it goes no further). */
	public static boolean tabClicked(AbstractContainerScreen<?> screen, double mx, double my, int button) {
		if (!Myriad.isReady() || screen != tabScreen || tabList == null) return false;
		for (int i = 0; i < tabRects.size() && i < tabList.size(); i++) {
			float[] rr = tabRects.get(i);
			if (mx < rr[0] || mx >= rr[0] + rr[2] || my < rr[1] || my >= rr[1] + rr[3]) continue;
			if (button == 0) MyriadImpl.get().containerTabs().switchTo(tabList.get(i));
			else if (button == 1) MyriadImpl.get().containerTabs().letGo(tabList.get(i));
			return true;
		}
		return false;
	}
}
