package dev.myriadclient.impl.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.util.Async;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Addons' own icons, as textures for the Addons window: an installed addon's from its jar ({@code fabric.mod.json}'s
 * {@code icon}), a Library listing's from the Library (checked against the hash in its signed index before it's used).
 * Each is read once and kept small (at most {@link #KEEP} pixels a side); its texture is made at the size it's drawn,
 * every source pixel averaged in, so a big icon in a small square stays sharp instead of shimmering. The least recently
 * used go first past {@link #MAX}. Render thread.
 */
public final class AddonIcons {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/AddonIcons");
	private static final int MAX = 64;
	/** Icons bigger than this aren't worth reading for a small square. */
	private static final long MAX_BYTES = 2 * 1024 * 1024;
	/** The most pixels a side kept of an icon: more than any card draws it at. */
	static final int KEEP = 128;
	/** A new drawn size has to hold this long before the texture's remade (UI scale changes, not animations). */
	private static final long SETTLE_MS = 250;

	private static final class Icon {
		final int[] argb;
		final int w, h;
		@Nullable Identifier texture;
		int texturePx, wantedPx;
		long wantedSince;

		Icon(int[] argb, int w, int h) {
			this.argb = argb;
			this.w = w;
			this.h = h;
		}
	}

	private static final Map<String, Icon> LOADED = new LinkedHashMap<>(16, 0.75f, true);
	/** Keys with no icon, or one that couldn't be read: not tried again this session. */
	private static final Set<String> MISSING = new HashSet<>();
	private static final Map<String, CompletableFuture<?>> LOADING = new HashMap<>();
	private static int next;

	private AddonIcons() {
	}

	/** {@code a}'s icon for a square {@code px} pixels a side, or null while it's read or when it has none. */
	public static @Nullable Identifier of(Addon a, int px) {
		String key = "installed/" + a.id() + "/" + a.version();
		Icon icon = LOADED.get(key);
		if (icon != null) return texture(icon, px);
		if (MISSING.contains(key)) return null;
		String path = a.iconPath().orElse(null);
		Path file = path == null ? null : a.container().findPath(path).orElse(null);
		if (file == null) {
			MISSING.add(key);
			return null;
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] png = in.readNBytes((int) MAX_BYTES + 1);
			if (png.length > MAX_BYTES) throw new java.io.IOException("too big");
			return texture(register(key, png), px);
		} catch (Exception e) {
			LOG.debug("No icon for {}: {}", a.id(), e.toString());
			MISSING.add(key);
			return null;
		}
	}

	/**
	 * A Library listing's icon for a square {@code px} pixels a side, {@code sha256} the hash its signed index gives;
	 * {@code fetch} gets the PNG (and checks it against that hash). Null while it's fetched, when there's none, or when it
	 * didn't match.
	 */
	public static @Nullable Identifier library(String addonId, @Nullable String sha256, Supplier<CompletableFuture<byte[]>> fetch, int px) {
		if (sha256 == null) return null;
		String key = "library/" + addonId + "/" + sha256;
		Icon icon = LOADED.get(key);
		if (icon != null) return texture(icon, px);
		if (MISSING.contains(key) || LOADING.containsKey(key)) return null;
		LOADING.put(key, fetch.get().whenComplete((png, err) -> Async.onRenderThread(() -> {
			LOADING.remove(key);
			if (err != null || png == null) {
				MISSING.add(key);
				return;
			}
			try {
				register(key, png);
			} catch (Exception e) {
				MISSING.add(key);
			}
		})));
		return null;
	}

	/** Icons claiming more pixels than this a side aren't decoded (a small file can claim a huge image). */
	private static final int MAX_SIDE = 1024;

	/** A PNG's size from its header, checked before anything is decoded. */
	static boolean saneSize(byte[] png) {
		if (png.length < 24 || (png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G' || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
			return false;
		}
		long w = ((png[16] & 0xffL) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
		long h = ((png[20] & 0xffL) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
		return w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE;
	}

	/** Decodes an icon and keeps it small; its texture is made when it's first drawn. */
	private static Icon register(String key, byte[] png) throws java.io.IOException {
		if (!saneSize(png)) throw new java.io.IOException("not a PNG of a sensible size");
		Icon icon;
		try (NativeImage image = NativeImage.read(png)) {
			int w = image.getWidth(), h = image.getHeight();
			int[] argb = new int[w * h];
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) argb[y * w + x] = image.getPixel(x, y);
			int kw = Math.min(w, KEEP), kh = Math.min(h, KEEP);
			icon = kw == w && kh == h ? new Icon(argb, w, h) : new Icon(resize(argb, w, h, kw, kh), kw, kh);
		}
		LOADED.put(key, icon);
		while (LOADED.size() > MAX) {
			var eldest = LOADED.entrySet().iterator().next();
			if (eldest.getValue().texture != null) Minecraft.getInstance().getTextureManager().release(eldest.getValue().texture);
			LOADED.remove(eldest.getKey());
		}
		return icon;
	}

	/** The icon's texture for {@code px}: made at once the first time, remade once a new size has settled. */
	private static Identifier texture(Icon icon, int px) {
		px = Math.max(1, px);
		long now = System.currentTimeMillis();
		if (icon.texture != null && px != icon.texturePx) {
			if (px != icon.wantedPx) {
				icon.wantedPx = px;
				icon.wantedSince = now;
			} else if (now - icon.wantedSince >= SETTLE_MS) {
				Minecraft.getInstance().getTextureManager().release(icon.texture);
				icon.texture = null;
			}
		}
		if (icon.texture == null) {
			int dw = Math.min(px, icon.w), dh = Math.min(px, icon.h);
			int[] argb = dw == icon.w && dh == icon.h ? icon.argb : resize(icon.argb, icon.w, icon.h, dw, dh);
			NativeImage image = new NativeImage(dw, dh, false);
			for (int y = 0; y < dh; y++) for (int x = 0; x < dw; x++) image.setPixel(x, y, argb[y * dw + x]);
			Identifier id = Identifier.fromNamespaceAndPath("myriad", "addon_icon/" + next++);
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Addon icon", image));
			icon.texture = id;
			icon.texturePx = px;
			icon.wantedPx = px;
		}
		return icon.texture;
	}

	/**
	 * {@code src} ({@code sw} by {@code sh}, ARGB) made {@code dw} by {@code dh}, each new pixel the average of the source
	 * area it covers (fractional pixels weighted by how much they cover). Colours are averaged by alpha, so transparent
	 * pixels don't darken the edges. For shrinking; growing just repeats pixels.
	 */
	static int[] resize(int[] src, int sw, int sh, int dw, int dh) {
		float[] rows = new float[dw * sh * 4];
		for (int y = 0; y < sh; y++) {
			for (int dx = 0; dx < dw; dx++) {
				float x0 = (float) dx * sw / dw, x1 = (float) (dx + 1) * sw / dw;
				float a = 0, r = 0, g = 0, b = 0;
				for (int sx = (int) x0; sx < Math.min(sw, (int) Math.ceil(x1)); sx++) {
					float wgt = Math.min(x1, sx + 1) - Math.max(x0, sx);
					int p = src[y * sw + sx];
					float pa = (p >>> 24) / 255f * wgt;
					a += pa;
					r += ((p >> 16) & 0xff) * pa;
					g += ((p >> 8) & 0xff) * pa;
					b += (p & 0xff) * pa;
				}
				int o = (y * dw + dx) * 4;
				float span = x1 - x0;
				rows[o] = a / span;
				rows[o + 1] = r / span;
				rows[o + 2] = g / span;
				rows[o + 3] = b / span;
			}
		}
		int[] out = new int[dw * dh];
		for (int dy = 0; dy < dh; dy++) {
			float y0 = (float) dy * sh / dh, y1 = (float) (dy + 1) * sh / dh, span = y1 - y0;
			for (int dx = 0; dx < dw; dx++) {
				float a = 0, r = 0, g = 0, b = 0;
				for (int sy = (int) y0; sy < Math.min(sh, (int) Math.ceil(y1)); sy++) {
					float wgt = Math.min(y1, sy + 1) - Math.max(y0, sy);
					int o = (sy * dw + dx) * 4;
					a += rows[o] * wgt;
					r += rows[o + 1] * wgt;
					g += rows[o + 2] * wgt;
					b += rows[o + 3] * wgt;
				}
				a /= span;
				r /= span;
				g /= span;
				b /= span;
				int ia = Math.round(Math.clamp(a * 255, 0, 255));
				if (ia == 0 || a <= 0) continue;
				out[dy * dw + dx] = ia << 24 | channel(r / a) << 16 | channel(g / a) << 8 | channel(b / a);
			}
		}
		return out;
	}

	private static int channel(float v) {
		return Math.round(Math.clamp(v, 0, 255));
	}
}
