package dev.myriadclient.gradle;

import dev.myriadclient.gradle.signing.Invites;
import dev.myriadclient.gradle.signing.Json;
import dev.myriadclient.gradle.signing.LibraryStatements;
import dev.myriadclient.gradle.signing.Signing;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages an invite-only Library listing's invites, signed with the addon's developer key (the one {@code signAddon} uses):
 *
 * <pre>
 *   ./gradlew libraryInvite -Paction=create [-Puses=1] [-Pdays=7] [-Plabel=friends]   a new code, printed once
 *   ./gradlew libraryInvite -Paction=list                                             the invites and their uses left
 *   ./gradlew libraryInvite -Paction=revoke -Pinvite=3                                that code stops working
 *   ./gradlew libraryInvite -Paction=grants                                           who has access
 *   ./gradlew libraryInvite -Paction=revoke-grant -Pplayer=&lt;uuid&gt;                       that player loses access (codes can't undo it)
 *   ./gradlew libraryInvite -Paction=restore-grant -Pplayer=&lt;uuid&gt;                      gives it back
 * </pre>
 *
 * The code is made here and never sent: the API keeps only its hash, so not even Myriad's server could tell anyone a code.
 */
@UntrackedTask(because = "it talks to the Library each time")
public abstract class LibraryInvite extends DefaultTask {
	@Internal
	public abstract Property<String> getAddonId();

	@Internal
	public abstract Property<String> getAction();

	@Internal
	public abstract Property<String> getKeyFile();

	@Internal
	public abstract Property<String> getApi();

	@Internal
	public abstract Property<Integer> getUses();

	@Internal
	public abstract Property<Integer> getDays();

	@Internal
	public abstract Property<String> getLabel();

	@Internal
	public abstract Property<Long> getInvite();

	@Internal
	public abstract Property<String> getPlayer();

	@TaskAction
	public void run() throws Exception {
		String action = getAction().getOrElse("list");
		String id = getAddonId().get();
		String api = getApi().getOrElse("https://api.myriadclient.dev").replaceAll("/+$", "");
		long now = Instant.now().getEpochSecond();
		String code = null;
		Invites.Request request;
		switch (action) {
			case "create" -> {
				code = Invites.newCode();
				int days = getDays().getOrElse(7), uses = getUses().getOrElse(1);
				if (days < 1 || days > 365) throw new GradleException("-Pdays is 1 to 365");
				request = new Invites.Request(id, action, api, now, Invites.nonce(), Invites.hash(code), uses, now + days * 86_400L, getLabel().getOrElse(""), 0, null);
			}
			case "revoke" -> request = new Invites.Request(id, action, api, now, Invites.nonce(), null, 0, 0, null,
				getInvite().getOrElse(0L), null);
			case "revoke-grant", "restore-grant" -> {
				String uuid = Invites.uuid(getPlayer().getOrNull());
				if (uuid == null) throw new GradleException("-Pplayer is the player's UUID (with or without dashes)");
				request = new Invites.Request(id, action, api, now, Invites.nonce(), null, 0, 0, null, 0, uuid);
			}
			case "list", "grants" -> request = new Invites.Request(id, action, api, now, Invites.nonce(), null, 0, 0, null, 0, null);
			default -> throw new GradleException("-Paction is create, list, revoke, grants, revoke-grant or restore-grant");
		}
		byte[] statement;
		try {
			statement = Invites.bytes(request);
		} catch (IllegalArgumentException e) {
			throw new GradleException(e.getMessage());
		}
		Signing.Key key = key();
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("id", request.id());
		req.put("action", request.action());
		req.put("api", request.api());
		req.put("issued", request.issued());
		req.put("nonce", request.nonce());
		if (action.equals("create")) {
			req.put("codeSha256", request.codeSha256());
			req.put("uses", request.uses());
			req.put("notAfter", request.notAfter());
			req.put("label", request.label());
		}
		if (action.equals("revoke")) req.put("invite", request.invite());
		if (action.equals("revoke-grant") || action.equals("restore-grant")) req.put("uuid", request.uuid());
		String body = "{\"request\": " + Json.write(req) + ", \"signatures\": [" + LibraryStatements.signatureJson(key, statement) + "]}";

		URI uri = URI.create(api + "/v1/library/invites");
		boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
		if (!"https".equals(uri.getScheme()) && !local) throw new GradleException("the Library API is https (or http on this computer, for testing)");
		HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("User-Agent", "myriad-gradle")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		Object answer = res.body().isBlank() ? null : Json.parse(res.body());
		if (res.statusCode() / 100 != 2) {
			String why = answer instanceof Map<?, ?> m && m.get("error") instanceof String e ? e : "HTTP " + res.statusCode();
			throw new GradleException("The Library said: " + why);
		}
		switch (action) {
			case "create" -> {
				long invite = answer instanceof Map<?, ?> m && m.get("invite") instanceof Number n ? n.longValue() : 0;
				getLogger().lifecycle("""

					  Invite %d for %s: %d %s, until %s%s

					      %s

					  Give this code to who you're inviting: in Myriad, Addons → Browse the Library → %s → Redeem code.
					  It's shown only now: Myriad keeps only its hash. Revoke it with -Paction=revoke -Pinvite=%d.
					""".formatted(invite, id, request.uses(), request.uses() == 1 ? "use" : "uses", Instant.ofEpochSecond(request.notAfter()).toString().substring(0, 10),
					request.label().isEmpty() ? "" : " (" + request.label() + ")", Invites.shown(code), id, invite));
			}
			case "list" -> table(answer, List.of("id", "label", "usesLeft", "maxUses", "expires", "revoked"), List.of("Invite", "Label", "Left", "Of", "Expires", "Revoked"));
			case "grants" -> table(answer, List.of("name", "uuid", "invite", "created", "revoked"), List.of("Player", "UUID", "Invite", "Since", "Revoked"));
			default -> getLogger().lifecycle("Done.");
		}
	}

	/** The API's answer as a plain table. */
	private void table(Object answer, List<String> keys, List<String> heads) {
		List<?> rows = answer instanceof List<?> l ? l : List.of();
		if (rows.isEmpty()) {
			getLogger().lifecycle("Nothing yet.");
			return;
		}
		int[] widths = new int[keys.size()];
		for (int i = 0; i < keys.size(); i++) widths[i] = heads.get(i).length();
		List<String[]> cells = new java.util.ArrayList<>();
		for (Object r : rows) {
			Map<?, ?> m = r instanceof Map<?, ?> mm ? mm : Map.of();
			String[] row = new String[keys.size()];
			for (int i = 0; i < keys.size(); i++) {
				Object v = m.get(keys.get(i));
				String s = v == null ? "" : v instanceof Double d && d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(v);
				if (keys.get(i).equals("expires") || keys.get(i).equals("created")) s = s.length() >= 10 ? s.substring(0, 10) : s;
				row[i] = s.replaceAll("[\\p{Cntrl}]", "");
				widths[i] = Math.max(widths[i], row[i].length());
			}
			cells.add(row);
		}
		StringBuilder out = new StringBuilder("\n");
		append(out, heads.toArray(String[]::new), widths);
		for (String[] row : cells) append(out, row, widths);
		getLogger().lifecycle(out.toString());
	}

	private static void append(StringBuilder out, String[] row, int[] widths) {
		out.append("  ");
		for (int i = 0; i < row.length; i++) out.append(String.format("%-" + (widths[i] + 2) + "s", row[i]));
		out.append('\n');
	}

	private Signing.Key key() throws Exception {
		String env = System.getenv("MYRIAD_SIGNING_KEY");
		if (env != null && !env.isBlank()) return Signing.parsePrivate(env);
		String file = getKeyFile().getOrNull();
		if (file == null) throw new GradleException("no key: set MYRIAD_SIGNING_KEY, or -PmyriadSigningKeyFile (the developer key your listing names)");
		String pass = System.getenv("MYRIAD_KEY_PASSPHRASE");
		char[] passphrase = pass != null ? pass.toCharArray() : System.console() != null ? System.console().readPassword("Passphrase for %s: ", file) : null;
		if (passphrase == null) throw new GradleException("set MYRIAD_KEY_PASSPHRASE for " + file);
		return Signing.decrypt(Files.readString(Path.of(file)), passphrase);
	}
}
