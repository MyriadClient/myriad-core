package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.ui.draw.Canvas;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The content of a window. Panels draw in local coordinates: (0,0) is the top-left of the content area (inside the
 * border and title bar) and {@code width}/{@code height} is its size, in UI units. For widget-based panels extend
 * {@link WidgetPanel}; for HUD elements, {@link dev.myriadclient.api.ui.hud.HudPanel}.
 * <p>
 * A panel lives in a window in the UI or, if its type is a HUD element, on the {@code HUD} workspace and nowhere
 * else. Panels may expose {@link #settings} (shown in the panel's settings window and saved with
 * the layout). Register a panel type with {@code AddonContext.registerPanel}; the UI makes one panel per window
 * through the type's factory, and keeps it until the window closes.
 * <p>
 * Every method is called on the render thread. Whatever a panel throws is caught and logged (once per window), so a
 * broken panel can't take the menu down: a render that throws shows the error in the window.
 *
 * <pre>{@code
 * public final class ClockPanel extends Panel {
 *     @Override
 *     public String title() { return "Clock"; }
 *
 *     @Override
 *     public void render(Canvas c, float width, float height, float mouseX, float mouseY) {
 *         String time = LocalTime.now().withNano(0).toString();
 *         float size = c.defaultFontSize() * 2;
 *         float w = c.textWidth(FontFamily.MONO, size, time);
 *         c.text(FontFamily.MONO, size, time, (width - w) / 2, (height - c.textHeight(FontFamily.MONO, size)) / 2,
 *             Myriad.ui().theme().accent.argb());
 *     }
 * }
 * // in the addon:
 * ctx.registerPanel("Clock", Icons.CLOCK, ClockPanel::new);
 * }</pre>
 */
public abstract class Panel {
	/** The game client. Bound again when Myriad starts, in case this class loaded before the client existed. */
	protected static Minecraft mc = Minecraft.getInstance();

	/**
	 * Per-instance options, saved with the window. Leave empty if the panel has none; when it has some, right-clicking
	 * the title bar (or the element, while editing the HUD) opens them in a settings window.
	 */
	public final Settings settings = new Settings();

	private @Nullable Window window;

	/** The title shown in the window's title bar (cut short to fit). Read every frame. */
	public abstract String title();

	/** Dim text after the title in the title bar (e.g. the addon a category window's modules come from), or null. */
	public @Nullable String subtitle() {
		return null;
	}

	/** A Nerd Font glyph ({@link Icons}) shown in the title bar and launcher, or null for the panel type's own icon. */
	public @Nullable String icon() {
		return null;
	}

	/**
	 * Draws the panel. Called every frame while visible, with the canvas clipped to the content area and translated so
	 * (0,0) is its top-left. {@code width}/{@code height} is the content area's size in UI units (animating while the
	 * window opens or moves). The mouse is in the same coordinates while the pointer is over this window and nothing
	 * covers it, and far outside (-10000) otherwise, so hover checks fail. A HUD element drawn in game, or while the HUD
	 * is being edited, gets no meaningful mouse position.
	 */
	public abstract void render(Canvas canvas, float width, float height, float mouseX, float mouseY);

	/**
	 * Natural size of the content, in UI units ({@link Rect#w()} and {@link Rect#h()}; x and y are ignored). For HUD
	 * elements it is their size (asked every frame; 80&times;20 if null); for windows it sets the initial size of a
	 * floating one (plus a margin and the title bar, at least 120&times;60 and at most the screen less 40). Return
	 * {@code null} for "no preference".
	 */
	public @Nullable Rect preferredSize(Canvas canvas) {
		return null;
	}

	/**
	 * A mouse press in the content area, in content coordinates and UI units; {@code button} is a GLFW button
	 * ({@code InputConstants.MOUSE_BUTTON_*}). The press goes to the panel whatever this returns; after it, the panel
	 * gets the drags and the release, even outside its area. Presses with the UI's mod key held move or resize the
	 * window instead. HUD elements get no mouse input.
	 */
	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		return false;
	}

	/** The release of a press that went to {@link #mouseClicked}, in content coordinates (possibly outside the area). */
	public boolean mouseReleased(float mouseX, float mouseY, int button) {
		return false;
	}

	/**
	 * The mouse moving with a button down after a press that went to {@link #mouseClicked}, in content coordinates;
	 * {@code dx}/{@code dy} is how far it moved since the last drag event (or the press), in UI units.
	 */
	public boolean mouseDragged(float mouseX, float mouseY, int button, float dx, float dy) {
		return false;
	}

	/**
	 * The wheel over the content, in content coordinates; {@code amount} is in notches, positive when the wheel turns
	 * up (scroll towards the top). Return whether it was used. Shift + wheel scrolls the workspace's layout instead
	 * where the layout scrolls sideways.
	 */
	public boolean mouseScrolled(float mouseX, float mouseY, float amount) {
		return false;
	}

	/**
	 * A key pressed while this window is focused. Return true to consume the key (e.g. while a text field is focused).
	 * {@code key} is its position (an {@code InputConstants.KEY_*} value), {@code scancode} what it types on the
	 * player's layout (as {@code KeyEvent}), {@code modifiers} {@code InputConstants.MOD_*} bits. While
	 * {@link #capturesKeyboard()} is true the panel sees every key first; otherwise the UI's mod-key shortcuts go first.
	 * Escape that the panel doesn't consume shuts the menu.
	 */
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return false;
	}

	/**
	 * A typed character, to the focused window's panel. Characters only arrive while {@link #capturesKeyboard()} is
	 * true (the game's text input is only on then), and not while the UI's mod key is held.
	 */
	public boolean charTyped(char chr, int modifiers) {
		return false;
	}

	/**
	 * True while the panel wants all keyboard input (a focused text field): keys come to it before the UI's shortcuts,
	 * single-key shortcuts are suppressed, and typed characters are delivered to {@link #charTyped}. Asked every frame.
	 */
	public boolean capturesKeyboard() {
		return false;
	}

	/**
	 * Called every client tick (20 a second) while the window is on the active workspace, or is a HUD element, whether
	 * or not the menu is showing and whether or not there's a world.
	 */
	public void tick() {
	}

	/**
	 * Called once the window holds this panel: when it's opened, and when it's restored from the saved layout (after
	 * {@link #load} and {@link #settings} were read back).
	 */
	public void onOpen() {
	}

	/** Called when the window is closed, or its panel is replaced. The game shutting down doesn't call it. */
	public void onClose() {
	}

	/**
	 * Extra instance state to persist (scroll position, selected tab, …), written into {@code out} whenever the UI's
	 * layout is saved. {@link #settings} are saved separately.
	 */
	public void save(JsonObject out) {
	}

	/** Reads back what {@link #save} wrote, when the window is restored; {@code in} is empty if nothing was saved. */
	public void load(JsonObject in) {
	}

	/**
	 * Controls for the window's title bar (a switch, icon buttons), right to left; see {@link TitleControl}. Asked
	 * every frame: return the same instances each time.
	 */
	public List<TitleControl> titleControls() {
		return List.of();
	}

	/**
	 * Tooltip for the hovered element, or null. Read after {@link #render} while the pointer is over this window (not
	 * while dragging). Lines split at {@code \n} and wrap; see
	 * {@link dev.myriadclient.api.ui.widget.Widget#badgeLine} for a line of pills.
	 */
	public @Nullable String tooltip() {
		return null;
	}

	/**
	 * The window showing this panel. Set as soon as the window is made, so it's null only inside the panel's
	 * constructor (and the type's factory); it stays set after the window closes.
	 */
	public @Nullable Window window() {
		return window;
	}

	/** Core's: binds the panel to its window. */
	@ApiStatus.Internal
	public void attach(Window window) {
		this.window = window;
	}

	/** Core's: binds {@link #mc} once the client exists. */
	@ApiStatus.Internal
	public static void bindClient(Minecraft client) {
		mc = client;
	}
}
