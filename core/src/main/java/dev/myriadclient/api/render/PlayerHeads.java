package dev.myriadclient.api.render;

import dev.myriadclient.api.ui.draw.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Players' faces drawn on the canvas, with their hat layer, for radars, target HUDs, nametags and lists. Skins come
 * from the tab list (so players out of render distance still have one); unknown players get a default skin.
 */
public final class PlayerHeads {
	private PlayerHeads() {
	}

	/**
	 * The skin texture of the player with this UUID, from the tab list; the default skin for that UUID if they aren't
	 * listed (or you aren't connected).
	 */
	public static Identifier skin(UUID player) {
		var handler = Minecraft.getInstance().getConnection();
		var entry = handler == null ? null : handler.getPlayerInfo(player);
		return entry != null ? entry.getSkin().body().texturePath() : DefaultPlayerSkin.get(player).body().texturePath();
	}

	/** The skin texture of {@code player}: its own for a player in the world, else as {@link #skin(UUID)}. */
	public static Identifier skin(Player player) {
		return player instanceof AbstractClientPlayer p ? p.getSkin().body().texturePath() : skin(player.getUUID());
	}

	/**
	 * Draws the face of {@code skin} with its top-left corner at (x, y), {@code size} units square, tinted by an ARGB
	 * colour (0xFFFFFFFF for none). The hat layer reaches {@code size / 16} past each edge.
	 */
	public static void draw(Canvas c, Identifier skin, float x, float y, float size, int tint) {
		c.texture(skin, x, y, size, size, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, tint);
		// The hat layer sits slightly larger, as in game.
		float hat = size / 16f;
		c.texture(skin, x - hat, y - hat, size + hat * 2, size + hat * 2, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, tint);
	}

	/** Draws {@code player}'s face, untinted; see {@link #draw(Canvas, Identifier, float, float, float, int)}. */
	public static void draw(Canvas c, Player player, float x, float y, float size) {
		draw(c, skin(player), x, y, size, 0xFFFFFFFF);
	}

	/** Draws the face of the player with this UUID, untinted; see {@link #skin(UUID)}. */
	public static void draw(Canvas c, UUID player, float x, float y, float size) {
		draw(c, skin(player), x, y, size, 0xFFFFFFFF);
	}
}
