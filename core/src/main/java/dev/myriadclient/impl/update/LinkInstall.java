package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

/**
 * Installing a new addon from a link: a GitHub repo or an update feed. Everything is downloaded and checked in memory
 * first, as an update is, and the player is shown what it is (name, id, version, where from, and its key's fingerprint,
 * or that it's unsigned) before anything is written to the mods folder.
 * <p>
 * The first install trusts the key it comes with (the feed's {@code keys}, or the jar's own): a signature is checked
 * against it, and the jar must list the key that signed it, so every later update needs that key. What a first install
 * can't know is whether the key is really the developer's: that's what the fingerprint is shown for. Only Myriad addons
 * (a {@code myriad} entrypoint) are installed this way, never another mod or one already installed.
 */
public final class LinkInstall {
	/** What a link offers, downloaded and checked, waiting for the player's yes. */
	public record Offer(String id, String name, String version, String from, @Nullable String keyId, String fileName, byte[] jar, @Nullable String token) {
		public boolean signed() {
			return keyId != null;
		}
	}

	private LinkInstall() {
	}

	static CompletableFuture<Offer> look(String link, @Nullable String token, Version minecraft, Version core, Set<String> myriadIds, Predicate<String> installed,
		Path mods) {
		UpdateSource source = UpdateSource.parseLink(link.strip()).orElse(null);
		if (source == null) return CompletableFuture.failedFuture(new IOException("that isn't a GitHub repo or an https feed address"));
		if (token != null && !FeedTokens.valid(token)) return CompletableFuture.failedFuture(new IOException("that token has spaces or odd characters in it"));
		CompletableFuture<Found> found = switch (source) {
			case UpdateSource.GitHub g -> GitHubReleases.releases(g.repo(), token, null, "0", minecraft).thenApply(f -> new Found(f.forThisMinecraft(), null, List.of()));
			case UpdateSource.Feed f -> Fetch.text(f.uri(), FeedReleases.MAX_FEED, token, "application/json").thenApply(text -> {
				try {
					FeedReleases.Feed feed = FeedReleases.parse(text, f.uri());
					return new Found(Releases.newest(feed.releases(), minecraft).forThisMinecraft(), feed.id(), feed.keys());
				} catch (IOException e) {
					throw new CompletionException(e);
				}
			});
		};
		return found.thenCompose(fd -> {
			Releases.Release r = fd.release();
			if (r == null) return CompletableFuture.failedFuture(new IOException("nothing's released there for Minecraft " + minecraft.getFriendlyString()));
			if (r.asset() == null) return CompletableFuture.failedFuture(new IOException(r.version() + " has no jar to install"));
			return UpdateManager.download(fd.feedId() == null ? "unknown" : fd.feedId(), r, token, UpdateManager.tokenOrigin(source))
				.thenApply(got -> offer(got, r, fd, source, token, minecraft, core, myriadIds, installed, mods));
		});
	}

	private record Found(@Nullable Releases.Release release, @Nullable String feedId, List<DevKey> feedKeys) {
	}

	private static Offer offer(UpdateManager.Downloaded got, Releases.Release r, Found fd, UpdateSource source, @Nullable String token, Version minecraft,
		Version core, Set<String> myriadIds, Predicate<String> installed, Path mods) {
		String why = JarCheck.verifyDownload(got.jar(), got.size(), got.sha256());
		if (why != null) throw fail(why);
		JsonObject meta = modJson(got.jar());
		String id = string(meta, "id"), name = string(meta, "name");
		if (!Names.isId(id)) throw fail("it isn't a mod Myriad can install");
		if (fd.feedId() != null && !fd.feedId().equals(id)) throw fail("the feed is for " + fd.feedId() + " but its jar is " + id);
		if (!isMyriadAddon(meta)) throw fail(id + " isn't a Myriad addon");
		if (installed.test(id)) throw fail(id + " is already installed");
		// Nor may it stand in for any mod you have, as itself or through provides.
		java.util.Set<String> provides = new java.util.LinkedHashSet<>();
		JarCheck.provided(meta, provides);
		for (String p : provides) if (installed.test(p)) throw fail("it would stand in for " + p + ", which you already have");
		JarCheck.Checked checked = JarCheck.check(got.jar(), id, r.version(), minecraft, core, myriadIds);
		if (checked.problem() != null) throw fail(checked.problem());
		List<DevKey> keys = fd.feedId() != null && !fd.feedKeys().isEmpty() ? fd.feedKeys() : checked.keys();
		DevKey signer = null;
		if (!keys.isEmpty()) {
			if (got.signatures().isEmpty()) throw fail("it names a signing key but isn't signed");
			signer = SignatureCheck.signer(ReleaseStatement.of(id, r.version(), got.jar(), JarCheck.sha256(got.jar())).bytes(), got.signatures(), keys);
			if (signer == null) throw fail("its signature doesn't match its key");
			if (!checked.keys().contains(signer)) throw fail("its jar doesn't list the key it's signed with");
		}
		if (Names.fileIn(mods, r.asset().name()) == null) throw fail("its file name is odd");
		if (Files.exists(mods.resolve(r.asset().name()))) throw fail("a file named " + r.asset().name() + " is already in mods");
		// The token is kept for its updates only if they come from where it was given for.
		String kept = token != null && sameSource(meta, source) ? token : null;
		return new Offer(id, name.equals("unknown") ? id : name, r.version(), source.describe(), signer == null ? null : signer.id(), r.asset().name(), got.jar(), kept);
	}

	/** Whether the jar's own update source ({@code custom.myriad.updates}, or its sources link) is at {@code link}'s origin. */
	private static boolean sameSource(JsonObject meta, UpdateSource link) {
		Object updates = null;
		JsonElement custom = meta.get("custom");
		if (custom != null && custom.isJsonObject() && custom.getAsJsonObject().get("myriad") instanceof JsonObject m && m.get("updates") != null
			&& m.get("updates").isJsonPrimitive()) {
			updates = m.getAsJsonPrimitive("updates").isBoolean() ? (Object) m.get("updates").getAsBoolean() : m.get("updates").getAsString();
		}
		String sources = meta.get("contact") instanceof JsonObject c && c.get("sources") != null && c.get("sources").isJsonPrimitive() ? c.get("sources").getAsString() : null;
		return UpdateSource.resolve(updates, sources).map(own -> Fetch.sameOrigin(UpdateManager.tokenOrigin(own), UpdateManager.tokenOrigin(link))).orElse(false);
	}

	/** Writes the offered jar into {@code mods}; it loads at the next start. */
	static void install(Offer o, Path mods) throws IOException {
		Path target = Names.fileIn(mods, o.fileName());
		if (target == null) throw new IOException("its file name is odd");
		Files.createDirectories(mods);
		// CREATE_NEW: never over a file that's there (it could have appeared since the look).
		try {
			Files.write(target, o.jar(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (java.nio.file.FileAlreadyExistsException e) {
			throw new IOException("a file named " + o.fileName() + " is already in mods");
		} catch (IOException e) {
			Files.deleteIfExists(target);
			throw e;
		}
	}

	private static boolean isMyriadAddon(JsonObject meta) {
		JsonElement e = meta.get("entrypoints");
		return e != null && e.isJsonObject() && e.getAsJsonObject().has("myriad");
	}

	private static JsonObject modJson(byte[] jar) {
		try {
			byte[] json = JarEntries.read(jar).content("fabric.mod.json", JarCheck.MAX_MOD_JSON);
			if (json == null) throw fail("it isn't a Fabric mod");
			return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw fail("it isn't a jar Myriad can vouch for (" + e.getMessage() + ")");
		} catch (RuntimeException e) {
			if (e instanceof CompletionException c) throw c;
			throw fail("it isn't a readable jar");
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "unknown";
	}

	private static CompletionException fail(String why) {
		return new CompletionException(new IOException(why));
	}
}
