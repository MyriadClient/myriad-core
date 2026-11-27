package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.ForcedUsernameChangeException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import dev.myriadclient.api.util.Async;
import dev.myriadclient.impl.MyriadApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * The player's Myriad account, for invite-only Library addons: a session with Myriad's API, made by proving the
 * Minecraft account the way joining a server does. Myriad asks the API for a one-time nonce, tells Mojang it's joining
 * "server" {@code sha1("myriad-link:" + nonce)} with the game's own login (which goes only to Mojang), and the API asks
 * Mojang whether this account just joined that server. No password or Mojang token ever reaches Myriad's API.
 *
 * <p>The session token is kept in {@code myriad/account/session.json}, readable only by you where the system allows,
 * and never put in profiles, shares or logs. Signing out ends it on the API too.
 */
public final class LibraryAccount {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Account");
	private static final Pattern TOKEN = Pattern.compile("myr_s_[A-Za-z0-9_-]{43}");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final long MAX_ANSWER = 64 * 1024;

	record Session(String token, String name) {
	}

	private final Path file;
	private volatile @Nullable Session session;
	private volatile Set<String> grants = Set.of();
	private volatile boolean busy;

	LibraryAccount(Path root) {
		this.file = root.resolve("account").resolve("session.json");
		this.session = load(file);
	}

	public boolean signedIn() {
		return session != null;
	}

	/** The Minecraft name the session is for, or null. */
	public @Nullable String name() {
		Session s = session;
		return s == null ? null : s.name();
	}

	/** The invite-only listings this account may install. */
	public Set<String> grants() {
		return grants;
	}

	public boolean busy() {
		return busy;
	}

	@Nullable String token() {
		Session s = session;
		return s == null ? null : s.token();
	}

	/** The "server" a link joins: Java's {@code new BigInteger(sha1(...)).toString(16)}, as Minecraft spells server hashes. */
	static String serverId(String nonce) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(("myriad-link:" + nonce).getBytes(StandardCharsets.UTF_8));
			return new BigInteger(digest).toString(16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Links the Minecraft account signed in now. Fails with why, in words for the player. */
	public CompletableFuture<Void> link() {
		if (busy) return CompletableFuture.failedFuture(new IOException("already signing in"));
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		if (mc.isOfflineDeveloperMode() || user == null || user.getAccessToken() == null || user.getAccessToken().length() < 20 || !NAME.matcher(user.getName()).matches()) {
			return CompletableFuture.failedFuture(new IOException("signing in needs a Minecraft account signed in to the game"));
		}
		busy = true;
		return call("POST", "/v1/auth/minecraft/start", "{}", null).thenApplyAsync(start -> {
			String nonce = string(start, "nonce");
			try {
				mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId(nonce));
			} catch (AuthenticationUnavailableException e) {
				throw new CompletionException(new IOException("Mojang's servers can't be reached right now"));
			} catch (InvalidCredentialsException e) {
				throw new CompletionException(new IOException("your Minecraft login has expired: restart the game or your launcher"));
			} catch (InsufficientPrivilegesException e) {
				throw new CompletionException(new IOException("your Minecraft account can't join servers (check its settings)"));
			} catch (UserBannedException | ForcedUsernameChangeException e) {
				throw new CompletionException(new IOException("Mojang won't let this account join servers"));
			} catch (AuthenticationException e) {
				throw new CompletionException(new IOException("Mojang refused: " + e.getMessage()));
			}
			JsonObject finish = new JsonObject();
			finish.addProperty("nonce", nonce);
			finish.addProperty("name", user.getName());
			finish.addProperty("label", "Myriad on " + System.getProperty("os.name", "a computer").split(" ")[0]);
			return finish.toString();
		}, Async.executor()).thenCompose(body -> call("POST", "/v1/auth/minecraft/finish", body, null)).thenAccept(done -> {
			String token = string(done, "token"), name = string(done, "name");
			if (!TOKEN.matcher(token).matches() || !NAME.matcher(name).matches()) throw new CompletionException(new IOException("Myriad's API answered oddly"));
			session = new Session(token, name);
			save(file, session);
			LOG.info("Signed in to Myriad as {}", name);
		}).thenCompose(v -> refresh()).whenComplete((v, e) -> busy = false);
	}

	/** Who the session is and what it may install; a session the API no longer knows is forgotten. */
	public CompletableFuture<Void> refresh() {
		String token = token();
		if (token == null) return CompletableFuture.completedFuture(null);
		return Fetch.call("GET", api("/v1/auth/session"), null, token, MAX_ANSWER).thenAccept(a -> {
			if (a.status() == 401) {
				forget();
				return;
			}
			if (!a.ok()) return;
			JsonObject o = object(a);
			Set<String> out = new LinkedHashSet<>();
			if (o.get("grants") instanceof JsonArray list) {
				for (JsonElement e : list) if (e.isJsonPrimitive() && ID.matcher(e.getAsString()).matches()) out.add(e.getAsString());
			}
			grants = Set.copyOf(out);
		});
	}

	/** Redeems an invite code; answers the addon's name. */
	public CompletableFuture<String> redeem(String code) {
		String token = token();
		if (token == null) return CompletableFuture.failedFuture(new IOException("sign in with Minecraft first"));
		String canonical = canonical(code);
		if (canonical == null) return CompletableFuture.failedFuture(new IOException("that isn't an invite code (they look like myr-ABCDE-…)"));
		if (!checks(canonical)) return CompletableFuture.failedFuture(new IOException("that code has a typo in it"));
		JsonObject body = new JsonObject();
		body.addProperty("code", canonical);
		return call("POST", "/v1/library/redeem", body.toString(), token).thenCompose(o -> refresh().thenApply(v -> {
			String name = o.get("name") instanceof JsonElement n && n.isJsonPrimitive() ? LibraryIndex.clean(n.getAsString(), 64) : "";
			return name.isEmpty() ? string(o, "listing") : name;
		}));
	}

	/** Ends the session, here and on the API. */
	public CompletableFuture<Void> signOut() {
		String token = token();
		forget();
		if (token == null) return CompletableFuture.completedFuture(null);
		return Fetch.call("DELETE", api("/v1/auth/session"), null, token, MAX_ANSWER).handle((a, e) -> null);
	}

	private void forget() {
		session = null;
		grants = Set.of();
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOG.warn("Couldn't delete {}", file, e);
		}
	}

	// ---- codes: the same rules as the developer's tool (the Gradle plugin's Invites) --------------------------------

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	static @Nullable String canonical(String typed) {
		if (typed == null || typed.length() > 64) return null;
		String c = typed.strip().toUpperCase(Locale.ROOT).replaceFirst("^MYR-?", "").replaceAll("[\\s-]", "");
		return c.matches("[A-Z2-7]{27}") ? c : null;
	}

	/** Whether a canonical code's last letter is its checksum: the first 5 bits of the SHA-256 of the 26 before it. */
	static boolean checks(String canonical) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(canonical.substring(0, 26).getBytes(StandardCharsets.US_ASCII));
			return ALPHABET.charAt((h[0] & 0xff) >>> 3) == canonical.charAt(26);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- plumbing --------------------------------------------------------------------------------------------------

	private static URI api(String path) {
		return URI.create(MyriadApi.API + path);
	}

	/** A call that must succeed: the API's own error message when it doesn't. */
	private CompletableFuture<JsonObject> call(String method, String path, @Nullable String json, @Nullable String token) {
		return Fetch.call(method, api(path), json, token, MAX_ANSWER).thenApply(a -> {
			if (a.status() == 401 && token != null) forget();
			if (!a.ok()) {
				String why = "Myriad's API answered HTTP " + a.status();
				try {
					JsonObject o = object(a);
					if (o.get("error") instanceof JsonElement e && e.isJsonPrimitive()) why = LibraryIndex.clean(e.getAsString(), 200);
				} catch (RuntimeException ignored) {
					// Not JSON: the status says enough.
				}
				throw new CompletionException(new IOException(why));
			}
			return object(a);
		});
	}

	private static JsonObject object(Fetch.Answer a) {
		try {
			return JsonParser.parseString(a.text()).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new CompletionException(new IOException("Myriad's API answered oddly"));
		}
	}

	private static String string(JsonObject o, String key) {
		if (o.get(key) instanceof JsonElement e && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
		throw new CompletionException(new IOException("Myriad's API answered oddly"));
	}

	static @Nullable Session load(Path file) {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > 4096) return null;
			JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			String token = o.get("token").getAsString(), name = o.get("name").getAsString();
			return TOKEN.matcher(token).matches() && NAME.matcher(name).matches() ? new Session(token, name) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Written owner-only from its first byte (where the system has such permissions), then moved into place; no backup. */
	static void save(Path file, Session s) {
		JsonObject o = new JsonObject();
		o.addProperty("token", s.token());
		o.addProperty("name", s.name());
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("session.json.tmp");
			Files.deleteIfExists(tmp);
			try {
				Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			} catch (UnsupportedOperationException e) {
				Files.createFile(tmp); // Windows: the profile folder's own permissions apply.
			}
			Files.writeString(tmp, o.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOG.warn("Couldn't save the Myriad session; you'll be asked to sign in again next time", e);
		}
	}
}
