package dev.myriadclient.api.event.events;

/**
 * Posted once, on the render thread, when the game has finished starting: the window and render system are ready, so
 * fonts, textures and shaders can be created. Addons are loaded and initialised before it.
 */
public final class GameReadyEvent {
	/** The one instance. */
	public static final GameReadyEvent INSTANCE = new GameReadyEvent();
}
