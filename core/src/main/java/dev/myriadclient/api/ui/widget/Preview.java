package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.ShapeBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A 3D view of something, for seeing what render settings do to it: an entity, you, a block (a chest, a shulker box),
 * or your first-person hands and held items (as View Model shapes them). Drag to turn it, right-drag or Shift-drag to
 * move it, scroll to zoom, double-click to put it back. The modules that are on draw on it as in the world: their
 * shader highlights ({@code HighlightEvent}) as they are, their boxes and lines through
 * {@link dev.myriadclient.api.event.events.PreviewEvent}. {@link #decorate} draws on this one preview with the same
 * shapes ({@link ShapeBuilder}, so a {@code BoxStyle} draws itself), in the subject's own coordinates: the subject's box
 * is passed in. Put one in a module's settings window with {@code Module.preview}, or in any window; players add
 * Preview windows of their own to a workspace.
 * <p>
 * Entities and you need a world: outside one, the preview shows only its background.
 *
 * <pre>{@code
 * @Override
 * public Widget preview() {
 *     return Preview.entity(EntityTypes.ZOMBIE);
 * }
 * }</pre>
 */
public final class Preview extends Widget {
	/** What a preview shows. */
	public sealed interface Subject permits EntitySubject, PlayerSubject, ItemSubject, ViewModelSubject {
	}

	/** An entity of {@code type}, standing, facing you. */
	public record EntitySubject(EntityType<?> type) implements Subject {
		public EntitySubject {
			Objects.requireNonNull(type);
		}
	}

	/** You: your skin, armour and what you hold. */
	public record PlayerSubject() implements Subject {
	}

	/** An item as a 3D model: a block's item shows the block (chests and shulker boxes as they stand in the world). */
	public record ItemSubject(ItemStack stack) implements Subject {
		public ItemSubject {
			stack = stack.copy();
		}
	}

	/** Your first-person hands and held items, as the camera sees them (View Model's changes included). */
	public record ViewModelSubject() implements Subject {
	}

	/**
	 * Draws on a preview: {@code box} is the subject's box (an entity's hitbox, a block's cube) in the same coordinates,
	 * {@code entity} the entity shown (null for a block, an item or the view model), for colouring it as the module would.
	 */
	@FunctionalInterface
	public interface Decoration {
		void draw(ShapeBuilder shapes, AABB box, @Nullable Entity entity);
	}

	/** The angle a preview starts at: a little from the side and above. */
	private static final float START_YAW = -35, START_PITCH = 20;
	private static final float MIN_ZOOM = 0.25f, MAX_ZOOM = 6f;
	/** Two clicks this close together (ms) put the view back. */
	private static final long DOUBLE_CLICK_MS = 300;

	private final Subject subject;
	private final List<Decoration> decorations = new ArrayList<>();
	private float previewHeight = 120;
	private float startYaw, startPitch;
	private float yaw, pitch, zoom = 1, panX, panY;
	private int dragButton = -1;
	/** Where the mouse was at the last click or drag: the movement is measured from it (not every window passes one). */
	private float lastX, lastY;
	private long lastClick;

	private Preview(Subject subject, float yaw, float pitch) {
		this.subject = subject;
		this.startYaw = this.yaw = yaw;
		this.startPitch = this.pitch = pitch;
	}

	/** An entity of {@code type} (a zombie, an end crystal). */
	public static Preview entity(EntityType<?> type) {
		return new Preview(new EntitySubject(type), START_YAW, START_PITCH);
	}

	/** You, as others see you. */
	public static Preview player() {
		return new Preview(new PlayerSubject(), START_YAW, START_PITCH);
	}

	/** A block, from its item (a chest, a shulker box, a spawner). */
	public static Preview block(Block block) {
		return item(new ItemStack(block));
	}

	/** An item as a 3D model. */
	public static Preview item(ItemStack stack) {
		return new Preview(new ItemSubject(stack), START_YAW, START_PITCH);
	}

	/** Your first-person hands and held items, from the camera. */
	public static Preview viewModel() {
		return new Preview(new ViewModelSubject(), 0, 0);
	}

	/** Draws {@code decoration} on the preview each frame; several are drawn in the order added. */
	public Preview decorate(Decoration decoration) {
		decorations.add(Objects.requireNonNull(decoration));
		return this;
	}

	/** The preview's height in UI units (120 by default); it takes the width it's given. */
	public Preview height(float height) {
		this.previewHeight = Math.max(40, height);
		return this;
	}

	/** The angle it starts at (and goes back to on a double-click): degrees around, and up from level. */
	public Preview view(float yaw, float pitch) {
		this.startYaw = this.yaw = yaw;
		this.startPitch = this.pitch = pitch;
		return this;
	}

	public Subject subject() {
		return subject;
	}

	public List<Decoration> decorations() {
		return List.copyOf(decorations);
	}

	/** Degrees the subject is turned about the vertical. */
	public float yaw() {
		return yaw;
	}

	/** Degrees it's tipped towards you (positive: seen from above). */
	public float pitch() {
		return pitch;
	}

	/** How much bigger than fitting the preview it's drawn (1: it just fits). */
	public float zoom() {
		return zoom;
	}

	/** How far it's moved from the middle, in UI units. */
	public float panX() {
		return panX;
	}

	public float panY() {
		return panY;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return previewHeight;
	}

	@Override
	public void render(Canvas c, float mouseX, float mouseY) {
		c.roundRect(x, y, width, height, 6, theme().surface.argb());
		c.preview(this, x, y, width, height);
	}

	@Override
	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		if (!isHovered(mouseX, mouseY) || button > 1) return false;
		long now = System.currentTimeMillis();
		if (button == 0 && now - lastClick < DOUBLE_CLICK_MS) {
			yaw = startYaw;
			pitch = startPitch;
			zoom = 1;
			panX = panY = 0;
			now = 0;
		}
		lastClick = now;
		dragButton = button;
		lastX = mouseX;
		lastY = mouseY;
		return true;
	}

	@Override
	public boolean mouseDragged(float mouseX, float mouseY, int button, float dx, float dy) {
		if (dragButton < 0) return false;
		dx = mouseX - lastX;
		dy = mouseY - lastY;
		lastX = mouseX;
		lastY = mouseY;
		if (dragButton == 1 || shiftDown()) {
			panX += dx;
			panY += dy;
		} else {
			yaw += dx * 0.8f;
			pitch = Math.clamp(pitch + dy * 0.8f, -90f, 90f);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(float mouseX, float mouseY, int button) {
		boolean was = dragButton >= 0;
		dragButton = -1;
		return was;
	}

	@Override
	public boolean mouseScrolled(float mouseX, float mouseY, float amount) {
		if (!isHovered(mouseX, mouseY)) return false;
		zoom = Math.clamp(zoom * (float) Math.pow(1.15, amount), MIN_ZOOM, MAX_ZOOM);
		return true;
	}

	private static boolean shiftDown() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return false;
		long w = mc.getWindow().handle();
		return GLFW.glfwGetKey(w, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
	}
}
