package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Inventory and container screens: drawing over slots (item highlights, shulker icons), replacing the tooltip (content
 * previews) and taking over clicks (opening a shulker from your inventory). Posted on the render thread, only while
 * something listens, for every container screen your inventory's included.
 */
public abstract class ContainerScreenEvent {
	private final AbstractContainerScreen<?> screen;

	/** Core's; addons don't make these. */
	protected ContainerScreenEvent(AbstractContainerScreen<?> screen) {
		this.screen = screen;
	}

	/** The screen being drawn or clicked. */
	public AbstractContainerScreen<?> screen() {
		return screen;
	}

	/**
	 * A slot has been drawn, every frame, for each slot; draw over it with {@link #graphics()}
	 * ({@code Myriad.ui().draw(graphics, canvas -> ...)} for the canvas). Coordinates are relative to the screen's
	 * container area, as the slot's {@code x} and {@code y} are. Not cancellable.
	 */
	public static final class SlotDrawn extends ContainerScreenEvent {
		private final Slot slot;
		private final GuiGraphicsExtractor graphics;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public SlotDrawn(AbstractContainerScreen<?> screen, Slot slot, GuiGraphicsExtractor graphics) {
			super(screen);
			this.slot = slot;
			this.graphics = graphics;
		}

		/** The slot just drawn. */
		public Slot slot() {
			return slot;
		}

		/** Vanilla's drawing context, translated to the container area. */
		public GuiGraphicsExtractor graphics() {
			return graphics;
		}
	}

	/**
	 * The tooltip for the hovered slot is about to be drawn, every frame the screen draws one: cancel after drawing your
	 * own, and vanilla's isn't drawn.
	 */
	public static final class Tooltip extends Cancellable {
		private final AbstractContainerScreen<?> screen;
		private final @Nullable Slot hovered;
		private final GuiGraphicsExtractor graphics;
		private final int mouseX, mouseY;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Tooltip(AbstractContainerScreen<?> screen, @Nullable Slot hovered, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			this.screen = screen;
			this.hovered = hovered;
			this.graphics = graphics;
			this.mouseX = mouseX;
			this.mouseY = mouseY;
		}

		/** The screen being drawn. */
		public AbstractContainerScreen<?> screen() {
			return screen;
		}

		/** The slot under the mouse, or null. */
		public @Nullable Slot hovered() {
			return hovered;
		}

		/** Vanilla's drawing context, in screen coordinates. */
		public GuiGraphicsExtractor graphics() {
			return graphics;
		}

		/** The mouse's x in scaled GUI pixels. */
		public int mouseX() {
			return mouseX;
		}

		/** The mouse's y in scaled GUI pixels. */
		public int mouseY() {
			return mouseY;
		}
	}

	/**
	 * A slot was clicked in the screen, by the player (or by code going through the screen): cancel to handle it
	 * yourself (nothing is sent to the server then). Clicks core's inventory services make don't go through the screen
	 * and aren't posted.
	 */
	public static final class Click extends Cancellable {
		private final AbstractContainerScreen<?> screen;
		private final @Nullable Slot slot;
		private final int slotId, button;
		private final ContainerInput input;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Click(AbstractContainerScreen<?> screen, @Nullable Slot slot, int slotId, int button, ContainerInput input) {
			this.screen = screen;
			this.slot = slot;
			this.slotId = slotId;
			this.button = button;
			this.input = input;
		}

		/** The screen clicked in. */
		public AbstractContainerScreen<?> screen() {
			return screen;
		}

		/** The slot clicked, or null for a click outside every slot (dropping what the cursor holds). */
		public @Nullable Slot slot() {
			return slot;
		}

		/** The slot's id in the menu, or -999 outside the window ({@code AbstractContainerMenu.SLOT_CLICKED_OUTSIDE}). */
		public int slotId() {
			return slotId;
		}

		/** The mouse button (0 left, 1 right), or the hotbar key's index for a swap, as vanilla's click packet carries it. */
		public int button() {
			return button;
		}

		/** The kind of click: pick up, quick move (shift), swap, clone, throw, drag or double click. */
		public ContainerInput input() {
			return input;
		}
	}
}
