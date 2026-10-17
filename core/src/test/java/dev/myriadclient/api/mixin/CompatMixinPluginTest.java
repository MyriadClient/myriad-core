package dev.myriadclient.api.mixin;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompatMixinPluginTest {
	private static final Set<String> LOADED = Set.of("sodium", "fabric-api");

	private static boolean applies(String name) {
		return CompatMixinPlugin.applies(name, LOADED::contains);
	}

	@Test
	void ordinaryMixinsAlwaysApply() {
		assertTrue(applies("WorldRendererMixin"));
		assertTrue(applies("render.WorldRendererMixin"));
		// A class merely named "compat" isn't a compat package.
		assertTrue(applies("compat"));
	}

	@Test
	void compatPackagesFollowTheMod() {
		assertTrue(applies("compat.sodium.SodiumMixin"));
		assertFalse(applies("compat.iris.IrisMixin"));
		assertTrue(applies("render.compat.fabric_api.ApiMixin"));
	}

	@Test
	void noPrefixMeansTheModIsAbsent() {
		assertFalse(applies("compat.no_sodium.VanillaChunkMixin"));
		assertTrue(applies("compat.no_iris.VanillaShaderMixin"));
	}
}
