package dev.myriadclient.impl.update;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an addon's releases come from: {@code "custom": {"myriad": {"updates": "owner/repo"}}} in its
 * {@code fabric.mod.json} ({@code false} opts out), otherwise its {@code contact.sources} if that's a GitHub repo.
 */
final class UpdateSource {
	private static final Pattern REPO = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
	private static final Pattern GITHUB = Pattern.compile("https?://(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?(?:[/?#].*)?");

	private UpdateSource() {
	}

	/** The GitHub repo ({@code owner/repo}) to check for {@code meta}, if any. */
	static Optional<String> of(ModMetadata meta) {
		Object updates = null;
		CustomValue myriad = meta.getCustomValue("myriad");
		if (myriad != null && myriad.getType() == CustomValue.CvType.OBJECT) {
			CustomValue v = myriad.getAsObject().get("updates");
			if (v != null && v.getType() == CustomValue.CvType.STRING) updates = v.getAsString();
			else if (v != null && v.getType() == CustomValue.CvType.BOOLEAN) updates = v.getAsBoolean();
		}
		return resolve(updates, meta.getContact().get("sources").orElse(null));
	}

	/** {@code updates} is the custom value (a repo, {@code false}, or {@code null} when absent). */
	static Optional<String> resolve(@Nullable Object updates, @Nullable String sources) {
		if (Boolean.FALSE.equals(updates)) return Optional.empty();
		if (updates instanceof String s) {
			s = s.strip();
			if (REPO.matcher(s).matches()) return Optional.of(s);
			return gitHubRepo(s);
		}
		return sources == null ? Optional.empty() : gitHubRepo(sources.strip());
	}

	/** {@code owner/repo} from a GitHub URL ({@code https://github.com/owner/repo}, with or without {@code .git} or a path). */
	static Optional<String> gitHubRepo(String url) {
		Matcher m = GITHUB.matcher(url);
		return m.matches() ? Optional.of(m.group(1) + "/" + m.group(2)) : Optional.empty();
	}
}
