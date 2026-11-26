package dev.myriadclient.gradle;

import dev.myriadclient.gradle.signing.Signing;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Signs the built jar for Myriad's updater: writes {@code <jar>.sig} next to it, to attach to the release with the jar.
 * The key comes from the environment variable {@code MYRIAD_SIGNING_KEY} (a CI secret, {@code ed25519-private:...}), or
 * from a passphrase-protected key file ({@code -PmyriadSigningKeyFile=...}, the passphrase in
 * {@code MYRIAD_KEY_PASSPHRASE}). The jar must list the key's public half under {@code custom.myriad.keys}.
 */
@UntrackedTask(because = "the key comes from outside the build, so it signs every time it's asked")
public abstract class SignAddon extends DefaultTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getJar();

	@Input
	public abstract Property<Boolean> getAllowUndeclaredKey();

	@Internal
	public abstract Property<String> getKeyFile();

	@TaskAction
	public void sign() {
		Path jar = getJar().get().getAsFile().toPath();
		try {
			Signing.Key key = key();
			Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
			Files.writeString(sig, Signing.sign(jar, key, getAllowUndeclaredKey().getOrElse(false)));
			getLogger().lifecycle("Signed {} with key {}: {}", jar.getFileName(), key.id(), sig.getFileName());
		} catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
			throw new GradleException("Couldn't sign " + jar.getFileName() + ": " + e.getMessage(), e);
		}
	}

	private Signing.Key key() throws IOException, GeneralSecurityException {
		String env = System.getenv("MYRIAD_SIGNING_KEY");
		if (env != null && !env.isBlank()) return Signing.parsePrivate(env);
		String file = getKeyFile().getOrNull();
		if (file != null) {
			String pass = System.getenv("MYRIAD_KEY_PASSPHRASE");
			if (pass == null) throw new IOException("set MYRIAD_KEY_PASSPHRASE for " + file);
			return Signing.decrypt(Files.readString(Path.of(file)), pass.toCharArray());
		}
		throw new IOException("no key: set MYRIAD_SIGNING_KEY (a CI secret) or -PmyriadSigningKeyFile");
	}
}
