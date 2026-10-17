package dev.myriadclient.impl.network;

import dev.myriadclient.impl.MyriadImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * The game version the server sees you as: what ViaFabricPlus translates to when it's installed, otherwise this
 * client's own. Grim judges some things by it (see {@code ClickRules}). Worked out once per connection, by reflection,
 * so ViaFabricPlus stays optional; if it can't be told, the protocol is -1 and callers assume the strictest rules.
 */
public final class JoinedVersion {
	private static final JoinedVersion INSTANCE = new JoinedVersion();

	private @Nullable Connection resolvedFor;
	private int protocol = -1;
	private String name = "unknown";
	private boolean translated;

	private JoinedVersion() {
	}

	public static JoinedVersion get() {
		return INSTANCE;
	}

	/** The protocol number the server sees, or -1 if it couldn't be told. */
	public int protocol() {
		refresh();
		return protocol;
	}

	/** "1.20.4 (ViaFabricPlus)", "26.2" or "unknown". */
	public String describe() {
		refresh();
		return translated ? name + " (ViaFabricPlus)" : name;
	}

	private void refresh() {
		var listener = Minecraft.getInstance().getConnection();
		Connection connection = listener == null ? null : listener.getConnection();
		if (connection == resolvedFor) return;
		resolvedFor = connection;
		resolve(connection);
		if (connection != null) MyriadImpl.LOG.info("Joined as {} (protocol {})", describe(), protocol);
	}

	private void resolve(@Nullable Connection connection) {
		protocol = SharedConstants.getProtocolVersion();
		name = SharedConstants.getCurrentVersion().name();
		translated = false;
		if (connection == null || !FabricLoader.getInstance().isModLoaded("viafabricplus")) return;
		try {
			Object api = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus").getMethod("getImpl").invoke(null);
			Method target = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusBase").getMethod("getTargetVersion", Connection.class);
			Object version = target.invoke(api, connection);
			if (version == null) return;
			Class<?> type = version.getClass();
			int number = (int) type.getMethod("getVersion").invoke(version);
			if (number == protocol) return;
			name = (String) type.getMethod("getName").invoke(version);
			translated = true;
			// Classic, alpha, beta and the like don't share release numbering.
			protocol = "RELEASE".equals(String.valueOf(type.getMethod("getVersionType").invoke(version))) ? number : -1;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			protocol = -1;
			name = "unknown";
			translated = true;
			MyriadImpl.LOG.warn("Couldn't read ViaFabricPlus's target version; assuming the strictest inventory rules", e);
		}
	}
}
