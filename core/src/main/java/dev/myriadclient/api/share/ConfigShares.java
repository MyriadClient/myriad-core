package dev.myriadclient.api.share;

import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sharing configs ({@code Myriad.shares()}): put together what to share, publish it for a code, take a code in.
 *
 * <pre>{@code
 * ConfigShare share = Myriad.shares().builder()
 *     .title("2b2t highway")
 *     .module(elytraFly)
 *     .group(travel)
 *     .controlPanel(highway)
 *     .theme()
 *     .build();
 * Myriad.shares().publish(share).thenAccept(p -> Myriad.notifications().info("Shared", p.code()));
 *
 * Myriad.shares().fetch("myriad_JS3k4d").thenAccept(s -> Myriad.shares().apply(s, ConfigShares.Apply.AS_CONTROL_PANEL));
 * }</pre>
 * Settings marked {@link dev.myriadclient.api.setting.Setting.Builder#protect()} are left out of every share, and
 * taking one in never changes them. Binds go in only with {@link Builder#binds(boolean)}.
 */
@ApiStatus.NonExtendable
public interface ConfigShares {
	/** How to take a share in. */
	enum Apply {
		/** Its settings become yours (protected ones excepted), its modules on or off as shared, its control panels, wheels and theme added. */
		REPLACE,
		/**
		 * As a new control panel of its modules that were on for the sharer (all of them if none was) and their
		 * settings, so you can try it and turn it off to have your own back (layered over yours, nothing overwritten);
		 * none for a share without modules. Its own control panels, wheels and theme are added as with REPLACE.
		 */
		AS_CONTROL_PANEL
	}

	/**
	 * A share published from this computer (the key that deletes it is kept here, not in this record).
	 *
	 * @param code      its code, {@code myriad_} and six letters or digits
	 * @param url       its page on the website
	 * @param title     its title when published
	 * @param createdAt when it was published, in epoch milliseconds
	 */
	record Published(String code, String url, String title, long createdAt) {
	}

	/**
	 * What a share took in.
	 *
	 * @param applied what was taken in, for showing the player: module names, "control panel <i>name</i>",
	 *                "wheel <i>name</i>", "theme"
	 * @param missing modules this install doesn't have (from addons not installed), as "Name (id)" or the id
	 */
	record Applied(List<String> applied, List<String> missing) {
	}

	/** Puts a share together. Every method returns this builder; adding the same module twice adds it once. */
	@ApiStatus.NonExtendable
	interface Builder {
		/** Its title on the website and in the list of shares ("Shared config" if not given). */
		Builder title(String title);

		/** A longer description shown on its page (none if not given). */
		Builder description(String description);

		/** One module, with its settings and whether it's on. */
		Builder module(Module module);

		/** Several modules, as {@link #module} each. */
		Builder modules(Collection<? extends Module> modules);

		/** Every module with {@code tag}, from every addon. */
		Builder tag(Tag tag);

		/** The modules of {@code group}: one of the menu's windows. */
		Builder group(ModuleGroup group);

		/** Every module of the addon with this mod id. */
		Builder addon(String modId);

		/** A control panel, with the settings it saves (its member modules aren't added; add them too to share theirs). */
		Builder controlPanel(ControlPanel panel);

		/** The menu's look: the active theme's colours and styles (not personal preferences). */
		Builder theme();

		/** Also every module's keybind and hold setting (off by default: binds are personal). */
		Builder binds(boolean include);

		/** The share as put together so far, with the current settings of everything in it. */
		ConfigShare build();
	}

	/** A new, empty builder. */
	Builder builder();

	/**
	 * Uploads {@code share}; the code arrives with the future, off the render thread. Remembered (with its delete key)
	 * in {@link #published()}. Fails with {@link IllegalArgumentException} for an empty share, and with the web
	 * request's error if the upload fails.
	 */
	CompletableFuture<Published> publish(ConfigShare share);

	/**
	 * Downloads the share with this code ({@code myriad_JS3k4d}, or text containing one such as its website link). The
	 * future completes off the render thread; it fails with {@link IllegalArgumentException} if there's no code in
	 * {@code code}, or if the share is in a format this version doesn't read.
	 */
	CompletableFuture<ConfigShare> fetch(String code);

	/**
	 * Deletes a share you published from this computer and forgets it; fails with {@link IllegalArgumentException} for a
	 * code that wasn't published from here.
	 */
	CompletableFuture<Void> unpublish(String code);

	/** The shares published from this computer, newest first. */
	List<Published> published();

	/** Takes {@code share} in. Call on the render thread. */
	Applied apply(ConfigShare share, Apply how);

	/** Whether {@code text} is exactly a share code ({@code myriad_} and six letters or digits); false for null. */
	static boolean isCode(@Nullable String text) {
		return text != null && text.matches("myriad_[A-Za-z0-9]{6}");
	}
}
