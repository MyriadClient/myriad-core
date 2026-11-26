package dev.myriadclient.impl.update;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** What an addon has released, wherever it came from (GitHub or a feed), and how the newest is picked. */
final class Releases {
	private Releases() {
	}

	/**
	 * A jar to download: where, and what it must be ({@code size}, {@code sha256} when the source gives one). Its
	 * developer's signatures come with it ({@code signatures}, from a feed) or from a {@code <jar>.sig} file next to it
	 * ({@code sigUrl}, on GitHub), read before the jar.
	 */
	record Asset(String name, String url, long size, @Nullable String sha256, List<SignatureCheck.Sig> signatures, @Nullable String sigUrl) {
		Asset(String name, String url, long size, @Nullable String sha256) {
			this(name, url, size, sha256, List.of(), null);
		}

		/** Whether it comes with any signature to check. */
		boolean signed() {
			return !signatures.isEmpty() || sigUrl != null;
		}
	}

	/**
	 * A release; {@code asset} is null when no jar in it could be told apart as the one to install, {@code minecraft}
	 * when it doesn't say which Minecraft it's for, {@code url} its notes.
	 */
	record Release(String version, @Nullable String url, @Nullable Asset asset, @Nullable String minecraft) {
		/** Whether it's for Minecraft {@code version}: when it doesn't say, or its range can't be read, yes. */
		boolean worksOn(Version version) {
			if (minecraft == null) return true;
			try {
				return VersionPredicate.parse(minecraft).test(version);
			} catch (VersionParsingException e) {
				return true;
			}
		}

		/** Its Minecraft for the user: {@code ~26.3} as {@code 26.3}. */
		String minecraftShown() {
			return minecraft == null ? "" : minecraft.replaceFirst("^[~^=]+", "");
		}
	}

	/**
	 * What a source has released: the newest release for the running Minecraft, and the newest of all (another
	 * Minecraft's, when it's newer than any for this one). Either is null when there's none.
	 */
	record Found(@Nullable Release forThisMinecraft, @Nullable Release newest) {
	}

	static Found newest(Iterable<Release> list, Version minecraft) {
		Release forThis = null, newest = null;
		for (Release r : list) {
			if (newest == null || isNewer(r.version(), newest.version())) newest = r;
			if (r.worksOn(minecraft) && (forThis == null || isNewer(r.version(), forThis.version()))) forThis = r;
		}
		return new Found(forThis, newest);
	}

	/** Whether {@code candidate} is a newer version than {@code installed}; false if either can't be read. */
	static boolean isNewer(String candidate, String installed) {
		try {
			return Version.parse(candidate).compareTo(Version.parse(installed)) > 0;
		} catch (VersionParsingException e) {
			return false;
		}
	}
}
