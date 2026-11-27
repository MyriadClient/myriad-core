package dev.myriadclient.impl.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myriadclient.impl.update.LibraryStatements.Listing;
import dev.myriadclient.impl.update.LibraryStatements.Role;
import dev.myriadclient.impl.update.LibraryStatements.Source;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static dev.myriadclient.impl.update.LibraryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Library index check, against the attacks it's there to stop. */
class LibraryIndexTest {
	private static LibraryFixture one() {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("good", 1, Source.OPEN, DEV.pub()), LISTING, version("good", "1.0.0", VERSION, DEV), version("good", "1.1.0", VERSION, DEV));
		return f;
	}

	private static String refused(LibraryFixture f, LibraryState seen) {
		return assertThrows(IOException.class, () -> f.verify(seen)).getMessage();
	}

	@Test
	void acceptsAGoodIndex() throws IOException {
		LibraryIndex.Verified v = one().verify(new LibraryState());
		LibraryIndex.Entry e = v.index().entry("good");
		assertNotNull(e);
		assertTrue(e.verified());
		assertEquals(List.of("1.1.0", "1.0.0"), e.versions().stream().map(o -> o.version().version()).toList());
		assertFalse(v.index().staleAt(NOW));
		assertEquals(NOW - 60, v.state().issued);
		assertEquals(1L, v.state().revisions.get("good"));
	}

	@Test
	void snapshotMustBeSignedByACertifiedCurrentIndexKey() {
		LibraryFixture f = one();
		f.snapshotSigner = OTHER;
		assertTrue(refused(f, new LibraryState()).contains("isn't signed"));
		LibraryFixture v = one();
		v.snapshotSigner = VERSION; // certified, but not for snapshots
		assertTrue(refused(v, new LibraryState()).contains("isn't signed"));
		LibraryFixture c = one();
		c.certSigner = OTHER; // certificates not from a root key
		assertTrue(refused(c, new LibraryState()).contains("isn't signed"));
		LibraryFixture x = one();
		x.indexNotAfter = NOW - 1; // the index key's certificate has run out
		assertTrue(refused(x, new LibraryState()).contains("isn't signed"));
	}

	@Test
	void bodyCantBeChangedUnderTheSnapshot() {
		LibraryFixture f = one();
		String text = f.text();
		JsonObject w = JsonParser.parseString(text).getAsJsonObject();
		w.addProperty("body", w.get("body").getAsString().replace("1.1.0", "1.1.1"));
		assertThrows(IOException.class, () -> LibraryIndex.verify(w.toString(), new LibraryState(), NOW, List.of(ROOT.pub())));
	}

	@Test
	void neverGoesBackwards() throws IOException {
		LibraryState seen = one().verify(new LibraryState()).state();
		LibraryFixture older = one();
		older.issued = NOW - 120;
		assertTrue(refused(older, seen).contains("older"));
		LibraryFixture sameTimeOtherBody = one();
		sameTimeOtherBody.addon(listing("extra", 1, Source.OPEN, DEV.pub()), LISTING, version("extra", "1.0.0", VERSION, DEV));
		assertTrue(refused(sameTimeOtherBody, seen).contains("without a new date"));
		assertDoesNotThrow(() -> one().verify(seen)); // the same index again is fine
	}

	@Test
	void futureIndexIsRefusedAndNotRemembered() {
		LibraryFixture f = one();
		f.issued = NOW + LibraryIndex.FUTURE_SLACK + 10;
		f.expires = f.issued + 3600;
		LibraryState seen = new LibraryState();
		assertTrue(refused(f, seen).contains("future"));
		assertEquals(0, seen.issued);
	}

	@Test
	void expiredIndexIsStale() throws IOException {
		LibraryFixture f = one();
		f.issued = NOW - 7200;
		f.expires = NOW - 3600;
		LibraryIndex.Index i = f.verify(new LibraryState()).index();
		assertTrue(i.staleAt(NOW));
		assertFalse(i.staleAt(NOW - 3601), "judged at the time it's asked, not when it was read");
	}

	@Test
	void listingsNeedAListingKeyAndNeverGoBack() throws IOException {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("byversionkey", 1, Source.OPEN, DEV.pub()), VERSION, version("byversionkey", "1.0.0", VERSION, DEV));
		f.addon(listing("byindexkey", 1, Source.OPEN, DEV.pub()), INDEX, version("byindexkey", "1.0.0", VERSION, DEV));
		f.addon(listing("bystranger", 1, Source.OPEN, DEV.pub()), OTHER, version("bystranger", "1.0.0", VERSION, DEV));
		assertTrue(f.verify(new LibraryState()).index().entries().isEmpty());

		LibraryState seen = new LibraryState();
		seen.revisions.put("good", 5L);
		assertNull(one().verify(seen).index().entry("good"));
	}

	@Test
	void versionKeyOnlySignsOpenSourceVersions() throws IOException {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("closed", 1, Source.CLOSED, DEV.pub()), LISTING, version("closed", "1.0.0", VERSION, DEV), version("closed", "1.1.0", LISTING, DEV));
		LibraryIndex.Entry e = f.verify(new LibraryState()).index().entry("closed");
		assertNotNull(e);
		assertFalse(e.verified());
		assertEquals(List.of("1.1.0"), e.versions().stream().map(o -> o.version().version()).toList());
	}

	@Test
	void everyVersionNeedsItsDeveloper() throws IOException {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("dev", 1, Source.OPEN, DEV.pub()), LISTING,
			version("dev", "1.0.0", VERSION, null), // no developer signature
			version("dev", "1.1.0", VERSION, DEV2), // a key the listing doesn't name
			version("dev", "1.2.0", null, DEV), // no Library signature
			version("dev", "1.3.0", VERSION, DEV));
		assertEquals(List.of("1.3.0"), f.verify(new LibraryState()).index().entry("dev").versions().stream().map(o -> o.version().version()).toList());
	}

	@Test
	void twoJarsForOneVersionDropTheListing() throws IOException {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("twice", 1, Source.OPEN, DEV.pub()), LISTING, version("twice", "1.0.0", VERSION, DEV), version("twice", "1.0.0", sha("other"), VERSION, DEV));
		assertNull(f.verify(new LibraryState()).index().entry("twice"));
	}

	@Test
	void anIdListedTwiceIsListedByNeither() throws IOException {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("dup", 1, Source.OPEN, DEV.pub()), LISTING, version("dup", "1.0.0", VERSION, DEV));
		f.addon(listing("dup", 1, Source.OPEN, DEV2.pub()), LISTING, version("dup", "1.0.0", VERSION, DEV2));
		assertNull(f.verify(new LibraryState()).index().entry("dup"));
	}

	@Test
	void yanksStickEvenWhenALaterIndexLeavesThemOut() throws IOException {
		LibraryFixture f = one();
		JsonObject addon = f.addons.get(0).getAsJsonObject();
		yank(addon, "good", "1.1.0", sha("1.1.0"), DEV); // the developer may yank their own release
		LibraryIndex.Verified v = f.verify(new LibraryState());
		assertEquals(List.of("1.0.0"), v.index().entry("good").versions().stream().map(o -> o.version().version()).toList());

		LibraryFixture later = one();
		later.issued = NOW - 30;
		assertEquals(List.of("1.0.0"), later.verify(v.state()).index().entry("good").versions().stream().map(o -> o.version().version()).toList());

		LibraryFixture forged = one();
		yank(forged.addons.get(0).getAsJsonObject(), "good", "1.0.0", sha("1.0.0"), OTHER); // not a key that may yank
		assertEquals(2, forged.verify(new LibraryState()).index().entry("good").versions().size());

		// The online keys can't yank: a yank is forever, and they're the ones most likely to be stolen.
		for (TestKeys online : List.of(INDEX, VERSION)) {
			LibraryFixture byOnline = one();
			yank(byOnline.addons.get(0).getAsJsonObject(), "good", "1.0.0", sha("1.0.0"), online);
			assertEquals(2, byOnline.verify(new LibraryState()).index().entry("good").versions().size());
		}
		LibraryFixture byListing = one();
		yank(byListing.addons.get(0).getAsJsonObject(), "good", "1.0.0", sha("1.0.0"), LISTING);
		assertEquals(List.of("1.1.0"), byListing.verify(new LibraryState()).index().entry("good").versions().stream().map(o -> o.version().version()).toList());
	}

	@Test
	void reservedIdsOnlyFromMyriad() throws IOException {
		LibraryFixture f = new LibraryFixture();
		f.addon(listing("myriad", 1, Source.OPEN, DEV.pub()), LISTING, version("myriad", "9.0.0", VERSION, DEV));
		f.addon(listing("fabric-api", 1, Source.OPEN, DEV.pub()), LISTING, version("fabric-api", "9.0.0", VERSION, DEV));
		f.addon(new Listing("sly", 1, 100, 200, Source.OPEN, 0, BUILD, List.of("myriad-essentials"), List.of(DEV.pub())), LISTING, version("sly", "1.0.0", VERSION, DEV));
		f.addon(new Listing("myriad-essentials", 1, 101, LibraryIndex.MYRIAD_OWNER, Source.OPEN, 0, BUILD, List.of(), List.of(DEV.pub())), LISTING,
			version("myriad-essentials", "3.0.0", VERSION, DEV));
		LibraryIndex.Index i = f.verify(new LibraryState()).index();
		assertEquals(List.of("myriad-essentials"), i.entries().stream().map(e -> e.listing().id()).toList());
	}

	@Test
	void aRevisionFloorGoesWithItsRevokedSigner() throws IOException {
		LibraryState seen = new LibraryState();
		seen.revisions.put("good", Long.MAX_VALUE - 1); // signed by a key that was then stolen and revoked
		seen.revisionSigners.put("good", OTHER.pub().text());
		assertNull(one().verify(seen).index().entry("good"), "still the floor while its signer stands");
		seen.revoked.add(OTHER.pub().text());
		LibraryIndex.Verified v = one().verify(seen);
		assertNotNull(v.index().entry("good"));
		assertEquals(LISTING.pub().text(), v.state().revisionSigners.get("good"));
	}

	@Test
	void aSignatureIsCheckedOnlyAgainstTheKeyItNames() {
		byte[] m = "hello".getBytes();
		SignatureCheck.Sig good = DEV.sign(m);
		assertEquals(DEV.pub(), SignatureCheck.signerByLabel(m, List.of(good), List.of(DEV2.pub(), DEV.pub())));
		SignatureCheck.Sig mislabelled = new SignatureCheck.Sig(DEV2.pub().id(), good.sig());
		assertNull(SignatureCheck.signerByLabel(m, List.of(mislabelled), List.of(DEV2.pub(), DEV.pub())));
	}

	@Test
	void revokedKeysStayRevoked() throws IOException {
		LibraryFixture f = one();
		f.revoke(1, ROOT, VERSION.pub());
		LibraryIndex.Verified v = f.verify(new LibraryState());
		assertTrue(v.index().entry("good").versions().isEmpty());
		assertEquals(1, v.state().revocationSeq);

		LibraryFixture later = one(); // leaves the revocation out
		later.issued = NOW - 30;
		assertTrue(later.verify(v.state()).index().entry("good").versions().isEmpty());

		LibraryFixture forged = one();
		forged.revoke(2, OTHER, LISTING.pub());
		assertTrue(refused(forged, new LibraryState()).contains("revocations"));
	}

	@Test
	void revokingTheIndexKeyRefusesTheWholeIndex() {
		LibraryFixture f = one();
		f.revoke(1, ROOT, INDEX.pub());
		assertTrue(refused(f, new LibraryState()).contains("isn't signed"));
	}

	@Test
	void displayTextIsCleaned() throws IOException {
		LibraryFixture f = one();
		JsonObject info = f.addons.get(0).getAsJsonObject().getAsJsonObject("info");
		info.addProperty("name", "Ev§cil\u0000‮\nName\uf00c");
		info.addProperty("repo", "../../evil");
		LibraryIndex.Entry e = f.verify(new LibraryState()).index().entry("good");
		assertEquals("EvcilName", e.name());
		assertNull(e.repo());
	}

	@Test
	void browseInfoIsReadAndCleaned() throws IOException {
		LibraryFixture f = one();
		JsonObject info = f.addons.get(0).getAsJsonObject().getAsJsonObject("info");
		info.addProperty("moduleCount", 52);
		info.addProperty("details", "b".repeat(64));
		info.addProperty("icon", "a".repeat(64));
		LibraryIndex.Entry e = f.verify(new LibraryState()).index().entry("good");
		assertEquals(52, e.moduleCount());
		assertEquals("b".repeat(64), e.detailsSha());
		assertEquals("a".repeat(64), e.iconSha());
	}

	@Test
	void browseInfoThatIsWrongIsLeftOut() throws IOException {
		LibraryFixture f = one();
		JsonObject info = f.addons.get(0).getAsJsonObject().getAsJsonObject("info");
		info.addProperty("moduleCount", "lots");
		info.addProperty("details", "../not-a-hash");
		info.addProperty("icon", 5);
		LibraryIndex.Entry e = f.verify(new LibraryState()).index().entry("good");
		assertEquals(0, e.moduleCount());
		assertNull(e.detailsSha());
		assertNull(e.iconSha());
		info.addProperty("moduleCount", 1_000_000);
		assertEquals(LibraryIndex.MAX_MODULES, f.verify(new LibraryState()).index().entry("good").moduleCount());
	}

	@Test
	void detailsAreReadAndCleaned() throws IOException {
		String doc = "{\"id\":\"good\",\"version\":\"1.1.0\",\"modules\":[{\"name\":\"Kill Aura\\uf00c\",\"description\":\"Attacks targets.\",\"tag\":\"Combat\"},"
			+ "\"junk\",{\"description\":\"nameless\"}],\"changelog\":\"- Faster\\r\\n\\n\\n\\n- Fixed \\u00a7cred\\u0000 text\\n\"}";
		LibraryIndex.Details d = LibraryIndex.details(doc, "good");
		assertEquals(List.of(new LibraryIndex.Module("Kill Aura", "Attacks targets.", "Combat")), d.modules());
		assertEquals(new LibraryIndex.Changelog("1.1.0", "- Faster\n\n- Fixed cred text"), d.changelog());
		assertThrows(IOException.class, () -> LibraryIndex.details(doc, "other"), "details for another addon");
		assertThrows(IOException.class, () -> LibraryIndex.details("{\"id\":\"good\"}", "good"), "no version");
		assertThrows(IOException.class, () -> LibraryIndex.details("not json", "good"));
	}

	@Test
	void longChangelogsAreCut() {
		String many = "line\n".repeat(500);
		String cut = LibraryIndex.cleanLines(many, LibraryIndex.MAX_CHANGELOG, LibraryIndex.MAX_CHANGELOG_LINES);
		assertEquals(LibraryIndex.MAX_CHANGELOG_LINES, cut.lines().count());
		assertTrue(LibraryIndex.cleanLines("x".repeat(9000), 4000, 80).length() <= 4000);
	}

	@Test
	void downloadsOnlyFromGitHubReleases() {
		assertTrue(LibraryIndex.downloadable("https://github.com/a/b/releases/download/v1.0.0/b-1.0.0.jar"));
		assertFalse(LibraryIndex.downloadable("https://evil.example/a/b/releases/download/v1.0.0/b-1.0.0.jar"));
		assertFalse(LibraryIndex.downloadable("http://github.com/a/b/releases/download/v1.0.0/b-1.0.0.jar"));
		assertFalse(LibraryIndex.downloadable("https://github.com:444/a/b/releases/download/v1.0.0/b-1.0.0.jar"));
		assertFalse(LibraryIndex.downloadable("https://github.com/a/b/releases/download/v1.0.0/../b.jar"));
		assertFalse(LibraryIndex.downloadable("https://github.com/a/b/releases/download/v1.0.0/b-1.0.0.jar?x=1"));
		assertFalse(LibraryIndex.downloadable("https://user@github.com/a/b/releases/download/v1.0.0/b-1.0.0.jar"));
		assertFalse(LibraryIndex.downloadable("https://github.com/a/b/archive/v1.0.0.zip"));
	}

	@Test
	void malformedIndexesAreRefused() {
		List<DevKey> roots = List.of(ROOT.pub());
		assertThrows(IOException.class, () -> LibraryIndex.verify("not json", new LibraryState(), NOW, roots));
		assertThrows(IOException.class, () -> LibraryIndex.verify("{\"format\":2,\"body\":\"{}\"}", new LibraryState(), NOW, roots));
		assertThrows(IOException.class, () -> LibraryIndex.verify("{\"format\":1,\"body\":\"[]\"}", new LibraryState(), NOW, roots));
		assertThrows(IOException.class, () -> LibraryIndex.verify("x".repeat((int) LibraryIndex.MAX_INDEX + 1), new LibraryState(), NOW, roots));
	}

	@Test
	void statementsRefuseOddFields() {
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Version("good", "1.0\nid=evil", 1, sha("x"), COMMIT));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Version("Good", "1.0", 1, sha("x"), COMMIT));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Version("good", "1.0", 1, sha("x"), "abc"));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.KeyCert(DEV.pub(), Role.LISTING, 5));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.KeyCert(DEV.pub(), Role.INDEX, 0));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Listing("good", 1, 1, 1, Source.OPEN, 5, BUILD, List.of(), List.of(DEV.pub())));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Listing("good", 1, 1, 1, Source.CLOSED, 0, BUILD, List.of(), List.of(DEV.pub())));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Listing("good", 1, 1, 1, Source.OPEN, 0, BUILD, List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new LibraryStatements.Listing("good", 1, 1, 1, Source.OPEN, 0, BUILD, List.of("good"), List.of(DEV.pub())));
	}

	/**
	 * An index made by the API's own code (myriad-api's rules.ts, with the same throwaway keys as here) is read and
	 * trusted as the API meant it: the listing, its versions newest first, the listing-key yank honoured.
	 */
	@Test
	void readsAnIndexTheApiMade() throws Exception {
		String text;
		try (var in = LibraryIndexTest.class.getResourceAsStream("/library/api-index.json")) {
			text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		LibraryIndex.Verified v = LibraryIndex.verify(text, new LibraryState(), NOW + 60, List.of(ROOT.pub()));
		LibraryIndex.Entry e = v.index().entry("good-addon");
		assertNotNull(e);
		assertEquals("Good Addon", e.name());
		assertEquals("dev/good-addon", e.repo());
		assertEquals(List.of("good-lib"), e.listing().provides());
		assertEquals(List.of("1.1.0", "1.0.0"), e.versions().stream().map(o -> o.version().version()).toList());
		assertTrue(v.state().yanks.contains(LibraryState.yankKey("good-addon", "1.2.0", LibraryIndex.sha256("1.2.0".getBytes()))));
		assertFalse(v.index().staleAt(NOW + 60));
	}

	/** Signatures the Gradle plugin made (pinned in its LibraryStatementsTest) verify against core's own spelling. */
	@Test
	void pluginSignaturesVerify() {
		DevKey k = DevKey.parse("ed25519:_RckOFqgx1tk-3jNYC-h2ZH96_drE8WO1wLqyDXp9hg");
		assertNotNull(k);
		assertEquals("dbc298251c51321b", k.id());
		byte[] listing = new LibraryStatements.Listing("my-addon", 2, 10, 20, Source.CLOSED, 30, BUILD, List.of("b-lib", "a-lib"), List.of(k)).bytes();
		byte[] version = new LibraryStatements.Version("my-addon", "1.2.0", 5, "a".repeat(64), COMMIT).bytes();
		byte[] snapshot = new LibraryStatements.Snapshot(10, 20, "d".repeat(64)).bytes();
		byte[] cert = new LibraryStatements.KeyCert(k, Role.INDEX, 1_900_000_000L).bytes();
		assertNotNull(SignatureCheck.signer(listing, List.of(new SignatureCheck.Sig("x", "UKOyo-L7WrULQzwNWR7gh_r1cqT2nVb-CphCUScreYrbzvdcoE7Xd_WsRUqyOTs3fDo3ZhsIWIyvNJO3-_j6CQ")), List.of(k)));
		assertNotNull(SignatureCheck.signer(version, List.of(new SignatureCheck.Sig("x", "EqKGIz1yA_h80CrETT4If0YmNi9drQIVGi-5HIF3du8jA8lYr7Yg4KeT1iqs0MObHUWue1h_NdRlDuuAaoejDg")), List.of(k)));
		assertNotNull(SignatureCheck.signer(snapshot, List.of(new SignatureCheck.Sig("x", "Qag6eUIp_3kyxcg5ISbvMT9704QLmz6DXcAdMhLh1L7PcwlwqeVu38_iV1g6ZJzONo9SVgrhJt25h72aIwRTDw")), List.of(k)));
		assertNotNull(SignatureCheck.signer(cert, List.of(new SignatureCheck.Sig("x", "CAp8v7w1dttztBfkGKF2QCRod5KwyfpVgdPUt3TP3lt_iyBMuHy1gKiu0DLPDVx41DmE-Sv6ru-gtqJkwMP2Dg")), List.of(k)));
		// And a signature for one kind never passes as another.
		assertNull(SignatureCheck.signer(version, List.of(new SignatureCheck.Sig("x", "UKOyo-L7WrULQzwNWR7gh_r1cqT2nVb-CphCUScreYrbzvdcoE7Xd_WsRUqyOTs3fDo3ZhsIWIyvNJO3-_j6CQ")), List.of(k)));
	}

	/**
	 * The exact bytes of each statement, so the Gradle plugin's builder of the same statements (and anything else that
	 * signs them) can be held to one spelling.
	 */
	@Test
	void goldenStatements() {
		DevKey k = new DevKey(new byte[32]);
		assertEquals("myriad-library-key:1\nkey=" + k.text() + "\nrole=index\nnot-after=1900000000\n",
			new String(new LibraryStatements.KeyCert(k, Role.INDEX, 1_900_000_000L).bytes()));
		assertEquals("myriad-library-listing:1\nid=my-addon\nrevision=2\nrepo-id=10\nowner-id=20\nsource=closed\ndownload-repo-id=30\nbuild=" + BUILD
				+ "\nprovides=a-lib,b-lib\nkey=" + k.text() + "\n",
			new String(new LibraryStatements.Listing("my-addon", 2, 10, 20, Source.CLOSED, 30, BUILD, List.of("b-lib", "a-lib", "b-lib"), List.of(k, k)).bytes()));
		assertEquals("myriad-library-version:1\nid=my-addon\nversion=1.2.0\nsize=5\nsha256=" + sha("v") + "\ncommit=" + COMMIT + "\n",
			new String(new LibraryStatements.Version("my-addon", "1.2.0", 5, sha("v"), COMMIT).bytes()));
		assertEquals("myriad-library-yank:1\nid=my-addon\nversion=1.2.0\nsha256=" + sha("v") + "\n",
			new String(new LibraryStatements.Yank("my-addon", "1.2.0", sha("v")).bytes()));
		assertEquals("myriad-library-snapshot:1\nissued=10\nexpires=20\nbody-sha256=" + sha("v") + "\n",
			new String(new LibraryStatements.Snapshot(10, 20, sha("v")).bytes()));
		assertEquals("myriad-library-revocations:1\nseq=3\nkey=" + k.text() + "\n",
			new String(new LibraryStatements.Revocations(3, List.of(k)).bytes()));
	}
}
