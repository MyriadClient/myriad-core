package dev.myriadclient.gradle;

import dev.myriadclient.gradle.signing.Signing;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Makes a new signing key for the addon's releases. The public half is printed, to add to {@code fabric.mod.json} under
 * {@code custom.myriad.keys}; the private half is written to a file readable only by you, to paste into a CI secret
 * named {@code MYRIAD_SIGNING_KEY}, keep a copy of somewhere safe and then delete. It's never printed, so it can't end up
 * in a build log.
 */
@DisableCachingByDefault(because = "makes a new key every time")
public abstract class GenerateSigningKey extends DefaultTask {
	@Internal
	public abstract DirectoryProperty getOutputDir();

	@TaskAction
	public void generate() {
		try {
			Signing.Key key = Signing.generate();
			Path file = getOutputDir().get().getAsFile().toPath().resolve("myriad-signing-key-" + key.id() + ".txt");
			Signing.writeSecret(file, key.privateText() + "\n");
			getLogger().lifecycle("""
				New signing key {}.
				1. Add its public key to fabric.mod.json:  "custom": {"myriad": {"keys": ["{}"]}}
				2. Make a "release" environment with yourself as a required reviewer (Settings > Environments) and put
				   the contents of {} in its secret MYRIAD_SIGNING_KEY, or with the GitHub CLI:
				   gh secret set MYRIAD_SIGNING_KEY --env release < {}
				3. Keep a copy in your password manager (GitHub never shows a secret again, and without the key your
				   players would have to reinstall by hand), then delete that file: anyone holding it can sign as you.
				Players can check your key against its fingerprint, {}.""", key.id(), key.publicText(), file, file, key.id());
		} catch (IOException | GeneralSecurityException e) {
			throw new GradleException("Couldn't make a key: " + e.getMessage(), e);
		}
	}
}
