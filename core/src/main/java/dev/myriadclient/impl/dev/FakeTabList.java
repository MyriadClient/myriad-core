package dev.myriadclient.impl.dev;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Dev console only: fills the player list with made-up players, each with a skin texture of its own as on a real
 * server, so the list can be measured without one. Goes through the packet a server sends.
 */
final class FakeTabList {
	private static final String[] WORDS = {"Stash", "Dupe", "Ender", "Crystal", "Nether", "Highway", "Spawn", "Queue", "Obby", "Elytra", "Pearl", "Fit"};
	private static final String[] PREFIXES = {"§c[Admin] ", "§a[Prio] ", "§6[VIP] ", "§b"};

	private final Minecraft mc = Minecraft.getInstance();
	private final List<UUID> ids = new ArrayList<>();
	private final List<Identifier> skins = new ArrayList<>();

	/** Makes the list {@code count} fake players (0: none), on top of the real ones. */
	void set(int count) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) throw new IllegalStateException("not in a world");
		clear(connection);
		mc.gui.hud.getTabList().setHeader(count <= 0 ? null : Component.literal("§bDev Server\n§7" + count + " players"));
		mc.gui.hud.getTabList().setFooter(count <= 0 ? null : Component.literal("§6TPS 20.0 §7· §aping 12"));
		if (count <= 0) return;
		Random random = new Random(count);
		List<String> names = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(new UUID(0x4d595249414420L, i + 1L));
			String word = WORDS[random.nextInt(WORDS.length)] + WORDS[random.nextInt(WORDS.length)];
			names.add(word.substring(0, Math.min(word.length(), 12)) + i);
		}
		connection.handlePlayerInfoUpdate(packet(connection, names, random));
		for (int i = 0; i < count; i++) {
			PlayerInfo info = connection.getPlayerInfo(ids.get(i));
			if (info != null) setSkin(info, makeSkin(i, random));
		}
	}

	private void clear(ClientPacketListener connection) {
		if (!ids.isEmpty()) connection.handlePlayerInfoRemove(new ClientboundPlayerInfoRemovePacket(List.copyOf(ids)));
		ids.clear();
		for (Identifier skin : skins) mc.getTextureManager().release(skin);
		skins.clear();
	}

	private ClientboundPlayerInfoUpdatePacket packet(ClientPacketListener connection, List<String> names, Random random) {
		EnumSet<Action> actions = EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_GAME_MODE, Action.UPDATE_LISTED, Action.UPDATE_LATENCY,
			Action.UPDATE_DISPLAY_NAME, Action.UPDATE_LIST_ORDER, Action.UPDATE_HAT);
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), connection.registryAccess());
		buf.writeEnumSet(actions, Action.class);
		buf.writeVarInt(names.size());
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			buf.writeUUID(ids.get(i));
			for (Action action : actions) {
				switch (action) {
					case ADD_PLAYER -> {
						ByteBufCodecs.PLAYER_NAME.encode(buf, name);
						ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buf, new GameProfile(ids.get(i), name).properties());
					}
					case UPDATE_GAME_MODE -> buf.writeVarInt((i % 40 == 39 ? GameType.SPECTATOR : GameType.SURVIVAL).getId());
					case UPDATE_LISTED -> buf.writeBoolean(true);
					case UPDATE_LATENCY -> buf.writeVarInt(random.nextInt(i % 25 == 24 ? 1500 : 250));
					case UPDATE_DISPLAY_NAME -> {
						Component display = i % 4 == 0 ? Component.literal(PREFIXES[i / 4 % PREFIXES.length] + name) : null;
						FriendlyByteBuf.writeNullable(buf, display, ComponentSerialization.TRUSTED_STREAM_CODEC);
					}
					case UPDATE_LIST_ORDER -> buf.writeVarInt(0);
					case UPDATE_HAT -> buf.writeBoolean(i % 8 != 7);
					default -> throw new IllegalStateException(action.name());
				}
			}
		}
		ClientboundPlayerInfoUpdatePacket packet = ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf);
		buf.release();
		return packet;
	}

	/** A skin with a plain face and hat of its own colours, registered as a downloaded skin is. */
	private PlayerSkin makeSkin(int index, Random random) {
		Identifier id = Identifier.fromNamespaceAndPath("myriad", "dev/fake_skin_" + index);
		NativeImage image = new NativeImage(64, 64, true);
		int face = 0xFF000000 | random.nextInt(0x1000000), hat = 0xFF000000 | random.nextInt(0x1000000);
		image.fillRect(8, 8, 8, 8, face);
		for (int y = 8; y < 16; y++) for (int x = 40; x < 48; x++) if ((x + y) % 3 == 0) image.setPixel(x, y, hat);
		mc.getTextureManager().register(id, new DynamicTexture(id::toString, image));
		skins.add(id);
		return PlayerSkin.insecure(new ClientAsset.ResourceTexture(id, id), null, null, PlayerModelType.WIDE);
	}

	private static void setSkin(PlayerInfo info, PlayerSkin skin) {
		try {
			Field lookup = PlayerInfo.class.getDeclaredField("skinLookup");
			lookup.setAccessible(true);
			lookup.set(info, (Supplier<PlayerSkin>) () -> skin);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
