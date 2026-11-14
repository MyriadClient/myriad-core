package dev.myriadclient.api.addon;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * An addon Myriad found: a mod with a {@code "myriad"} entrypoint, Myriad core included. Its name, version and the rest
 * come straight from its {@code fabric.mod.json} (its Fabric {@link ModContainer}); its {@link #state()} says whether
 * it loaded. {@link dev.myriadclient.api.Myriad#addons()} lists them all, and {@link AddonContext#addon()} is your own.
 *
 * <pre>{@code
 * for (Addon a : Myriad.addons()) {
 *     if (a.state() == AddonState.FAILED) ctx.logger().warn("{} failed: {}", a.name(), a.failure());
 * }
 * }</pre>
 */
public final class Addon {
	private final ModContainer container;
	private final MyriadAddon entrypoint;
	private AddonState state = AddonState.LOADING;
	private @Nullable Throwable failure;

	/** Core's: made by the addon loader for each mod with a {@code myriad} entrypoint. */
	@ApiStatus.Internal
	public Addon(ModContainer container, MyriadAddon entrypoint) {
		this.container = container;
		this.entrypoint = entrypoint;
	}

	/** The mod id, which is also the namespace of every id the addon registers ({@code "myriad-essentials"}). */
	public String id() {
		return metadata().getId();
	}

	/** The display name from {@code fabric.mod.json} (the mod id when it gives none). */
	public String name() {
		return metadata().getName();
	}

	/** The version as written in {@code fabric.mod.json} ({@code "1.4.0"}). */
	public String version() {
		return metadata().getVersion().getFriendlyString();
	}

	/** The description from {@code fabric.mod.json}; empty when it has none. */
	public String description() {
		return metadata().getDescription();
	}

	/** The authors' names from {@code fabric.mod.json}, in order; empty when it lists none. */
	public List<String> authors() {
		return metadata().getAuthors().stream().map(Person::getName).toList();
	}

	/** The website from {@code contact.homepage} in fabric.mod.json. */
	public Optional<String> homepage() {
		return metadata().getContact().get("homepage");
	}

	/** The source repository from {@code contact.sources} in fabric.mod.json. */
	public Optional<String> sources() {
		return metadata().getContact().get("sources");
	}

	/** Where to report problems, from {@code contact.issues} in fabric.mod.json. */
	public Optional<String> issues() {
		return metadata().getContact().get("issues");
	}

	/** The path inside the mod's jar of its icon closest to 64 pixels wide, from {@code icon} in fabric.mod.json. */
	public Optional<String> iconPath() {
		return metadata().getIconPath(64);
	}

	/** The full Fabric metadata, for what the accessors above don't cover. */
	public ModMetadata metadata() {
		return container.getMetadata();
	}

	/** The Fabric mod container: the mod's files ({@code findPath}) and metadata. */
	public ModContainer container() {
		return container;
	}

	/** The instance of the addon's {@link MyriadAddon} entrypoint class that Myriad created and runs. */
	public MyriadAddon entrypoint() {
		return entrypoint;
	}

	/** Whether the addon is still loading, loaded, or failed. */
	public AddonState state() {
		return state;
	}

	/**
	 * What the addon threw in the phase it failed in, or null unless its {@link #state()} is {@link AddonState#FAILED}.
	 */
	public @Nullable Throwable failure() {
		return failure;
	}

	/** Core's: the addon's initialize and postInitialize ran without throwing. */
	@ApiStatus.Internal
	public void markLoaded() {
		if (state == AddonState.LOADING) state = AddonState.LOADED;
	}

	/** Core's: the addon threw while loading; what it registered has been rolled back. */
	@ApiStatus.Internal
	public void markFailed(Throwable t) {
		state = AddonState.FAILED;
		failure = t;
	}

	/** Whether this is Myriad core itself (mod id {@code "myriad"}), which is listed as an addon like any other. */
	public boolean isCore() {
		return id().equals("myriad");
	}

	/** The name and version, {@code "Myriad Essentials 1.4.0"}. */
	@Override
	public String toString() {
		return name() + " " + version();
	}
}
