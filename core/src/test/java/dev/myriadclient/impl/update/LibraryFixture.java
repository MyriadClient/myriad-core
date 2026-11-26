package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.impl.update.LibraryStatements.KeyCert;
import dev.myriadclient.impl.update.LibraryStatements.Listing;
import dev.myriadclient.impl.update.LibraryStatements.Revocations;
import dev.myriadclient.impl.update.LibraryStatements.Role;
import dev.myriadclient.impl.update.LibraryStatements.Snapshot;
import dev.myriadclient.impl.update.LibraryStatements.Source;
import dev.myriadclient.impl.update.LibraryStatements.Version;
import dev.myriadclient.impl.update.LibraryStatements.Yank;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Builds signed Library indexes for tests, with throwaway keys for every role. */
final class LibraryFixture {
	static final TestKeys ROOT = seeded(1), LISTING = seeded(2), VERSION = seeded(3), INDEX = seeded(4), DEV = seeded(5), DEV2 = seeded(6), OTHER = seeded(7);
	static final long NOW = 1_800_000_000L;
	static final String BUILD = "b".repeat(64);
	static final String COMMIT = "c".repeat(40);

	/** A key from a fixed seed byte, its public half worked out by the JDK. */
	static TestKeys seeded(int n) {
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte) n);
		try {
			KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
			g.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
				@Override
				public void nextBytes(byte[] bytes) {
					System.arraycopy(seed, 0, bytes, 0, bytes.length);
				}
			});
			KeyPair kp = g.generateKeyPair();
			byte[] der = kp.getPublic().getEncoded();
			return new TestKeys(kp.getPrivate(), new DevKey(Arrays.copyOfRange(der, der.length - 32, der.length)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	long issued = NOW - 60, expires = NOW + 3600, indexNotAfter = NOW + 365 * 86400L;
	TestKeys snapshotSigner = INDEX, certSigner = ROOT;
	final List<JsonObject> certs = new ArrayList<>();
	JsonObject revocations;
	final JsonArray addons = new JsonArray();
	boolean standardCerts = true;

	LibraryFixture cert(DevKey key, Role role, long notAfter, TestKeys signer) {
		KeyCert c = new KeyCert(key, role, notAfter);
		JsonObject o = new JsonObject();
		o.addProperty("key", key.text());
		o.addProperty("role", role.text());
		if (role == Role.INDEX) o.addProperty("notAfter", notAfter);
		o.add("signatures", sigs(signer, c.bytes()));
		certs.add(o);
		return this;
	}

	LibraryFixture revoke(long seq, TestKeys signer, DevKey... keys) {
		Revocations r = new Revocations(seq, List.of(keys));
		JsonObject o = new JsonObject();
		o.addProperty("seq", seq);
		JsonArray k = new JsonArray();
		for (DevKey key : r.keys()) k.add(key.text());
		o.add("keys", k);
		o.add("signatures", sigs(signer, r.bytes()));
		revocations = o;
		return this;
	}

	static Listing listing(String id, long revision, Source source, DevKey... keys) {
		return new Listing(id, revision, 100, 200, source, source == Source.CLOSED ? 300 : 0, BUILD, List.of(), List.of(keys));
	}

	/** Adds an addon: its listing signed by {@code listingSigner}, and {@code versions}. */
	JsonObject addon(Listing l, TestKeys listingSigner, JsonObject... versions) {
		JsonObject a = new JsonObject();
		JsonObject lo = new JsonObject();
		lo.addProperty("id", l.id());
		lo.addProperty("revision", l.revision());
		lo.addProperty("repoId", l.repoId());
		lo.addProperty("ownerId", l.ownerId());
		lo.addProperty("source", l.source().text());
		if (l.source() == Source.CLOSED) lo.addProperty("downloadRepoId", l.downloadRepoId());
		lo.addProperty("build", l.build());
		JsonArray p = new JsonArray();
		l.provides().forEach(p::add);
		lo.add("provides", p);
		JsonArray k = new JsonArray();
		l.keys().forEach(key -> k.add(key.text()));
		lo.add("keys", k);
		lo.add("signatures", sigs(listingSigner, l.bytes()));
		a.add("listing", lo);
		JsonObject info = new JsonObject();
		info.addProperty("name", "Test " + l.id());
		info.addProperty("description", "A test addon.");
		info.addProperty("author", "someone");
		info.addProperty("repo", "someone/" + l.id());
		a.add("info", info);
		JsonArray vs = new JsonArray();
		for (JsonObject v : versions) vs.add(v);
		a.add("versions", vs);
		a.add("yanks", new JsonArray());
		addons.add(a);
		return a;
	}

	static String sha(String version) {
		return LibraryIndex.sha256(("jar " + version).getBytes(StandardCharsets.UTF_8));
	}

	/** A release of {@code id}, signed by {@code librarySigner} and {@code dev}. */
	static JsonObject version(String id, String version, TestKeys librarySigner, TestKeys dev) {
		return version(id, version, sha(version), librarySigner, dev);
	}

	static JsonObject version(String id, String version, String sha256, TestKeys librarySigner, TestKeys dev) {
		Version v = new Version(id, version, 1000, sha256, COMMIT);
		JsonObject o = new JsonObject();
		o.addProperty("version", version);
		o.addProperty("size", 1000);
		o.addProperty("sha256", sha256);
		o.addProperty("commit", COMMIT);
		o.addProperty("url", "https://github.com/someone/" + id + "/releases/download/v" + version + "/" + id + "-" + version + ".jar");
		o.addProperty("minecraft", "~26.3");
		o.add("signatures", librarySigner == null ? new JsonArray() : sigs(librarySigner, v.bytes()));
		o.add("developer", dev == null ? new JsonArray() : sigs(dev, new ReleaseStatement(id, version, 1000, sha256).bytes()));
		return o;
	}

	static void yank(JsonObject addon, String id, String version, String sha256, TestKeys signer) {
		JsonObject y = new JsonObject();
		y.addProperty("version", version);
		y.addProperty("sha256", sha256);
		y.add("signatures", sigs(signer, new Yank(id, version, sha256).bytes()));
		addon.getAsJsonArray("yanks").add(y);
	}

	String body() {
		JsonObject b = new JsonObject();
		b.addProperty("issued", issued);
		b.addProperty("expires", expires);
		JsonArray c = new JsonArray();
		if (standardCerts) {
			List<JsonObject> saved = new ArrayList<>(certs);
			certs.clear();
			cert(LISTING.pub(), Role.LISTING, 0, certSigner);
			cert(VERSION.pub(), Role.VERSION, 0, certSigner);
			cert(INDEX.pub(), Role.INDEX, indexNotAfter, certSigner);
			certs.addAll(saved);
		}
		certs.forEach(c::add);
		b.add("certs", c);
		if (revocations != null) b.add("revocations", revocations);
		b.add("addons", addons);
		return b.toString();
	}

	/** The whole index, as the API serves it. */
	String text() {
		String body = body();
		Snapshot s = new Snapshot(issued, expires, LibraryIndex.sha256(body.getBytes(StandardCharsets.UTF_8)));
		JsonObject w = new JsonObject();
		w.addProperty("format", 1);
		w.addProperty("body", body);
		w.add("signatures", sigs(snapshotSigner, s.bytes()));
		return w.toString();
	}

	LibraryIndex.Verified verify(LibraryState seen) throws java.io.IOException {
		return LibraryIndex.verify(text(), seen, NOW, List.of(ROOT.pub()));
	}

	static JsonArray sigs(TestKeys signer, byte[] message) {
		JsonArray a = new JsonArray();
		SignatureCheck.Sig s = signer.sign(message);
		JsonObject o = new JsonObject();
		o.addProperty("key", s.key());
		o.addProperty("sig", s.sig());
		a.add(o);
		return a;
	}

	static String hex(byte[] b) {
		return HexFormat.of().formatHex(b);
	}
}
