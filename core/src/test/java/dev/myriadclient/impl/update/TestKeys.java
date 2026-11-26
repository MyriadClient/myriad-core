package dev.myriadclient.impl.update;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.HexFormat;

/** A signing key for tests, from a fixed seed, and the public key that goes with it. */
record TestKeys(PrivateKey priv, DevKey pub) {
	/** RFC 8032's first test vector. */
	static final TestKeys RFC = of("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
	static final TestKeys A = of("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb", "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
	static final TestKeys B = of("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025");

	static TestKeys of(String seedHex, String pubHex) {
		try {
			PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, HexFormat.of().parseHex(seedHex)));
			return new TestKeys(priv, new DevKey(HexFormat.of().parseHex(pubHex)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	byte[] signRaw(byte[] message) {
		try {
			Signature s = Signature.getInstance("Ed25519");
			s.initSign(priv);
			s.update(message);
			return s.sign();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	SignatureCheck.Sig sign(byte[] message) {
		return new SignatureCheck.Sig(pub.id(), Base64.getUrlEncoder().withoutPadding().encodeToString(signRaw(message)));
	}
}
