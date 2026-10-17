package dev.myriadclient.api.event.events;

/** Fired once, when the game window and render system are ready (fonts and shaders can be created). */
public final class GameReadyEvent {
	public static final GameReadyEvent INSTANCE = new GameReadyEvent();
}
