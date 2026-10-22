package dev.myriadclient.api.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/**
 * The game's player, world, connection and interaction manager, never null: each returns the one there is, or throws an
 * {@link IllegalStateException} naming the method that asked outside a world. Inside a handler subscribed with
 * {@code @Subscribe(inGame = true)} (and from steps, which only run in game) there always is one, so the body needs no
 * {@code mc.player == null} check:
 *
 * <pre>{@code
 * @Subscribe(inGame = true)
 * private void onTick(TickEvent.Pre e) {
 *     if (Client.player().getHealth() < 6) eat();
 * }
 * }</pre>
 *
 * Code that also runs on the title screen (a HUD preview, a command) asks {@link #inGame()} first, or reads
 * {@code Minecraft.getInstance()} itself.
 */
public final class Client {
	private Client() {
	}

	/** Whether there's a player in a world. */
	public static boolean inGame() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.player != null && mc.level != null;
	}

	public static LocalPlayer player() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc == null ? null : mc.player;
		if (p == null) throw outside("player");
		return p;
	}

	public static ClientLevel level() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel l = mc == null ? null : mc.level;
		if (l == null) throw outside("level");
		return l;
	}

	public static ClientPacketListener connection() {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener c = mc == null ? null : mc.getConnection();
		if (c == null) throw outside("connection");
		return c;
	}

	/** The interaction manager ({@code mc.gameMode}): what clicks blocks and entities and moves items. */
	public static MultiPlayerGameMode interaction() {
		Minecraft mc = Minecraft.getInstance();
		MultiPlayerGameMode g = mc == null ? null : mc.gameMode;
		if (g == null) throw outside("interaction");
		return g;
	}

	/** Only on the way to throwing, so walking the stack costs nothing in game. */
	private static IllegalStateException outside(String what) {
		String caller = StackWalker.getInstance().walk(frames -> frames
			.filter(f -> !f.getClassName().equals(Client.class.getName()))
			.findFirst()
			.map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
			.orElse("?"));
		return new IllegalStateException("No " + what + " outside a world: " + caller + " asked for it (subscribe with inGame = true, or check Client.inGame())");
	}
}
