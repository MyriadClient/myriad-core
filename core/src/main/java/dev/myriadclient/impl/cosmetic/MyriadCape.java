package dev.myriadclient.impl.cosmetic;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.ui.WindowManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Myriad's cape (and elytra), worn by Myriad players who leave Theme → Preferences → Myriad Cape on, and seen by every
 * other Myriad player near them, with no server of Myriad's own. The skin parts a client reports (cape, jacket, sleeves,
 * hat) are one byte with a spare top bit: the server passes the whole byte to the players tracking you, and vanilla
 * clients ignore that bit. Myriad sets it while the cape is on; a player with it set wears Myriad's cape in place of any
 * other, on every Myriad client in range. Nothing else is sent, and only players already in range ever see it.
 */
public final class MyriadCape {
	/** The skin-parts bit no part uses. */
	public static final int BIT = 0x80;
	private static final Logger LOG = LoggerFactory.getLogger("Myriad");
	/** Myriad's art: the logo on the cape's faces and on the wings (the cloth under it is made here). */
	private static final Identifier ART = Identifier.fromNamespaceAndPath("myriad", "textures/cape.png");
	/** Minecraft's elytra, whose shape and shading the wings take. */
	private static final Identifier ELYTRA = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
	/** The texture built from both (cape and wings share one sheet, as a player's cape does). */
	private static final Identifier BUILT = Identifier.fromNamespaceAndPath("myriad", "dynamic/cape");
	private static final ClientAsset.Texture TEXTURE = new ClientAsset.ResourceTexture(BUILT, BUILT);
	/** The wings' shading from Minecraft's elytra is mapped from its darkest to its lightest onto this range. */
	private static final int WING_DARK = 0xFF0C0C0C, WING_LIGHT = 0xFF262B28;
	/** Where the wings start on the 64-wide sheet: the elytra model's texture offset. */
	private static final int WING_U = 22;
	/**
	 * The cape's ragged hem: texels torn off the bottom of each of its 10 columns (left to right on its outer face).
	 * Its box sits at (0,0) on the 64x32 sheet, 10 wide, 16 high and 1 deep.
	 */
	private static final int[] TORN = {1, 0, 2, 1, 0, 3, 1, 0, 2, 1};
	private static boolean built;

	private MyriadCape() {
	}

	/** Whether this client wears the cape (and so sends the bit). */
	public static boolean enabled() {
		return Myriad.isReady() && ((WindowManager) Myriad.ui()).theme().myriadCape.get();
	}

	/** The skin parts to report: the player's own, with the bit set while the cape is on. */
	public static int report(int parts) {
		return enabled() ? parts | BIT : parts & ~BIT;
	}

	/** Whether this player wears Myriad's cape: yourself by your setting, anyone else by the bit their client reports. */
	public static boolean wears(AbstractClientPlayer player) {
		if (player instanceof LocalPlayer) return enabled();
		return (player.getEntityData().get(Avatar.DATA_PLAYER_MODE_CUSTOMISATION) & BIT) != 0;
	}

	/** The skin with Myriad's cape and elytra in place of any others. */
	public static PlayerSkin dress(PlayerSkin skin) {
		if (!built && RenderSystem.isOnRenderThread()) build();
		return new PlayerSkin(skin.body(), TEXTURE, TEXTURE, skin.model(), skin.secure());
	}

	/**
	 * Builds the sheet once: the cape's faces from Myriad's art, and wings in exactly the shape of the elytra Minecraft
	 * (or a resource pack) has, with its shading moved onto Myriad's dark colours and the logo on top. Minecraft's art is
	 * read from the game the player has, never shipped.
	 */
	private static void build() {
		built = true;
		Minecraft mc = Minecraft.getInstance();
		try (NativeImage art = read(mc, ART); NativeImage elytra = read(mc, ELYTRA)) {
			int w = art.getWidth(), h = art.getHeight(), scale = w / 64, ew = elytra.getWidth(), eh = elytra.getHeight();
			float lo = 1, hi = 0;
			for (int y = 0; y < eh; y++) {
				for (int x = ew * WING_U / 64; x < ew; x++) {
					int p = elytra.getPixel(x, y);
					if (ARGB.alpha(p) == 0) continue;
					lo = Math.min(lo, luma(p));
					hi = Math.max(hi, luma(p));
				}
			}
			NativeImage sheet = new NativeImage(w, h, true);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int mine = art.getPixel(x, y);
					if (x < WING_U * scale) {
						int cloth = cloth(x / scale, y / scale);
						if (cloth != 0) sheet.setPixel(x, y, ARGB.srgbLerp(ARGB.alpha(mine) / 255f, cloth, ARGB.opaque(mine)));
						continue;
					}
					int theirs = elytra.getPixel(x * ew / w, y * eh / h);
					int alpha = ARGB.alpha(theirs);
					if (alpha == 0) continue;
					float t = hi > lo ? (luma(theirs) - lo) / (hi - lo) : 0.5f;
					int wing = ARGB.srgbLerp(t, WING_DARK, WING_LIGHT);
					// The logo over the wing, only where the wing is.
					int a = ARGB.alpha(mine);
					int color = a == 0 ? wing : ARGB.srgbLerp(a / 255f, wing, mine);
					sheet.setPixel(x, y, ARGB.color(alpha, color));
				}
			}
			mc.getTextureManager().register(BUILT, new DynamicTexture(() -> "Myriad cape", sheet));
		} catch (IOException | RuntimeException e) {
			LOG.error("Couldn't build Myriad's cape; Minecraft's own capes show instead", e);
		}
	}

	/**
	 * The cape's cloth at a texel of the 64x32 sheet, or 0 where it's torn away: dark, a little lighter towards the hem,
	 * speckled per texel like Minecraft's elytra. Made here rather than drawn, so it stays texel-sized at any resolution.
	 */
	private static int cloth(int u, int v) {
		if (v == 0) {
			// Top face (1..10) is cloth; the bottom face (11..20) would float under a torn hem, so it's left out.
			return u >= 1 && u <= 10 ? shade(u, 0, 0) : 0;
		}
		if (v > 16 || u > 21) return 0;
		int row = v - 1;
		// Each face's column in outer-face order: the inner face runs the other way, the sides take the edge columns.
		int column = u == 0 ? 0 : u <= 10 ? u - 1 : u == 11 ? 9 : 9 - (u - 12);
		if (row >= 16 - TORN[column]) return 0;
		return shade(u, v, row / 15f);
	}

	private static int shade(int u, int v, float down) {
		int h = (u * 73_856_093) ^ (v * 19_349_663);
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		float speckle = ((h & 0xFF) / 255f - 0.5f) * 0.35f;
		return ARGB.srgbLerp(Math.clamp(down * 0.7f + 0.15f + speckle, 0, 1), WING_DARK, WING_LIGHT);
	}

	/** Whether this is Myriad's cape sheet (drawn with holes, where Minecraft draws capes solid). */
	public static boolean isMine(Identifier texture) {
		return BUILT.equals(texture);
	}

	private static NativeImage read(Minecraft mc, Identifier id) throws IOException {
		try (InputStream in = mc.getResourceManager().getResourceOrThrow(id).open()) {
			return NativeImage.read(in);
		}
	}

	private static float luma(int argb) {
		return (0.2126f * ARGB.red(argb) + 0.7152f * ARGB.green(argb) + 0.0722f * ARGB.blue(argb)) / 255f;
	}
}
