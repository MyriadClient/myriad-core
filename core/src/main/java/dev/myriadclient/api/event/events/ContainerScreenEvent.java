package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Inventory and container screens: drawing over slots (item highlights, shulker icons), replacing the tooltip (content
 * previews) and taking over clicks (opening a shulker from your inventory). Posted on the render thread.
 */
public abstract class ContainerScreenEvent {
	private final AbstractContainerScreen<?> screen;

	protected ContainerScreenEvent(AbstractContainerScreen<?> screen) {
		this.screen = screen;
	}

	public AbstractContainerScreen<?> screen() {
		return screen;
	}

	/** A slot has been drawn; draw over it with {@link #graphics()} ({@code Myriad.ui().draw(graphics, canvas -> ...)} for the canvas). */
	public static final class SlotDrawn extends ContainerScreenEvent {
		private final Slot slot;
		private final GuiGraphicsExtractor graphics;

		@ApiStatus.Internal
		public SlotDrawn(AbstractContainerScreen<?> screen, Slot slot, GuiGraphicsExtractor graphics) {
			super(screen);
			this.slot = slot;
			this.graphics = graphics;
		}

		public Slot slot() {
			return slot;
		}

		public GuiGraphicsExtractor graphics() {
			return graphics;
		}
	}

	/** The tooltip for the hovered slot is about to be drawn: cancel after drawing your own. */
	public static final class Tooltip extends Cancellable {
		private final AbstractContainerScreen<?> screen;
		private final @Nullable Slot hovered;
		private final GuiGraphicsExtractor graphics;
		private final int mouseX, mouseY;

		@ApiStatus.Internal
		public Tooltip(AbstractContainerScreen<?> screen, @Nullable Slot hovered, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			this.screen = screen;
			this.hovered = hovered;
			this.graphics = graphics;
			this.mouseX = mouseX;
			this.mouseY = mouseY;
		}

		public AbstractContainerScreen<?> screen() {
			return screen;
		}

		/** The slot under the mouse, or null. */
		public @Nullable Slot hovered() {
			return hovered;
		}

		public GuiGraphicsExtractor graphics() {
			return graphics;
		}

		public int mouseX() {
			return mouseX;
		}

		public int mouseY() {
			return mouseY;
		}
	}

	/** A slot was clicked: cancel to handle it yourself (nothing is sent to the server then). */
	public static final class Click extends Cancellable {
		private final AbstractContainerScreen<?> screen;
		private final @Nullable Slot slot;
		private final int slotId, button;
		private final ContainerInput input;

		@ApiStatus.Internal
		public Click(AbstractContainerScreen<?> screen, @Nullable Slot slot, int slotId, int button, ContainerInput input) {
			this.screen = screen;
			this.slot = slot;
			this.slotId = slotId;
			this.button = button;
			this.input = input;
		}

		public AbstractContainerScreen<?> screen() {
			return screen;
		}

		public @Nullable Slot slot() {
			return slot;
		}

		public int slotId() {
			return slotId;
		}

		public int button() {
			return button;
		}

		public ContainerInput input() {
			return input;
		}
	}
}
