package dev.myriadclient.gradle.signing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The steps of the Myriad Library's public builder (MyriadClient/myriad-builder's {@code build.yml}), each a command so
 * the workflow's shell steps only call this jar, pinned by hash. Everything a step reads from GitHub, the API or an
 * earlier job is checked here before use; values reach the workflow only as checked outputs.
 *
 * <ul>
 * <li>{@code builder-prepare}: the submission from the API; the repository and tag checked with GitHub; the source at
 * that commit; its build-logic hash; the signed listing, checked back to Myriad's root keys.</li>
 * <li>{@code builder-inspect}: the developer's released jar and {@code .sig}, downloaded and inspected ({@link Inspect}).</li>
 * <li>{@code builder-sign}: with the version key, signs the release only if {@link #decide} finds nothing to review.</li>
 * <li>{@code builder-report}: tells the API how it went.</li>
 * </ul>
 * The private builder (closed-source addons) uses {@code builder-fetch} in place of {@code builder-prepare}, and never
 * signs: every closed-source version waits for a person.
 */
public final class Builder {
	static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}");
	static final Pattern TAG = Pattern.compile("[A-Za-z0-9._+-]{1,100}");
	static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
	static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
	static final Pattern ASSET = Pattern.compile("https://github\\.com/([A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100})/releases/download/([A-Za-z0-9._+-]{1,100})/([A-Za-z0-9._+-]{1,128}\\.jar)");
	private static final Set<String> DOWNLOAD_HOSTS = Set.of("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com");
	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();

	private Builder() {
	}

	// ---- the signing decision ---------------------------------------------------------------------------------------

	/** What the sign step decided: the reasons it won't sign (none means sign). */
	public record Decision(List<String> reasons) {
		public boolean sign() {
			return reasons.isEmpty();
		}
	}

	/**
	 * Whether a release may be signed without a person: an open-source listing whose build logic is unchanged since it
	 * was listed, whose released jar is byte for byte what the builder built from the tagged source, which is the
	 * listing's addon, signed by one of the listing's developer keys that the jar itself lists, and with no finding the
	 * maintainer hasn't accepted. Anything else waits for review.
	 *
	 * @param listing  the signed listing (already checked back to the root keys)
	 * @param accepted findings the maintainer has accepted for this listing
	 * @param summary  the inspect step's summary of the released jar
	 * @param buildSha the SHA-256 of the jar the build step built
	 * @param buildHash the build-logic hash of the tagged source
	 */
	public static Decision decide(Map<String, Object> listing, Set<String> accepted, Map<String, Object> summary, String buildSha, String buildHash) {
		List<String> why = new ArrayList<>();
		if (!"open".equals(listing.get("source"))) why.add("it's a closed-source listing");
		if (!buildHash.equals(listing.get("build"))) why.add("its build logic changed since it was listed");
		Object sha = summary.get("sha256");
		if (!(sha instanceof String s) || !SHA256.matcher(s).matches()) why.add("the released jar couldn't be read");
		else if (!s.equals(buildSha)) why.add("the released jar isn't what its tagged source builds");
		if (!java.util.Objects.equals(summary.get("id"), listing.get("id"))) why.add("the jar is a different addon than the listing");
		if (!new TreeSet<>(strings(summary.get("provides"))).equals(new TreeSet<>(strings(listing.get("provides"))))) why.add("what the jar provides changed");
		if (!(summary.get("version") instanceof String v) || !VERSION.matcher(v).matches()) why.add("the jar's version isn't one Myriad signs");
		Set<String> devKeys = new HashSet<>(strings(listing.get("keys")));
		devKeys.retainAll(strings(summary.get("keys")));
		if (!developerSigned(summary, devKeys)) why.add("the release isn't signed by a developer key both the listing and the jar name");
		for (String f : strings(summary.get("findings"))) {
			if (f.equals("obfuscated")) why.add("the jar looks obfuscated");
			else if (!accepted.contains(f)) why.add("new finding: " + f);
		}
		return new Decision(List.copyOf(why));
	}

	private static boolean developerSigned(Map<String, Object> summary, Set<String> keys) {
		try {
			byte[] release = Signing.statement(Json.string(summary.get("id"), "id"), Json.string(summary.get("version"), "version"),
				Json.number(summary.get("size"), "size"), java.util.HexFormat.of().parseHex(Json.string(summary.get("sha256"), "sha256")));
			return LibraryTrust.signer(release, summary.get("developer"), keys) != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	static List<String> strings(Object v) {
		List<String> out = new ArrayList<>();
		if (v instanceof List<?> l) for (Object o : l) if (o instanceof String s) out.add(s);
		return out;
	}

	// ---- commands ---------------------------------------------------------------------------------------------------

	static void run(String command) throws Exception {
		switch (command) {
			case "builder-prepare" -> prepare();
			case "builder-fetch" -> fetchClosed();
			case "builder-inspect" -> inspect();
			case "builder-sign" -> sign();
			case "builder-report" -> report();
			default -> throw new IllegalArgumentException("unknown builder command " + command);
		}
	}

	private static void prepare() throws Exception {
		String api = api(), token = env("BUILDER_TOKEN");
		long id = submission();
		Path work = Path.of(env("WORK"));
		Map<String, Object> spec = Json.object(Json.parse(get(api + "/v1/library/builds/" + id, "Bearer " + token, 64 * 1024)), "the build");
		post(api + "/v1/library/builds/" + id + "/started", token, "{}");
		try {
			if (!"open".equals(spec.get("builder"))) throw new IllegalArgumentException("not an open-source build");
			Map<String, Object> repo = Json.object(spec.get("repo"), "the repository");
			long repoId = Json.number(repo.get("id"), "repo id"), ownerId = Json.number(repo.get("ownerId"), "owner id");
			String full = check(REPO, Json.string(repo.get("fullName"), "repo name"), "repository");
			String tag = check(TAG, Json.string(spec.get("tag"), "tag"), "tag");
			String gh = "token " + env("GH_TOKEN");
			Map<String, Object> live = Json.object(Json.parse(get("https://api.github.com/repositories/" + repoId, gh, 1024 * 1024)), "the repository");
			if (Json.number(live.get("id"), "id") != repoId || !full.equals(live.get("full_name")) || !Boolean.FALSE.equals(live.get("private"))
				|| Json.number(Json.object(live.get("owner"), "owner").get("id"), "owner id") != ownerId) {
				throw new IllegalArgumentException("the repository isn't the public one this build is for");
			}
			String commit = check(SHA1, Json.string(Json.object(Json.parse(get("https://api.github.com/repos/" + full + "/commits/" + tag, gh, 4 * 1024 * 1024)), "the commit").get("sha"), "sha"), "commit");
			Path src = work.resolve("src");
			checkout(full, "refs/tags/" + tag, src, commit);
			checkSource(src);
			String buildHash = BuildHash.of(src);
			String assetUrl = spec.get("assetUrl") instanceof String a && ASSET.matcher(a).matches() ? a : null;
			if (assetUrl == null) throw new IllegalArgumentException("the release has no jar attached");
			var m = ASSET.matcher(assetUrl);
			if (!m.matches() || !m.group(1).equals(full) || !m.group(2).equals(tag)) throw new IllegalArgumentException("the release's jar isn't in this release");
			Object listing = spec.get("listing");
			boolean listed = false;
			Set<String> accepted = Set.of();
			if (listing instanceof Map<?, ?>) {
				Map<String, Object> l = Json.object(listing, "the listing");
				Map<String, Object> body = LibraryTrust.verifiedBody(get(api + "/v1/index.json", null, 8L * 1024 * 1024), LibraryTrust.ROOT_KEYS, now());
				if (!LibraryTrust.listingSigned(l, body, LibraryTrust.ROOT_KEYS, now())) throw new IllegalArgumentException("its listing isn't signed by a Myriad listing key");
				if (Json.number(l.get("repoId"), "repoId") != repoId || Json.number(l.get("ownerId"), "ownerId") != ownerId) throw new IllegalArgumentException("its listing is for another repository");
				accepted = LibraryTrust.acceptedFindings(l, spec.get("accepted"), spec.get("acceptedSignatures"), body, LibraryTrust.ROOT_KEYS, now());
				listed = true;
			}
			output("ok", "true");
			output("repo", full);
			output("tag", tag);
			output("commit", commit);
			output("build_hash", buildHash);
			output("asset_url", assetUrl);
			output("asset_name", m.group(3));
			output("listed", String.valueOf(listed));
			output("listing", listed ? Json.write(listing) : "");
			output("accepted", Json.write(new ArrayList<>(accepted)));
		} catch (IllegalArgumentException | IOException e) {
			output("ok", "false");
			output("problem", oneLine(e.getMessage()));
		}
	}

	/**
	 * The private builder's first step: the closed-source repository, read once with a 15-minute token the API makes for
	 * it alone, archived for the later jobs (they check the archive against the hash given here).
	 */
	private static void fetchClosed() throws Exception {
		String api = api(), token = env("BUILDER_TOKEN");
		long id = submission();
		Path work = Path.of(env("WORK"));
		Map<String, Object> spec = Json.object(Json.parse(get(api + "/v1/library/builds/" + id, "Bearer " + token, 64 * 1024)), "the build");
		post(api + "/v1/library/builds/" + id + "/started", token, "{}");
		try {
			if (!"closed".equals(spec.get("builder"))) throw new IllegalArgumentException("not a closed-source build");
			Map<String, Object> repo = Json.object(spec.get("repo"), "the repository");
			Map<String, Object> source = Json.object(spec.get("source"), "the source repository");
			String tag = check(TAG, Json.string(spec.get("tag"), "tag"), "tag");
			Map<String, Object> grant = Json.object(Json.parse(postFor(api + "/v1/library/builds/" + id + "/source-token", token)), "the token");
			String sourceRepo = check(REPO, Json.string(grant.get("repo"), "repo"), "repository");
			if (!sourceRepo.equals(source.get("fullName"))) throw new IllegalArgumentException("the token is for another repository");
			Path src = work.resolve("src");
			String commit = checkoutWith(sourceRepo, "refs/tags/" + tag, src, Json.string(grant.get("token"), "token"));
			checkSource(src);
			String buildHash = BuildHash.of(src);
			Path archive = work.resolve("source.tar.gz");
			git(src, "archive", "--format=tar.gz", "-o", archive.toString(), "HEAD");
			// Where the jar is published: this release's repository when it's public (an update to a listing), else the
			// public repository the addon names as its update source (a new closed-source addon).
			String publicRepo;
			if (!Boolean.TRUE.equals(repo.get("private"))) publicRepo = check(REPO, Json.string(repo.get("fullName"), "repo"), "repository");
			else {
				Path mod = src.resolve("src/main/resources/fabric.mod.json");
				Object updates = Files.isRegularFile(mod) && Json.object(Json.parse(Files.readString(mod)), "fabric.mod.json").get("custom") instanceof Map<?, ?> c
					&& c.get("myriad") instanceof Map<?, ?> m ? m.get("updates") : null;
				if (!(updates instanceof String u) || !REPO.matcher(u).matches()) {
					throw new IllegalArgumentException("a closed-source addon names its public releases repository in fabric.mod.json (custom.myriad.updates)");
				}
				publicRepo = u;
			}
			// The public releases repository must really be public and have the source repository's owner.
			long ownerId = Json.number(source.get("ownerId"), "owner id");
			String auth = System.getenv("GH_TOKEN") == null ? null : "token " + System.getenv("GH_TOKEN");
			Map<String, Object> pub = Json.object(Json.parse(get("https://api.github.com/repos/" + publicRepo, auth, 1024 * 1024)), "the public repository");
			if (!Boolean.FALSE.equals(pub.get("private")) || Json.number(Json.object(pub.get("owner"), "owner").get("id"), "owner id") != ownerId) {
				throw new IllegalArgumentException(publicRepo + " isn't a public repository with the same owner as the source");
			}
			long publicRepoId = Json.number(pub.get("id"), "id");
			Object listing = spec.get("listing");
			output("ok", "true");
			output("source_repo", sourceRepo);
			output("public_repo", publicRepo);
			output("public_repo_id", String.valueOf(publicRepoId));
			output("tag", tag);
			output("commit", commit);
			output("build_hash", buildHash);
			output("source_sha256", Signing.hex(Signing.sha256(Files.readAllBytes(archive))));
			output("listed", String.valueOf(listing instanceof Map<?, ?>));
		} catch (IllegalArgumentException | IOException e) {
			output("ok", "false");
			output("problem", oneLine(e.getMessage()));
		}
	}

	/** Like {@link #checkout}, with a token for a private repository (in git's environment, never on its command line). */
	private static String checkoutWith(String repo, String ref, Path dir, String token) throws IOException, InterruptedException {
		Files.createDirectories(dir);
		git(dir, "init", "--quiet");
		String basic = java.util.Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
		gitWith(dir, Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader", "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic),
			"-c", "protocol.allow=never", "-c", "protocol.https.allow=always", "fetch", "--quiet", "--depth", "1", "--no-tags", "--no-recurse-submodules",
			"https://github.com/" + repo + ".git", ref);
		git(dir, "-c", "core.hooksPath=/dev/null", "checkout", "--quiet", "--detach", "FETCH_HEAD");
		return check(SHA1, git(dir, "rev-parse", "HEAD").strip(), "commit");
	}

	private static void inspect() throws Exception {
		String commit = check(SHA1, env("COMMIT"), "commit");
		Path work = Path.of(env("WORK"));
		Path src = work.resolve("src");
		String url;
		String archive = System.getenv("SOURCE_ARCHIVE");
		if (archive == null) {
			checkout(check(REPO, env("REPO"), "repository"), commit, src, commit);
			url = env("ASSET_URL");
		} else {
			// The private builder: the source from the fetch job's archive (checked against its hash), the jar from the
			// public releases repository under the same tag.
			if (!Signing.hex(Signing.sha256(Files.readAllBytes(Path.of(archive)))).equals(env("SOURCE_SHA256"))) throw new IllegalArgumentException("the source archive was changed");
			Files.createDirectories(src);
			Process tar = new ProcessBuilder("tar", "-xzf", archive, "-C", src.toString(), "--no-same-owner", "--no-same-permissions").redirectErrorStream(true).start();
			tar.getInputStream().readAllBytes();
			if (tar.waitFor() != 0) throw new IOException("the source archive couldn't be read");
			url = "https://github.com/" + check(REPO, env("PUBLIC_REPO"), "repository") + "/releases/download/" + check(TAG, env("TAG"), "tag") + "/" + env("JAR_NAME");
		}
		if (!ASSET.matcher(url).matches()) throw new IllegalArgumentException("not a release jar: " + oneLine(url));
		Path jar = work.resolve("released.jar");
		Files.write(jar, download(url, 64L * 1024 * 1024));
		Map<String, Object> sigEntry = Json.object(Json.parse(new String(download(url + ".sig", 64 * 1024), StandardCharsets.UTF_8)), "the .sig");
		Map<String, Object> report = Inspect.inspect(jar, src);
		if (!report.get("sha256").equals(sigEntry.get("sha256")) || !Long.valueOf(Json.number(sigEntry.get("size"), "size")).equals(report.get("size"))
			|| !java.util.Objects.equals(report.get("version"), sigEntry.get("version"))) {
			throw new IllegalArgumentException("the release's .sig is for a different jar");
		}
		report.put("url", url);
		report.put("commit", commit);
		report.put("developer", sigEntry.get("signatures"));
		Path reportFile = work.resolve("report.json");
		Files.writeString(reportFile, Json.write(report));
		List<String> findings = new ArrayList<>();
		for (Object f : Json.array(report.get("findings"), "findings")) findings.add(Json.string(Json.object(f, "finding").get("id"), "id"));
		if (findings.size() > 200) {
			findings = new ArrayList<>(findings.subList(0, 200));
			findings.add("too-many-findings");
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		for (String k : List.of("sha256", "size", "id", "version", "provides", "keys", "minecraft")) summary.put(k, report.get(k));
		summary.put("findings", findings);
		summary.put("developer", sigEntry.get("signatures"));
		output("summary", Json.write(summary));
		output("report_sha256", Signing.hex(Signing.sha256(Files.readAllBytes(reportFile))));
	}

	private static void sign() throws Exception {
		Map<String, Object> listing = Json.object(Json.parse(env("LISTING")), "the listing");
		Map<String, Object> body = LibraryTrust.verifiedBody(get(api() + "/v1/index.json", null, 8L * 1024 * 1024), LibraryTrust.ROOT_KEYS, now());
		if (!LibraryTrust.listingSigned(listing, body, LibraryTrust.ROOT_KEYS, now())) throw new IllegalArgumentException("the listing isn't signed by a Myriad listing key");
		Map<String, Object> summary = Json.object(Json.parse(env("SUMMARY")), "the summary");
		Set<String> accepted = new HashSet<>(strings(Json.parse(env("ACCEPTED"))));
		String buildSha = env("BUILD_SHA"), buildHash = check(SHA256, env("BUILD_HASH"), "build hash"), commit = check(SHA1, env("COMMIT"), "commit");
		String url = env("ASSET_URL");
		if (!ASSET.matcher(url).matches()) throw new IllegalArgumentException("not a release jar");
		Decision d = decide(listing, accepted, summary, buildSha, buildHash);
		if (!d.sign()) {
			output("decision", "review");
			output("reasons", oneLine(String.join("; ", d.reasons())));
			return;
		}
		Signing.Key key = Signing.parsePrivate(env("LIBRARY_VERSION_KEY"));
		Map<String, List<String>> f = new LinkedHashMap<>();
		f.put("id", List.of((String) summary.get("id")));
		f.put("version", List.of((String) summary.get("version")));
		f.put("size", List.of(String.valueOf(Json.number(summary.get("size"), "size"))));
		f.put("sha256", List.of((String) summary.get("sha256")));
		f.put("commit", List.of(commit));
		byte[] statement = LibraryStatements.statement("version", f);
		Map<String, Object> version = new LinkedHashMap<>();
		version.put("id", summary.get("id"));
		version.put("version", summary.get("version"));
		version.put("size", Json.number(summary.get("size"), "size"));
		version.put("sha256", summary.get("sha256"));
		version.put("commit", commit);
		version.put("url", url);
		if (summary.get("minecraft") instanceof String mc && mc.length() <= 200) version.put("minecraft", mc);
		version.put("signatures", List.of(Json.parse(LibraryStatements.signatureJson(key, statement))));
		version.put("developer", summary.get("developer"));
		output("decision", "signed");
		output("version", Json.write(version));
	}

	private static void report() throws Exception {
		String api = api(), token = env("BUILDER_TOKEN");
		long id = submission();
		Map<String, Object> result = new LinkedHashMap<>();
		String reportFile = System.getenv("REPORT_FILE"), reportSha = System.getenv("REPORT_SHA256");
		Map<String, Object> report = null;
		if (reportFile != null && reportSha != null && Files.isRegularFile(Path.of(reportFile))) {
			byte[] r = Files.readAllBytes(Path.of(reportFile));
			// The report came through an artifact, which the build job could have swapped: only the inspect job's hash counts.
			if (Signing.hex(Signing.sha256(r)).equals(reportSha)) report = Json.object(Json.parse(new String(r, StandardCharsets.UTF_8)), "the report");
		}
		if (!"true".equals(System.getenv("PREPARE_OK"))) {
			result.put("outcome", "failed");
			result.put("reason", "Couldn't prepare this release: " + orElse(System.getenv("PROBLEM"), "the builder failed"));
		} else if (!"success".equals(System.getenv("BUILD_RESULT"))) {
			result.put("outcome", "failed");
			result.put("reason", "It didn't build with ./gradlew jar on JDK 25.");
		} else if (!"success".equals(System.getenv("INSPECT_RESULT")) || report == null) {
			result.put("outcome", "failed");
			result.put("reason", "Its released jar couldn't be checked (a jar and its .sig attached to the release are needed).");
		} else if ("closed".equals(System.getenv("KIND"))) {
			result.put("outcome", "review");
			result.put("reason", "true".equals(System.getenv("LISTED"))
				? "A closed-source release: every version waits for the Myriad maintainer's review."
				: "A new closed-source addon: waiting for the Myriad maintainer's review.");
		} else if (!"true".equals(System.getenv("LISTED"))) {
			result.put("outcome", "review");
			result.put("reason", "A new addon: waiting for the Myriad maintainer's review.");
		} else if ("signed".equals(System.getenv("DECISION"))) {
			result.put("outcome", "signed");
			result.put("version", Json.parse(env("VERSION")));
		} else {
			result.put("outcome", "review");
			result.put("reason", orElse(System.getenv("REASONS"), "The sign step didn't finish."));
		}
		String run = System.getenv("GITHUB_RUN_ID");
		if (report == null) report = new LinkedHashMap<>();
		if (run != null && run.matches("[0-9]{1,20}")) report.put("run", run);
		String built = System.getenv("BUILD_SHA"), buildHash = System.getenv("BUILD_HASH");
		if (built != null && SHA256.matcher(built).matches()) report.put("built", built);
		if (buildHash != null && SHA256.matcher(buildHash).matches()) report.put("buildHash", buildHash);
		if (!report.isEmpty()) {
			Object icon = report.remove("icon");
			if (icon instanceof String s) result.put("icon", s);
			result.put("report", report);
		}
		post(api + "/v1/library/builds/" + id + "/result", token, Json.write(result));
	}

	// ---- source checks ----------------------------------------------------------------------------------------------

	/** What {@code fabric.mod.json} may hold of Gradle's template syntax: the plugin's three placeholders, nothing else. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(version|myriad_version|minecraft_version)}");
	private static final Pattern GRADLE_DIST = Pattern.compile("https\\\\?://services\\.gradle\\.org/distributions/gradle-[0-9A-Za-z.-]{1,40}-(bin|all)\\.zip");

	/**
	 * Refuses source that could run code or change the build without changing its build-logic hash:
	 * <ul>
	 * <li>a {@code fabric.mod.json} with template code: older plugins fill it in with Gradle's {@code expand}, which runs
	 * Groovy, and the file is the addon's own (not build logic);</li>
	 * <li>a Gradle wrapper that downloads Gradle from anywhere but services.gradle.org without pinning its SHA-256;</li>
	 * <li>git attributes that change files on checkout or archive ({@code filter}, {@code export-ignore},
	 * {@code export-subst}), so what's hashed is what's built.</li>
	 * </ul>
	 */
	static void checkSource(Path src) throws IOException {
		try (var walk = Files.walk(src)) {
			for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
				String rel = src.relativize(p).toString().replace('\\', '/');
				if (rel.startsWith(".git/")) continue;
				String name = p.getFileName().toString();
				if (name.equals("fabric.mod.json")) {
					String text = PLACEHOLDER.matcher(Files.readString(p, StandardCharsets.UTF_8)).replaceAll("");
					if (text.indexOf('$') >= 0 || text.contains("<%")) {
						throw new IllegalArgumentException(rel + " holds template code ('$' or '<%' other than ${version}, ${myriad_version}, ${minecraft_version})");
					}
				}
				if (name.equals(".gitattributes")) {
					for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
						String l = line.strip();
						if (l.startsWith("#")) continue;
						if (l.matches(".*(\\s|^)(-?)(filter|export-ignore|export-subst)(=|\\s|$).*")) {
							throw new IllegalArgumentException(rel + " uses filter, export-ignore or export-subst, which change files on checkout");
						}
					}
				}
			}
		}
		Path wrapper = src.resolve("gradle/wrapper/gradle-wrapper.properties");
		if (Files.isRegularFile(wrapper)) {
			java.util.Properties props = new java.util.Properties();
			try (var in = Files.newInputStream(wrapper)) {
				props.load(in);
			}
			String url = props.getProperty("distributionUrl", "");
			String sum = props.getProperty("distributionSha256Sum");
			if (sum == null && !GRADLE_DIST.matcher(url.replace("\\:", ":")).matches()) {
				throw new IllegalArgumentException("the Gradle wrapper downloads Gradle from somewhere other than services.gradle.org without distributionSha256Sum");
			}
			if (sum != null && !SHA256.matcher(sum).matches()) throw new IllegalArgumentException("the Gradle wrapper's distributionSha256Sum isn't a SHA-256");
		}
	}

	// ---- helpers ----------------------------------------------------------------------------------------------------

	/** The source at {@code ref}, which must be {@code commit}: fetched without any credentials, no submodules or hooks. */
	static void checkout(String repo, String ref, Path dir, String commit) throws IOException, InterruptedException {
		Files.createDirectories(dir);
		git(dir, "init", "--quiet");
		git(dir, "-c", "protocol.allow=never", "-c", "protocol.https.allow=always", "fetch", "--quiet", "--depth", "1", "--no-tags", "--no-recurse-submodules",
			"https://github.com/" + repo + ".git", ref);
		git(dir, "-c", "core.hooksPath=/dev/null", "checkout", "--quiet", "--detach", "FETCH_HEAD");
		String head = git(dir, "rev-parse", "HEAD").strip();
		if (!head.equals(commit)) throw new IOException("the tag moved: it's at " + head + ", not " + commit);
	}

	private static String git(Path dir, String... args) throws IOException, InterruptedException {
		return gitWith(dir, Map.of(), args);
	}

	private static String gitWith(Path dir, Map<String, String> env, String... args) throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
		cmd.addAll(List.of(args));
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		pb.environment().putAll(env);
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		pb.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
		Process p = pb.start();
		String out;
		try (InputStream in = p.getInputStream()) {
			out = new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
		}
		if (p.waitFor() != 0) throw new IOException("git " + args[args.length > 1 ? 1 : 0] + " failed: " + oneLine(out));
		return out;
	}

	private static String get(String url, String auth, long max) throws IOException, InterruptedException {
		HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "myriad-builder")
			.header("Accept", url.startsWith("https://api.github.com/") ? "application/vnd.github+json" : "application/json");
		if (auth != null) r.header("Authorization", auth);
		HttpResponse<InputStream> res = HTTP.send(r.build(), HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = res.body()) {
			byte[] b = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
			if (res.statusCode() / 100 != 2) throw new IOException(URI.create(url).getHost() + " answered HTTP " + res.statusCode());
			if (b.length > max) throw new IOException("the answer from " + URI.create(url).getHost() + " is too big");
			return new String(b, StandardCharsets.UTF_8);
		}
	}

	private static void post(String url, String token, String json) throws IOException, InterruptedException {
		HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "myriad-builder")
			.header("Content-Type", "application/json").header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(json)).build();
		HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() / 100 != 2) throw new IOException("the API answered HTTP " + res.statusCode() + ": " + oneLine(res.body().substring(0, Math.min(300, res.body().length()))));
	}

	private static String postFor(String url, String token) throws IOException, InterruptedException {
		HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "myriad-builder")
			.header("Content-Type", "application/json").header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
		HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() / 100 != 2) throw new IOException("the API answered HTTP " + res.statusCode());
		return res.body();
	}

	/** A GitHub release asset, following GitHub's redirects to its storage (https only, no credentials, capped). */
	static byte[] download(String url, long max) throws IOException, InterruptedException {
		URI u = URI.create(url);
		for (int hop = 0; hop < 5; hop++) {
			if (!"https".equals(u.getScheme()) || !DOWNLOAD_HOSTS.contains(u.getHost())) throw new IOException("won't download from " + u.getHost());
			HttpResponse<InputStream> res = HTTP.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(120)).header("User-Agent", "myriad-builder").build(),
				HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = res.body()) {
				if (res.statusCode() / 100 == 3) {
					u = u.resolve(res.headers().firstValue("location").orElseThrow(() -> new IOException("a redirect to nowhere")));
					continue;
				}
				if (res.statusCode() != 200) throw new IOException("GitHub answered HTTP " + res.statusCode() + " for " + u.getPath());
				byte[] b = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
				if (b.length > max) throw new IOException("the download is too big");
				return b;
			}
		}
		throw new IOException("too many redirects");
	}

	private static void output(String name, String value) throws IOException {
		if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException("an output can't hold a line break");
		Files.writeString(Path.of(env("GITHUB_OUTPUT")), name + "=" + value + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static String env(String name) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " isn't set");
		return v;
	}

	private static String api() {
		String a = env("API");
		if (!a.equals("https://api.myriadclient.dev")) throw new IllegalArgumentException("the builder only talks to Myriad's API");
		return a;
	}

	private static long submission() {
		String s = env("SUBMISSION");
		if (!s.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException("not a submission number");
		return Long.parseLong(s);
	}

	private static String check(Pattern p, String v, String what) {
		if (!p.matcher(v).matches()) throw new IllegalArgumentException("odd " + what + ": " + oneLine(v));
		return v;
	}

	private static String oneLine(String s) {
		if (s == null) return "";
		String t = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").strip();
		return t.length() > 500 ? t.substring(0, 500) : t;
	}

	private static String orElse(String s, String fallback) {
		return s == null || s.isBlank() ? fallback : oneLine(s);
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}
}
