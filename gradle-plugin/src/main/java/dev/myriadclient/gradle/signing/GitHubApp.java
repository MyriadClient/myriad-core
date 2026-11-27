package dev.myriadclient.gradle.signing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Myriad Library GitHub App, as the private builder uses it. Its key lives only in the private builder (never on
 * the API), so only the builder can read a developer's repository or post a check run on it.
 *
 * <p>Every token it makes is for one repository and only the permissions asked for, and lasts an hour at most. The
 * repository is checked by id after the token is made (a name the API gave can't be swapped for another repository).
 */
public final class GitHubApp {
	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
	private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}");
	private static final String API = "https://api.github.com";

	private final String appId;
	private final PrivateKey key;

	public GitHubApp(String appId, String pem) throws GeneralSecurityException {
		if (!appId.matches("[1-9][0-9]{0,15}")) throw new IllegalArgumentException("not a GitHub App id");
		this.appId = appId;
		this.key = privateKey(pem);
	}

	/** The App, from the private builder's {@code library-app} environment ({@code LIBRARY_APP_ID}, {@code LIBRARY_APP_KEY}). */
	public static GitHubApp fromEnv() throws GeneralSecurityException {
		String id = System.getenv("LIBRARY_APP_ID"), pem = System.getenv("LIBRARY_APP_KEY");
		if (id == null || id.isBlank() || pem == null || pem.isBlank()) throw new IllegalArgumentException("the Library App's ID and key aren't set (library-app environment)");
		return new GitHubApp(id.strip(), pem);
	}

	/** A repository as GitHub says it is: what a token was checked against. */
	public record Repo(long id, String fullName, long ownerId, boolean isPrivate) {
	}

	/** A token for {@code repo} alone, with {@code permissions}, and the repository it's for (checked by id). */
	public record Grant(String token, Repo repo) {
	}

	/**
	 * A token for exactly the repository with id {@code repoId}, found by {@code fullName}, with {@code permissions}
	 * (e.g. {@code contents: read}). Fails when the name leads to another repository, so a wrong name from anyone can't
	 * redirect the read.
	 */
	public Grant token(String fullName, long repoId, Map<String, String> permissions) throws IOException, InterruptedException {
		if (!REPO.matcher(fullName).matches() || repoId <= 0) throw new IllegalArgumentException("odd repository");
		String jwt = jwt(System.currentTimeMillis() / 1000);
		Map<String, Object> inst = Json.object(Json.parse(call("GET", API + "/repos/" + fullName + "/installation", "Bearer " + jwt, null)), "the installation");
		long installation = Json.number(inst.get("id"), "installation id");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("repository_ids", List.of(repoId));
		body.put("permissions", permissions);
		Map<String, Object> made = Json.object(Json.parse(call("POST", API + "/app/installations/" + installation + "/access_tokens", "Bearer " + jwt, Json.write(body))), "the token");
		String token = Json.string(made.get("token"), "token");
		// GitHub lists the repositories a scoped token covers: exactly the one asked for.
		List<Object> covered = Json.array(made.get("repositories"), "the token's repositories");
		if (covered.size() != 1 || Json.number(Json.object(covered.getFirst(), "repository").get("id"), "repository id") != repoId) {
			throw new IOException("GitHub's token isn't for exactly repository " + repoId);
		}
		Repo repo = repo(fullName, token);
		if (repo.id() != repoId) throw new IllegalArgumentException(fullName + " isn't repository " + repoId);
		return new Grant(token, repo);
	}

	/** {@code fullName} as GitHub says it is, read with {@code token}. */
	public static Repo repo(String fullName, String token) throws IOException, InterruptedException {
		Map<String, Object> r = Json.object(Json.parse(call("GET", API + "/repos/" + fullName, "token " + token, null)), "the repository");
		return new Repo(Json.number(r.get("id"), "id"), Json.string(r.get("full_name"), "full name"), Json.number(Json.object(r.get("owner"), "owner").get("id"), "owner id"),
			Boolean.TRUE.equals(r.get("private")));
	}

	/** GitHub's API with a token: the answer's text, or an error with the status. */
	static String call(String method, String url, String auth, String json) throws IOException, InterruptedException {
		HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "myriad-builder")
			.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("Authorization", auth);
		r.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
		if (json != null) r.header("Content-Type", "application/json");
		HttpResponse<InputStream> res = HTTP.send(r.build(), HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = res.body()) {
			byte[] b = in.readNBytes(4 * 1024 * 1024 + 1);
			if (res.statusCode() / 100 != 2) throw new IOException("GitHub answered HTTP " + res.statusCode() + " for " + method + " " + URI.create(url).getPath());
			if (b.length > 4 * 1024 * 1024) throw new IOException("GitHub's answer is too big");
			return new String(b, StandardCharsets.UTF_8);
		}
	}

	/** The App's JWT (RS256), good for nine minutes, back-dated a minute for clock drift (as GitHub suggests). */
	String jwt(long nowSeconds) {
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		String head = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
			+ b64.encodeToString(("{\"iat\":" + (nowSeconds - 60) + ",\"exp\":" + (nowSeconds + 540) + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
		try {
			Signature s = Signature.getInstance("SHA256withRSA");
			s.initSign(key);
			s.update(head.getBytes(StandardCharsets.US_ASCII));
			return head + "." + b64.encodeToString(s.sign());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** An RSA key from PEM: PKCS#8, or PKCS#1 (what GitHub hands out), wrapped as PKCS#8 for Java. */
	static PrivateKey privateKey(String pem) throws GeneralSecurityException {
		String text = pem.replace("\\n", "\n");
		boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
		if (!pkcs1 && !text.contains("BEGIN PRIVATE KEY")) throw new IllegalArgumentException("not a PEM private key");
		String b64 = text.replaceAll("-----(BEGIN|END) (RSA )?PRIVATE KEY-----", "").replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(b64);
		if (pkcs1) der = pkcs8(der);
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
	}

	/** PKCS#1 RSAPrivateKey wrapped in a PKCS#8 PrivateKeyInfo (version 0, rsaEncryption, the key as an octet string). */
	static byte[] pkcs8(byte[] pkcs1) {
		byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
		ByteArrayOutputStream inner = new ByteArrayOutputStream();
		inner.writeBytes(new byte[]{0x02, 0x01, 0x00});
		inner.writeBytes(algorithm);
		inner.write(0x04);
		inner.writeBytes(derLength(pkcs1.length));
		inner.writeBytes(pkcs1);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x30);
		out.writeBytes(derLength(inner.size()));
		out.writeBytes(inner.toByteArray());
		return out.toByteArray();
	}

	private static byte[] derLength(int n) {
		if (n < 0x80) return new byte[]{(byte) n};
		if (n < 0x100) return new byte[]{(byte) 0x81, (byte) n};
		if (n < 0x10000) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
		return new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
	}
}
