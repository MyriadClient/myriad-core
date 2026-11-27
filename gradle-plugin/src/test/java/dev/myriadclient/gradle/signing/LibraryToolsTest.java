package dev.myriadclient.gradle.signing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The Library's tools: the JSON reader, the inspector, the trust chain and the builder's signing decision. */
class LibraryToolsTest {
	static Signing.Key key(int n) throws Exception {
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte) n);
		return Signing.fromSeed(seed);
	}

	@Test
	void jsonIsStrict() {
		assertEquals(Map.of("a", List.of(new java.math.BigDecimal("1"), "x\n", true)), Json.parse("{\"a\": [1, \"x\\n\", true]}"));
		assertEquals("{\"a\":[1,\"x\\n\",null],\"b\":\"\\u2028\"}", Json.write(new LinkedHashMap<>(Map.of("a", Arrays.asList(1, "x\n", null)))).replace("}", ",\"b\":\"\\u2028\"}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1, \"a\": 2}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2,]"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{} {}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("\"a\u0001\""));
	}

	/** A jar built here from real compiled classes, with the things the inspector looks for. */
	static Path addonJar(Path dir, boolean withSourceForAll) throws Exception {
		Path src = dir.resolve("src/main/java/com/example");
		Files.createDirectories(src);
		Files.writeString(src.resolve("Good.java"), "package com.example; public class Good { void run() throws Exception { Runtime.getRuntime().exec(\"ls\"); String u = \"https://Example.COM/api\"; } }");
		Path extra = dir.resolve("gen/com/example");
		Files.createDirectories(extra);
		Files.writeString(extra.resolve("Sneaky.java"), "package com.example; public class Sneaky extends ClassLoader { }");
		Path out = dir.resolve("classes");
		Files.createDirectories(out);
		List<String> args = new ArrayList<>(List.of("-d", out.toString(), src.resolve("Good.java").toString(), extra.resolve("Sneaky.java").toString()));
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
		if (withSourceForAll) Files.copy(extra.resolve("Sneaky.java"), src.resolve("Sneaky.java"));

		Path jar = dir.resolve("addon.jar");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
			z.putNextEntry(new ZipEntry("fabric.mod.json"));
			z.write(("{\"schemaVersion\":1,\"id\":\"my-addon\",\"version\":\"1.2.0\",\"name\":\"My Addon\",\"provides\":[\"my-lib\"],\"icon\":\"assets/my-addon/icon.png\","
				+ "\"depends\":{\"minecraft\":\"~26.3\"},\"custom\":{\"myriad\":{\"keys\":[\"" + key(5).publicText() + "\"]}}}").getBytes(StandardCharsets.UTF_8));
			for (String c : List.of("Good", "Sneaky")) {
				z.putNextEntry(new ZipEntry("com/example/" + c + ".class"));
				z.write(Files.readAllBytes(out.resolve("com/example/" + c + ".class")));
			}
			z.putNextEntry(new ZipEntry("META-INF/jars/lib.jar"));
			z.write(new byte[]{1, 2, 3});
			z.putNextEntry(new ZipEntry("natives/libevil.so"));
			z.write(new byte[]{0});
			z.putNextEntry(new ZipEntry("assets/my-addon/icon.png"));
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			javax.imageio.ImageIO.write(img, "png", png);
			z.write(png.toByteArray());
		}
		return jar;
	}

	@Test
	void inspectorFindsWhatDeservesALook(@TempDir Path dir) throws Exception {
		Path jar = addonJar(dir, false);
		Map<String, Object> r = Inspect.inspect(jar, dir);
		assertEquals("my-addon", r.get("id"));
		assertEquals("1.2.0", r.get("version"));
		assertEquals(List.of("my-lib"), r.get("provides"));
		assertEquals("~26.3", r.get("minecraft"));
		List<String> ids = new ArrayList<>();
		for (Object f : Json.array(r.get("findings"), "findings")) ids.add((String) ((Map<?, ?>) f).get("id"));
		assertTrue(ids.contains("process:com/example/Good"), ids.toString());
		assertTrue(ids.contains("host:example.com"), ids.toString());
		assertTrue(ids.contains("classloader:com/example/Sneaky"), ids.toString());
		assertTrue(ids.contains("native-file:natives/libevil.so"), ids.toString());
		assertTrue(ids.stream().anyMatch(i -> i.startsWith("nested:META-INF/jars/lib.jar:")), ids.toString());
		assertTrue(ids.contains("unexplained:com/example/Sneaky"), ids.toString());
		assertFalse(ids.contains("unexplained:com/example/Good"), ids.toString());
		// The icon came back re-encoded and shrunk.
		byte[] icon = java.util.Base64.getDecoder().decode((String) r.get("icon"));
		assertEquals(256, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(icon)).getWidth());

		Map<String, Object> withSource = Inspect.inspect(addonJar(dir.resolve("b"), true), dir.resolve("b"));
		assertFalse(Json.write(withSource.get("findings")).contains("unexplained"));
	}

	@Test
	void inspectorRefusesAJarWithoutMetadata(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("x.jar");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
			z.putNextEntry(new ZipEntry("a.txt"));
		}
		assertThrows(java.io.IOException.class, () -> Inspect.inspect(jar, null));
	}

	// ---- trust chain ------------------------------------------------------------------------------------------------

	static Map<String, Object> sig(Signing.Key k, byte[] statement) throws Exception {
		return Json.object(Json.parse(LibraryStatements.signatureJson(k, statement)), "sig");
	}

	static byte[] st(String kind, String fields) {
		return LibraryStatements.statement(kind, LibraryStatements.fields(List.of(fields.split(" "))));
	}

	@Test
	void trustChainStartsAtTheRoots() throws Exception {
		Signing.Key root = key(1), listingKey = key(2), indexKey = key(4), other = key(7);
		List<Object> certs = new ArrayList<>();
		for (Object[] c : new Object[][]{{listingKey, "listing", root}, {indexKey, "index", root}, {other, "listing", other}}) {
			Signing.Key k = (Signing.Key) c[0];
			String role = (String) c[1];
			Map<String, Object> cert = new LinkedHashMap<>();
			cert.put("key", k.publicText());
			cert.put("role", role);
			String f = "key=" + k.publicText() + " role=" + role;
			if (role.equals("index")) {
				cert.put("notAfter", 1_900_000_000L);
				f += " not-after=1900000000";
			}
			cert.put("signatures", List.of(sig((Signing.Key) c[2], st("key", f))));
			certs.add(cert);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("issued", 1_800_000_000L);
		body.put("expires", 1_800_100_000L);
		body.put("certs", certs);
		body.put("addons", List.of());
		List<String> roots = List.of(root.publicText());
		Map<String, java.util.Set<String>> keys = LibraryTrust.certifiedKeys(body, roots, 1_800_000_000L);
		assertEquals(Set.of(listingKey.publicText()), keys.get("listing"), "a self-certified key counts for nothing");
		assertEquals(Set.of(indexKey.publicText()), keys.get("index"));
		assertTrue(LibraryTrust.certifiedKeys(body, roots, 1_900_000_000L).get("index").isEmpty(), "an expired index key counts for nothing");

		String bodyText = Json.write(body);
		Map<String, Object> wrapper = new LinkedHashMap<>();
		wrapper.put("format", 1);
		wrapper.put("body", bodyText);
		wrapper.put("signatures", List.of(sig(indexKey, st("snapshot", "issued=1800000000 expires=1800100000 body-sha256=" + Signing.hex(Signing.sha256(bodyText.getBytes(StandardCharsets.UTF_8)))))));
		assertNotNull(LibraryTrust.verifiedBody(Json.write(wrapper), roots, 1_800_000_000L));
		assertThrows(IllegalArgumentException.class, () -> LibraryTrust.verifiedBody(Json.write(wrapper), List.of(other.publicText()), 1_800_000_000L));
		wrapper.put("body", bodyText.replace("1800100000", "1800100001"));
		assertThrows(IllegalArgumentException.class, () -> LibraryTrust.verifiedBody(Json.write(wrapper), roots, 1_800_000_000L));

		Map<String, Object> listing = listing(5);
		listing.put("signatures", List.of(sig(listingKey, LibraryTrust.listingStatement(listing))));
		assertTrue(LibraryTrust.listingSigned(listing, body, roots, 1_800_000_000L));
		listing.put("signatures", List.of(sig(other, LibraryTrust.listingStatement(listing))));
		assertFalse(LibraryTrust.listingSigned(listing, body, roots, 1_800_000_000L));

		// Accepted findings count only when a listing key signed exactly them, for this id and revision.
		List<String> found = List.of("process", "host:example.com");
		Object good = List.of(sig(listingKey, st("accepted", "id=my-addon revision=1 finding=process finding=host:example.com")));
		assertEquals(Set.copyOf(found), LibraryTrust.acceptedFindings(listing, found, good, body, roots, 1_800_000_000L));
		assertEquals(Set.of(), LibraryTrust.acceptedFindings(listing, List.of("process", "host:example.com", "unsafe"), good, body, roots, 1_800_000_000L), "one added by the API");
		Object oldRevision = List.of(sig(listingKey, st("accepted", "id=my-addon revision=2 finding=process finding=host:example.com")));
		assertEquals(Set.of(), LibraryTrust.acceptedFindings(listing, found, oldRevision, body, roots, 1_800_000_000L), "another revision's");
		Object byOther = List.of(sig(other, st("accepted", "id=my-addon revision=1 finding=process finding=host:example.com")));
		assertEquals(Set.of(), LibraryTrust.acceptedFindings(listing, found, byOther, body, roots, 1_800_000_000L), "not a listing key");
		assertEquals(Set.of(), LibraryTrust.acceptedFindings(listing, found, null, body, roots, 1_800_000_000L), "unsigned");
	}

	// ---- what could change a build without changing its hash --------------------------------------------------------

	@Test
	void sourceThatCouldRunCodeIsRefused(@TempDir Path dir) throws Exception {
		Path mod = dir.resolve("src/main/resources/fabric.mod.json");
		Files.createDirectories(mod.getParent());
		Files.writeString(mod, "{\"version\": \"${version}\", \"depends\": {\"myriad\": \">=${myriad_version}\", \"minecraft\": \"~${minecraft_version}\"}}");
		Builder.checkSource(dir);
		for (String bad : List.of("<% new File('x').text = 'y' %>", "${new File('x').text}", "$evil", "<%= 1 %>")) {
			Files.writeString(mod, "{\"name\": \"" + bad + "\"}");
			assertThrows(IllegalArgumentException.class, () -> Builder.checkSource(dir), bad);
		}
		Files.writeString(mod, "{}");

		Path wrapper = dir.resolve("gradle/wrapper/gradle-wrapper.properties");
		Files.createDirectories(wrapper.getParent());
		Files.writeString(wrapper, "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
		Builder.checkSource(dir);
		Files.writeString(wrapper, "distributionUrl=https\\://evil.example/gradle-9.7.1-bin.zip\n");
		assertThrows(IllegalArgumentException.class, () -> Builder.checkSource(dir), "Gradle from elsewhere, unpinned");
		Files.writeString(wrapper, "distributionUrl=https\\://evil.example/gradle-9.7.1-bin.zip\ndistributionSha256Sum=" + "c".repeat(64) + "\n");
		Builder.checkSource(dir);
		Files.writeString(wrapper, "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");

		for (String attr : List.of("*.gradle export-subst", "build.gradle export-ignore", "* filter=lfs diff=lfs merge=lfs")) {
			Files.writeString(dir.resolve(".gitattributes"), "*.java text\n" + attr + "\n");
			assertThrows(IllegalArgumentException.class, () -> Builder.checkSource(dir), attr);
		}
		Files.writeString(dir.resolve(".gitattributes"), "* text=auto eol=lf\n");
		Builder.checkSource(dir);
	}

	@Test
	void buildHashCoversWhatABuildCanRead(@TempDir Path dir) throws Exception {
		assertTrue(BuildHash.payload("LICENSE"));
		assertTrue(BuildHash.payload("README.md"));
		assertTrue(BuildHash.payload("src/main/java/a/B.java"));
		assertFalse(BuildHash.payload("libs/NOTICE.jar"), "a licence-like name elsewhere is build logic");
		assertFalse(BuildHash.payload("buildSrc/src/main/java/a/LICENSEUtil.java"));
		assertFalse(BuildHash.payload("gradle/notes.md"));
		assertFalse(BuildHash.payload("src/main/resources/x.gradle"));

		Path props = dir.resolve("gradle.properties");
		Files.writeString(props, "org.gradle.jvmargs=-Xmx2G\nmod_version=1.0.0\n");
		String h1 = BuildHash.of(dir);
		Files.writeString(props, "org.gradle.jvmargs=-Xmx2G\nmod_version=1.0.1\n");
		assertEquals(h1, BuildHash.of(dir), "a new version alone keeps the hash");
		Files.writeString(props, "org.gradle.jvmargs=-Xmx2G\nmod_version=1.0.1\\\norg.gradle.jvmargs=-javaagent:evil.jar\n");
		assertNotEquals(h1, BuildHash.of(dir), "a continued line can't hide the next one");
		Files.writeString(props, "org.gradle.jvmargs=-Xmx2G\\\nmod_version=1.0.1\n");
		assertNotEquals(h1, BuildHash.of(dir), "nor can a line continued into it");
		Files.writeString(props, "org.gradle.jvmargs=-Xmx2G\nmod_version=1.0.1\nmod_version=2\n");
		assertNotEquals(h1, BuildHash.of(dir), "two of them count");
	}

	@Test
	void inspectorFindsClassesAndJarsWhereverTheyAre(@TempDir Path dir) throws Exception {
		Path built = addonJar(dir.resolve("a"), true);
		byte[] good;
		try (var z = new java.util.zip.ZipFile(built.toFile())) {
			good = z.getInputStream(z.getEntry("com/example/Good.class")).readAllBytes();
		}
		Path jar = dir.resolve("hidden.jar");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
			z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
			z.write("Manifest-Version: 1.0\nMulti-Release: true\n".getBytes(StandardCharsets.UTF_8));
			z.putNextEntry(new ZipEntry("fabric.mod.json"));
			z.write("{\"id\":\"my-addon\",\"version\":\"1.0.0\",\"jars\":[{\"file\":\"assets/x/data.bin\"}]}".getBytes(StandardCharsets.UTF_8));
			z.putNextEntry(new ZipEntry("META-INF/x/Evil.class"));
			z.write(good);
			z.putNextEntry(new ZipEntry("assets/x/pic.png"));
			z.write(good);
			z.putNextEntry(new ZipEntry("assets/x/data.bin"));
			z.write(new byte[]{1, 2, 3});
			z.putNextEntry(new ZipEntry("assets/x/other.bin"));
			z.write(new byte[]{0x50, 0x4b, 0x03, 0x04, 0, 0});
		}
		List<String> ids = new ArrayList<>();
		for (Object f : Json.array(Inspect.inspect(jar, null).get("findings"), "findings")) ids.add((String) ((Map<?, ?>) f).get("id"));
		assertTrue(ids.contains("hidden-class:META-INF/x/Evil.class"), ids.toString());
		assertTrue(ids.contains("hidden-class:assets/x/pic.png"), ids.toString());
		assertTrue(ids.stream().anyMatch(i -> i.startsWith("process:")), "hidden classes are scanned too: " + ids);
		assertTrue(ids.stream().anyMatch(i -> i.startsWith("nested:assets/x/data.bin:")), ids.toString());
		assertTrue(ids.stream().anyMatch(i -> i.startsWith("nested:assets/x/other.bin:")), ids.toString());
		assertTrue(ids.contains("multi-release"), ids.toString());
	}

	// ---- the signing decision ---------------------------------------------------------------------------------------

	static final String BUILD = "b".repeat(64);

	static Map<String, Object> listing(int devSeed) throws Exception {
		Map<String, Object> l = new LinkedHashMap<>();
		l.put("id", "my-addon");
		l.put("revision", new java.math.BigDecimal(1));
		l.put("repoId", new java.math.BigDecimal(10));
		l.put("ownerId", new java.math.BigDecimal(20));
		l.put("source", "open");
		l.put("build", BUILD);
		l.put("provides", List.of("my-lib"));
		l.put("keys", List.of(key(devSeed).publicText()));
		return l;
	}

	static Map<String, Object> summary(Signing.Key dev, List<String> jarKeys, List<String> findings) throws Exception {
		String sha = "a".repeat(64);
		Map<String, Object> s = new HashMap<>();
		s.put("sha256", sha);
		s.put("size", new java.math.BigDecimal(1000));
		s.put("id", "my-addon");
		s.put("version", "1.2.0");
		s.put("provides", List.of("my-lib"));
		s.put("keys", jarKeys);
		s.put("findings", findings);
		s.put("developer", List.of(sig(dev, Signing.statement("my-addon", "1.2.0", 1000, java.util.HexFormat.of().parseHex(sha)))));
		return s;
	}

	@Test
	void signsOnlyWhenNothingNeedsAPerson() throws Exception {
		Signing.Key dev = key(5), stranger = key(6);
		String a = "a".repeat(64);
		Map<String, Object> l = listing(5);
		List<String> jarKeys = List.of(dev.publicText());
		assertTrue(Builder.decide(l, Set.of("network"), summary(dev, jarKeys, List.of("network")), a, BUILD, Map.of()).sign());

		assertFalse(Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of()), a, "c".repeat(64), Map.of()).sign(), "build logic changed");
		assertFalse(Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of()), "d".repeat(64), BUILD, Map.of()).sign(), "the release isn't what was built");
		assertFalse(Builder.decide(l, Set.of(), summary(stranger, jarKeys, List.of()), a, BUILD, Map.of()).sign(), "not the developer");
		assertFalse(Builder.decide(l, Set.of(), summary(dev, List.of(stranger.publicText()), List.of()), a, BUILD, Map.of()).sign(), "the jar stopped listing the key");
		assertFalse(Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of("process")), a, BUILD, Map.of()).sign(), "a new finding");
		assertFalse(Builder.decide(l, Set.of("obfuscated"), summary(dev, jarKeys, List.of("obfuscated")), a, BUILD, Map.of()).sign(), "obfuscation is never accepted");
		assertFalse(Builder.decide(l, Set.of("account:com/example/A"), summary(dev, jarKeys, List.of("account:com/example/A")), a, BUILD, Map.of()).sign(),
			"reaching for the account is read every release");
		assertFalse(Builder.decide(l, Set.of("too-many-findings"), summary(dev, jarKeys, List.of("too-many-findings")), a, BUILD, Map.of()).sign(),
			"findings that weren't all passed on are never accepted");
		assertFalse(Builder.decide(l, Set.of("network:com/example/Old"), summary(dev, jarKeys, List.of("network:com/example/Old", "network:com/example/New")), a, BUILD,
			Map.of()).sign(), "accepting one class's network use doesn't cover a new class");
		Map<String, Object> other = summary(dev, jarKeys, List.of());
		other.put("id", "other-addon");
		assertFalse(Builder.decide(l, Set.of(), other, a, BUILD, Map.of()).sign(), "another addon");
		Map<String, Object> provides = summary(dev, jarKeys, List.of());
		provides.put("provides", List.of("my-lib", "minecraft"));
		assertFalse(Builder.decide(l, Set.of(), provides, a, BUILD, Map.of()).sign(), "provides changed");
		Map<String, Object> closed = listing(5);
		closed.put("source", "closed");
		assertFalse(Builder.decide(closed, Set.of(), summary(dev, jarKeys, List.of()), a, BUILD, Map.of()).sign(), "closed source always waits");
		assertTrue(Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of()), a, BUILD, Map.of("1.2.0", Set.of(a))).sign(), "the same jar again");
		assertFalse(Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of()), a, BUILD, Map.of("1.2.0", Set.of("e".repeat(64)))).sign(),
			"a version the Library holds is never signed with another jar");

		// Nothing a person could approve is refused outright; the rest waits for review.
		assertFalse(Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of()), "d".repeat(64), BUILD, Map.of()).refused().isEmpty(), "not what was built");
		assertFalse(Builder.decide(l, Set.of(), summary(stranger, jarKeys, List.of()), a, BUILD, Map.of()).refused().isEmpty(), "not the developer");
		Builder.Decision changed = Builder.decide(l, Set.of(), summary(dev, jarKeys, List.of("process")), a, "c".repeat(64), Map.of());
		assertTrue(changed.refused().isEmpty());
		assertEquals(2, changed.review().size(), "changed build logic and a new finding wait for a person");

		Map<String, Object> report = summary(dev, jarKeys, List.of());
		assertNull(Builder.refusal(report, a));
		assertNotNull(Builder.refusal(report, "d".repeat(64)), "the released jar isn't what was built");
		assertNotNull(Builder.refusal(report, null), "nothing was built");
		assertNotNull(Builder.refusal(summary(stranger, jarKeys, List.of()), a), "signed by a key the jar doesn't list");
		assertNotNull(Builder.refusal(summary(dev, List.of(), List.of()), a), "a jar that lists no keys");
	}

	@Test
	void heldVersionsComeFromListedVersionsAndYanks() {
		Map<String, Object> body = Map.of("addons", List.of(
			Map.of("listing", Map.of("id", "my-addon"),
				"versions", List.of(Map.of("version", "1.0.0", "sha256", "a".repeat(64))),
				"yanks", List.of(Map.of("version", "0.9.0", "sha256", "b".repeat(64)))),
			Map.of("listing", Map.of("id", "other"), "versions", List.of(Map.of("version", "2.0.0", "sha256", "c".repeat(64))))));
		assertEquals(Map.of("1.0.0", Set.of("a".repeat(64)), "0.9.0", Set.of("b".repeat(64))), LibraryTrust.held(body, "my-addon"));
		assertEquals(Map.of(), LibraryTrust.held(body, "nobody"));
	}

	@Test
	void downloadsOnlyFromGitHub() {
		assertThrows(java.io.IOException.class, () -> Builder.download("https://evil.example/x.jar", 10));
		assertThrows(java.io.IOException.class, () -> Builder.download("http://github.com/x.jar", 10));
	}

	// ---- what Browse shows: modules and the changelog ------------------------------------------------------------------

	/** Compiles {@code sources} (path → code) against stand-ins for Myriad's Module and Tag, into a jar with them. */
	static Path moduleJar(Path dir, Map<String, String> sources) throws Exception {
		Map<String, String> all = new java.util.LinkedHashMap<>();
		all.put("dev/myriadclient/api/module/Tag.java", "package dev.myriadclient.api.module; public record Tag(String id) {}");
		all.put("dev/myriadclient/api/module/Module.java", "package dev.myriadclient.api.module; public abstract class Module { protected Module(Tag tag, String name, String description) {} }");
		all.put("com/example/Tags.java", "package com.example; import dev.myriadclient.api.module.Tag; public final class Tags { public static final Tag COMBAT = new Tag(\"combat\"); "
			+ "public static final Tag WORLD = new Tag(\"world\"); }");
		all.putAll(sources);
		Path src = dir.resolve("src"), out = dir.resolve("classes");
		List<String> args = new ArrayList<>(List.of("-d", out.toString()));
		all.put("net/minecraft/client/User.java", "package net.minecraft.client; public class User { public String accessToken; public String getName() { return \"\"; } }");
		for (Map.Entry<String, String> e : all.entrySet()) {
			if (e.getKey().startsWith("@")) continue;
			Path f = src.resolve(e.getKey());
			Files.createDirectories(f.getParent());
			Files.writeString(f, e.getValue());
			args.add(f.toString());
		}
		Files.createDirectories(out);
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
		Path jar = dir.resolve("modules.jar");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(out)) {
			z.putNextEntry(new ZipEntry("fabric.mod.json"));
			z.write("{\"schemaVersion\":1,\"id\":\"mods\",\"version\":\"2.0.0\",\"accessWidener\":\"mods.accesswidener\"}".getBytes(StandardCharsets.UTF_8));
			z.putNextEntry(new ZipEntry("mods.accesswidener"));
			z.write(sources.getOrDefault("@aw", "accessWidener v2 named\n").getBytes(StandardCharsets.UTF_8));
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				String name = out.relativize(p).toString().replace('\\', '/');
				// The stand-ins are core's, not the addon's: like a real addon jar, it doesn't carry them.
				if (name.startsWith("dev/") || name.startsWith("net/")) continue;
				z.putNextEntry(new ZipEntry(name));
				z.write(Files.readAllBytes(p));
			}
		}
		return jar;
	}

	@Test
	void modulesAreReadFromTheBytecode(@TempDir Path dir) throws Exception {
		Path jar = moduleJar(dir, Map.of(
			"com/example/KillAura.java", "package com.example; public class KillAura extends dev.myriadclient.api.module.Module { "
				+ "public KillAura() { super(Tags.COMBAT, \"Kill Aura\", \"Attacks targets in reach.\"); } }",
			"com/example/Nuker.java", "package com.example; public class Nuker extends dev.myriadclient.api.module.Module { "
				+ "private final int r; public Nuker() { super(Tags.WORLD, \"Nuker\", \"Breaks blocks around you.\"); r = Integer.parseInt(\"3\"); } }",
			"com/example/Base.java", "package com.example; public abstract class Base extends dev.myriadclient.api.module.Module { "
				+ "protected Base(String name) { super(Tags.COMBAT, name, \"shared\"); } }",
			"com/example/Child.java", "package com.example; public class Child extends Base { public Child() { super(\"Child Module\"); } }",
			"com/example/NotAModule.java", "package com.example; public class NotAModule { public NotAModule() { String s = \"Fake\"; } }"));
		List<?> modules = Json.array(Inspect.inspect(jar, null).get("modules"), "modules");
		List<String> names = new ArrayList<>();
		for (Object m : modules) names.add((String) ((Map<?, ?>) m).get("name"));
		assertEquals(List.of("Child Module", "Kill Aura", "Nuker"), names);
		Map<?, ?> kill = (Map<?, ?>) modules.get(1);
		assertEquals("Attacks targets in reach.", kill.get("description"));
		assertEquals("Combat", kill.get("tag"));
		assertEquals("World", ((Map<?, ?>) modules.get(2)).get("tag"));
	}

	@Test
	void theChangelogSectionForTheVersionIsRead(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("CHANGELOG.md"), "# Changelog\n\n## 2.1.0\n\n- Newer\n\n## [2.0.0] - 2026-11-26\n\n### Added\n- Kill Aura\n- Nuker\n\n## 1.9.0\n- Old\n");
		assertEquals("### Added\n- Kill Aura\n- Nuker", Inspect.changelog(dir, "2.0.0"));
		assertEquals("- Newer", Inspect.changelog(dir, "2.1.0"));
		assertNull(Inspect.changelog(dir, "2.0"), "a version is matched whole");
		assertNull(Inspect.changelog(dir, "3.0.0"));
		Files.writeString(dir.resolve("CHANGELOG.md"), "## 2.0.0\n" + "x".repeat(9000));
		assertEquals(4000, Inspect.changelog(dir, "2.0.0").length());
	}

	@Test
	void accountsHostsAndSensitiveMixinsAreFound(@TempDir Path dir) throws Exception {
		Path jar = moduleJar(dir, Map.of(
			"com/example/Grab.java", "package com.example; public class Grab { String a = \"%APPDATA%/.minecraft/launcher_accounts.json\"; "
				+ "String b = \"evil-collector.xyz\"; String c = \"10.0.0.5:8080\"; String d = \"1.21.4\"; String e = \"com.example.thing\"; }",
			"com/example/Plain.java", "package com.example; public class Plain { String x = \"just text\"; }"));
		List<String> ids = new ArrayList<>();
		for (Object f : Json.array(Inspect.inspect(jar, null).get("findings"), "findings")) ids.add((String) ((Map<?, ?>) f).get("id"));
		assertTrue(ids.contains("account:com/example/Grab"), ids.toString());
		assertTrue(ids.contains("host:evil-collector.xyz"), ids.toString());
		assertTrue(ids.contains("host:10.0.0.5"), ids.toString());
		assertFalse(ids.stream().anyMatch(i -> i.contains("1.21.4") || i.contains("com.example")), ids.toString());
		assertFalse(ids.stream().anyMatch(i -> i.endsWith("com/example/Plain")), ids.toString());
	}

	@Test
	void readingTheAccessTokenAnyWayIsFound(@TempDir Path dir) throws Exception {
		Path jar = moduleJar(dir, Map.of(
			"com/example/Field.java", "package com.example; public class Field { String grab(net.minecraft.client.User u) { return u.accessToken; } }",
			"com/example/Name.java", "package com.example; public class Name { String name(net.minecraft.client.User u) { return u.getName(); } }",
			"@aw", "accessWidener v2 named\naccessible field net/minecraft/client/User accessToken Ljava/lang/String;\n"));
		List<String> ids = new ArrayList<>();
		for (Object f : Json.array(Inspect.inspect(jar, null).get("findings"), "findings")) ids.add((String) ((Map<?, ?>) f).get("id"));
		assertTrue(ids.contains("account:com/example/Field"), "a direct field read: " + ids);
		assertTrue(ids.contains("account:mods.accesswidener"), "an access widener opening the account: " + ids);
		assertFalse(ids.contains("account:com/example/Name"), "the player's name is fine: " + ids);
	}
}
