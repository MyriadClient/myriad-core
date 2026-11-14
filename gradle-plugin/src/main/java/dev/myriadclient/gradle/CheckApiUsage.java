package dev.myriadclient.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fails the build when an addon's compiled classes use parts of Myriad's API that aren't for addons: members (or
 * whole types) marked {@code @ApiStatus.Internal}, which are core's own wiring and public only so its packages can
 * reach each other, and members deprecated for removal, which go in the next major release. {@link CheckApiOnly}
 * catches the internals package by name in the sources; this reads what the compiler actually linked, so a call
 * through a subclass or a static import is caught too.
 */
@DisableCachingByDefault(because = "Reading the classes again is quicker than the cache")
public abstract class CheckApiUsage extends DefaultTask {
	private static final String API = "dev/myriadclient/api/";
	private static final String INTERNAL = "Lorg/jetbrains/annotations/ApiStatus$Internal;";
	private static final String DEPRECATED = "Ljava/lang/Deprecated;";

	/** The addon's compiled classes. */
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getClasses();

	/** Where Myriad's API classes are read from (the compile classpath). */
	@Classpath
	public abstract ConfigurableFileCollection getApiClasspath();

	/** What's known of one API class: its supertypes, and whether it, and each of its members, is off limits. */
	private record ApiClass(String superName, String[] interfaces, String why, Map<String, String> members) {
	}

	private final Map<String, ApiClass> api = new HashMap<>();
	private final List<ZipFile> jars = new ArrayList<>();
	private final List<File> dirs = new ArrayList<>();

	@TaskAction
	public void check() throws IOException {
		for (File f : getApiClasspath().getFiles()) {
			if (f.isDirectory()) dirs.add(f);
			else if (f.getName().endsWith(".jar")) jars.add(new ZipFile(f));
		}
		Set<String> bad = new LinkedHashSet<>();
		try {
			for (File f : getClasses().getAsFileTree().getFiles()) {
				if (!f.getName().endsWith(".class")) continue;
				try (InputStream in = Files.newInputStream(f.toPath())) {
					new ClassReader(in).accept(new Scanner(bad), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				}
			}
		} finally {
			for (ZipFile jar : jars) jar.close();
		}
		if (!bad.isEmpty()) {
			throw new GradleException("This addon uses parts of Myriad's API that aren't for addons:\n  " + String.join("\n  ", bad)
				+ "\nUse what the API offers for addons instead (its Javadoc says), or ask for what's missing.");
		}
	}

	/** Collects every off-limits API member the classes it reads link to. */
	private final class Scanner extends ClassVisitor {
		private final Set<String> bad;
		private String self = "";

		Scanner(Set<String> bad) {
			super(Opcodes.ASM9);
			this.bad = bad;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			self = name.replace('/', '.');
			if (superName != null) type(superName, "extends");
			if (interfaces != null) for (String i : interfaces) type(i, "implements");
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			String where = self + "." + name;
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean isInterface) {
					member(where, owner, member + desc, member);
				}

				@Override
				public void visitFieldInsn(int opcode, String owner, String member, String desc) {
					member(where, owner, member + ":" + desc, member);
				}

				@Override
				public void visitTypeInsn(int opcode, String type) {
					if (type.startsWith("[")) return;
					typeIn(where, type);
				}
			};
		}

		private void type(String owner, String how) {
			typeIn(self + " (" + how + ")", owner);
		}

		private void typeIn(String where, String owner) {
			if (!owner.startsWith(API)) return;
			ApiClass c = load(owner);
			if (c != null && c.why() != null) bad.add(where + " uses " + owner.replace('/', '.') + ", which " + c.why());
		}

		private void member(String where, String owner, String key, String name) {
			if (!owner.startsWith(API)) return;
			String why = find(owner, key);
			if (why != null) bad.add(where + " uses " + owner.replace('/', '.') + "." + name + ", which " + why);
		}
	}

	/** Why {@code key} (name and descriptor) of {@code owner} or the type declaring it is off limits; null if it isn't. */
	private String find(String owner, String key) {
		Set<String> seen = new LinkedHashSet<>();
		List<String> todo = new ArrayList<>(List.of(owner));
		while (!todo.isEmpty()) {
			String name = todo.removeFirst();
			if (!name.startsWith(API) || !seen.add(name)) continue;
			ApiClass c = load(name);
			if (c == null) continue;
			if (c.members().containsKey(key)) {
				String why = c.members().get(key);
				return why != null ? why : c.why();
			}
			if (c.superName() != null) todo.add(c.superName());
			if (c.interfaces() != null) todo.addAll(List.of(c.interfaces()));
		}
		return null;
	}

	private ApiClass load(String name) {
		if (api.containsKey(name)) return api.get(name);
		ApiClass c = null;
		try (InputStream in = open(name + ".class")) {
			if (in != null) {
				Reader reader = new Reader();
				new ClassReader(in).accept(reader, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				c = new ApiClass(reader.superName, reader.interfaces, reader.why, reader.members);
			}
		} catch (IOException e) {
			throw new GradleException("Couldn't read " + name + " from the classpath", e);
		}
		api.put(name, c);
		return c;
	}

	private InputStream open(String path) throws IOException {
		for (File dir : dirs) {
			File f = new File(dir, path);
			if (f.isFile()) return Files.newInputStream(f.toPath());
		}
		for (ZipFile jar : jars) {
			ZipEntry e = jar.getEntry(path);
			if (e != null) return jar.getInputStream(e);
		}
		return null;
	}

	/** Reads one API class's annotations: on the class, and on each method and field (null for those that are fine). */
	private static final class Reader extends ClassVisitor {
		String superName, why;
		String[] interfaces;
		final Map<String, String> members = new HashMap<>();

		Reader() {
			super(Opcodes.ASM9);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			this.superName = superName;
			this.interfaces = interfaces;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			return mark(descriptor, w -> why = w);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			String key = name + descriptor;
			members.put(key, null);
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
					return mark(annotation, w -> members.put(key, w));
				}
			};
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			String key = name + ":" + descriptor;
			members.put(key, null);
			return new FieldVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
					return mark(annotation, w -> members.put(key, w));
				}
			};
		}

		/** Records why something marked {@code descriptor} is off limits, if that annotation makes it so. */
		private AnnotationVisitor mark(String descriptor, Consumer<String> why) {
			if (INTERNAL.equals(descriptor)) {
				why.accept("is core's own (@ApiStatus.Internal)");
				return null;
			}
			if (!DEPRECATED.equals(descriptor)) return null;
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visit(String name, Object value) {
					if ("forRemoval".equals(name) && Boolean.TRUE.equals(value)) why.accept("is deprecated for removal");
				}
			};
		}
	}
}
