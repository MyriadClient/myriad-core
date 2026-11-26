package dev.myriadclient.gradle.signing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The hash of an addon repository's build logic: every file that isn't the addon's own code, resources, tests or docs.
 * A Library listing names it, and a new version is signed without a person looking only while it's unchanged, because
 * a build script can make a jar out of anything (even download one), so matching hashes prove nothing unless someone
 * has read how the jar is built. Changed build logic waits for review.
 *
 * <p>The addon's own files (left out): {@code src/main/**} and {@code src/client/**} (except scripts and Gradle files in
 * them), {@code src/test/**}, {@code docs/**}, {@code .github/**}, and at the top level only, Markdown files and the
 * licence ({@code LICENSE}, {@code COPYING} or {@code NOTICE}, plain or {@code .txt}/{@code .md}). Everything else is build
 * logic: the Gradle files, the wrapper, {@code buildSrc}, settings, any script. In {@code gradle.properties} the
 * {@code mod_version} line is ignored, since it changes every release, but only when there's exactly one and neither it
 * nor the line before it continues onto the next (a trailing {@code \}); otherwise the whole file counts. A symbolic
 * link anywhere refuses the whole repository: it could make a file look like something it isn't. {@code .git} is
 * skipped.
 *
 * <p>The hash is SHA-256 over {@code <path>\0<file sha256 hex>\n} for each build-logic file, sorted by path, paths
 * relative with {@code /}.
 */
public final class BuildHash {
	private static final Pattern PAYLOAD = Pattern.compile(
		"src/(main|client)/.+|src/test/.+|docs/.+|\\.github/.+|[^/]+\\.md|(LICEN[CS]E|COPYING|NOTICE)(\\.(txt|md))?");
	/** Never the addon's own, wherever they are: they run during a build. */
	private static final Pattern SCRIPT = Pattern.compile(".+\\.(gradle|kts|sh|bat|cmd|ps1|py|groovy)");
	private static final Pattern MOD_VERSION = Pattern.compile("\\s*mod_version\\s*[=:].*");
	public static final int MAX_FILES = 20_000;

	private BuildHash() {
	}

	/** Whether {@code path} (relative, with {@code /}) is the addon's own, not build logic. */
	public static boolean payload(String path) {
		return PAYLOAD.matcher(path).matches() && !SCRIPT.matcher(path).matches();
	}

	/** The build-logic files of {@code repo} and their SHA-256s, sorted by path. */
	public static TreeMap<String, String> files(Path repo) throws IOException {
		Path root = repo.toRealPath();
		TreeMap<String, String> out = new TreeMap<>();
		List<String> problems = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (dir.equals(root.resolve(".git"))) return FileVisitResult.SKIP_SUBTREE;
				if (Files.isSymbolicLink(dir)) problems.add(rel(root, dir));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String rel = rel(root, file);
				if (attrs.isSymbolicLink() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					problems.add(rel);
					return FileVisitResult.CONTINUE;
				}
				if (out.size() >= MAX_FILES) throw new IOException("the repository has too many files");
				if (!payload(rel)) out.put(rel, Signing.hex(Signing.sha256(content(rel, file))));
				return FileVisitResult.CONTINUE;
			}
		});
		if (!problems.isEmpty()) throw new IOException("the repository has symbolic links or special files: " + String.join(", ", problems.subList(0, Math.min(5, problems.size()))));
		return out;
	}

	/** The build-logic hash of {@code repo}. */
	public static String of(Path repo) throws IOException {
		StringBuilder s = new StringBuilder();
		for (var e : files(repo).entrySet()) s.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
		return HexFormat.of().formatHex(Signing.sha256(s.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] content(String rel, Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (!rel.equals("gradle.properties")) return bytes;
		String[] lines = new String(bytes, StandardCharsets.ISO_8859_1).split("\n", -1);
		int at = -1;
		for (int i = 0; i < lines.length; i++) {
			if (!MOD_VERSION.matcher(lines[i]).matches()) continue;
			if (at >= 0) return bytes; // twice: there's no telling which counts
			at = i;
		}
		if (at < 0 || continues(lines[at]) || (at > 0 && continues(lines[at - 1]))) return bytes;
		StringBuilder kept = new StringBuilder();
		for (int i = 0; i < lines.length; i++) if (i != at) kept.append(lines[i]).append('\n');
		return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
	}

	/** Whether a properties line goes on onto the next one (it ends in a backslash). */
	private static boolean continues(String line) {
		return line.stripTrailing().endsWith("\\");
	}

	private static String rel(Path root, Path p) {
		return root.relativize(p).toString().replace('\\', '/');
	}
}
