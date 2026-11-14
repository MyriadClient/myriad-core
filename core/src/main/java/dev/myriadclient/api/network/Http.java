package dev.myriadclient.api.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.myriadclient.api.util.Async;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Simple web requests on Myriad's worker threads. Results arrive off the render thread; use
 * {@link dev.myriadclient.api.util.Async#onRenderThread} before touching the game. A non-2xx response fails the future
 * with a {@link StatusException}, which has the status and the body.
 *
 * <pre>{@code
 * Http.getJson("https://api.example.net/prices").thenAccept(json -> Async.onRenderThread(() -> update(json)));
 * }</pre>
 */
public final class Http {
	private static final HttpClient CLIENT = HttpClient.newBuilder().executor(Async.executor()).connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final String USER_AGENT = "Myriad (Minecraft client)";

	/** Tries a request is made at most, however many retries are asked for. */
	private static final int MAX_ATTEMPTS = 6;
	/** The longest a retry waits for, whatever the server asks. */
	private static final Duration MAX_WAIT = Duration.ofSeconds(60);

	/** A response that wasn't a success (not 2xx): its status, body, and how long the server asked you to wait. */
	@ApiStatus.AvailableSince("2.0")
	public static final class StatusException extends IOException {
		/** The HTTP status. */
		private final int status;
		/** The response body as text. */
		private final String body;
		/** The wait the server asked for, if it said. */
		private final @Nullable Duration retryAfter;

		StatusException(int status, String body, URI uri, @Nullable Duration retryAfter) {
			super("HTTP " + status + " from " + uri);
			this.status = status;
			this.body = body;
			this.retryAfter = retryAfter;
		}

		/** The HTTP status (404, 429, 500...). */
		public int status() {
			return status;
		}

		/** The response body as text (an error message, often JSON); empty if there was none. */
		public String body() {
			return body;
		}

		/**
		 * How long the server asked you to wait before trying again (its {@code Retry-After} header, read as seconds);
		 * null if it didn't say or gave a date instead.
		 */
		public @Nullable Duration retryAfter() {
			return retryAfter;
		}
	}

	private Http() {
	}

	/**
	 * GETs {@code url} and returns the body as text. Redirects are followed; the connection times out after 10 seconds
	 * and the whole request after 30. A malformed URL throws {@link IllegalArgumentException} straight away.
	 */
	public static CompletableFuture<String> get(String url) {
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofString());
	}

	/** GETs {@code url} and parses the body as JSON; the future fails if it isn't JSON. */
	public static CompletableFuture<JsonElement> getJson(String url) {
		return get(url).thenApply(JsonParser::parseString);
	}

	/** GETs {@code url} and returns the raw body, for images and other files. */
	public static CompletableFuture<byte[]> getBytes(String url) {
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofByteArray());
	}

	/** POSTs {@code json} (as {@code application/json}) and returns the response body; no retries. */
	public static CompletableFuture<String> postJson(String url, JsonElement json) {
		return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString())),
			HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * POSTs {@code json}, trying again up to {@code retries} times while the server says to slow down (429, or 503 with
	 * a {@code Retry-After}), each time after the wait it asks for (2 seconds if it doesn't say, at most a minute): for
	 * webhooks and other rate-limited services. At most 5 retries are made whatever {@code retries} says; other failures
	 * aren't retried.
	 */
	@ApiStatus.AvailableSince("2.0")
	public static CompletableFuture<String> postJson(String url, JsonElement json, int retries) {
		return retrying(() -> postJson(url, json), Math.min(retries, MAX_ATTEMPTS - 1));
	}

	private static CompletableFuture<String> retrying(Supplier<CompletableFuture<String>> request, int retries) {
		return request.get().handle((body, t) -> {
			Throwable cause = t instanceof CompletionException c && c.getCause() != null ? c.getCause() : t;
			if (cause == null) return CompletableFuture.completedFuture(body);
			if (retries <= 0 || !(cause instanceof StatusException s) || !(s.status() == 429 || s.status() == 503 && s.retryAfter() != null))
				return CompletableFuture.<String>failedFuture(cause);
			Duration wait = s.retryAfter() != null ? s.retryAfter() : Duration.ofSeconds(2);
			if (wait.compareTo(MAX_WAIT) > 0) wait = MAX_WAIT;
			Executor later = CompletableFuture.delayedExecutor(wait.toMillis() + 250, TimeUnit.MILLISECONDS, Async.executor());
			return CompletableFuture.supplyAsync(() -> null, later).thenCompose(v -> retrying(request, retries - 1));
		}).thenCompose(f -> f);
	}

	/** DELETEs {@code url}, authorised with {@code bearerToken} (none if null), and returns the response body. */
	public static CompletableFuture<String> delete(String url, @Nullable String bearerToken) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).DELETE();
		if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
		return send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static <T> CompletableFuture<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> body) {
		request.header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(30));
		return CLIENT.sendAsync(request.build(), body).thenApply(r -> {
			if (r.statusCode() / 100 != 2) {
				String text = r.body() instanceof String str ? str : r.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : "";
				throw new CompletionException(new StatusException(r.statusCode(), text, r.uri(), retryAfter(r.headers())));
			}
			return r.body();
		});
	}

	private static @Nullable Duration retryAfter(HttpHeaders headers) {
		try {
			return headers.firstValue("Retry-After").map(v -> Duration.ofMillis((long) (Double.parseDouble(v.trim()) * 1000))).orElse(null);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
