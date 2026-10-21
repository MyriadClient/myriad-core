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
 */
final class SneakClick {
	private SneakClick() {
	}

	/** Runs {@code action} (a click) with the sneak key down for the server and the client. */
	static void during(Runnable action) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.getConnection() == null || p.isShiftKeyDown()) {
			action.run();
			return;
		}
		Input real = p.input.keyPresses, sent = p.getLastSentInput();
		mc.getConnection().send(new ServerboundPlayerInputPacket(shift(sent)));
		p.input.keyPresses = shift(real);
		try {
			action.run();
		} finally {
			p.input.keyPresses = real;
			// Back to what the server last had, so vanilla's own input packet next is the change it expects.
			mc.getConnection().send(new ServerboundPlayerInputPacket(sent));
		}
	}

	private static Input shift(Input i) {
		return new Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), true, i.sprint());
	}
}
