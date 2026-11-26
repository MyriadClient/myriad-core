package dev.myriadclient.gradle.signing;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs addon releases the way Myriad checks them: Ed25519 over a fixed statement of the addon's id, version, the jar's
 * size and its SHA-256 (it must match core's {@code ReleaseStatement} byte for byte). Plain JDK, no Gradle, so it runs
 * from the Gradle tasks ({@code generateSigningKey}, {@code signAddon}) and from the command line for signing offline:
 * <pre>
 * java -cp myriad-gradle-plugin.jar dev.myriadclient.gradle.signing.Signing generate &lt;key file&gt;
 * java -cp myriad-gradle-plugin.jar dev.myriadclient.gradle.signing.Signing sign &lt;jar&gt; &lt;key file&gt;
 * java -cp myriad-gradle-plugin.jar dev.myriadclient.gradle.signing.Signing public &lt;key file&gt;
 * MYRIAD_SIGNING_KEY=... java -cp myriad-gradle-plugin.jar dev.myriadclient.gradle.signing.Signing sign-env &lt;jar&gt;
 * </pre>
 * {@code sign-env} is for CI: a release workflow signs in a job of its own that runs no build, so nothing a build pulls
 * in ever runs where the key is.
 * Keys are written as text: the public key {@code ed25519:<base64url>} (it goes in {@code fabric.mod.json}), the private
 * key {@code ed25519-private:<base64url seed>} (a CI secret), or a key file encrypted with a passphrase (PBKDF2-SHA256,
 * 600,000 rounds, then AES-256-GCM) for keeping offline.
 */
public final class Signing {
	public static final String PUBLIC = "ed25519:", PRIVATE = "ed25519-private:";
	static final String HEADER = "myriad-addon-release:1";
	private static final int ITERATIONS = 600_000;
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}"), VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
	private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._+-]{1,128}\\.jar");

	private Signing() {
	}

	/** A signing key: its 32-byte seed and the public key that goes with it. */
	public record Key(byte[] seed, byte[] publicKey) {
		public String publicText() {
			return PUBLIC + b64(publicKey);
		}

		public String privateText() {
			return PRIVATE + b64(seed);
		}

		/** The first 8 bytes of the public key's SHA-256 in hex: how a signature names its key. */
		public String id() {
			return HexFormat.of().formatHex(sha256(publicKey), 0, 8);
		}

		PrivateKey privateKey() throws GeneralSecurityException {
			return KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
		}

		public byte[] sign(byte[] message) throws GeneralSecurityException {
			Signature s = Signature.getInstance("Ed25519");
			s.initSign(privateKey());
			s.update(message);
			return s.sign();
		}
	}

	public static Key generate() throws GeneralSecurityException {
		KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		byte[] seed = ((EdECPrivateKey) kp.getPrivate()).getBytes().orElseThrow();
		byte[] der = kp.getPublic().getEncoded();
		return new Key(seed, Arrays.copyOfRange(der, der.length - 32, der.length));
	}

	/** The key with this seed (its public key worked out by generating from the seed). */
	public static Key fromSeed(byte[] seed) throws GeneralSecurityException {
		if (seed.length != 32) throw new GeneralSecurityException("an Ed25519 seed is 32 bytes");
		KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
		g.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
			@Override
			public void nextBytes(byte[] bytes) {
				System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
			}
		});
		byte[] der = g.generateKeyPair().getPublic().getEncoded();
		return new Key(seed.clone(), Arrays.copyOfRange(der, der.length - 32, der.length));
	}

	/** A private key from its text, {@code ed25519-private:<base64url seed>}. */
	public static Key parsePrivate(String text) throws GeneralSecurityException {
		text = text.strip();
		if (!text.startsWith(PRIVATE)) throw new GeneralSecurityException("not a Myriad signing key (it starts " + PRIVATE + ")");
		return fromSeed(Base64.getUrlDecoder().decode(text.substring(PRIVATE.length())));
	}

	// ---- signing ----------------------------------------------------------------------------------------------------

	/** What's signed for a release: must match core's ReleaseStatement exactly. */
	public static byte[] statement(String id, String version, long size, byte[] sha256) {
		if (!ID.matcher(id).matches()) throw new IllegalArgumentException("not a mod id: " + id);
		if (!VERSION.matcher(version).matches()) throw new IllegalArgumentException("not a version Myriad signs: " + version);
		return (HEADER + "\nid=" + id + "\nversion=" + version + "\nsize=" + size + "\nsha256=" + HexFormat.of().formatHex(sha256) + "\n")
			.getBytes(StandardCharsets.US_ASCII);
	}

	/** A jar's id, version, keys and Minecraft range, read from its {@code fabric.mod.json}. */
	public record ModInfo(String id, String version, List<String> keys, String minecraft) {
	}

	public static ModInfo modInfo(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry("fabric.mod.json");
			if (e == null) throw new IOException(jar + " has no fabric.mod.json");
			String json;
			try (InputStream in = zip.getInputStream(e)) {
				json = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
			}
			return new ModInfo(field(json, "id"), field(json, "version"), keys(json), minecraft(json));
		}
	}

	/**
	 * Signs {@code jar} and returns its {@code .sig}: one update-feed entry for the jar with the signature, which is what
	 * goes next to the jar in a GitHub release (and, merged, into a self-hosted feed).
	 */
	public static String sign(Path jar, Key key, boolean allowUndeclared) throws IOException, GeneralSecurityException {
		ModInfo info = modInfo(jar);
		String file = jar.getFileName().toString();
		if (!FILE.matcher(file).matches() || file.startsWith(".")) throw new IOException("the jar's file name must be a plain jar name: " + file);
		if (!allowUndeclared && !info.keys().contains(key.publicText())) {
			throw new IOException(info.id() + "'s fabric.mod.json doesn't list this key (" + key.publicText() + ") under custom.myriad.keys. "
				+ "Add it, or pass -PallowUndeclaredKey when rotating to a new key.");
		}
		byte[] bytes = Files.readAllBytes(jar);
		byte[] sha = sha256(bytes);
		byte[] sig = key.sign(statement(info.id(), info.version(), bytes.length, sha));
		StringBuilder sb = new StringBuilder("{\n");
		sb.append("  \"version\": \"").append(info.version()).append("\",\n");
		sb.append("  \"file\": \"").append(file).append("\",\n");
		sb.append("  \"url\": \"").append(file).append("\",\n");
		sb.append("  \"size\": ").append(bytes.length).append(",\n");
		sb.append("  \"sha256\": \"").append(HexFormat.of().formatHex(sha)).append("\",\n");
		if (!info.minecraft().isEmpty()) sb.append("  \"minecraft\": \"").append(info.minecraft()).append("\",\n");
		sb.append("  \"signatures\": [{\"key\": \"").append(key.id()).append("\", \"sig\": \"").append(b64(sig)).append("\"}]\n");
		sb.append("}\n");
		return sb.toString();
	}

	// ---- encrypted key files ------------------------------------------------------------------------------------------

	/** The key encrypted with {@code passphrase}, as a key file's text. */
	public static String encrypt(Key key, char[] passphrase) throws GeneralSecurityException {
		SecureRandom random = new SecureRandom();
		byte[] salt = new byte[16], nonce = new byte[12];
		random.nextBytes(salt);
		random.nextBytes(nonce);
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, aesKey(passphrase, salt, ITERATIONS), new GCMParameterSpec(128, nonce));
		c.updateAAD(key.publicText().getBytes(StandardCharsets.US_ASCII));
		byte[] data = c.doFinal(key.seed());
		return "{\n  \"format\": 1,\n  \"kind\": \"myriad-signing-key\",\n  \"public\": \"" + key.publicText() + "\",\n  \"iterations\": " + ITERATIONS
			+ ",\n  \"salt\": \"" + b64(salt) + "\",\n  \"nonce\": \"" + b64(nonce) + "\",\n  \"data\": \"" + b64(data) + "\"\n}\n";
	}

	/** The key in a key file's text; fails on a wrong passphrase (or a file that was changed). */
	public static Key decrypt(String file, char[] passphrase) throws GeneralSecurityException {
		if (!"1".equals(number(file, "format")) || !"myriad-signing-key".equals(field(file, "kind"))) throw new GeneralSecurityException("not a Myriad key file");
		String pub = field(file, "public");
		int iterations = Integer.parseInt(number(file, "iterations"));
		if (iterations < ITERATIONS) throw new GeneralSecurityException("the key file's iterations are too few");
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.DECRYPT_MODE, aesKey(passphrase, b64d(field(file, "salt")), iterations), new GCMParameterSpec(128, b64d(field(file, "nonce"))));
		c.updateAAD(pub.getBytes(StandardCharsets.US_ASCII));
		byte[] seed;
		try {
			seed = c.doFinal(b64d(field(file, "data")));
		} catch (javax.crypto.AEADBadTagException e) {
			throw new GeneralSecurityException("wrong passphrase");
		}
		Key key = fromSeed(seed);
		if (!key.publicText().equals(pub)) throw new GeneralSecurityException("the key file is inconsistent");
		return key;
	}

	private static SecretKeySpec aesKey(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
		PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
		try {
			return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
		} finally {
			spec.clearPassword();
		}
	}

	/** Writes {@code text} to a new file readable only by you (fails if the file exists). */
	public static void writeSecret(Path file, String text) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException ignored) {
			// Windows: the folder's own permissions apply.
		}
	}

	// ---- command line -----------------------------------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: generate <key file> | sign <jar> <key file> [--allow-undeclared] | sign-env <jar> [--allow-undeclared] | public <key file>");
			System.exit(2);
		}
		switch (args[0]) {
			case "generate" -> {
				Path out = Path.of(args[1]);
				if (Files.exists(out)) throw new IOException(out + " exists; not overwriting a key");
				char[] pass = passphrase("Passphrase for the new key: ");
				char[] again = passphrase("Again: ");
				if (!Arrays.equals(pass, again)) throw new IOException("the passphrases differ");
				if (pass.length < 12) throw new IOException("use a passphrase of at least 12 characters");
				Key key = generate();
				writeSecret(out, encrypt(key, pass));
				Arrays.fill(pass, ' ');
				Arrays.fill(again, ' ');
				System.out.println("Wrote " + out + ". Its public key (for fabric.mod.json and Myriad's root keys):");
				System.out.println(key.publicText());
			}
			case "public" -> System.out.println(field(Files.readString(Path.of(args[1])), "public"));
			case "sign" -> {
				if (args.length < 3) throw new IOException("sign <jar> <key file>");
				Path jar = Path.of(args[1]);
				char[] pass = passphrase("Passphrase for " + args[2] + ": ");
				Key key = decrypt(Files.readString(Path.of(args[2])), pass);
				Arrays.fill(pass, ' ');
				Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
				Files.writeString(sig, sign(jar, key, args.length > 3 && args[3].equals("--allow-undeclared")));
				System.out.println("Wrote " + sig + " (key " + key.id() + ")");
			}
			case "sign-env" -> {
				String env = System.getenv("MYRIAD_SIGNING_KEY");
				if (env == null || env.isBlank()) throw new IOException("MYRIAD_SIGNING_KEY isn't set");
				Path jar = Path.of(args[1]);
				Key key = parsePrivate(env);
				Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
				Files.writeString(sig, sign(jar, key, args.length > 2 && args[2].equals("--allow-undeclared")));
				System.out.println("Wrote " + sig + " (key " + key.id() + ")");
			}
			default -> throw new IOException("unknown command " + args[0]);
		}
	}

	private static char[] passphrase(String prompt) throws IOException {
		Console console = System.console();
		if (console != null) return console.readPassword(prompt);
		String env = System.getenv("MYRIAD_KEY_PASSPHRASE");
		if (env != null) return env.toCharArray();
		throw new IOException("no console to ask for the passphrase (or set MYRIAD_KEY_PASSPHRASE)");
	}

	// ---- helpers ----------------------------------------------------------------------------------------------------

	static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static String b64(byte[] b) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static byte[] b64d(String s) {
		return Base64.getUrlDecoder().decode(s);
	}

	/** A top-level string field of a small, trusted JSON document (a key file or this jar's own fabric.mod.json). */
	private static String field(String json, String name) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
		return m.find() ? m.group(1) : "";
	}

	private static String number(String json, String name) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\\d+)").matcher(json);
		return m.find() ? m.group(1) : "";
	}

	private static List<String> keys(String json) {
		List<String> out = new ArrayList<>();
		Matcher block = Pattern.compile("\"keys\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
		if (!block.find()) return out;
		Matcher k = Pattern.compile("\"(ed25519:[A-Za-z0-9_-]+=*)\"").matcher(block.group(1));
		while (k.find()) out.add(k.group(1));
		return out;
	}

	private static String minecraft(String json) {
		Matcher m = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
		return m.find() ? m.group(1) : "";
	}
}
