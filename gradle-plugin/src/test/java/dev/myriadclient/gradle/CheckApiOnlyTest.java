package dev.myriadclient.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code checkApiOnly}: the internals are refused, with their line; the API and look-alike names aren't. */
class CheckApiOnlyTest {
	@Test
	void internalsAreRefusedWithTheirLine() {
		List<String> lines = List.of(
			"package com.example.addon;",
			"",
			"import dev.myriadclient.api.Myriad;",
			"import dev.myriadclient.impl.MyriadImpl;",
			"import dev.myriadclient.essentials.modules.Sprint;",
			"\tvar ui = dev.myriadclient.impl.ui.DesktopScreen.class;");
		assertEquals(List.of(
			"Addon.java:4: import dev.myriadclient.impl.MyriadImpl;",
			"Addon.java:6: var ui = dev.myriadclient.impl.ui.DesktopScreen.class;"), CheckApiOnly.violations("Addon.java", lines));
	}

	@Test
	void aPackageThatOnlyStartsTheSameIsAllowed() {
		assertEquals(List.of(), CheckApiOnly.violations("A.java", List.of("import dev.myriadclient.implants.Thing;")));
	}
}
