package dev.myriadclient.impl.update;

import dev.myriadclient.api.util.Async;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads for updates, stricter than {@link dev.myriadclient.api.network.Http}: https only (plain http only to this
 * machine, with {@code -Dmyriad.dev}), never more than a set size, and redirects followed here rather than by the HTTP
 * client so a private feed's token goes only to the feed's own origin: it's dropped at the first hop anywhere else (a
 * private GitHub release's download is sent on to storage that must not see it).
 */
final class Fetch {
	private static final HttpClient CLIENT = HttpClient.newBuilder().executor(Async.executor()).connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final String USER_AGENT = "Myriad (Minecraft client)";
	private static final int MAX_REDIRECTS = 5;
	private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
	private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
	/** The longest a whole download may take: a server dripping its body slowly is cut off here. */
	private static final long DEADLINE_SECONDS = 300;

	private Fetch() {
	}

	/** A download's address, checked: why it isn't one this will fetch, or null when it is. */
	static @Nullable String refused(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (uri.getHost() == null || uri.getUserInfo() != null) return "not a web address";
		if (scheme.equals("https")) return null;
		if (scheme.equals("http") && LOCAL.contains(uri.getHost().toLowerCase(Locale.ROOT)) && Boolean.getBoolean("myriad.dev")) return null;
		return "only https addresses are taken";
	}

	/** Whether two addresses share an origin (scheme, host and port). */
	static boolean sameOrigin(URI a, URI b) {
		return Objects.equals(lower(a.getScheme()), lower(b.getScheme())) && Objects.equals(lower(a.getHost()), lower(b.getHost())) && port(a) == port(b);
	}

	static CompletableFuture<String> text(URI uri, long max, @Nullable String token, String accept) {
		return bytes(uri, max, token, accept).thenApply(b -> new String(b, StandardCharsets.UTF_8));
	}

	/** {@code uri}'s body, failing when it's over {@code max} bytes; {@code token} goes only to {@code uri}'s origin. */
	static CompletableFuture<byte[]> bytes(URI uri, long max, @Nullable String token, String accept) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return get(uri, max, token, accept);
			} catch (IOException e) {
				throw new java.util.concurrent.CompletionException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new java.util.concurrent.CompletionException(e);
			}
		}, Async.executor());
	}

	private static byte[] get(URI start, long max, @Nullable String token, String accept) throws IOException, InterruptedException {
		URI uri = start;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			String why = refused(uri);
			if (why != null) throw new IOException(why + ": " + uri.getHost());
			HttpRequest.Builder req = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(120)).header("User-Agent", USER_AGENT).header("Accept", accept);
			if (token != null && sameOrigin(uri, start)) req.header("Authorization", "Bearer " + token);
			HttpResponse<InputStream> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = res.body()) {
				int status = res.statusCode();
				if (REDIRECTS.contains(status)) {
					String to = res.headers().firstValue("Location").orElseThrow(() -> new IOException("a redirect to nowhere"));
					uri = uri.resolve(to);
					continue;
				}
				if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
				long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
				if (declared > max) throw new IOException("the download is larger than it should be");
				// The client's timeout ends at the headers; closing the stream at the deadline ends a read stuck after them.
				var cutoff = CompletableFuture.runAsync(() -> {
					try {
						body.close();
					} catch (IOException ignored) {
						// It's being abandoned anyway.
					}
				}, CompletableFuture.delayedExecutor(DEADLINE_SECONDS, java.util.concurrent.TimeUnit.SECONDS, Async.executor()));
				try {
					return read(body, max);
				} catch (IOException e) {
					throw cutoff.isDone() ? new IOException("the download took too long") : e;
				} finally {
					cutoff.cancel(false);
				}
			}
		}
		throw new IOException("too many redirects");
	}

	private static byte[] read(InputStream in, long max) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[16384];
		for (int n; (n = in.read(buf)) > 0; ) {
			if (out.size() + (long) n > max) throw new IOException("the download is larger than it should be");
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private static @Nullable String lower(@Nullable String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	private static int port(URI u) {
		if (u.getPort() != -1) return u.getPort();
		return "http".equalsIgnoreCase(u.getScheme()) ? 80 : 443;
	}
}
