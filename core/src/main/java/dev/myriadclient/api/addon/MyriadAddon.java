package dev.myriadclient.api.addon;

/**
 * Entry point of a Myriad addon. Declare the implementing class in your {@code fabric.mod.json}:
 *
 * <pre>{@code
 * "entrypoints": { "myriad": ["com.example.MyAddon"] },
 * "depends": { "myriad": ">=0.1.0" }
 * }</pre>
 *
 * Name, version, authors and icon come from your mod metadata. Myriad calls the methods below in phases across all
 * addons: every addon's {@link #registerTags} runs before any {@link #initialize}, and config is loaded between
 * {@link #initialize} and {@link #postInitialize}. If any phase throws, the addon is marked failed, everything it
 * registered is removed, and the game keeps running.
 */
public interface MyriadAddon {
	/** Register your own module tags here so other addons can use them in {@link #initialize}. */
	default void registerTags(AddonContext ctx) {
	}

	/** Register modules, commands, panels, setting types, layouts, themes, keybinds and listeners. */
	void initialize(AddonContext ctx);

	/**
	 * All addons are initialised and config has been loaded: the place for optional integrations. Registering is
	 * still allowed here, and later too (from a tick handler, say) for an addon that bridges another mod and has to
	 * wait for it, though modules registered after this point missed the config load and should
	 * {@linkplain dev.myriadclient.api.module.Module#isMirror mirror} state kept elsewhere.
	 */
	default void postInitialize(AddonContext ctx) {
	}
}
