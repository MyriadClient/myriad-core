package dev.myriadclient.impl.dev;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.util.Packets;
import dev.myriadclient.harness.Harness;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code .packets log}: what went out, with its fields, in order; cancelled packets left out; a filter keeps only what it names. */
class PacketLogTest {
	@TempDir
	Path dir;

	private List<String> lines() throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return Files.readAllLines(files.findFirst().orElseThrow());
		}
	}

	@Test
	void sentPacketsAreLoggedWithTheirFieldsButNotCancelledOnes() throws IOException {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 64, 0.5, 0, 0);
			assertTrue(PacketLog.start(dir, List.of()).startsWith("Logging packets to"));
			Packets.send(new ServerboundSwingPacket(InteractionHand.OFF_HAND));
			Subscription cancel = Myriad.events().listen(PacketEvent.Send.class, e -> {
				if (e.packet() instanceof ServerboundSwingPacket) e.cancel();
			});
			Packets.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			cancel.unsubscribe();
			String stopped = PacketLog.stop();
			assertTrue(stopped.startsWith("1 packet in"), stopped);
			List<String> lines = lines();
			assertTrue(lines.getFirst().startsWith("# "), lines.getFirst());
			assertEquals(2, lines.size(), String.join("\n", lines));
			assertTrue(lines.get(1).matches("t\\d+ \\+\\d+ > SwingPacket \\{hand=OFF_HAND\\}"), lines.get(1));
		}
	}

	@Test
	void aFilterKeepsOnlyWhatItNames() throws IOException {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 64, 0.5, 0, 0);
			PacketLog.start(dir, List.of("swing"));
			h.tick();
			Packets.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			h.tick();
			PacketLog.stop();
			List<String> lines = lines();
			assertEquals(2, lines.size(), String.join("\n", lines));
			assertTrue(lines.get(1).contains("> SwingPacket"));
			assertFalse(String.join("\n", lines).contains("MovePlayer"));
			assertEquals("Not logging packets", PacketLog.stop());
		}
	}
}
