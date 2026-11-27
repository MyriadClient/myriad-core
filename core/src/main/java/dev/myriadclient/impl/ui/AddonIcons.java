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
 * Each is read once and kept while it's in use; the least recently used go first past {@link #MAX}. Render thread.
 */
public final class AddonIcons {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/AddonIcons");
	private static final int MAX = 64;
	/** Icons bigger than this aren't worth reading for a 12-unit square. */
	private static final long MAX_BYTES = 2 * 1024 * 1024;

	private static final Map<String, Identifier> LOADED = new LinkedHashMap<>(16, 0.75f, true);
	/** Keys with no icon, or one that couldn't be read: not tried again this session. */
	private static final Set<String> MISSING = new HashSet<>();
	private static final Map<String, CompletableFuture<?>> LOADING = new HashMap<>();
	private static int next;

	private AddonIcons() {
	}

	/** {@code a}'s icon, or null while it's read or when it has none. */
	public static @Nullable Identifier of(Addon a) {
		String key = "installed/" + a.id() + "/" + a.version();
		Identifier id = LOADED.get(key);
		if (id != null || MISSING.contains(key)) return id;
		String path = a.iconPath().orElse(null);
		Path file = path == null ? null : a.container().findPath(path).orElse(null);
		if (file == null) {
			MISSING.add(key);
			return null;
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] png = in.readNBytes((int) MAX_BYTES + 1);
			if (png.length > MAX_BYTES) throw new java.io.IOException("too big");
			return register(key, png);
		} catch (Exception e) {
			LOG.debug("No icon for {}: {}", a.id(), e.toString());
			MISSING.add(key);
			return null;
		}
	}

	/**
	 * A Library listing's icon, {@code sha256} the hash its signed index gives; {@code fetch} gets the PNG (and checks it
	 * against that hash). Null while it's fetched, when there's none, or when it didn't match.
	 */
	public static @Nullable Identifier library(String addonId, @Nullable String sha256, Supplier<CompletableFuture<byte[]>> fetch) {
		if (sha256 == null) return null;
		String key = "library/" + addonId + "/" + sha256;
		Identifier id = LOADED.get(key);
		if (id != null || MISSING.contains(key) || LOADING.containsKey(key)) return id;
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

	private static Identifier register(String key, byte[] png) throws java.io.IOException {
		if (!saneSize(png)) throw new java.io.IOException("not a PNG of a sensible size");
		NativeImage image = NativeImage.read(png);
		Identifier id = Identifier.fromNamespaceAndPath("myriad", "addon_icon/" + next++);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Addon icon " + key, image));
		LOADED.put(key, id);
		while (LOADED.size() > MAX) {
			var eldest = LOADED.entrySet().iterator().next();
			Minecraft.getInstance().getTextureManager().release(eldest.getValue());
			LOADED.remove(eldest.getKey());
		}
		return id;
	}
}
