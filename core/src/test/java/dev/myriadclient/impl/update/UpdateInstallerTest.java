package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerTest {
	@TempDir
	Path game;

	private Path mods() throws Exception {
		return Files.createDirectories(game.resolve("mods"));
	}

	private UpdateInstaller installer() {
		return new UpdateInstaller(game.resolve("myriad/updates"), false);
	}

	@Test
	void installSwapsTheJarAndKeepsTheOldOne() throws Exception {
		Path old = Files.writeString(mods().resolve("addon-1.0.0.jar"), "old");
		UpdateInstaller u = installer();
		UpdateInstaller.Pending p = u.stage("addon", "1.1.0", "addon-1.1.0.jar", "new".getBytes(), old);
		Path oldDir = u.dir().resolve("old");

		assertEquals(List.of(p), UpdateInstaller.apply(List.of(p), oldDir));
		assertFalse(Files.exists(old));
		assertEquals("new", Files.readString(mods().resolve("addon-1.1.0.jar")));
		assertEquals("old", Files.readString(oldDir.resolve("addon/addon-1.0.0.jar")));
	}

	@Test
	void onlyTheLastOldJarIsKept() throws Exception {
		Path oldDir = installer().dir().resolve("old");
		Files.createDirectories(oldDir.resolve("addon"));
		Files.writeString(oldDir.resolve("addon/addon-0.9.0.jar"), "older");
		Path old = Files.writeString(mods().resolve("addon-1.0.0.jar"), "old");
		UpdateInstaller.Pending p = installer().stage("addon", "1.1.0", "addon-1.1.0.jar", "new".getBytes(), old);
		UpdateInstaller.apply(List.of(p), oldDir);
		assertFalse(Files.exists(oldDir.resolve("addon/addon-0.9.0.jar")));
		assertTrue(Files.exists(oldDir.resolve("addon/addon-1.0.0.jar")));
	}

	@Test
	void anUninstalledModIsLeftAlone() throws Exception {
		Path old = mods().resolve("gone-1.0.0.jar");
		UpdateInstaller.Pending p = installer().stage("gone", "1.1.0", "gone-1.1.0.jar", "new".getBytes(), old);
		assertEquals(List.of(), UpdateInstaller.apply(List.of(p), installer().dir().resolve("old")));
		assertFalse(Files.exists(mods().resolve("gone-1.1.0.jar")));
	}

	@Test
	void theNextLaunchReportsWhatWentIn() throws Exception {
		Path old = Files.writeString(mods().resolve("addon-1.0.0.jar"), "old");
		UpdateInstaller u = installer();
		UpdateInstaller.Pending p = u.stage("addon", "1.1.0", "addon-1.1.0.jar", "new".getBytes(), old);
		UpdateInstaller.apply(List.of(p), u.dir().resolve("old"));

		UpdateInstaller next = installer();
		UpdateInstaller.Outcome o = next.reconcile(Map.of("addon", new UpdateInstaller.Installed("1.1.0", mods().resolve("addon-1.1.0.jar"))));
		assertEquals(1, o.installed().size());
		assertEquals("1.1.0", o.installed().getFirst().version());
		assertEquals(List.of(), o.stillWaiting());
		assertNull(next.pending("addon"));
		// Nothing left to report the time after.
		assertEquals(List.of(), installer().reconcile(Map.of()).installed());
	}

	@Test
	void anUpdateThatDidntGoInIsTriedAgain() throws Exception {
		Path old = Files.writeString(mods().resolve("addon-1.0.0.jar"), "old");
		installer().stage("addon", "1.1.0", "addon-1.1.0.jar", "new".getBytes(), old);

		// The game was killed, so nothing was swapped; meanwhile the jar was renamed.
		Path renamed = Files.move(old, mods().resolve("addon.jar"));
		UpdateInstaller next = installer();
		UpdateInstaller.Outcome o = next.reconcile(Map.of("addon", new UpdateInstaller.Installed("1.0.0", renamed)));
		assertEquals(List.of(), o.installed());
		assertEquals(1, o.stillWaiting().size());
		assertEquals(renamed, next.pending("addon").replaces());
	}

	@Test
	void aRemovedModsUpdateIsDropped() throws Exception {
		Path old = Files.writeString(mods().resolve("addon-1.0.0.jar"), "old");
		UpdateInstaller.Pending p = installer().stage("addon", "1.1.0", "addon-1.1.0.jar", "new".getBytes(), old);
		UpdateInstaller.Outcome o = installer().reconcile(Map.of());
		assertEquals(List.of(), o.stillWaiting());
		assertFalse(Files.exists(p.staged()));
	}

	@Test
	void theWindowsScriptQuotesPaths() {
		Path dir = Path.of("/game/it's/myriad/updates");
		UpdateInstaller.Pending p = new UpdateInstaller.Pending("addon", "1.1.0", dir.resolve("addon-1.1.0.jar"), Path.of("/game/it's/mods/addon-1.0.0.jar"));
		String script = UpdateInstaller.windowsScript(List.of(p), dir.resolve("old"), 1234);
		assertTrue(script.contains("Wait-Process -Id 1234"));
		assertTrue(script.contains("'/game/it''s/mods/addon-1.0.0.jar'"));
		assertTrue(script.contains("-Destination '/game/it''s/mods/addon-1.1.0.jar'"));
	}
}
