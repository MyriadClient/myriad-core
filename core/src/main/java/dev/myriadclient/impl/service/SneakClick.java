package dev.myriadclient.impl.service;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * Clicks made as if sneaking, without sneaking: the server is told the sneak key is down just for the click and up
 * again straight after, all before the tick's movement, so you don't crouch, slow down or change height. The server
 * then places against blocks that would otherwise open or toggle (chests, doors, repeaters, note blocks), and keeps a
 * chest single beside another. The client counts the key as down for the click too, so what it predicts matches.
 * The other way round works too: a click made unsneaked while you sneak uses the block instead of placing on it.
 */
final class SneakClick {
	private SneakClick() {
	}

	/** Runs {@code action} (a click) with the sneak key down for the server and the client. */
	static void during(Runnable action) {
		as(true, action);
	}

	/** Runs {@code action} (a click) with the sneak key down ({@code sneak}) or up, for the server and the client. */
	static void as(boolean sneak, Runnable action) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.getConnection() == null || p.getLastSentInput().shift() == sneak && p.input.keyPresses.shift() == sneak) {
			action.run();
			return;
		}
		Input real = p.input.keyPresses, sent = p.getLastSentInput();
		mc.getConnection().send(new ServerboundPlayerInputPacket(shift(sent, sneak)));
		p.input.keyPresses = shift(real, sneak);
		try {
			action.run();
		} finally {
			p.input.keyPresses = real;
			// Back to what the server last had, so vanilla's own input packet next is the change it expects.
			mc.getConnection().send(new ServerboundPlayerInputPacket(sent));
		}
	}

	private static Input shift(Input i, boolean shift) {
		return new Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), shift, i.sprint());
	}
}
