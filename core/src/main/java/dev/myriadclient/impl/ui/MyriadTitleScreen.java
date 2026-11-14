package dev.myriadclient.impl.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.realmsclient.RealmsMainScreen;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.draw.Animated;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.render.title.TitleScene;
import dev.myriadclient.impl.render.title.TitleSceneRenderer;
import dev.myriadclient.impl.render.title.TitleSceneState;
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
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Myriad's title screen, in place of Minecraft's while Theme → Preferences → Themed Screens is on: the logo over the
 * theme's own colours, the usual destinations as a column of buttons (plus the Myriad menu), and the keys that open the
 * menu. Drawn by Myriad's renderer, so it follows the theme like the UI does. Arrow keys and Enter work too.
 * <p>
 * With the width for it, the column moves left, frosted, over a {@link TitleScene} you stand in:
 * your head follows the mouse, a drag turns you and you settle back, a click makes you swing.
 */
public final class MyriadTitleScreen extends Screen {
	private static final Identifier LOGO_TEXTURE = Identifier.fromNamespaceAndPath("myriad", "textures/logo.png");
	private static final float BUTTON_W = 150, BUTTON_H = 20, BUTTON_GAP = 6, LOGO = 76, SMALL_LOGO = 40;
	/** The width (units) the column and the scene both need; narrower, the column has the screen to itself. */
	private static final float SCENE_MIN_WIDTH = 560;
	/** Where you're seen from when nothing's moving you (degrees): turned towards the buttons, from a little above. */
	private static final float REST_YAW = -16, REST_PITCH = 7;
	private static final long SWING_MS = 320;

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

	private TitleScene scene = TitleScene.pick();
	private final long opened = System.nanoTime();
	private long lastFrame = opened;
	private float tiltX, tiltY, headYaw, headPitch;
	/** Turned by hand, in degrees; settles back to 0 once let go. */
	private float dragTurn;
	private boolean dragging, dragged;
	private float dragFromX;
	private long swingStart;

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
		add("Options", () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options)), () -> true, false);
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

	private boolean withScene() {
		return wm.unitsWide() >= SCENE_MIN_WIDTH;
	}

	private float logo() {
		return withScene() ? SMALL_LOGO : LOGO;
	}

	/** The logo's and name's height together: side by side beside the scene, one over the other without it. */
	private float headerHeight(UiRenderer c) {
		float name = c.textHeight(FontFamily.SANS, titleSize(c)) + 4 + c.textHeight();
		return withScene() ? Math.max(logo(), name) : logo() + 10 + name;
	}

	/** Top of the logo: the whole column (logo, name, version, buttons) centred on the screen. */
	private float top(UiRenderer c) {
		float column = headerHeight(c) + 26 + entries.size() * (BUTTON_H + BUTTON_GAP) - BUTTON_GAP;
		return Math.max(8, (wm.unitsHigh() - column) / 2 - 10);
	}

	private float buttonsTop(UiRenderer c) {
		return top(c) + headerHeight(c) + 26;
	}

	private float buttonX() {
		return withScene() ? Math.max(30, wm.unitsWide() * 0.085f) : (wm.unitsWide() - BUTTON_W) / 2;
	}

	/** Where the scene's space starts: the screen from the column's edge. */
	private float sceneSpace() {
		return buttonX() + BUTTON_W;
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
			if (withScene()) {
				// Blur is off out of a world, with nothing to blur; here there's the scene (FrameBlur.from).
				c.setBlur(theme.blur.get(), theme.blurPasses.get(), theme.blurSize.get().floatValue());
				scene(c, theme, w, h);
			}

			float t = intro.get();
			c.push();
			c.alpha(t);
			c.translate(0, (1 - t) * 10);
			int accent = theme.accent.argb();
			if (withScene()) {
				float bx = buttonX(), y = top(c), lh = headerHeight(c);
				c.shadow(bx, y + (lh - logo()) / 2, logo(), logo(), logo() / 2, 40, ColorUtil.withAlpha(accent, 0x28));
				c.texture(LOGO_TEXTURE, bx, y + (lh - logo()) / 2, logo(), logo(), 0, 0, 1, 1, accent);
				float ts = titleSize(c), tx = bx + logo() + 10, th = c.textHeight(FontFamily.SANS, ts) + 4 + c.textHeight(), ty = y + (lh - th) / 2;
				c.text(FontFamily.SANS, ts, "Myriad", tx, ty, theme.text.argb());
				c.text(version(), tx, ty + c.textHeight(FontFamily.SANS, ts) + 4, theme.textDim.argb());
			} else {
				float cx = w / 2, y = top(c);
				// A soft light in the accent behind the logo.
				c.shadow(cx - LOGO / 2, y, LOGO, LOGO, LOGO / 2, 70, ColorUtil.withAlpha(accent, 0x30));
				c.texture(LOGO_TEXTURE, cx - LOGO / 2, y, LOGO, LOGO, 0, 0, 1, 1, accent);
				y += LOGO + 10;
				float ts = titleSize(c);
				String name = "Myriad";
				c.text(FontFamily.SANS, ts, name, cx - c.textWidth(FontFamily.SANS, ts, name) / 2, y, theme.text.argb());
				y += c.textHeight(FontFamily.SANS, ts) + 4;
				c.text(version(), cx - c.textWidth(version()) / 2, y, theme.textDim.argb());
			}

			float bx = buttonX(), by = buttonsTop(c), r = theme.rounding.get();
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				boolean on = e.enabled.getAsBoolean();
				Animated a = hover.get(i);
				a.animateTo(i == selected && on ? 1 : 0, 140, theme.bezier());
				float k = a.get(), yy = by + i * (BUTTON_H + BUTTON_GAP);
				int fill = ColorUtil.lerp(theme.surface.argb(), theme.surfaceHover.argb(), k);
				// Frosted over the scene behind them.
				if (withScene()) {
					c.backdrop(bx, yy, BUTTON_W, BUTTON_H, r, 1);
					fill = ColorUtil.withAlpha(fill, theme.blur.get() ? 0x90 : 0xE0);
				}
				c.roundRect(bx, yy, BUTTON_W, BUTTON_H, r, fill);
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

	/** Shows another scene (the dev console's, for looking at each). */
	public void scene(TitleScene scene) {
		this.scene = scene;
	}

	private String version() {
		return Myriad.version() + " · Minecraft " + SharedConstants.getCurrentVersion().name();
	}

	/** You in your scene, with a light behind and motes rising through it. */
	private void scene(UiRenderer c, ThemeSettings theme, float w, float h) {
		long now = System.nanoTime();
		float seconds = (now - opened) / 1e9f, dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		// Past the buttons, nearer them than the far side of the screen; the scene carries on behind them.
		float space = w - sceneSpace(), cx = sceneSpace() + Math.min(space / 2, h * 0.6f), cy = h / 2;
		float sw = Math.min(w, h * 1.3f);
		int accent = theme.accent.argb();

		// Eased towards the mouse, so nothing snaps: the view leans a little, your head more.
		float ease = 1 - (float) Math.exp(-dt * 5);
		float nx = Math.clamp((mouseX - cx) / (w / 2), -1, 1), ny = Math.clamp((mouseY - cy) / (h / 2), -1, 1);
		tiltX += (nx * 5 - tiltX) * ease;
		tiltY += (ny * 2.5f - tiltY) * ease;
		headYaw += (nx * -42 + REST_YAW - headYaw) * ease;
		headPitch += (ny * 26 - headPitch) * ease;
		if (!dragging) dragTurn *= (float) Math.exp(-dt * 2.2f);

		float t = intro.get();
		float swing = swingStart == 0 ? 0 : (now - swingStart) / (SWING_MS * 1e6f);
		if (swing >= 1) {
			swing = 0;
			swingStart = 0;
		}
		float sway = (float) Math.sin(seconds * 0.17) * 1.5f;

		// A light behind you.
		c.push();
		c.alpha(t);
		float glow = Math.min(sw, h) * 0.5f;
		c.shadow(cx - glow / 2, cy - glow / 2 - h * 0.12f, glow, glow, glow / 2, glow * 0.8f, ColorUtil.withAlpha(accent, 0x2C));
		c.pop();

		int green = ColorUtil.lerp(0xFF6FAE4D, accent, 0.45f);
		c.titleScene(new TitleSceneState.View(scene, seconds, REST_YAW + tiltX + sway + dragTurn + (1 - t) * -45, REST_PITCH + tiltY, 0,
			headYaw, headPitch, swing, green, t, cx / (w / 2) - 1, 0, TitleSceneState.LOOK_AT, TitleSceneState.SHOW_HIGH, 0, 0, ItemStack.EMPTY, true), 0, 0, w, h);

		// Motes, each on its own slow way up; the same ones every time.
		c.push();
		c.alpha(t);
		for (int i = 0; i < 26; i++) {
			float a = hash(i, 1), b = hash(i, 2), d = hash(i, 3);
			float life = (seconds * (0.018f + d * 0.03f) + a) % 1;
			float x = cx + (b - 0.5f) * Math.min(sw, h * 1.1f) + (float) Math.sin(seconds * 0.3 + i) * 4;
			float y = cy + h * 0.32f - life * h * 0.75f;
			float fade = (float) Math.sin(life * Math.PI);
			// Big enough not to twinkle as they cross the pixels.
			c.circle(x, y, 0.9f + d * 0.7f, ColorUtil.withAlpha(accent, (int) (fade * (35 + d * 55))));
		}
		c.pop();
	}

	static float hash(int i, int salt) {
		int h = i * 73_856_093 ^ salt * 19_349_663;
		h = (h ^ h >>> 13) * 1_274_126_177;
		return ((h ^ h >>> 16) & 0xFFFF) / 65_535f;
	}

	// ---- input --------------------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (credits) {
			minecraft.gui.setScreen(new CreditsAndAttributionScreen(this));
			return true;
		}
		float x = wm.units(event.x());
		int at = buttonAt(x, wm.units(event.y()));
		if (at >= 0) {
			run(at);
			return true;
		}
		if (!withScene() || x < sceneSpace()) return false;
		dragging = true;
		dragged = false;
		dragFromX = x;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (!dragging) return false;
		float x = wm.units(event.x());
		if (Math.abs(x - dragFromX) > 2) dragged = true;
		if (dragged) dragTurn = Math.clamp(dragTurn + (x - dragFromX) * 0.7f, -160, 160);
		dragFromX = x;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (!dragging) return false;
		dragging = false;
		if (!dragged) swingStart = System.nanoTime();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int n = entries.size();
		switch (event.key()) {
			case InputConstants.KEY_DOWN, InputConstants.KEY_TAB -> selected = selected < 0 ? 0 : (selected + 1) % n;
			case InputConstants.KEY_UP -> selected = selected <= 0 ? n - 1 : selected - 1;
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> {
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
	public void removed() {
		TitleSceneRenderer.release();
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
