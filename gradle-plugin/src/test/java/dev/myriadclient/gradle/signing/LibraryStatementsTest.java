package dev.myriadclient.gradle.signing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Library's statements as this plugin writes them. The signatures pinned here are checked by core's
 * {@code LibraryIndexTest.pluginSignaturesVerify} against core's own spelling, so the two can't drift apart.
 */
class LibraryStatementsTest {
	static final String B = "b".repeat(64), A = "a".repeat(64), C = "c".repeat(40), D = "d".repeat(64);

	static Signing.Key key() throws Exception {
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte) 9);
		return Signing.fromSeed(seed);
	}

	static String text(String kind, String fields) {
		return new String(LibraryStatements.statement(kind, LibraryStatements.fields(List.of(fields.split(" ")))), StandardCharsets.US_ASCII);
	}

	@Test
	void spellsStatementsAsCoreDoes() throws Exception {
		String k = key().publicText();
		assertEquals("ed25519:_RckOFqgx1tk-3jNYC-h2ZH96_drE8WO1wLqyDXp9hg", k);
		assertEquals("myriad-library-listing:1\nid=my-addon\nrevision=2\nrepo-id=10\nowner-id=20\nsource=closed\ndownload-repo-id=30\nbuild=" + B
				+ "\nprovides=a-lib,b-lib\nkey=" + k + "\n",
			text("listing", "id=my-addon revision=2 repo-id=10 owner-id=20 source=closed download-repo-id=30 build=" + B + " provides=b-lib,a-lib key=" + k + " key=" + k));
		assertEquals("myriad-library-listing:1\nid=x-addon\nrevision=1\nrepo-id=1\nowner-id=2\nsource=open\nbuild=" + B + "\nprovides=\nkey=" + k + "\n",
			text("listing", "id=x-addon revision=1 repo-id=1 owner-id=2 source=open build=" + B + " key=" + k));
		assertEquals("myriad-library-version:1\nid=my-addon\nversion=1.2.0\nsize=5\nsha256=" + A + "\ncommit=" + C + "\n",
			text("version", "id=my-addon version=1.2.0 size=5 sha256=" + A + " commit=" + C));
		assertEquals("myriad-library-key:1\nkey=" + k + "\nrole=index\nnot-after=1900000000\n", text("key", "key=" + k + " role=index not-after=1900000000"));
		assertEquals("myriad-library-key:1\nkey=" + k + "\nrole=listing\n", text("key", "key=" + k + " role=listing"));
		assertEquals("myriad-library-yank:1\nid=my-addon\nversion=1.2.0\nsha256=" + A + "\n", text("yank", "id=my-addon version=1.2.0 sha256=" + A));
		assertEquals("myriad-library-snapshot:1\nissued=10\nexpires=20\nbody-sha256=" + D + "\n", text("snapshot", "issued=10 expires=20 body-sha256=" + D));
		assertEquals("myriad-library-revocations:1\nseq=3\nkey=" + k + "\n", text("revocations", "seq=3 key=" + k));
	}

	@Test
	void pinnedSignatures() throws Exception {
		Signing.Key k = key();
		byte[] listing = LibraryStatements.statement("listing", LibraryStatements.fields(List.of(("id=my-addon revision=2 repo-id=10 owner-id=20 source=closed "
			+ "download-repo-id=30 build=" + B + " provides=b-lib,a-lib key=" + k.publicText()).split(" "))));
		assertEquals("{\"key\": \"dbc298251c51321b\", \"sig\": \"UKOyo-L7WrULQzwNWR7gh_r1cqT2nVb-CphCUScreYrbzvdcoE7Xd_WsRUqyOTs3fDo3ZhsIWIyvNJO3-_j6CQ\"}",
			LibraryStatements.signatureJson(k, listing));
		byte[] version = LibraryStatements.statement("version", LibraryStatements.fields(List.of(("id=my-addon version=1.2.0 size=5 sha256=" + A + " commit=" + C).split(" "))));
		assertEquals("{\"key\": \"dbc298251c51321b\", \"sig\": \"EqKGIz1yA_h80CrETT4If0YmNi9drQIVGi-5HIF3du8jA8lYr7Yg4KeT1iqs0MObHUWue1h_NdRlDuuAaoejDg\"}",
			LibraryStatements.signatureJson(k, version));
	}

	@Test
	void refusesAnythingOdd() {
		String k = "ed25519:_RckOFqgx1tk-3jNYC-h2ZH96_drE8WO1wLqyDXp9hg";
		assertThrows(IllegalArgumentException.class, () -> text("version", "id=my-addon version=1.2.0 size=5 sha256=" + A)); // no commit
		assertThrows(IllegalArgumentException.class, () -> text("version", "id=my-addon version=1.2.0 size=5 sha256=" + A + " commit=" + C + " extra=1"));
		assertThrows(IllegalArgumentException.class, () -> text("version", "id=My-addon version=1.2.0 size=5 sha256=" + A + " commit=" + C));
		assertThrows(IllegalArgumentException.class, () -> text("version", "id=my-addon version=1.2.0 size=05 sha256=" + A + " commit=" + C));
		assertThrows(IllegalArgumentException.class, () -> text("version", "id=my-addon version=1.2.0 size=99999999999 sha256=" + A + " commit=" + C));
		assertThrows(IllegalArgumentException.class, () -> text("version", "id=my-addon version=1.2.0 version=1.3.0 size=5 sha256=" + A + " commit=" + C));
		assertThrows(IllegalArgumentException.class, () -> text("listing", "id=x-addon revision=1 repo-id=1 owner-id=2 source=open download-repo-id=3 build=" + B + " key=" + k));
		assertThrows(IllegalArgumentException.class, () -> text("listing", "id=x-addon revision=1 repo-id=1 owner-id=2 source=closed build=" + B + " key=" + k));
		assertThrows(IllegalArgumentException.class, () -> text("listing", "id=x-addon revision=1 repo-id=1 owner-id=2 source=open build=" + B));
		assertThrows(IllegalArgumentException.class, () -> text("listing", "id=x-addon revision=1 repo-id=1 owner-id=2 source=open build=" + B + " provides=x-addon key=" + k));
		assertThrows(IllegalArgumentException.class, () -> text("listing", "id=x-addon revision=1 repo-id=1 owner-id=2 source=open build=" + B + " key=" + k + "="));
		assertThrows(IllegalArgumentException.class, () -> text("key", "key=" + k + " role=listing not-after=5"));
		assertThrows(IllegalArgumentException.class, () -> text("key", "key=" + k + " role=index"));
		assertThrows(IllegalArgumentException.class, () -> text("key", "key=" + k + " role=root"));
		assertThrows(IllegalArgumentException.class, () -> text("snapshot", "issued=20 expires=10 body-sha256=" + D));
		assertThrows(IllegalArgumentException.class, () -> text("nonsense", "a=b"));
	}

	@Test
	void buildHashFollowsBuildLogicOnly(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		Files.createDirectories(dir.resolve("src/main/java/x"));
		Files.createDirectories(dir.resolve("gradle/wrapper"));
		Files.writeString(dir.resolve("build.gradle"), "plugins {}\n");
		Files.writeString(dir.resolve("gradle.properties"), "mod_version=1.0.0\nfoo=1\n");
		Files.writeString(dir.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=x\n");
		Files.writeString(dir.resolve("src/main/java/x/A.java"), "class A {}");
		Files.writeString(dir.resolve("README.md"), "hi");
		String h = BuildHash.of(dir);

		Files.writeString(dir.resolve("src/main/java/x/A.java"), "class A { int changed; }");
		Files.writeString(dir.resolve("README.md"), "changed");
		Files.writeString(dir.resolve("gradle.properties"), "mod_version=1.1.0\nfoo=1\n");
		assertEquals(h, BuildHash.of(dir), "code, docs and the version don't count");

		Files.writeString(dir.resolve("gradle.properties"), "mod_version=1.1.0\nfoo=2\n");
		assertNotEquals(h, BuildHash.of(dir), "other properties do");
		Files.writeString(dir.resolve("gradle.properties"), "mod_version=1.1.0\nfoo=1\n");
		assertEquals(h, BuildHash.of(dir));

		Files.writeString(dir.resolve("src/main/java/x/sneaky.gradle"), "apply plugin: 'evil'");
		assertNotEquals(h, BuildHash.of(dir), "a Gradle script counts wherever it is");
		Files.delete(dir.resolve("src/main/java/x/sneaky.gradle"));

		Files.writeString(dir.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=evil\n");
		assertNotEquals(h, BuildHash.of(dir), "the wrapper counts");
	}

	@Test
	void buildHashRefusesSymbolicLinks(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("build.gradle"), "plugins {}\n");
		Files.createDirectories(dir.resolve("src/main/resources"));
		Files.createSymbolicLink(dir.resolve("src/main/resources/x.txt"), dir.resolve("build.gradle"));
		assertThrows(java.io.IOException.class, () -> BuildHash.of(dir));
	}
}
