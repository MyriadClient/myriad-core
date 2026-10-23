package dev.myriadclient.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fails the build when an addon's source references Myriad's internals ({@code dev.myriadclient.impl}), which change
 * without notice: the addon would break players' games on a later release rather than its build now. Essentials needs
 * no check of its own: it's only on the dev client's runtime, so code using it doesn't compile unless the addon asks
 * for it (to handle its hooks, say).
 */
@DisableCachingByDefault(because = "Reading the sources again is quicker than the cache")
public abstract class CheckApiOnly extends DefaultTask {
	private static final Pattern INTERNALS = Pattern.compile("\\bdev\\.myriadclient\\.impl\\b");

	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getSources();

	/** For the paths in the message. */
	@Internal
	public abstract DirectoryProperty getProjectDirectory();

	@TaskAction
	public void check() throws IOException {
		Path root = getProjectDirectory().get().getAsFile().toPath();
		List<String> bad = new ArrayList<>();
		for (File file : getSources().getFiles()) bad.addAll(violations(root.relativize(file.toPath()).toString(), Files.readAllLines(file.toPath())));
		if (!bad.isEmpty()) {
			throw new GradleException("Addons may only use Myriad's API (dev.myriadclient.api.*), but these lines reference its internals:\n  "
				+ String.join("\n  ", bad));
		}
	}

	/** The lines of {@code file} that reference the internals, as {@code file:line: text}. */
	static List<String> violations(String file, List<String> lines) {
		List<String> bad = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			if (INTERNALS.matcher(lines.get(i)).find()) bad.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
		}
		return bad;
	}
}
