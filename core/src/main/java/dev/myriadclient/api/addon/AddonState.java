package dev.myriadclient.api.addon;

/** Where an addon is in loading, from {@link Addon#state()}. */
public enum AddonState {
	/** Found, and going through its {@link MyriadAddon} phases; also the state of every addon while the game starts. */
	LOADING,
	/** Every phase ran without throwing: its modules, commands and the rest are registered. */
	LOADED,
	/**
	 * A phase threw: its registrations were removed and its later phases skipped, while the game and the other addons
	 * keep running. {@link Addon#failure()} says why.
	 */
	FAILED
}
