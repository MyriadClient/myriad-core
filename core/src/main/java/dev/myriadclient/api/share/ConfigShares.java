package dev.myriadclient.api.share;

import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import org.jetbrains.annotations.ApiStatus;

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
		/** Its settings become yours (protected ones excepted), its modules on or off as shared, its control panels and theme added. */
		REPLACE,
		/**
		 * As a new control panel of its modules and their settings, so you can try it and turn it off to have your own
		 * back (layered over yours, nothing overwritten). Its own control panels and theme are added as with REPLACE.
		 */
		AS_CONTROL_PANEL
	}

	/** A share published: its code, its page on the website, and the key that deletes it (kept on this computer). */
	record Published(String code, String url, String title, long createdAt) {
	}

	/** What a share took in: what was applied, and what this install is missing (modules of addons not installed). */
	record Applied(List<String> applied, List<String> missing) {
	}

	/** Puts a share together. */
	@ApiStatus.NonExtendable
	interface Builder {
		Builder title(String title);

		Builder description(String description);

		Builder module(Module module);

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

		ConfigShare build();
	}

	Builder builder();

	/** Uploads {@code share}; the code arrives with the future. Remembered (with its delete key) in {@link #published()}. */
	CompletableFuture<Published> publish(ConfigShare share);

	/** Downloads the share with this code ({@code myriad_JS3k4d}, or its website link). */
	CompletableFuture<ConfigShare> fetch(String code);

	/** Deletes a share you published from this computer. */
	CompletableFuture<Void> unpublish(String code);

	/** The shares published from this computer, newest first. */
	List<Published> published();

	/** Takes {@code share} in. Call on the render thread. */
	Applied apply(ConfigShare share, Apply how);

	/** Whether {@code text} is a share code ({@code myriad_} and six letters or digits). */
	static boolean isCode(String text) {
		return text != null && text.matches("myriad_[A-Za-z0-9]{6}");
	}
}
