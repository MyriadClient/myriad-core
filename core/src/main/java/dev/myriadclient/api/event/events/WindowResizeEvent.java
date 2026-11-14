package dev.myriadclient.api.event.events;

/**
 * The game window was resized, or the GUI scale changed: posted on the render thread once Minecraft has worked out the
 * new scaled size, so layouts made for the old size can be redone. Not cancellable.
 *
 * @param framebufferWidth  the window's drawable width in real pixels (not GUI-scaled)
 * @param framebufferHeight the window's drawable height in real pixels (not GUI-scaled)
 */
public record WindowResizeEvent(int framebufferWidth, int framebufferHeight) {
}
