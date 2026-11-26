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
 * <li>{@code native-load}, {@code native-file:<path>}: loads native code, or carries some.</li>
 * <li>{@code network}, {@code host:<host>}: opens connections itself; the hosts named in its constants.</li>
 * <li>{@code instrument}: {@code java.lang.instrument} or scripting engines.</li>
 * <li>{@code myriad-internals}: reaches into Myriad's {@code impl} packages, which addons mustn't use.</li>
 * <li>{@code nested:<path>:<sha256>}: a jar inside the jar.</li>
 * <li>{@code obfuscated}: mostly one- and two-letter class names: unreadable in review, so refused.</li>
 * <li>{@code unexplained:<class>}: a class with no source file it could come from (only with the source given).</li>
 * </ul>
 */
public final class Inspect {
	private static final long MAX_ENTRY = 32L * 1024 * 1024;
	private static final int MAX_ENTRIES = 20_000;
	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?)://([a-z0-9.-]{1,253})(?::\\d{1,5})?(?:[/?#]|$)");
	private static final Pattern NATIVE = Pattern.compile("(?i).+\\.(so|dll|dylib|jnilib)");
	private static final Set<String> LOADER_SUPERS = Set.of("java/lang/ClassLoader", "java/net/URLClassLoader", "java/security/SecureClassLoader");

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
		int shortNames = 0;
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String name = e.getKey();
			if (NATIVE.matcher(name).matches()) add(findings, "native-file:" + name, name);
			if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) add(findings, "nested:" + name + ":" + Signing.hex(Signing.sha256(e.getValue())), name);
			if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
			String cls = name.substring(0, name.length() - 6);
			classes.add(cls);
			String simple = cls.substring(cls.lastIndexOf('/') + 1);
			if (simple.length() <= 2 || !simple.chars().allMatch(c -> c < 0x80)) shortNames++;
			scanClass(cls, e.getValue(), findings);
		}
		report.put("classes", (long) classes.size());
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

	/** What a class refers to, from its constant pool (nothing is loaded or run). */
	static void scanClass(String cls, byte[] bytes, TreeMap<String, TreeSet<String>> findings) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readInt() != 0xCAFEBABE) return;
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
						return;
					}
				}
			}
			in.readUnsignedShort();
			in.readUnsignedShort();
			int superIndex = in.readUnsignedShort();
			String superName = superIndex > 0 && superIndex < count && tag[superIndex] == 7 ? utf[a[superIndex]] : null;
			if (superName != null && LOADER_SUPERS.contains(superName)) add(findings, "classloader", cls);
			for (int k = 1; k < count; k++) {
				if (tag[k] == 7 && utf[a[k]] != null) classRef(cls, utf[a[k]], findings);
				if ((tag[k] == 10 || tag[k] == 11) && a[k] < count && b[k] < count && tag[a[k]] == 7 && tag[b[k]] == 12) {
					String owner = utf[a[a[k]]], name = utf[a[b[k]]];
					if (owner != null && name != null) methodRef(cls, owner, name, findings);
				}
				if (tag[k] == 8 && a[k] < count && utf[a[k]] != null) constant(cls, utf[a[k]], findings);
			}
		} catch (IOException | RuntimeException e) {
			add(findings, "unreadable-class", cls);
		}
	}

	private static void classRef(String cls, String ref, TreeMap<String, TreeSet<String>> f) {
		if (ref.equals("sun/misc/Unsafe") || ref.equals("jdk/internal/misc/Unsafe")) add(f, "unsafe", cls);
		if (ref.equals("java/lang/ProcessBuilder")) add(f, "process", cls);
		if (ref.startsWith("java/net/http/") || ref.equals("java/net/Socket") || ref.equals("java/net/ServerSocket") || ref.equals("java/net/DatagramSocket")
			|| ref.equals("java/net/URLConnection") || ref.equals("java/net/HttpURLConnection") || ref.startsWith("javax/net/ssl/")) add(f, "network", cls);
		if (ref.startsWith("java/lang/instrument/") || ref.startsWith("javax/script/")) add(f, "instrument", cls);
		if (ref.startsWith("dev/myriadclient/impl/")) add(f, "myriad-internals", cls);
	}

	private static void methodRef(String cls, String owner, String name, TreeMap<String, TreeSet<String>> f) {
		if ((name.equals("defineClass") || name.equals("defineHiddenClass")) && (LOADER_SUPERS.contains(owner) || owner.equals("java/lang/invoke/MethodHandles$Lookup"))) add(f, "classloader", cls);
		if (owner.equals("java/lang/Runtime") && name.equals("exec")) add(f, "process", cls);
		if ((owner.equals("java/lang/System") || owner.equals("java/lang/Runtime")) && (name.equals("load") || name.equals("loadLibrary"))) add(f, "native-load", cls);
		if (owner.equals("java/net/URL") && (name.equals("openConnection") || name.equals("openStream"))) add(f, "network", cls);
	}

	private static void constant(String cls, String s, TreeMap<String, TreeSet<String>> f) {
		if (s.startsWith("dev.myriadclient.impl.")) add(f, "myriad-internals", cls);
		Matcher m = URL.matcher(s);
		while (m.find()) add(f, "host:" + m.group(1).toLowerCase(java.util.Locale.ROOT), cls);
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

	private static void add(TreeMap<String, TreeSet<String>> f, String id, String where) {
		f.computeIfAbsent(id, k -> new TreeSet<>()).add(where);
	}
}
