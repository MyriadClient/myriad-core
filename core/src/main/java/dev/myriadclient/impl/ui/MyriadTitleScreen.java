package dev.myriadclient.impl.ui;

import com.mojang.realmsclient.RealmsMainScreen;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.render.UiRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Myriad's title screen, in place of Minecraft's while Theme → Preferences → Title Screen is on: the logo over the
 * theme's own colours, the usual destinations as a column of buttons (plus the Myriad menu), and the keys that open the
 * menu. Drawn by Myriad's renderer, so it follows the theme like the desktop does. Arrow keys and Enter work too.
 */
public final class MyriadTitleScreen extends Screen {
	private static final Identifier LOGO_TEXTURE = Identifier.fromNamespaceAndPath("myriad", "textures/logo.png");
	private static final float BUTTON_W = 150, BUTTON_H = 20, BUTTON_GAP = 6, LOGO = 76;

	private record Entry(String label, Runnable run, BooleanSupplier enabled, boolean accent) {
	}

	private final WindowManager wm;
	private final List<Entry> entries = new ArrayList<>();
	private final List<Animated> hover = new ArrayList<>();
	private final Animated intro = new Animated(0);
	/** The button under the mouse or picked with the keys; -1 for none. */
	private int selected = -1;
	private float mouseX = -1, mouseY = -1;
	/** The selection follows the mouse while it's over a button; the keys take it over until the mouse moves. */
	private boolean hoveredByMouse;
	/** Where the buttons were drawn last frame, for clicks. */
	private float buttonsTop = -1;
	private boolean credits;

	public MyriadTitleScreen(WindowManager wm) {
		super(Component.translatable("narrator.screen.title"));
		this.wm = wm;
	}

	@Override
	protected void init() {
		entries.clear();
		hover.clear();
		add("Singleplayer", () -> minecraft.gui.setScreen(new SelectWorldScreen(this)), () -> true, false);
		add("Multiplayer", () -> minecraft.gui.setScreen(minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this)),
			minecraft::allowsMultiplayer, false);
		if (minecraft.allowsRealms()) add("Minecraft Realms", () -> minecraft.gui.setScreen(new RealmsMainScreen(this)), () -> true, false);
		add("Myriad", wm::open, () -> true, true);
		Screen mods = modMenu();
		if (mods != null) add("Mods", () -> minecraft.gui.setScreen(modMenu()), () -> true, false);
		add("Options", () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options, false)), () -> true, false);
		add("Quit Game", minecraft::stop, () -> true, false);
	}

	private void add(String label, Runnable run, BooleanSupplier enabled, boolean accent) {
		entries.add(new Entry(label, run, enabled, accent));
		hover.add(new Animated(0));
	}

	/** Mod Menu's list, when it's installed (it adds its button to Minecraft's title screen, which this replaces). */
	private Screen modMenu() {
		if (!FabricLoader.getInstance().isModLoaded("modmenu")) return null;
		try {
			return (Screen) Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen.class).newInstance(this);
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	// ---- layout -------------------------------------------------------------------------------------------------------

	private float titleSize(UiRenderer c) {
		return c.defaultFontSize() * 3.4f;
	}

	/** Top of the logo: the whole column (logo, name, version, buttons) centred on the screen. */
	private float top(UiRenderer c) {
		float column = LOGO + 10 + c.textHeight(FontFamily.SANS, titleSize(c)) + 4 + c.textHeight() + 26 + entries.size() * (BUTTON_H + BUTTON_GAP) - BUTTON_GAP;
		return Math.max(8, (wm.unitsHigh() - column) / 2 - 10);
	}

	private float buttonsTop(UiRenderer c) {
		return top(c) + LOGO + 10 + c.textHeight(FontFamily.SANS, titleSize(c)) + 4 + c.textHeight() + 26;
	}

	private float buttonX() {
		return (wm.unitsWide() - BUTTON_W) / 2;
	}

	private int buttonAt(float x, float y) {
		float bx = buttonX(), by = buttonsTop;
		if (by < 0 || x < bx || x >= bx + BUTTON_W || y < by) return -1;
		int i = (int) ((y - by) / (BUTTON_H + BUTTON_GAP));
		boolean inButton = (y - by) - i * (BUTTON_H + BUTTON_GAP) < BUTTON_H;
		return i < entries.size() && inButton ? i : -1;
	}

	// ---- drawing ------------------------------------------------------------------------------------------------------

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int guiX, int guiY, float delta) {
		ThemeSettings theme = wm.theme();
		intro.animateTo(1, 700, theme.bezier());
		wm.painter().paint(context, c -> {
			float w = wm.unitsWide(), h = wm.unitsHigh();
			buttonsTop = buttonsTop(c);
			float mx = wm.units(guiX), my = wm.units(guiY);
			if (mx != mouseX || my != mouseY) {
				mouseX = mx;
				mouseY = my;
				int at = buttonAt(mx, my);
				if (at >= 0) selected = at;
				else if (hoveredByMouse) selected = -1;
				hoveredByMouse = at >= 0;
			}
			// The theme's own surfaces, opaque: the bar's colour at the bottom, the windows' at the top.
			int top = ColorUtil.withAlpha(theme.windowBackground.argb(), 0xFF), bottom = ColorUtil.withAlpha(theme.barBackground.argb(), 0xFF);
			c.gradientRect(0, 0, w, h, 0, top, bottom, 90);

			float t = intro.get();
			c.push();
			c.alpha(t);
			c.translate(0, (1 - t) * 10);
			float cx = w / 2, y = top(c);
			int accent = theme.accent.argb();
			// A soft light in the accent behind the logo.
			c.shadow(cx - LOGO / 2, y, LOGO, LOGO, LOGO / 2, 70, ColorUtil.withAlpha(accent, 0x30));
			c.texture(LOGO_TEXTURE, cx - LOGO / 2, y, LOGO, LOGO, 0, 0, 1, 1, accent);
			y += LOGO + 10;
			float ts = titleSize(c);
			String name = "Myriad";
			c.text(FontFamily.SANS, ts, name, cx - c.textWidth(FontFamily.SANS, ts, name) / 2, y, theme.text.argb());
			y += c.textHeight(FontFamily.SANS, ts) + 4;
			String version = Myriad.version() + " · Minecraft " + SharedConstants.getCurrentVersion().name();
			c.text(version, cx - c.textWidth(version) / 2, y, theme.textDim.argb());

			float bx = buttonX(), by = buttonsTop(c), r = theme.rounding.get();
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				boolean on = e.enabled.getAsBoolean();
				Animated a = hover.get(i);
				a.animateTo(i == selected && on ? 1 : 0, 140, theme.bezier());
				float k = a.get(), yy = by + i * (BUTTON_H + BUTTON_GAP);
				c.roundRect(bx, yy, BUTTON_W, BUTTON_H, r, ColorUtil.lerp(theme.surface.argb(), theme.surfaceHover.argb(), k));
				if (k > 0.01f || e.accent) {
					c.push();
					c.alpha(e.accent ? Math.max(0.55f, k) : k);
					c.gradientOutline(bx, yy, BUTTON_W, BUTTON_H, r, Math.max(1, theme.borderSize.get()), theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
					c.pop();
				}
				if (k < 0.99f && !e.accent) c.outline(bx, yy, BUTTON_W, BUTTON_H, r, 1, ColorUtil.fade(theme.inactiveBorder.argb(), 1 - k));
				int color = !on ? theme.textDim.argb() : e.accent ? ColorUtil.lerp(accent, theme.text.argb(), k) : theme.text.argb();
				c.text(e.label, bx + (BUTTON_W - c.textWidth(e.label)) / 2, yy + (BUTTON_H - c.textHeight()) / 2, color);
			}
			c.pop();

			// The keys that open the menu, bottom left; Minecraft's notice (its credits on click), bottom right.
			float fy = h - c.textHeight() - 6;
			c.text(wm.openHint(), 6, fy, theme.textDim.argb());
			String notice = "Copyright Mojang AB. Do not distribute!";
			float nw = c.textWidth(notice), nx = w - nw - 6;
			credits = mouseX >= nx && mouseY >= fy && mouseY < fy + c.textHeight();
			c.text(notice, nx, fy, credits ? theme.text.argb() : theme.textDim.argb());
			if (credits) c.rect(nx, fy + c.textHeight(), nw, 0.75f, theme.text.argb());
		});
	}

	// ---- input --------------------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		if (credits) {
			minecraft.gui.setScreen(new CreditsAndAttributionScreen(this));
			return true;
		}
		int at = buttonAt(wm.units(event.x()), wm.units(event.y()));
		if (at < 0) return false;
		run(at);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int n = entries.size();
		switch (event.key()) {
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> selected = selected < 0 ? 0 : (selected + 1) % n;
			case GLFW.GLFW_KEY_UP -> selected = selected <= 0 ? n - 1 : selected - 1;
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
				if (selected >= 0) run(selected);
			}
			default -> {
				return false;
			}
		}
		hoveredByMouse = false;
		return true;
	}

	private void run(int i) {
		Entry e = entries.get(i);
		if (e.enabled.getAsBoolean()) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
			e.run.run();
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
