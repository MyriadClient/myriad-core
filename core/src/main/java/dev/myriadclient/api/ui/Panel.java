package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.Settings;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;

/**
 * The content of a window. Panels draw in local coordinates: (0,0) is the top-left of the content area and
 * {@code width}/{@code height} is its size, in UI units. For widget-based panels extend {@link WidgetPanel}.
 * <p>
 * The same panel can live as a tiled window on the desktop or as a pinned HUD element on the {@code HUD} workspace
 * (if its type allows it). Panels may expose {@link #settings} (shown in the panel's settings window and saved with
 * the layout).
 */
public abstract class Panel {
	/** The game client. Bound again when Myriad starts, in case this class loaded before the client existed. */
	protected static Minecraft mc = Minecraft.getInstance();

	/** Per-instance options, saved with the window. Leave empty if the panel has none. */
	public final Settings settings = new Settings();

	private Window window;

	public abstract String title();

	/** Dim text after the title in the title bar (e.g. the addon a category window's modules come from), or null. */
	public String subtitle() {
		return null;
	}

	/** A Nerd Font glyph shown in the title bar and launcher, or null. */
	public String icon() {
		return null;
	}

	/** Draws the panel. Called every frame while visible. */
	public abstract void render(Canvas canvas, float width, float height, float mouseX, float mouseY);

	/**
	 * Natural size of the content, used for HUD elements (which size to their content) and as the initial size of
	 * floating windows. Return {@code null} for "no preference".
	 */
	public Rect preferredSize(Canvas canvas) {
		return null;
	}

	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		return false;
	}

	public boolean mouseReleased(float mouseX, float mouseY, int button) {
		return false;
	}

	public boolean mouseDragged(float mouseX, float mouseY, int button, float dx, float dy) {
		return false;
	}

	public boolean mouseScrolled(float mouseX, float mouseY, float amount) {
		return false;
	}

	/** Return true to consume the key (e.g. while a text field is focused). */
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return false;
	}

	public boolean charTyped(char chr, int modifiers) {
		return false;
	}

	/** True while the panel wants all keyboard input (a focused text field), suppressing single-key WM shortcuts. */
	public boolean capturesKeyboard() {
		return false;
	}

	/** Called every client tick while the panel is open. */
	public void tick() {
	}

	public void onOpen() {
	}

	public void onClose() {
	}

	/** Extra instance state to persist (scroll position, selected tab, …). */
	public void save(JsonObject out) {
	}

	public void load(JsonObject in) {
	}

	/** Tooltip for the hovered element, or null. Read after {@link #render}. */
	public String tooltip() {
		return null;
	}

	public Window window() {
		return window;
	}

	@ApiStatus.Internal
	public void attach(Window window) {
		this.window = window;
	}

	@org.jetbrains.annotations.ApiStatus.Internal
	public static void bindClient(Minecraft client) {
		mc = client;
	}
}
