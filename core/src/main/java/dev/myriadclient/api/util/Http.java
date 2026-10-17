package dev.myriadclient.api.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Simple web requests on Myriad's worker threads. Results arrive off the render thread; use
 * {@link Async#onRenderThread} before touching the game. A non-2xx response fails the future with an
 * {@link IOException}.
 *
 * <pre>{@code
 * Http.getJson("https://api.example.net/prices").thenAccept(json -> Async.onRenderThread(() -> update(json)));
 * }</pre>
 */
public final class Http {
	private static final HttpClient CLIENT = HttpClient.newBuilder().executor(Async.executor()).connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final String USER_AGENT = "Myriad (Minecraft client)";

	private Http() {
	}

	public static CompletableFuture<String> get(String url) {
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofString());
	}

	public static CompletableFuture<JsonElement> getJson(String url) {
		return get(url).thenApply(JsonParser::parseString);
	}

	public static CompletableFuture<byte[]> getBytes(String url) {
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofByteArray());
	}

	/** POSTs {@code json} and returns the response body. */
	public static CompletableFuture<String> postJson(String url, JsonElement json) {
		return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString())),
			HttpResponse.BodyHandlers.ofString());
	}

	/** DELETEs {@code url}, authorised with {@code bearerToken} (none if null), and returns the response body. */
	public static CompletableFuture<String> delete(String url, String bearerToken) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).DELETE();
		if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
		return send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static <T> CompletableFuture<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> body) {
		request.header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(30));
		return CLIENT.sendAsync(request.build(), body).thenApply(r -> {
			if (r.statusCode() / 100 != 2) throw new CompletionException(new IOException("HTTP " + r.statusCode() + " from " + r.uri()));
			return r.body();
		});
	}
}
