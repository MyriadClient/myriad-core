package dev.myriadclient.gradle;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JvmVendorSpec;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * {@code dev.myriadclient.addon}: what every Myriad addon's build has in common, so an addon's build file says only
 * what's its own. It applies Loom and sets up:
 * <ul>
 *   <li>Myriad's maven, for {@code dev.myriadclient} only;</li>
 *   <li>Minecraft, Fabric and Myriad at the versions this plugin's Myriad release was built with, each overridable by
 *   a Gradle property ({@code minecraft_version}, {@code loader_version}, {@code fabric_api_version},
 *   {@code myriad_version}); {@code -PcoreDir=../myriad} builds against core's jar in a checkout instead;</li>
 *   <li>Essentials on the dev client when {@code essentials_version} is set ({@code -PessentialsDir} for a checkout's,
 *   {@code -PnoEssentials} to leave it out); never on the compile classpath, as players can remove it;</li>
 *   <li>the dev client's switches: {@code -PopenDesktop}, {@code -Photswap} (and {@code -PdebugPort}),
 *   {@code -PrunDir}, {@code -PquickPlay}, {@code -PquickPlayServer}, {@code -Pusername}, {@code -PscreenshotEvery},
 *   {@code -PauditMixins}, with dev checks and the dev console always on;</li>
 *   <li>{@code checkApiOnly} before every compile;</li>
 *   <li>Java 25, a sources jar, the LICENSE in the jar, {@code ${version}}, {@code ${myriad_version}} and
 *   {@code ${minecraft_version}} filled in {@code fabric.mod.json}, JUnit 5 for tests;</li>
 *   <li>a maven publication named after {@code archivesName}, and {@code publishAddon}.</li>
 * </ul>
 */
public final class AddonPlugin implements Plugin<Project> {
	static final String MAVEN = "https://maven.myriadclient.dev/releases";
	private static final String JAVA = "25";

	@Override
	public void apply(Project project) {
		project.getPluginManager().apply("net.fabricmc.fabric-loom");
		project.getPluginManager().apply("maven-publish");
		Map<String, String> versions = versions(project);
		repositories(project.getRepositories());
		dependencies(project, versions);
		java(project);
		devClient(project);
		TaskProvider<CheckApiOnly> checkApiOnly = project.getTasks().register("checkApiOnly", CheckApiOnly.class, t -> {
			t.setGroup("verification");
			t.setDescription("Fails if the addon references Myriad's internals (dev.myriadclient.impl).");
			t.getSources().from(project.fileTree("src/main/java", f -> f.include("**/*.java")));
			t.getProjectDirectory().set(project.getLayout().getProjectDirectory());
		});
		project.getTasks().named("compileJava", t -> t.dependsOn(checkApiOnly));
		publishing(project);
	}

	/**
	 * The versions core was built with, with the project's own properties winning. Each is also set as an extra property
	 * when the project has none, so a build script can still say {@code project.minecraft_version}.
	 */
	private static Map<String, String> versions(Project project) {
		Properties defaults = new Properties();
		try (InputStream in = AddonPlugin.class.getResourceAsStream("versions.properties")) {
			if (in == null) throw new GradleException("The Myriad addon plugin is missing its versions.properties");
			defaults.load(in);
		} catch (IOException e) {
			throw new GradleException("Couldn't read the Myriad addon plugin's versions", e);
		}
		ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
		Map<String, String> versions = new HashMap<>();
		for (String name : defaults.stringPropertyNames()) {
			Object own = project.findProperty(name);
			String value = own != null ? own.toString() : defaults.getProperty(name);
			versions.put(name, value);
			if (own == null) extra.set(name, value);
		}
		return versions;
	}

	private static void repositories(RepositoryHandler repositories) {
		repositories.exclusiveContent(exclusive -> exclusive
			.forRepositories(repositories.maven(maven -> {
				maven.setName("Myriad");
				maven.setUrl(MAVEN);
			}))
			.filter(filter -> filter.includeGroup("dev.myriadclient")));
	}

	private static void dependencies(Project project, Map<String, String> versions) {
		DependencyHandler dependencies = project.getDependencies();
		dependencies.add("minecraft", "com.mojang:minecraft:" + versions.get("minecraft_version"));
		dependencies.add("implementation", "net.fabricmc:fabric-loader:" + versions.get("loader_version"));
		dependencies.add("implementation", "net.fabricmc.fabric-api:fabric-api:" + versions.get("fabric_api_version"));

		// While changing core and an addon together: core's jar from a checkout next to this one (`./gradlew :core:jar`
		// there), with no publishing step.
		Object coreDir = project.findProperty("coreDir");
		if (coreDir != null) {
			dependencies.add("implementation", project.fileTree(coreDir + "/core/build/libs", t -> {
				t.include("myriad-*.jar");
				t.exclude("*-sources.jar", "*-javadoc.jar");
			}));
		} else {
			dependencies.add("implementation", "dev.myriadclient:myriad:" + versions.get("myriad_version"));
		}

		Object essentialsDir = project.findProperty("essentialsDir");
		Object essentials = project.findProperty("essentials_version");
		if (essentialsDir != null) {
			// The newest jar built in that checkout: an older one can still be in build/libs after a version bump.
			Callable<List<File>> newest = () -> project.fileTree(essentialsDir + "/build/libs", t -> {
				t.include("myriad-essentials-*.jar");
				t.exclude("*-sources.jar");
			}).getFiles().stream().max(Comparator.comparingLong(File::lastModified)).stream().toList();
			dependencies.add("localRuntime", project.files(newest));
		} else if (essentials != null && !project.hasProperty("noEssentials")) {
			ModuleDependency dependency = (ModuleDependency) dependencies.add("localRuntime", "dev.myriadclient:myriad-essentials:" + essentials);
			// The core Essentials was released on would load beside this addon's under the same mod id, and Fabric runs
			// whichever it picks.
			dependency.exclude(Map.of("group", "dev.myriadclient", "module", "myriad"));
		}
	}

	private static void java(Project project) {
		project.getTasks().withType(JavaCompile.class).configureEach(t -> {
			t.getOptions().getRelease().set(Integer.parseInt(JAVA));
			t.getOptions().setEncoding("UTF-8");
		});
		JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
		java.withSourcesJar();
		java.setSourceCompatibility(JavaVersion.toVersion(JAVA));
		java.setTargetCompatibility(JavaVersion.toVersion(JAVA));

		BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
		Provider<String> archivesName = base.getArchivesName();
		project.getTasks().named("jar", Jar.class, jar -> jar.from("LICENSE", spec -> spec.rename(name -> name + "_" + archivesName.get())));

		// Read when the file is copied: the build file sets the version after the plugin is applied.
		Provider<String> version = project.provider(() -> project.getVersion().toString());
		Provider<String> myriad = project.provider(() -> String.valueOf(project.findProperty("myriad_version")));
		Provider<String> minecraft = project.provider(() -> String.valueOf(project.findProperty("minecraft_version")));
		project.getTasks().named("processResources", ProcessResources.class, t -> {
			t.getInputs().property("version", version);
			t.getInputs().property("myriad_version", myriad);
			t.getInputs().property("minecraft_version", minecraft);
			t.filesMatching("fabric.mod.json", file -> file.expand(Map.of("version", version.get(), "myriad_version", myriad.get(), "minecraft_version", minecraft.get())));
		});

		project.getTasks().withType(Test.class).configureEach(Test::useJUnitPlatform);
	}

	private static void devClient(Project project) {
		LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
		loom.getRuns().matching(run -> run.getName().equals("client")).configureEach(run -> {
			// -PrunDir=run2: another game directory, for a second dev client beside the first.
			Object runDir = project.findProperty("runDir");
			run.getRunDirectory().set(project.getLayout().getProjectDirectory().dir(runDir != null ? runDir.toString() : "run"));
			MapProperty<String, String> properties = run.getSystemProperties();
			// Dev checks: a core service called off the render thread is logged, and .bench measures core's hot paths.
			properties.put("myriad.dev", "true");
			// Opens the Myriad menu over the title screen on launch (handy while working on panels and HUD elements).
			properties.put("myriad.openDesktop", String.valueOf(project.hasProperty("openDesktop")));
			// Applies every mixin at startup, so a broken one fails straight away instead of when its class loads.
			properties.put("myriad.auditMixins", String.valueOf(project.hasProperty("auditMixins")));
			// Lines appended to <runDir>/myriad-dev-inbox are run in game (commands, held keys, the menu, screenshots), so
			// tests can be scripted from outside; Essentials' tools/grim-test/client writes there.
			properties.put("myriad.devConsole", "myriad-dev-inbox");
			if (project.hasProperty("screenshotEvery")) properties.put("myriad.screenshotEvery", project.property("screenshotEvery").toString());
			ListProperty<String> args = run.getProgramArguments();
			if (project.hasProperty("quickPlay")) args.addAll("--quickPlaySingleplayer", project.property("quickPlay").toString());
			// -PquickPlayServer=localhost:25565 -Pusername=GrimTester joins a server as that player (a Grim test server,
			// see Testing in Myriad's docs).
			if (project.hasProperty("quickPlayServer")) args.addAll("--quickPlayMultiplayer", project.property("quickPlayServer").toString());
			if (project.hasProperty("username")) args.addAll("--username", project.property("username").toString());
		});

		// -Photswap: the dev client on the JetBrains Runtime with a debug port, so Essentials' tools/grim-test/reload can
		// swap freshly compiled classes in without a restart (JBR's enhanced redefinition takes added and removed methods
		// and fields too). -PdebugPort=5006 for a second client.
		if (project.hasProperty("hotswap")) {
			JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
			Object port = project.findProperty("debugPort");
			project.getTasks().withType(JavaExec.class).matching(t -> t.getName().equals("runClient")).configureEach(t -> {
				t.getJavaLauncher().set(toolchains.launcherFor(spec -> {
					spec.getLanguageVersion().set(JavaLanguageVersion.of(JAVA));
					spec.getVendor().set(JvmVendorSpec.JETBRAINS);
				}));
				t.jvmArgs("-XX:+AllowEnhancedClassRedefinition",
					"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:" + (port != null ? port : "5005"));
			});
		}
	}

	/**
	 * The addon as {@code <group>:<archivesName>:<version>}, with sources, for addons that build on it (Essentials on
	 * other addons' dev clients, say). {@code publishToMavenLocal} for a local build; {@code publishAddon} builds and
	 * tests it, then publishes to Myriad's maven with the {@code myriadUsername} and {@code myriadPassword} properties.
	 */
	private static void publishing(Project project) {
		PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
		MavenPublication publication = publishing.getPublications().create("mavenJava", MavenPublication.class,
			p -> p.from(project.getComponents().getByName("java")));
		BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
		project.afterEvaluate(p -> publication.setArtifactId(base.getArchivesName().get()));
		publishing.getRepositories().maven(maven -> {
			maven.setName("myriad");
			maven.setUrl(MAVEN);
			maven.credentials(PasswordCredentials.class);
		});
		project.getTasks().withType(PublishToMavenRepository.class).configureEach(t -> t.mustRunAfter("build"));
		project.getTasks().register("publishAddon", t -> {
			t.setGroup("publishing");
			t.setDescription("Builds and tests the addon, then publishes it to Myriad's maven (myriadUsername and myriadPassword).");
			t.dependsOn("build", "publishAllPublicationsToMyriadRepository");
		});
	}
}
