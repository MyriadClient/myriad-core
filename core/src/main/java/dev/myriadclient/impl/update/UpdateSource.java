package dev.myriadclient.impl.update;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an addon's releases come from: {@code "custom": {"myriad": {"updates": ...}}} in its {@code fabric.mod.json},
 * a GitHub repo ({@code owner/repo} or its address), an update feed's https address (see {@link FeedReleases}), or
 * {@code false} to opt out; otherwise its {@code contact.sources} if that's a GitHub repo.
 */
sealed interface UpdateSource {
	/** A GitHub repo, {@code owner/repo}. */
	record GitHub(String repo) implements UpdateSource {
		@Override
		public String describe() {
			return "github.com/" + repo;
		}
	}

	/** An update feed. */
	record Feed(URI uri) implements UpdateSource {
		@Override
		public String describe() {
			return uri.getHost();
		}
	}

	/** Where it is, for the user. */
	String describe();

	Pattern REPO = Pattern.compile("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}");
	Pattern GITHUB = Pattern.compile("https?://(?:www\\.)?github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?(?:[/?#].*)?");

	/** Where to check for {@code meta}, if anywhere. */
	static Optional<UpdateSource> of(ModMetadata meta) {
		Object updates = null;
		CustomValue myriad = meta.getCustomValue("myriad");
		if (myriad != null && myriad.getType() == CustomValue.CvType.OBJECT) {
			CustomValue v = myriad.getAsObject().get("updates");
			if (v != null && v.getType() == CustomValue.CvType.STRING) updates = v.getAsString();
			else if (v != null && v.getType() == CustomValue.CvType.BOOLEAN) updates = v.getAsBoolean();
		}
		return resolve(updates, meta.getContact().get("sources").orElse(null));
	}

	/** {@code updates} is the custom value (a repo, a feed, {@code false}, or {@code null} when absent). */
	static Optional<UpdateSource> resolve(@Nullable Object updates, @Nullable String sources) {
		if (Boolean.FALSE.equals(updates)) return Optional.empty();
		if (updates instanceof String s) {
			s = s.strip();
			if (REPO.matcher(s).matches()) return Optional.of(new GitHub(s));
			Optional<UpdateSource> repo = gitHub(s);
			return repo.isPresent() ? repo : feed(s);
		}
		return sources == null ? Optional.empty() : gitHub(sources.strip());
	}

	/** A GitHub repo or a feed from what the user typed or pasted ({@code owner/repo}, a GitHub address or a feed's). */
	static Optional<UpdateSource> parseLink(String text) {
		return resolve(text, null);
	}

	static Optional<UpdateSource> gitHub(String url) {
		Matcher m = GITHUB.matcher(url);
		return m.matches() ? Optional.of(new GitHub(m.group(1) + "/" + m.group(2))) : Optional.empty();
	}

	static Optional<UpdateSource> feed(String url) {
		try {
			URI uri = new URI(url);
			return Fetch.refused(uri) == null ? Optional.of(new Feed(uri)) : Optional.empty();
		} catch (java.net.URISyntaxException e) {
			return Optional.empty();
		}
	}
}
