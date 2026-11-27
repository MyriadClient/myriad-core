package dev.myriadclient.gradle.signing;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Looks at an addon jar without running any of it, for the Myriad Library's builder and the maintainer's review: what
 * it says it is ({@code fabric.mod.json}), and what in it deserves a person's look. Each finding has a stable id (like
 * {@code process} or {@code host:example.com}) so one the maintainer has accepted for a listing doesn't hold up its next
 * version, plus where it was found, for people. Findings are hints, not verdicts: they decide only whether a new version
 * waits for review.
 *
 * <ul>
 * <li>{@code classloader}: defines classes at run time (custom class loaders, {@code defineClass}, hidden classes).</li>
 * <li>{@code unsafe}: {@code sun.misc.Unsafe} or the JDK's internal one.</li>
 * <li>{@code process}: starts programs ({@code Runtime.exec}, {@code ProcessBuilder}).</li>
 * <li>{@code native-load}, {@code native-file:<path>}: loads native code ({@code System.load}, {@code java.lang.foreign}),
 * or carries some.</li>
 * <li>{@code network}, {@code host:<host>}: opens connections itself (sockets, channels, HTTP clients, URL streams,
 * Minecraft's and common libraries' download helpers); the hosts named in its constants.</li>
 * <li>{@code reflection}: looks up or calls code by name at run time ({@code Class.forName}, {@code Method.invoke},
 * {@code setAccessible}, method handle lookups), which can reach anything the other checks look for.</li>
 * <li>{@code instrument}: {@code java.lang.instrument} or scripting engines.</li>
 * <li>{@code desktop}, {@code jndi}, {@code deserialization}: opens files or links outside the game, JNDI lookups,
 * Java object deserialization.</li>
 * <li>{@code myriad-internals}: reaches into Myriad's {@code impl} packages, which addons mustn't use.</li>
 * <li>{@code nested:<path>:<sha256>}: a jar inside the jar, wherever it is: any entry that is a zip, and anything its
 * {@code fabric.mod.json} names under {@code jars}.</li>
 * <li>{@code hidden-class:<path>}: a class file under {@code META-INF/} or not named {@code .class}.</li>
 * <li>{@code multi-release}: a multi-release jar, whose classes differ by Java version.</li>
 * <li>{@code obfuscated}: mostly one- and two-letter class names: unreadable in review, so refused.</li>
 * <li>{@code unexplained:<class>}: a class with no source file it could come from (only with the source given).</li>
 * <li>{@code account:<class>}: reaches for the player's session or login (the access token, Mojang's session service, the
 * launcher's account files, browser or Discord storage): never accepted for good, every version that has it is read.</li>
 * <li>{@code mixin-network:<class>}: a mixin into the game's connection, packet handling, login or account classes.</li>
 * </ul>
 *
 * <p>What can reach the outside world or other code ({@link #PER_CLASS}) is found per class ({@code network:<class>}), so
 * accepting it for one class never lets a new class do the same unseen.
 *
 * <p>Beside the findings, the report says what the addon adds, for Browse: its modules (read from the bytecode, each
 * Module subclass's name, description and tag as passed to {@code super(...)}), and its changelog for this version (from
 * the source's {@code CHANGELOG.md}, when the source is given).
 */
public final class Inspect {
	private static final long MAX_ENTRY = 32L * 1024 * 1024;
	private static final int MAX_ENTRIES = 20_000;
	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?)://([a-z0-9.-]{1,253})(?::\\d{1,5})?(?:[/?#]|$)");
	private static final Pattern NATIVE = Pattern.compile("(?i).+\\.(so|dll|dylib|jnilib)");
	private static final Set<String> LOADER_SUPERS = Set.of("java/lang/ClassLoader", "java/net/URLClassLoader", "java/security/SecureClassLoader");
	/** Kinds found per class: accepting one class's never covers another's. */
	static final Set<String> PER_CLASS = Set.of("classloader", "unsafe", "process", "native-load", "network", "reflection", "instrument", "desktop", "jndi",
		"deserialization", "myriad-internals", "account");
	/** Bare host names and addresses in constants (a URL's host is caught with its scheme too). */
	private static final Pattern BARE_HOST = Pattern.compile("(?i)(?<![\\w.-])((?:[a-z0-9-]{1,63}\\.)+(?:com|net|org|io|dev|gg|xyz|me|co|ru|cn|uk|de|app|cloud|site|online|tk|top|info|biz|link|sh|pw|cc|su|ws|club|fun|lol))(?![\\w.-])");
	private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?::\\d{1,5})?(?![\\w.])");
	/** Constants that name the player's accounts or a browser's or Discord's storage. */
	private static final List<String> ACCOUNT_TEXT = List.of("launcher_accounts", "launcher_profiles", "accesstoken", "local storage", "leveldb", "login data",
		"microsoft_accounts", "session.json", ".minecraft" + "/");
	private static final String MODULE = "dev/myriadclient/api/module/Module", TAG_DESC = "Ldev/myriadclient/api/module/Tag;";
	private static final String USER = "net/minecraft/client/User";
	/** The account's secrets, by getter or by field. */
	private static final Set<String> ACCOUNT_MEMBERS = Set.of("accessToken", "getAccessToken", "sessionId", "getSessionId", "xuid", "getXuid", "clientId", "getClientId");
	private static final int MAX_MODULES = 300, MAX_CHANGELOG = 4000;

	private Inspect() {
	}

	/** The report on {@code jar}; with {@code source} (the repository it was built from), also unexplained classes. */
	public static Map<String, Object> inspect(Path jar, Path source) throws IOException {
		Map<String, Object> report = new LinkedHashMap<>();
		byte[] bytes = Files.readAllBytes(jar);
		report.put("size", (long) bytes.length);
		report.put("sha256", Signing.hex(Signing.sha256(bytes)));
		TreeMap<String, TreeSet<String>> findings = new TreeMap<>();
		Map<String, byte[]> entries = readEntries(jar, bytes);

		byte[] modJson = entries.get("fabric.mod.json");
		if (modJson == null) throw new IOException("the jar has no fabric.mod.json");
		Map<String, Object> meta = Json.object(Json.parse(new String(modJson, StandardCharsets.UTF_8)), "fabric.mod.json");
		report.put("id", Json.string(meta.get("id"), "the id"));
		report.put("version", Json.string(meta.get("version"), "the version"));
		report.put("name", meta.get("name") instanceof String s ? s : "");
		report.put("description", meta.get("description") instanceof String s ? s : "");
		report.put("authors", authors(meta.get("authors")));
		report.put("provides", meta.get("provides") instanceof List<?> l ? l.stream().filter(String.class::isInstance).toList() : List.of());
		report.put("entrypoints", meta.get("entrypoints") instanceof Map<?, ?> m ? m : Map.of());
		report.put("mixins", meta.get("mixins") instanceof List<?> l ? l : List.of());
		Map<?, ?> depends = meta.get("depends") instanceof Map<?, ?> m ? m : Map.of();
		report.put("minecraft", depends.get("minecraft") instanceof String s ? s : null);
		Map<?, ?> custom = meta.get("custom") instanceof Map<?, ?> m ? m : Map.of();
		Map<?, ?> myriad = custom.get("myriad") instanceof Map<?, ?> m ? m : Map.of();
		report.put("keys", myriad.get("keys") instanceof List<?> l ? l.stream().filter(String.class::isInstance).toList() : List.of());
		report.put("updates", myriad.get("updates") instanceof String s ? s : null);

		Set<String> classes = new TreeSet<>();
		Map<String, ClassInfo> infos = new LinkedHashMap<>();
		int shortNames = 0;
		// Nested jars the loader would load: whatever fabric.mod.json names, zip or not.
		for (Object j : meta.get("jars") instanceof List<?> l ? l : List.of()) {
			String file = j instanceof Map<?, ?> m && m.get("file") instanceof String s ? s : null;
			if (file == null) continue;
			byte[] nested = entries.get(file.startsWith("/") ? file.substring(1) : file);
			add(findings, "nested:" + file + ":" + (nested == null ? "missing" : Signing.hex(Signing.sha256(nested))), file);
		}
		byte[] manifest = entries.get("META-INF/MANIFEST.MF");
		if (manifest != null && new String(manifest, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT).matches("(?s).*(^|\n)multi-release:\\s*true.*")) {
			add(findings, "multi-release", "META-INF/MANIFEST.MF");
		}
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String name = e.getKey();
			byte[] b = e.getValue();
			if (NATIVE.matcher(name).matches()) add(findings, "native-file:" + name, name);
			// By name and by content: any jar or zip inside is one the loader might be told to load.
			if (name.endsWith(".jar") || startsWith(b, 0x50, 0x4b, 0x03, 0x04) || startsWith(b, 0x50, 0x4b, 0x05, 0x06)) add(findings, "nested:" + name + ":" + Signing.hex(Signing.sha256(b)), name);
			if (!startsWith(b, 0xca, 0xfe, 0xba, 0xbe)) continue;
			if (!name.endsWith(".class") || name.startsWith("META-INF/")) add(findings, "hidden-class:" + name, name);
			String cls = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
			classes.add(cls);
			String simple = cls.substring(cls.lastIndexOf('/') + 1);
			if (simple.length() <= 2 || !simple.chars().allMatch(c -> c < 0x80)) shortNames++;
			ClassInfo info = scanClass(cls, e.getValue(), findings);
			if (info != null) infos.put(cls, info);
		}
		report.put("classes", (long) classes.size());
		accessWidener(meta, entries, findings);
		report.put("modules", modules(infos));
		if (source != null) {
			String log = changelog(source, Json.string(meta.get("version"), "the version"));
			if (log != null) report.put("changelog", log);
		}
		if (classes.size() >= 10 && shortNames * 10 >= classes.size() * 3) add(findings, "obfuscated", shortNames + " of " + classes.size() + " classes have one- or two-letter names");
		if (source != null) unexplained(classes, source, findings);

		report.put("icon", icon(meta.get("icon"), entries));
		List<Map<String, Object>> list = new ArrayList<>();
		findings.forEach((id, where) -> {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("id", id);
			f.put("where", new ArrayList<>(where).subList(0, Math.min(10, where.size())));
			list.add(f);
		});
		report.put("findings", list);
		return report;
	}

	/**
	 * Every entry, read through the central directory as the loader reads it, after checking the jar's local headers
	 * agree with it: the same names in the same order, none twice. A jar that disagrees with itself is refused.
	 */
	private static Map<String, byte[]> readEntries(Path jar, byte[] bytes) throws IOException {
		List<String> local = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				if (local.size() >= MAX_ENTRIES) throw new IOException("the jar has too many entries");
				local.add(e.getName());
			}
		}
		Map<String, byte[]> out = new LinkedHashMap<>();
		List<String> central = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
				ZipEntry e = en.nextElement();
				String name = e.getName();
				if (name.startsWith("/") || name.contains("\\") || name.contains("..") || name.chars().anyMatch(c -> c < 0x20)) throw new IOException("the jar has an odd entry name: " + name);
				central.add(name);
				if (e.isDirectory()) continue;
				try (InputStream in = zip.getInputStream(e)) {
					byte[] b = in.readNBytes((int) MAX_ENTRY + 1);
					if (b.length > MAX_ENTRY) throw new IOException(name + " is too big");
					out.put(name, b);
				}
			}
		}
		if (!local.equals(central)) throw new IOException("the jar's local headers don't match its central directory");
		if (new HashSet<>(central).size() != central.size()) throw new IOException("the jar has an entry twice");
		return out;
	}

	private static List<String> authors(Object v) {
		List<String> out = new ArrayList<>();
		if (v instanceof List<?> l) {
			for (Object a : l) {
				if (a instanceof String s) out.add(s);
				else if (a instanceof Map<?, ?> m && m.get("name") instanceof String s) out.add(s);
			}
		}
		return out;
	}

	/** What one class is, for finding modules: its superclass, whether it's abstract, and how its constructor calls super. */
	record ClassInfo(String superName, boolean isAbstract, SuperCall superCall) {
	}

	/** The string constants pushed and the tag read just before a constructor called its superclass's. */
	record SuperCall(List<String> strings, String tag, String descriptor) {
	}

	/** What a class refers to and how it calls its super constructor, from its bytes (nothing is loaded or run). */
	static ClassInfo scanClass(String cls, byte[] bytes, TreeMap<String, TreeSet<String>> findings) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readInt() != 0xCAFEBABE) return null;
			in.readUnsignedShort();
			in.readUnsignedShort();
			int count = in.readUnsignedShort();
			String[] utf = new String[count];
			int[] tag = new int[count], a = new int[count], b = new int[count];
			for (int k = 1; k < count; k++) {
				tag[k] = in.readUnsignedByte();
				switch (tag[k]) {
					case 1 -> utf[k] = in.readUTF();
					case 3, 4 -> in.readInt();
					case 5, 6 -> {
						in.readLong();
						k++;
					}
					case 7, 8, 16, 19, 20 -> a[k] = in.readUnsignedShort();
					case 9, 10, 11, 12, 17, 18 -> {
						a[k] = in.readUnsignedShort();
						b[k] = in.readUnsignedShort();
					}
					case 15 -> {
						in.readUnsignedByte();
						a[k] = in.readUnsignedShort();
					}
					default -> {
						add(findings, "unreadable-class", cls);
						return null;
					}
				}
			}
			int access = in.readUnsignedShort();
			in.readUnsignedShort();
			int superIndex = in.readUnsignedShort();
			String superName = superIndex > 0 && superIndex < count && tag[superIndex] == 7 ? utf[a[superIndex]] : null;
			if (superName != null && LOADER_SUPERS.contains(superName)) add(findings, "classloader", cls);
			boolean mixin = false;
			for (int k = 1; k < count; k++) {
				if (tag[k] == 1 && "Lorg/spongepowered/asm/mixin/Mixin;".equals(utf[k])) mixin = true;
				if (tag[k] == 7 && utf[a[k]] != null) classRef(cls, utf[a[k]], findings);
				if ((tag[k] == 10 || tag[k] == 11) && a[k] < count && b[k] < count && tag[a[k]] == 7 && tag[b[k]] == 12) {
					String owner = utf[a[a[k]]], name = utf[a[b[k]]];
					if (owner != null && name != null) methodRef(cls, owner, name, findings);
				}
				// A field read directly (made accessible by an access widener) counts like its getter.
				if (tag[k] == 9 && a[k] < count && b[k] < count && tag[a[k]] == 7 && tag[b[k]] == 12) {
					String owner = utf[a[a[k]]], name = utf[a[b[k]]];
					if (owner != null && name != null && owner.equals(USER) && ACCOUNT_MEMBERS.contains(name)) add(findings, "account", cls);
				}
				if (tag[k] == 8 && a[k] < count && utf[a[k]] != null) constant(cls, utf[a[k]], findings);
			}
			if (mixin) {
				for (int k = 1; k < count; k++) {
					if (tag[k] != 1 || utf[k] == null) continue;
					String target = utf[k].startsWith("L") && utf[k].endsWith(";") ? utf[k].substring(1, utf[k].length() - 1) : utf[k].replace('.', '/');
					if (accountClass(target)) add(findings, "account", cls);
					else if (networkClass(target)) add(findings, "mixin-network", cls);
				}
			}
			// The rest of the class: interfaces and fields skipped, each method's code read for its super constructor call.
			int interfaces = in.readUnsignedShort();
			in.skipNBytes(interfaces * 2L);
			int fields = in.readUnsignedShort();
			for (int f = 0; f < fields; f++) {
				in.skipNBytes(6);
				skipAttributes(in);
			}
			SuperCall superCall = null;
			int methods = in.readUnsignedShort();
			for (int m = 0; m < methods; m++) {
				in.readUnsignedShort();
				int nameIndex = in.readUnsignedShort();
				in.readUnsignedShort();
				boolean init = nameIndex < count && "<init>".equals(utf[nameIndex]);
				int attrs = in.readUnsignedShort();
				for (int at = 0; at < attrs; at++) {
					int attrName = in.readUnsignedShort();
					int length = in.readInt();
					if (init && superCall == null && attrName < count && "Code".equals(utf[attrName])) {
						byte[] attr = in.readNBytes(length);
						superCall = superCall(attr, superName, utf, tag, a, b, count);
					} else {
						in.skipNBytes(length);
					}
				}
			}
			return new ClassInfo(superName, (access & 0x0400) != 0 || (access & 0x0200) != 0, superCall);
		} catch (IOException | RuntimeException e) {
			add(findings, "unreadable-class", cls);
			return null;
		}
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int attrs = in.readUnsignedShort();
		for (int at = 0; at < attrs; at++) {
			in.readUnsignedShort();
			in.skipNBytes(in.readInt() & 0xffffffffL);
		}
	}

	/**
	 * Walks a constructor's bytecode to its first call of the superclass's constructor: the string constants pushed since
	 * the last other call, and the last {@code Tag} read, are what it was given.
	 */
	private static SuperCall superCall(byte[] attr, String superName, String[] utf, int[] tag, int[] a, int[] b, int count) {
		if (superName == null || attr.length < 8) return null;
		int codeLength = ((attr[4] & 0xff) << 24) | ((attr[5] & 0xff) << 16) | ((attr[6] & 0xff) << 8) | (attr[7] & 0xff);
		int start = 8, end = Math.min(attr.length, start + codeLength);
		List<String> strings = new ArrayList<>();
		String lastTag = null;
		int pc = start;
		while (pc < end) {
			int op = attr[pc] & 0xff;
			int len = instructionLength(attr, pc, start);
			if (len <= 0) return null;
			int index = op == 0x12 ? attr[pc + 1] & 0xff : (pc + 2 < end) ? ((attr[pc + 1] & 0xff) << 8) | (attr[pc + 2] & 0xff) : -1;
			if ((op == 0x12 || op == 0x13) && index > 0 && index < count && tag[index] == 8 && utf[a[index]] != null) {
				strings.add(utf[a[index]]);
			} else if (op == 0xb2 && index > 0 && index < count && tag[index] == 9 && tag[b[index]] == 12) {
				if (TAG_DESC.equals(utf[b[b[index]]])) lastTag = utf[a[b[index]]];
			} else if (op >= 0xb6 && op <= 0xba) {
				if (op == 0xb7 && index > 0 && index < count && (tag[index] == 10 || tag[index] == 11) && tag[a[index]] == 7 && tag[b[index]] == 12) {
					String owner = utf[a[a[index]]], name = utf[a[b[index]]];
					if (superName.equals(owner) && "<init>".equals(name)) return new SuperCall(List.copyOf(strings), lastTag, utf[b[b[index]]]);
				}
				strings.clear();
			}
			pc += len;
		}
		return null;
	}

	/** How many bytes the instruction at {@code pc} takes ({@code base}: where the code starts, for the switches' padding). */
	private static int instructionLength(byte[] code, int pc, int base) {
		int op = code[pc] & 0xff;
		return switch (op) {
			case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc -> 2;
			case 0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3;
			case 0xc5 -> 4;
			case 0xb9, 0xba, 0xc8, 0xc9 -> 5;
			case 0xc4 -> (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
			case 0xaa -> {
				int p = pc + 1 + ((4 - ((pc + 1 - base) % 4)) % 4);
				int low = readInt(code, p + 4), high = readInt(code, p + 8);
				yield (p - pc) + 12 + (high - low + 1) * 4;
			}
			case 0xab -> {
				int p = pc + 1 + ((4 - ((pc + 1 - base) % 4)) % 4);
				int pairs = readInt(code, p + 4);
				yield (p - pc) + 8 + pairs * 8;
			}
			default -> op >= 0x99 && op <= 0xa8 ? 3 : 1;
		};
	}

	private static int readInt(byte[] b, int p) {
		return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
	}

	/** The modules the jar adds: every concrete class whose superclasses (in this jar) reach Module, by what it passed up. */
	static List<Map<String, Object>> modules(Map<String, ClassInfo> infos) {
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (Map.Entry<String, ClassInfo> e : infos.entrySet()) {
			ClassInfo c = e.getValue();
			if (c.isAbstract() || c.superCall() == null || !reachesModule(e.getKey(), infos)) continue;
			List<String> s = c.superCall().strings();
			if (s.isEmpty()) continue;
			String name = s.size() >= 2 ? s.get(s.size() - 2) : s.getFirst();
			String description = s.size() >= 2 ? s.getLast() : "";
			if (name.isBlank() || name.length() > 64 || !names.add(name)) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("description", description.length() > 200 ? description.substring(0, 200) : description);
			m.put("tag", c.superCall().tag() == null ? "" : titleCase(c.superCall().tag()));
			out.add(m);
			if (out.size() >= MAX_MODULES) break;
		}
		out.sort(java.util.Comparator.comparing(m -> ((String) m.get("name")).toLowerCase(java.util.Locale.ROOT)));
		return out;
	}

	private static boolean reachesModule(String cls, Map<String, ClassInfo> infos) {
		String at = cls;
		for (int depth = 0; depth < 16 && at != null; depth++) {
			ClassInfo c = infos.get(at);
			if (c == null) return false;
			if (MODULE.equals(c.superName())) return true;
			at = c.superName();
		}
		return false;
	}

	private static String titleCase(String constant) {
		String lower = constant.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/**
	 * This version's part of {@code CHANGELOG.md} at the source's root: the lines under the heading that names it
	 * ({@code ## 2.9.0}, {@code ## [2.9.0] - date}), up to the next heading of that level; null when there's none.
	 */
	static String changelog(Path source, String version) {
		Path file = source.resolve("CHANGELOG.md");
		if (!Files.isRegularFile(file)) return null;
		List<String> lines;
		try {
			if (Files.size(file) > 2 * 1024 * 1024) return null;
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
		Pattern heading = Pattern.compile("^##\\s+\\[?v?" + Pattern.quote(version) + "\\]?(?:\\s|$|[-(:]).*");
		StringBuilder out = new StringBuilder();
		boolean in = false;
		for (String line : lines) {
			if (line.startsWith("## ") || line.equals("##")) {
				if (in) break;
				in = heading.matcher(line).matches();
				continue;
			}
			if (in) out.append(line).append('\n');
		}
		String text = out.toString().strip();
		if (text.isEmpty()) return null;
		return text.length() > MAX_CHANGELOG ? text.substring(0, MAX_CHANGELOG).strip() : text;
	}

	/** The player's account: a mixin into it, or an access widener opening it, is read every release. */
	private static boolean accountClass(String t) {
		return t.equals(USER) || t.startsWith("com/mojang/authlib/");
	}

	/** The connection, packets and login: a mixin into them is a finding of its own. */
	private static boolean networkClass(String t) {
		return t.equals("net/minecraft/network/Connection") || t.startsWith("net/minecraft/network/Connection$")
			|| t.equals("net/minecraft/client/multiplayer/ClientPacketListener") || t.equals("net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl");
	}

	/**
	 * The access widener (or class tweaker) {@code fabric.mod.json} names: one that opens the player's account to the
	 * addon's code is an {@code account} finding, as reading it through a getter would be.
	 */
	private static void accessWidener(Map<String, Object> meta, Map<String, byte[]> entries, TreeMap<String, TreeSet<String>> findings) {
		for (String key : List.of("accessWidener", "classTweaker")) {
			if (!(meta.get(key) instanceof String path)) continue;
			byte[] file = entries.get(path.startsWith("/") ? path.substring(1) : path);
			if (file == null) continue;
			for (String line : new String(file, StandardCharsets.UTF_8).split("\\R")) {
				String[] words = line.strip().split("\\s+");
				for (String w : words) {
					if (accountClass(w)) {
						add(findings, "account", path);
						break;
					}
				}
			}
		}
	}

	private static final Set<String> NETWORK_CLASSES = Set.of("java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket", "java/net/MulticastSocket",
		"java/net/URLConnection", "java/net/HttpURLConnection", "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel",
		"java/nio/channels/DatagramChannel", "java/nio/channels/AsynchronousSocketChannel", "java/nio/channels/AsynchronousServerSocketChannel",
		"net/minecraft/util/HttpUtil", "net/minecraft/network/Connection", "com/mojang/authlib/minecraft/client/MinecraftClient",
		"com/mojang/authlib/HttpAuthenticationService");
	private static final List<String> NETWORK_PACKAGES = List.of("java/net/http/", "javax/net/ssl/", "org/apache/http/", "org/apache/hc/", "okhttp3/",
		"com/sun/net/httpserver/", "jdk/internal/net/");

	private static void classRef(String cls, String ref, TreeMap<String, TreeSet<String>> f) {
		if (ref.equals("sun/misc/Unsafe") || ref.equals("jdk/internal/misc/Unsafe")) add(f, "unsafe", cls);
		if (ref.equals("java/lang/ProcessBuilder")) add(f, "process", cls);
		if (NETWORK_CLASSES.contains(ref) || NETWORK_PACKAGES.stream().anyMatch(ref::startsWith)) add(f, "network", cls);
		if (ref.startsWith("java/lang/foreign/")) add(f, "native-load", cls);
		if (ref.startsWith("java/lang/instrument/") || ref.startsWith("javax/script/")) add(f, "instrument", cls);
		if (ref.equals("java/awt/Desktop")) add(f, "desktop", cls);
		if (ref.startsWith("javax/naming/")) add(f, "jndi", cls);
		if (ref.equals("java/io/ObjectInputStream")) add(f, "deserialization", cls);
		if (ref.startsWith("dev/myriadclient/impl/")) add(f, "myriad-internals", cls);
		if (ref.equals("com/mojang/authlib/minecraft/MinecraftSessionService") || ref.startsWith("com/mojang/authlib/yggdrasil/")) add(f, "account", cls);
	}

	private static void methodRef(String cls, String owner, String name, TreeMap<String, TreeSet<String>> f) {
		if ((name.equals("defineClass") || name.equals("defineHiddenClass")) && (LOADER_SUPERS.contains(owner) || owner.equals("java/lang/invoke/MethodHandles$Lookup"))) add(f, "classloader", cls);
		if (owner.equals("java/lang/Runtime") && name.equals("exec")) add(f, "process", cls);
		if ((owner.equals("java/lang/System") || owner.equals("java/lang/Runtime")) && (name.equals("load") || name.equals("loadLibrary"))) add(f, "native-load", cls);
		if (owner.equals("java/net/URL") && (name.equals("openConnection") || name.equals("openStream"))) add(f, "network", cls);
		if (owner.equals("org/apache/commons/io/FileUtils") && name.startsWith("copyURL")) add(f, "network", cls);
		if ((owner.equals("java/lang/Class") && (name.equals("forName") || name.startsWith("getDeclared") || name.equals("getMethod") || name.equals("getField")))
			|| (owner.equals("java/lang/reflect/Method") && name.equals("invoke"))
			|| (owner.equals("java/lang/reflect/AccessibleObject") || owner.equals("java/lang/reflect/Field") || owner.equals("java/lang/reflect/Method")
				|| owner.equals("java/lang/reflect/Constructor")) && (name.equals("setAccessible") || name.equals("trySetAccessible"))
			|| (owner.equals("java/lang/reflect/Constructor") && name.equals("newInstance"))
			|| (owner.equals("java/lang/invoke/MethodHandles$Lookup") && (name.startsWith("find") || name.startsWith("unreflect")))
			|| (owner.equals("java/lang/invoke/MethodHandles") && name.equals("privateLookupIn"))
			|| (owner.equals("java/lang/ClassLoader") && name.equals("loadClass"))) add(f, "reflection", cls);
		if (owner.equals(USER) && ACCOUNT_MEMBERS.contains(name)) add(f, "account", cls);
	}

	private static boolean startsWith(byte[] b, int... magic) {
		if (b.length < magic.length) return false;
		for (int i = 0; i < magic.length; i++) if ((b[i] & 0xff) != magic[i]) return false;
		return true;
	}

	private static void constant(String cls, String s, TreeMap<String, TreeSet<String>> f) {
		if (s.startsWith("dev.myriadclient.impl.")) add(f, "myriad-internals", cls);
		Matcher m = URL.matcher(s);
		while (m.find()) add(f, "host:" + m.group(1).toLowerCase(java.util.Locale.ROOT), cls);
		Matcher bare = BARE_HOST.matcher(s);
		while (bare.find()) add(f, "host:" + bare.group(1).toLowerCase(java.util.Locale.ROOT), cls);
		Matcher ip = IPV4.matcher(s);
		while (ip.find()) add(f, "host:" + ip.group(1), cls);
		String lower = s.toLowerCase(java.util.Locale.ROOT);
		for (String t : ACCOUNT_TEXT) {
			if (lower.contains(t)) {
				add(f, "account", cls);
				break;
			}
		}
	}

	/** Classes that no source file in the repository could have made (inner and synthetic classes follow their outer class). */
	private static void unexplained(Set<String> classes, Path source, TreeMap<String, TreeSet<String>> f) throws IOException {
		Set<String> known = new HashSet<>();
		for (String root : List.of("src/main/java", "src/main/kotlin", "src/client/java", "src/client/kotlin")) {
			Path dir = source.resolve(root);
			if (!Files.isDirectory(dir)) continue;
			try (var walk = Files.walk(dir)) {
				walk.filter(Files::isRegularFile).forEach(p -> {
					String rel = dir.relativize(p).toString().replace('\\', '/');
					if (rel.endsWith(".java")) known.add(rel.substring(0, rel.length() - 5));
					if (rel.endsWith(".kt")) {
						String base = rel.substring(0, rel.length() - 3);
						known.add(base);
						known.add(base + "Kt");
					}
				});
			}
		}
		for (String cls : classes) {
			String outer = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
			if (!known.contains(outer) && !outer.endsWith("/package-info") && !outer.equals("module-info")) add(f, "unexplained:" + cls, cls);
		}
	}

	/** The icon, decoded and written again as a small PNG (at most 256 px, 64 KB), or null. */
	private static String icon(Object field, Map<String, byte[]> entries) {
		String path = null;
		if (field instanceof String s) path = s;
		else if (field instanceof Map<?, ?> m) {
			int best = 0;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				try {
					int size = Integer.parseInt(String.valueOf(e.getKey()));
					if (e.getValue() instanceof String s && size <= 256 && size > best) {
						best = size;
						path = s;
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		byte[] png = path == null ? null : entries.get(path.startsWith("/") ? path.substring(1) : path);
		if (png == null || png.length > 2 * 1024 * 1024) return null;
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
			if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0 || img.getWidth() > 4096 || img.getHeight() > 4096) return null;
			int w = img.getWidth(), h = img.getHeight();
			if (Math.max(w, h) > 256) {
				double scale = 256.0 / Math.max(w, h);
				w = Math.max(1, (int) Math.round(w * scale));
				h = Math.max(1, (int) Math.round(h * scale));
			}
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			out.createGraphics().drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(out, "png", bytes);
			return bytes.size() <= 64 * 1024 ? Base64.getEncoder().encodeToString(bytes.toByteArray()) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Notes a finding; kinds in {@link #PER_CLASS} are kept apart per class ({@code where} is the class then). */
	private static void add(TreeMap<String, TreeSet<String>> f, String id, String where) {
		String key = PER_CLASS.contains(id) || id.equals("mixin-network") ? id + ":" + where : id;
		f.computeIfAbsent(key, k -> new TreeSet<>()).add(where);
	}
}
