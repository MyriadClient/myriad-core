package dev.myriadclient.impl.update;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Installing an addon from the Myriad Library: the same checks as installing from a link ({@link LinkInstall}), but
 * trust comes from the Library rather than from the first key seen. The release's Library signature was checked with
 * the index; here the jar must be exactly the one signed (its size and SHA-256), be the listed addon (its id and what it
 * provides), and carry its developer's signature by a key both the listing and the jar itself name, so its later updates
 * stay with that developer.
 */
final class LibraryInstall {
	private LibraryInstall() {
	}

	static CompletableFuture<LinkInstall.Offer> look(LibraryIndex.Index index, LibraryIndex.Entry entry, LibraryIndex.Offered offered, Version minecraft,
		Version core, Set<String> myriadIds, Predicate<String> installed, Path mods) {
		if (index.stale()) return CompletableFuture.failedFuture(new IOException("the Library's information is out of date; try again in a moment"));
		LibraryStatements.Version v = offered.version();
		URI url = URI.create(offered.url());
		return Fetch.bytes(url, v.size(), null, "application/octet-stream").thenApply(jar -> {
			String why = JarCheck.verifyDownload(jar, v.size(), v.sha256());
			if (why != null) throw LinkInstall.fail(why);
			JsonObject meta = LinkInstall.modJson(jar);
			String id = LinkInstall.string(meta, "id"), name = LinkInstall.string(meta, "name");
			if (!id.equals(entry.listing().id()) || !id.equals(v.id())) throw LinkInstall.fail("the jar isn't the listed addon");
			if (!LinkInstall.isMyriadAddon(meta)) throw LinkInstall.fail(id + " isn't a Myriad addon");
			if (installed.test(id)) throw LinkInstall.fail(id + " is already installed");
			Set<String> provides = new LinkedHashSet<>();
			JarCheck.provided(meta, provides);
			provides.remove(id); // provided() counts the mod's own id too; a listing names only the others
			if (!new TreeSet<>(provides).equals(new TreeSet<>(entry.listing().provides()))) throw LinkInstall.fail("the jar provides something its listing doesn't say");
			for (String p : provides) if (installed.test(p)) throw LinkInstall.fail("it would stand in for " + p + ", which you already have");
			JarCheck.Checked checked = JarCheck.check(jar, id, v.version(), minecraft, core, myriadIds);
			if (checked.problem() != null) throw LinkInstall.fail(checked.problem());
			List<DevKey> keys = new ArrayList<>(entry.listing().keys());
			keys.retainAll(checked.keys());
			DevKey signer = SignatureCheck.signer(ReleaseStatement.of(id, v.version(), jar, JarCheck.sha256(jar)).bytes(), offered.developer(), keys);
			if (signer == null) throw LinkInstall.fail("it isn't signed by a developer key both its listing and its jar name");
			String path = url.getPath();
			String fileName = path.substring(path.lastIndexOf('/') + 1);
			if (Names.fileIn(mods, fileName) == null) throw LinkInstall.fail("its file name is odd");
			if (Files.exists(mods.resolve(fileName))) throw LinkInstall.fail("a file named " + fileName + " is already in mods");
			return new LinkInstall.Offer(id, name.equals("unknown") ? entry.name() : name, v.version(), "the Myriad Library", signer.id(), fileName, jar, null,
				entry.verified() ? LinkInstall.Library.VERIFIED : LinkInstall.Library.REVIEWED);
		});
	}

	/** The newest of {@code entry}'s releases that works on {@code minecraft} (by its listed range), or null. */
	static LibraryIndex.Offered forMinecraft(LibraryIndex.Entry entry, Version minecraft) {
		for (LibraryIndex.Offered o : entry.versions()) {
			Releases.Release r = new Releases.Release(o.version().version(), null, null, o.minecraft());
			if (r.worksOn(minecraft)) return o;
		}
		return null;
	}

	/** {@code entry}'s releases as ordinary releases, for the update check: no {@code .sig}, the developer's signatures carried. */
	static List<Releases.Release> releases(LibraryIndex.Entry entry) {
		List<Releases.Release> out = new ArrayList<>();
		for (LibraryIndex.Offered o : entry.versions()) {
			String path = URI.create(o.url()).getPath();
			String file = path.substring(path.lastIndexOf('/') + 1);
			out.add(new Releases.Release(o.version().version(), null, new Releases.Asset(file, o.url(), o.version().size(), o.version().sha256(), o.developer(), null), o.minecraft()));
		}
		return out;
	}
}
